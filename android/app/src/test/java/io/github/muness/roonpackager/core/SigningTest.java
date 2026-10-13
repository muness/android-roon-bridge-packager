package io.github.muness.roonpackager.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

/**
 * The key and the signature, checked on the JVM before anything is asked of a
 * device.
 *
 * <p>Two things here are load-bearing and neither is obvious: the certificate
 * is DER-encoded by hand, because Android has no public API that makes a
 * self-signed one and this app cannot borrow {@code keytool}; and the same key
 * has to come back on the second run, because Android installs an update over
 * an app only while both are signed by the same key.
 */
public final class SigningTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aKeyIsMadeOnceAndComesBackAfterwards() throws Exception {
        File keystore = new File(folder.getRoot(), "packing-key.p12");
        SigningKey first = SigningKey.loadOrCreate(keystore);
        assertTrue("the first call must create the key", first.created);
        assertTrue(keystore.isFile());
        assertTrue(SigningKey.passwordFile(keystore).isFile());

        SigningKey second = SigningKey.loadOrCreate(keystore);
        assertTrue("the second call must load it, not make a new one", !second.created);
        assertEquals("a reloaded key must be the same key -- an update depends on it",
                first.certificateSha256(), second.certificateSha256());
    }

    @Test
    public void aPackedApkSignsAndVerifies() throws Exception {
        File shell = FixtureArchives.shellApk(folder.newFile("shell.apk"));
        File deb = FixtureArchives.deb(folder.newFile("fake.deb"), "usr/sbin/networkaudiod",
                FixtureArchives.fakeExecutable(4096));
        Catalogue catalogue = Catalogue.read(SigningTest.class.getClassLoader()
                .getResourceAsStream("parity/naa-fixture.json"));
        Catalogue.Endpoint endpoint = catalogue.byId("fake-naa");

        ArchiveReader.Tree tree = ArchiveReader.extract(deb, "deb", folder.newFolder("deb"));
        File unsigned = folder.newFile("unsigned.apk");
        ApkPacker.Report report = new ApkPacker(endpoint, PackProgress.NONE)
                .pack(shell, tree, new java.util.ArrayList<ArchiveReader.Tree>(), unsigned);

        File keystore = new File(folder.getRoot(), "sign.p12");
        SigningKey key = SigningKey.loadOrCreate(keystore);
        File signed = folder.newFile("signed.apk");
        ApkSigning.sign(unsigned, signed, key, 26);

        String result = ApkSigning.verify(signed, 26);
        assertTrue("v2 must be on: " + result, result.contains("v2 true"));
        assertTrue("v3 must be on: " + result, result.contains("v3 true"));

        // Signing must not have disturbed a single packed byte, and the entry
        // must still be stored uncompressed -- which is what makes the packed
        // library usable at all.
        java.util.Map<String, File> sources = new java.util.HashMap<>();
        sources.put("lib/arm64-v8a/libnetworkaudiod_exec.so",
                tree.file("usr/sbin/networkaudiod"));
        PackVerifier.Result verification = PackVerifier.verify(signed, endpoint, report, sources);
        assertTrue("the verifier rejected a signed APK:\n" + verification.summary(),
                verification.ok());
        assertEquals(Sha256.of(FixtureArchives.fakeExecutable(4096)),
                report.entrySha256.get("lib/arm64-v8a/libnetworkaudiod_exec.so"));
    }
}
