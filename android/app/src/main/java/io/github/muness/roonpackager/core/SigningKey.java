package io.github.muness.roonpackager.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One key, made once, kept.
 *
 * <p>The reason is Android's, not this app's: an update installs over an
 * existing app only when both are signed by the same key. So the first packing
 * run makes a key, every later run reuses it, and the packed endpoint can be
 * updated in place -- a new vendor version, a new app shell -- without losing
 * its settings and its approved clients.
 *
 * <p>It lives in the manager's own data directory, in a PKCS12 keystore with a
 * random password in a file beside it, exactly the way
 * {@code pack-naa-apk.sh} keeps its own. The password is generated rather than
 * prompted for because unattended re-packs are the normal case; it protects a
 * local signing key for locally sideloaded builds and is not a secret that
 * keeps anyone out of anything else.
 *
 * <p><b>Reinstalling the manager loses the key.</b> Android deletes an app's
 * data directory on uninstall, and there is nowhere else this app may keep a
 * private key without asking for storage permission it should not have. If the
 * key is gone, a packed endpoint can no longer be updated in place: it has to
 * be uninstalled first. The UI says so in as many words.
 */
public final class SigningKey {

    public static final String ALIAS = "endpoint-manager";
    private static final String DN_CN = "Android endpoint manager (local packing key)";
    private static final String DN_O = "local";

    public final PrivateKey privateKey;
    public final List<X509Certificate> certificates;
    /** True when this call created the key rather than loading it. */
    public final boolean created;

    private SigningKey(PrivateKey privateKey, List<X509Certificate> certificates,
            boolean created) {
        this.privateKey = privateKey;
        this.certificates = Collections.unmodifiableList(certificates);
        this.created = created;
    }

    /**
     * Loads the key at {@code keystore}, or makes it.
     *
     * @param keystore the PKCS12 file; the password lives beside it in
     *                 {@code <name>.pass}
     */
    public static SigningKey loadOrCreate(File keystore)
            throws GeneralSecurityException, IOException {
        File passwordFile = passwordFile(keystore);
        if (keystore.isFile() && passwordFile.isFile()) {
            char[] password = readPassword(passwordFile);
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream(keystore)) {
                store.load(in, password);
            }
            PrivateKey key = (PrivateKey) store.getKey(ALIAS, password);
            Certificate[] chain = store.getCertificateChain(ALIAS);
            if (key == null || chain == null || chain.length == 0) {
                throw new IOException("the keystore at " + keystore + " has no key called "
                        + ALIAS);
            }
            List<X509Certificate> certificates = new ArrayList<>();
            for (Certificate certificate : chain) {
                certificates.add((X509Certificate) certificate);
            }
            return new SigningKey(key, certificates, false);
        }
        return create(keystore, passwordFile);
    }

    private static SigningKey create(File keystore, File passwordFile)
            throws GeneralSecurityException, IOException {
        File parent = keystore.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        char[] password = randomPassword();
        try (OutputStream out = new FileOutputStream(passwordFile)) {
            out.write(new String(password).getBytes("US-ASCII"));
        }
        restrict(passwordFile);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        X509Certificate certificate = SelfSignedCertificate.create(pair, DN_CN, DN_O, 30);

        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, password);
        store.setKeyEntry(ALIAS, pair.getPrivate(), password,
                new Certificate[]{certificate});
        try (OutputStream out = new FileOutputStream(keystore)) {
            store.store(out, password);
        }
        restrict(keystore);
        return new SigningKey(pair.getPrivate(), Collections.singletonList(certificate), true);
    }

    public static File passwordFile(File keystore) {
        String path = keystore.getPath();
        String base = path.endsWith(".p12") ? path.substring(0, path.length() - 4) : path;
        return new File(base + ".pass");
    }

    /** The certificate's sha256, which is what "the same key" means to Android. */
    public String certificateSha256() throws GeneralSecurityException {
        return Sha256.of(certificates.get(0).getEncoded());
    }

    private static char[] randomPassword() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        char[] password = new char[32];
        for (int index = 0; index < password.length; index++) {
            password[index] = alphabet.charAt(random.nextInt(alphabet.length()));
        }
        return password;
    }

    private static char[] readPassword(File file) throws IOException {
        byte[] bytes = ZipWriter.readAll(file);
        String text = new String(bytes, "US-ASCII").trim();
        if (text.isEmpty()) {
            throw new IOException("the password file at " + file + " is empty");
        }
        return text.toCharArray();
    }

    private static void restrict(File file) {
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(false, false);
        //noinspection ResultOfMethodCallIgnored
        file.setWritable(false, false);
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(true, true);
        //noinspection ResultOfMethodCallIgnored
        file.setWritable(true, true);
    }
}
