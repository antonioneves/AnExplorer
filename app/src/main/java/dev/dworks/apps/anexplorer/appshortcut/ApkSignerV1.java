/*
 * Copyright (C) 2026 The AnExplorer Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.dworks.apps.anexplorer.appshortcut;

import android.util.Base64;

import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.pkcs.ContentInfo;
import org.bouncycastle.asn1.pkcs.IssuerAndSerialNumber;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.SignedData;
import org.bouncycastle.asn1.pkcs.SignerInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.Certificate;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Applies a v1 (JAR) signature. The shortcut targets SDK 29, which Android still accepts without
 * the newer APK Signature Scheme, and this needs no apksig dependency on the TV.
 */
final class ApkSignerV1 {

    private static final String MANIFEST_ENTRY = "META-INF/MANIFEST.MF";
    private static final String SIGNATURE_ENTRY = "META-INF/CERT.SF";
    private static final String BLOCK_ENTRY = "META-INF/CERT.RSA";
    private static final String DIGEST_ATTRIBUTE = "SHA-256-Digest";
    private static final byte[] CRLF = {'\r', '\n'};

    private ApkSignerV1() {
    }

    static void sign(File input, File output, PrivateKey key, X509Certificate certificate)
            throws Exception {
        final SortedMap<String, byte[]> digests = new TreeMap<>();
        try (ZipFile zip = new ZipFile(input)) {
            final Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || isSignatureEntry(entry.getName())) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    digests.put(entry.getName(), sha256(in));
                }
            }
        }

        final ManifestFile manifest = createManifest(digests);
        final byte[] signatureFile = createSignatureFile(manifest);
        final byte[] signatureBlock = createSignatureBlock(signatureFile, key, certificate);

        try (ZipFile zip = new ZipFile(input);
             ZipOutputStream out = new ZipOutputStream(
                     new BufferedOutputStream(new FileOutputStream(output)))) {
            final Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || isSignatureEntry(entry.getName())) {
                    continue;
                }
                final ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                copy.setMethod(entry.getMethod());
                if (entry.getMethod() == ZipEntry.STORED) {
                    copy.setSize(entry.getSize());
                    copy.setCrc(entry.getCrc());
                }
                out.putNextEntry(copy);
                try (InputStream in = zip.getInputStream(entry)) {
                    copy(in, out);
                }
                out.closeEntry();
            }
            writeEntry(out, MANIFEST_ENTRY, manifest.content);
            writeEntry(out, SIGNATURE_ENTRY, signatureFile);
            writeEntry(out, BLOCK_ENTRY, signatureBlock);
        }
    }

    private static boolean isSignatureEntry(String name) {
        final String upper = name.toUpperCase(Locale.US);
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        final String file = upper.substring("META-INF/".length());
        return file.equals("MANIFEST.MF") || file.endsWith(".SF") || file.endsWith(".RSA")
                || file.endsWith(".DSA") || file.endsWith(".EC") || file.startsWith("SIG-");
    }

    private static final class ManifestFile {
        final byte[] content;
        final SortedMap<String, byte[]> sections;

        ManifestFile(byte[] content, SortedMap<String, byte[]> sections) {
            this.content = content;
            this.sections = sections;
        }
    }

    private static ManifestFile createManifest(Map<String, byte[]> digests) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAttribute(out, "Manifest-Version", "1.0");
        writeAttribute(out, "Created-By", "AnExplorer");
        out.write(CRLF, 0, CRLF.length);

        final SortedMap<String, byte[]> sections = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : digests.entrySet()) {
            final ByteArrayOutputStream section = new ByteArrayOutputStream();
            writeAttribute(section, "Name", entry.getKey());
            writeAttribute(section, DIGEST_ATTRIBUTE,
                    Base64.encodeToString(entry.getValue(), Base64.NO_WRAP));
            section.write(CRLF, 0, CRLF.length);
            final byte[] bytes = section.toByteArray();
            sections.put(entry.getKey(), bytes);
            out.write(bytes, 0, bytes.length);
        }
        return new ManifestFile(out.toByteArray(), sections);
    }

    private static byte[] createSignatureFile(ManifestFile manifest) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAttribute(out, "Signature-Version", "1.0");
        writeAttribute(out, "Created-By", "AnExplorer");
        writeAttribute(out, "SHA-256-Digest-Manifest",
                Base64.encodeToString(sha256(manifest.content), Base64.NO_WRAP));
        out.write(CRLF, 0, CRLF.length);

        for (Map.Entry<String, byte[]> entry : manifest.sections.entrySet()) {
            final ByteArrayOutputStream section = new ByteArrayOutputStream();
            writeAttribute(section, "Name", entry.getKey());
            writeAttribute(section, DIGEST_ATTRIBUTE,
                    Base64.encodeToString(sha256(entry.getValue()), Base64.NO_WRAP));
            section.write(CRLF, 0, CRLF.length);
            final byte[] bytes = section.toByteArray();
            out.write(bytes, 0, bytes.length);
        }

        // Android's JAR reader throws when the signature file is exactly 1024 bytes long
        if (out.size() > 0 && out.size() % 1024 == 0) {
            out.write(CRLF, 0, CRLF.length);
        }
        return out.toByteArray();
    }

    private static byte[] createSignatureBlock(byte[] signatureFile, PrivateKey key,
                                               X509Certificate certificate) throws Exception {
        final Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(signatureFile);

        final AlgorithmIdentifier digestAlgorithm = new AlgorithmIdentifier(
                new ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.1"), DERNull.INSTANCE);
        final AlgorithmIdentifier signatureAlgorithm = new AlgorithmIdentifier(
                PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE);

        final byte[] issuer = certificate.getIssuerX500Principal().getEncoded();
        final IssuerAndSerialNumber signer = new IssuerAndSerialNumber(
                X500Name.getInstance(ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(issuer))),
                certificate.getSerialNumber());
        final SignerInfo signerInfo = new SignerInfo(
                new ASN1Integer(1),
                signer,
                digestAlgorithm,
                null,
                signatureAlgorithm,
                new DEROctetString(signature.sign()),
                null);
        final Certificate bcCertificate = Certificate.getInstance(
                ASN1Primitive.fromByteArray(certificate.getEncoded()));
        final SignedData signedData = new SignedData(
                new ASN1Integer(1),
                new DERSet(digestAlgorithm),
                new ContentInfo(PKCSObjectIdentifiers.data, new DEROctetString(signatureFile)),
                new DERSet(bcCertificate),
                null,
                new DERSet(signerInfo));
        return new ContentInfo(PKCSObjectIdentifiers.signedData, signedData)
                .getEncoded(ASN1Encoding.DER);
    }

    private static void writeAttribute(ByteArrayOutputStream out, String name, String value) {
        final byte[] bytes = (name + ": " + value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int offset = 0;
        boolean first = true;
        while (offset < bytes.length) {
            final int limit = first ? 70 : 69;
            int count = Math.min(limit, bytes.length - offset);
            // Keep UTF-8 sequences whole: a line that ends inside one is not readable
            while (offset + count < bytes.length && count > 1
                    && (bytes[offset + count] & 0xC0) == 0x80) {
                count--;
            }
            if (!first) {
                out.write(' ');
            }
            out.write(bytes, offset, count);
            out.write(CRLF, 0, CRLF.length);
            offset += count;
            first = false;
        }
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static byte[] sha256(InputStream in) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return digest.digest();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static void writeEntry(ZipOutputStream out, String name, byte[] data) throws Exception {
        final ZipEntry entry = new ZipEntry(name);
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }
}
