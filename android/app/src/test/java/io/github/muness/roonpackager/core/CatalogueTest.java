package io.github.muness.roonpackager.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * The shipped catalogue, checked as data.
 *
 * <p>The claim the catalogue exists to make good on is that adding a third
 * endpoint is a change to one JSON file. That is only true while every fact a
 * run needs is in the file and every name in it obeys Android's packaging rule,
 * so both are asserted here rather than discovered on a device.
 */
public final class CatalogueTest {

    private static Catalogue shipped() throws Exception {
        // The packaged asset, read from the source tree: a unit test has no
        // AssetManager, and this is the same file the app ships.
        File asset = new File("src/main/assets/catalogue.json");
        if (!asset.isFile()) {
            asset = new File("android/app/src/main/assets/catalogue.json");
        }
        assertTrue("cannot find the shipped catalogue at " + asset.getAbsolutePath(),
                asset.isFile());
        try (InputStream in = new FileInputStream(asset)) {
            return Catalogue.read(in);
        }
    }

    @Test
    public void theShippedCatalogueNamesOnlyRoon() throws Exception {
        Catalogue catalogue = shipped();
        assertEquals(1, catalogue.schema);
        assertEquals(1, catalogue.endpoints.size());
        assertNotNull(catalogue.byId("roon"));
    }

    @Test
    public void everyEndpointSaysWhereItsArchiveAndItsShellComeFrom() throws Exception {
        for (Catalogue.Endpoint endpoint : shipped().endpoints) {
            assertTrue(endpoint.id + " must name a vendor URL",
                    endpoint.vendorArchiveUrl().startsWith("http"));
            assertTrue(endpoint.id + " must name a shell URL",
                    endpoint.shell.url.startsWith("http") || endpoint.shell.url.startsWith("file"));
            assertEquals(endpoint.id + "'s shell sha256 must be 64 hex characters",
                    64, endpoint.shell.sha256.length());
            assertTrue(endpoint.id + " must carry a licence statement saying the user is "
                            + "downloading the vendor's software",
                    endpoint.licence.text.length() > 200);
            assertTrue(endpoint.id + "'s licence must say this app redistributes nothing",
                    endpoint.licence.text.contains("does not contain"));
        }
    }

    @Test
    public void everyNativeEntryNameIsOneAndroidExtracts() throws Exception {
        for (Catalogue.Endpoint endpoint : shipped().endpoints) {
            Catalogue.PackPlan plan = endpoint.packPlan;
            for (Catalogue.Member member : plan.members) {
                checkEntry(endpoint.id, plan, member);
            }
            for (Catalogue.Supplement supplement : plan.supplements) {
                assertEquals(supplement.url + " must carry a sha256", 64,
                        supplement.sha256 == null ? 0 : supplement.sha256.length());
                for (Catalogue.Member member : supplement.members) {
                    checkEntry(endpoint.id, plan, member);
                    // A supplement's entry name has to be what the rename rule
                    // produces for its SONAME, or the app's symlink and the APK
                    // would disagree.
                    assertEquals(member.path + ": the declared entry is not what the rename "
                                    + "rule produces for " + member.effectiveName(),
                            plan.nativeDir + Names.packaged(member.effectiveName(),
                                    plan.renameSuffix),
                            member.apkEntry);
                }
            }
        }
    }

    @Test
    public void noEndpointCanOverwriteItsShellsOwnLibraries() throws Exception {
        for (Catalogue.Endpoint endpoint : shipped().endpoints) {
            Catalogue.PackPlan plan = endpoint.packPlan;
            for (Catalogue.Supplement supplement : plan.supplements) {
                for (Catalogue.Member member : supplement.members) {
                    String base = member.apkEntry.substring(
                            member.apkEntry.lastIndexOf('/') + 1);
                    if (plan.ownLibraries.contains(base)) {
                        fail(endpoint.id + ": " + member.path + " would be packaged as " + base
                                + ", which is one of the shell's own libraries");
                    }
                }
            }
        }
    }

    @Test
    public void thePortsAreTheOnesTheOtherAppsUse() throws Exception {
        Catalogue catalogue = shipped();
        assertEquals(48620, catalogue.byId("roon").endpointDiagnosticsPort);
    }

    @Test
    public void anUnrecognisedArchiveIsRefusedRatherThanPacked() throws Exception {
        Catalogue.Archive archive = new Catalogue.Archive();
        archive.urlPattern = "https://example.invalid/{version}.deb";
        archive.format = "deb";
        try {
            archive.sha256For("9.9");
            fail("an archive with no recorded hash must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("refuses"));
        }
    }

    @Test
    public void aSupplementWithoutAHashIsRefusedRatherThanPacked() throws Exception {
        Catalogue.Supplement supplement = new Catalogue.Supplement();
        supplement.url = "https://example.invalid/libexample.deb";
        supplement.format = "deb";
        try {
            supplement.validate("roon");
            fail("a supplement with no sha256 must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("sha256"));
        }
        supplement.sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        supplement.url = null;
        try {
            supplement.validate("roon");
            fail("a supplement with no URL must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("url"));
        }
        supplement.url = "https://example.invalid/libexample.deb";
        supplement.validate("roon");
    }

    private static void checkEntry(String id, Catalogue.PackPlan plan,
            Catalogue.Member member) {
        if (!member.executable()) {
            return;
        }
        assertTrue(id + ": " + member.apkEntry + " must live under " + plan.nativeDir,
                member.apkEntry.startsWith(plan.nativeDir));
        String base = member.apkEntry.substring(plan.nativeDir.length());
        assertEquals(id + ": " + base + " must have exactly one dot, or Android silently "
                + "does not extract it", 1, Names.dotCount(base));
        assertTrue(id + ": " + base + " must be a lib*.so name", Names.extractable(base));
    }
}
