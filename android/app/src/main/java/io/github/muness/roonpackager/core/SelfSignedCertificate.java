package io.github.muness.roonpackager.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A self-signed X.509 certificate, written by hand.
 *
 * <p>Android has no public API that makes one, and this app cannot borrow
 * {@code keytool}: the whole point is that it runs where there is no JDK. So
 * the eighty bytes of structure a signing certificate actually is are encoded
 * here -- version, serial, algorithm, issuer, validity, subject, public key --
 * and signed with the key it belongs to.
 *
 * <p>It is not a claim about anybody's identity and it is not meant to be. It
 * exists because Android will only install an update over an existing app when
 * both are signed by the same key, so this app needs <em>a</em> key and needs
 * to keep it. See {@code docs/how-it-works.md} on what is lost if it goes.
 */
public final class SelfSignedCertificate {

    /** sha256WithRSAEncryption, 1.2.840.113549.1.1.11. */
    private static final byte[] OID_SHA256_RSA =
            {0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b};
    /** id-at-commonName, 2.5.4.3. */
    private static final byte[] OID_CN = {0x55, 0x04, 0x03};
    /** id-at-organizationName, 2.5.4.10. */
    private static final byte[] OID_O = {0x55, 0x04, 0x0a};

    private SelfSignedCertificate() {
    }

    /**
     * @param commonName  what the certificate calls itself
     * @param years       how long it is valid for
     */
    public static X509Certificate create(KeyPair keyPair, String commonName, String organisation,
            int years) throws GeneralSecurityException, IOException {
        long now = System.currentTimeMillis();
        byte[] tbs = tbsCertificate(keyPair, commonName, organisation, now,
                now + years * 365L * 24 * 3600 * 1000);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign((PrivateKey) keyPair.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign();

        byte[] certificate = sequence(concat(
                tbs,
                algorithmIdentifier(),
                bitString(signature)));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(certificate));
    }

    private static byte[] tbsCertificate(KeyPair keyPair, String commonName, String organisation,
            long notBefore, long notAfter) throws IOException {
        byte[] name = name(commonName, organisation);
        return sequence(concat(
                explicit(0, integer(BigInteger.valueOf(2))),        // v3
                integer(new BigInteger(64, new java.security.SecureRandom()).abs()
                        .add(BigInteger.ONE)),
                algorithmIdentifier(),
                name,
                sequence(concat(utcTime(notBefore), utcTime(notAfter))),
                name,
                keyPair.getPublic().getEncoded()));                  // SubjectPublicKeyInfo
    }

    private static byte[] algorithmIdentifier() throws IOException {
        return sequence(concat(tlv(0x06, OID_SHA256_RSA), new byte[]{0x05, 0x00}));
    }

    /** {@code SEQUENCE OF SET OF SEQUENCE { OID, PrintableString }}. */
    private static byte[] name(String commonName, String organisation) throws IOException {
        byte[] cn = tlv(0x31, sequence(concat(tlv(0x06, OID_CN), printable(commonName))));
        byte[] o = tlv(0x31, sequence(concat(tlv(0x06, OID_O), printable(organisation))));
        return sequence(concat(o, cn));
    }

    // --------------------------------------------------------------- DER bits

    private static byte[] sequence(byte[] content) throws IOException {
        return tlv(0x30, content);
    }

    private static byte[] explicit(int tag, byte[] content) throws IOException {
        return tlv(0xa0 | tag, content);
    }

    private static byte[] integer(BigInteger value) throws IOException {
        return tlv(0x02, value.toByteArray());
    }

    private static byte[] printable(String text) throws IOException {
        return tlv(0x13, text.getBytes("US-ASCII"));
    }

    private static byte[] bitString(byte[] content) throws IOException {
        byte[] padded = new byte[content.length + 1];
        System.arraycopy(content, 0, padded, 1, content.length);
        return tlv(0x03, padded);
    }

    private static byte[] utcTime(long millis) throws IOException {
        SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, format.format(new Date(millis)).getBytes("US-ASCII"));
    }

    private static byte[] tlv(int tag, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 8);
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            int bytes = 1;
            while ((length >>> (8 * bytes)) != 0) {
                bytes++;
            }
            out.write(0x80 | bytes);
            for (int index = bytes - 1; index >= 0; index--) {
                out.write((length >>> (8 * index)) & 0xff);
            }
        }
        out.write(content);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part);
        }
        return out.toByteArray();
    }
}
