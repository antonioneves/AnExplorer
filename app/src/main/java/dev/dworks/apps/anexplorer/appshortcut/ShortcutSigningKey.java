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

import android.content.Context;

import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

/**
 * Holds the self-signed key that signs generated shortcuts. It lives in AnExplorer's private
 * files so the same key is reused: Android replaces an APK only when the signature matches.
 */
final class ShortcutSigningKey {

    private static final String DIRECTORY = "shortcut-signing";
    private static final String KEY_FILE = "key.pk8";
    private static final String CERT_FILE = "cert.der";

    private static Material sMaterial;

    static final class Material {
        final PrivateKey key;
        final X509Certificate certificate;

        Material(PrivateKey key, X509Certificate certificate) {
            this.key = key;
            this.certificate = certificate;
        }
    }

    private ShortcutSigningKey() {
    }

    static synchronized Material get(Context context) throws Exception {
        if (sMaterial != null) {
            return sMaterial;
        }
        final File directory = new File(context.getFilesDir(), DIRECTORY);
        final File keyFile = new File(directory, KEY_FILE);
        final File certFile = new File(directory, CERT_FILE);
        if (keyFile.isFile() && certFile.isFile()) {
            try {
                final PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(
                        new PKCS8EncodedKeySpec(readFile(keyFile)));
                final X509Certificate certificate = (X509Certificate) CertificateFactory
                        .getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(readFile(certFile)));
                return sMaterial = new Material(key, certificate);
            } catch (Exception e) {
                // A truncated file, from a crash while writing it, is replaced below
            }
        }

        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final KeyPair keyPair = generator.generateKeyPair();
        final X509Certificate certificate = createCertificate(keyPair);
        directory.mkdirs();
        writeFile(keyFile, keyPair.getPrivate().getEncoded());
        writeFile(certFile, certificate.getEncoded());
        return sMaterial = new Material(keyPair.getPrivate(), certificate);
    }

    private static X509Certificate createCertificate(KeyPair keyPair) throws Exception {
        final X500Name name = new X500Name("CN=AnExplorer Shortcuts");
        final long now = System.currentTimeMillis();
        final AlgorithmIdentifier algorithm = new AlgorithmIdentifier(
                PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE);

        final V3TBSCertificateGenerator generator = new V3TBSCertificateGenerator();
        generator.setSerialNumber(new ASN1Integer(BigInteger.valueOf(now)));
        generator.setIssuer(name);
        generator.setSubject(name);
        generator.setStartDate(new Time(new Date(now - 24L * 60 * 60 * 1000)));
        generator.setEndDate(new Time(new Date(now + 30L * 365 * 24 * 60 * 60 * 1000)));
        generator.setSubjectPublicKeyInfo(
                SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded()));
        generator.setSignature(algorithm);
        final TBSCertificate tbs = generator.generateTBSCertificate();

        final Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(tbs.getEncoded("DER"));
        final Certificate certificate =
                new Certificate(tbs, algorithm, new DERBitString(signature.sign()));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificate.getEncoded()));
    }

    private static byte[] readFile(File file) throws Exception {
        final byte[] data = new byte[(int) file.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                final int read = in.read(data, offset, data.length - offset);
                if (read < 0) {
                    throw new java.io.EOFException();
                }
                offset += read;
            }
        }
        return data;
    }

    private static void writeFile(File file, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
            out.getFD().sync();
        }
    }
}
