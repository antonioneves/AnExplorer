package dev.dworks.apps.anexplorer.network;

import android.content.Context;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Date;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * The self-signed certificate the web page is served with. A TV on a home network has no name a
 * public authority would sign, so browsers warn once; the traffic is still encrypted. It is made on
 * first use and kept, so the warning does not come back each time the server starts.
 */
class WebCertificate {

    private static final String FILE = "web_server.keystore";
    private static final String ALIAS = "web";
    private static final char[] PASSWORD = "anexplorer".toCharArray();
    private static final long TEN_YEARS_MS = 10L * 365 * 24 * 3600 * 1000;

    private WebCertificate() {
    }

    static SSLContext sslContext(Context context) throws Exception {
        final File file = new File(context.getFilesDir(), FILE);
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) {
                store.load(in, PASSWORD);
            } catch (IOException e) {
                store = create(file);
            }
        } else {
            store = create(file);
        }
        final KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD);
        final SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(keys.getKeyManagers(), null, null);
        return ssl;
    }

    private static KeyStore create(File file) throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        final KeyPair pair = generator.generateKeyPair();

        final X500Name name = new X500Name("CN=AnExplorer");
        final AlgorithmIdentifier algorithm = new AlgorithmIdentifier(
                new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.113549.1.1.11"));
        final long now = System.currentTimeMillis();

        final V3TBSCertificateGenerator tbs = new V3TBSCertificateGenerator();
        tbs.setSerialNumber(new ASN1Integer(BigInteger.valueOf(now)));
        tbs.setSignature(algorithm);
        tbs.setIssuer(name);
        tbs.setStartDate(new Time(new Date(now - 24 * 3600 * 1000L)));
        tbs.setEndDate(new Time(new Date(now + TEN_YEARS_MS)));
        tbs.setSubject(name);
        tbs.setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded()));
        final org.bouncycastle.asn1.x509.TBSCertificate body = tbs.generateTBSCertificate();

        final Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(body.getEncoded("DER"));

        final ASN1EncodableVector parts = new ASN1EncodableVector();
        parts.add(body);
        parts.add(algorithm);
        parts.add(new DERBitString(signer.sign()));
        final Certificate certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(new DERSequence(parts).getEncoded("DER")));

        final KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setKeyEntry(ALIAS, pair.getPrivate(), PASSWORD, new Certificate[]{certificate});
        try (FileOutputStream out = new FileOutputStream(file)) {
            store.store(out, PASSWORD);
        }
        return store;
    }
}
