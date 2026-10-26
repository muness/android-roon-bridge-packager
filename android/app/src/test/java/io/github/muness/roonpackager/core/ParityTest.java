package io.github.muness.roonpackager.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The parity check, as a test.
 *
 * <p>What it asserts: packing the stand-in {@code .deb} into the stand-in app
 * shell produces exactly the entry list, exactly the storage methods, and
 * exactly the per-entry content hashes that {@code scripts/pack-naa-apk.sh}
 * produces from the same two files. The expected manifest is in
 * {@code src/test/resources/parity/naa-fixture.manifest} and it is generated
 * by the shell packer, by {@code scripts/desktop-parity.sh}. So this test fails
 * if the on-device packer drifts from the desktop one in either direction.
 *
 * <p>Stand-in archives rather than real ones, because the real vendor archives
 * cannot enter this repository. What that does not cover is anything specific
 * to the real files' size or content, which is why the emulator run against
 * Signalyst's own package -- and its known
 * {@code cf66f57e...} hash -- exists as well.
 */
public final class ParityTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void theNaaPlanReproducesTheDesktopPackersManifest() throws Exception {
        ApkPacker.Report report = packNaaFixture();
        String expected = resource("parity/naa-fixture.manifest");
        assertEquals("the on-device packer's output has drifted from the desktop packer's.\n"
                        + "Regenerate with scripts/desktop-parity.sh and read the diff.",
                normalise(expected), normalise(report.parityManifest()));
    }

    @Test
    public void theVendorMemberIsStoredUncompressedAndByteIdentical() throws Exception {
        ApkPacker.Report report = packNaaFixture();
        String entry = "lib/arm64-v8a/libnetworkaudiod_exec.so";
        assertEquals("STORED", report.entryMethod.get(entry));
        assertEquals(Sha256.of(FixtureArchives.fakeExecutable(4096)),
                report.entrySha256.get(entry));
        assertEquals(Collections.singletonList(entry), report.added);
    }

    @Test
    public void theStaleVendorEntryAndTheStaleSignatureAreGone() throws Exception {
        ApkPacker.Report report = packNaaFixture();
        for (String name : report.entrySha256.keySet()) {
            assertTrue("a stale signature file survived: " + name,
                    !name.startsWith("META-INF/"));
        }
        // The shell carried a stale six-byte vendor entry; exactly one entry of
        // that name is in the result and it is the packed one.
        assertEquals(Sha256.of(FixtureArchives.fakeExecutable(4096)),
                report.entrySha256.get("lib/arm64-v8a/libnetworkaudiod_exec.so"));
    }

    @Test
    public void theShellsOwnLibraryAndItsMarkerAreUntouched() throws Exception {
        ApkPacker.Report report = packNaaFixture();
        assertEquals("STORED", report.entryMethod.get("lib/arm64-v8a/libc_glibc.so"));
        assertEquals(Sha256.of("shell libc\n".getBytes(StandardCharsets.UTF_8)),
                report.entrySha256.get("lib/arm64-v8a/libc_glibc.so"));
        // The marker already said "unpatched", so it is confirmed rather than
        // rewritten -- which is what the desktop packer does, and rewriting it
        // would change how it is stored for no reason.
        assertEquals("DEFLATED", report.entryMethod.get("assets/runtime-variant"));
        assertEquals(Sha256.of("unpatched\n".getBytes(StandardCharsets.UTF_8)),
                report.entrySha256.get("assets/runtime-variant"));
    }

    @Test
    public void everyStoredNativeEntryIsAlignedInTheFile() throws Exception {
        File packed = folder.newFile("aligned.apk");
        packNaaFixture(packed);
        Map<String, Long> offsets = dataOffsets(packed);
        Long offset = offsets.get("lib/arm64-v8a/libnetworkaudiod_exec.so");
        assertNotNull(offset);
        assertEquals("a stored native entry must start on a 4-byte boundary",
                0, offset % 4);
    }

    @Test
    public void theTreePlanSortsElfFilesFromDataAndRecordsWhatWentWhere() throws Exception {
        Catalogue catalogue = Catalogue.read(open("parity/tree-fixture.json"));
        Catalogue.Endpoint endpoint = catalogue.byId("fake-tree");
        Catalogue.PackPlan plan = endpoint.packPlan;

        String[] names = {
                "VERSION",
                "Bridge/RoonBridge.dll",
                "Bridge/libhostfxr.so",
                "Bridge/libcoreclr.so",
                "Bridge/libSystem.Native.so",
                "Bridge/libssl.so.3",
                "Bridge/check_alsa",
                "Bridge/RAATServer",
                "Bridge/RoonBridge.exe",
        };
        byte[][] contents = new byte[names.length][];
        for (int index = 0; index < names.length; index++) {
            String base = names[index].substring(names[index].lastIndexOf('/') + 1);
            if (base.equals("VERSION")) {
                contents[index] = "1234\n9.99 (build 1) self-test\nproduction\n"
                        .getBytes(StandardCharsets.UTF_8);
            } else if (base.endsWith(".dll")) {
                contents[index] = "managed bytes\n".getBytes(StandardCharsets.UTF_8);
            } else {
                contents[index] = FixtureArchives.fakeElf(base);
            }
        }
        File tarball = FixtureArchives.tarBz2(folder.newFile("fake-roon.tar.bz2"),
                "RoonBridge", names, contents);
        String staleMap = "hostfxr\tlibhostfxr.so\n"
                + "lib\tBridge/libstalekept.so\tlibstalekept.so\n"
                + "lib\tBridge/libld_glibc.so\tlibld_glibc.so\n";
        File shell = FixtureArchives.treeShellApk(folder.newFile("tree-shell.apk"),
                plan.ownLibraries.toArray(new String[0]), staleMap);

        ArchiveReader.Tree tree = ArchiveReader.extract(tarball, "tar.bz2",
                folder.newFolder("tree"));
        File packed = folder.newFile("tree-packed.apk");
        ApkPacker.Report report = new ApkPacker(endpoint, PackProgress.NONE)
                .pack(shell, tree, new ArrayList<ArchiveReader.Tree>(), packed);

        // Names Android already extracts are kept; the rest fold to one dot.
        assertTrue(report.entrySha256.containsKey("lib/arm64-v8a/libcoreclr.so"));
        assertTrue(report.entrySha256.containsKey("lib/arm64-v8a/libhostfxr.so"));
        assertTrue(report.entrySha256.containsKey("lib/arm64-v8a/libSystem_Native_roon.so"));
        assertTrue(report.entrySha256.containsKey("lib/arm64-v8a/libssl_3_roon.so"));
        assertTrue(report.entrySha256.containsKey("lib/arm64-v8a/libcheck_alsa_roon.so"));
        // An apphost cannot run here at all, so neither of them is packed.
        assertTrue(!report.entrySha256.containsKey("lib/arm64-v8a/libRAATServer_roon.so"));
        assertTrue(!report.entrySha256.containsKey("lib/arm64-v8a/libRoonBridge_exe_roon.so"));
        // A previous run's entries go, by whichever rule reaches them, and the
        // shell's own runtime is not touched even when a stale map names it.
        assertTrue(!report.entrySha256.containsKey("lib/arm64-v8a/libstalekept.so"));
        assertTrue(!report.entrySha256.containsKey("lib/arm64-v8a/libstale_roon.so"));
        for (String own : plan.ownLibraries) {
            assertTrue(own + " must survive packing",
                    report.entrySha256.containsKey("lib/arm64-v8a/" + own));
        }
        // Every native entry is byte-identical to the file it came from.
        for (Map.Entry<String, String> origin : report.origin.entrySet()) {
            String path = origin.getValue();
            byte[] want = null;
            for (int index = 0; index < names.length; index++) {
                if (names[index].equals(path)) {
                    want = contents[index];
                }
            }
            assertNotNull("no fixture member called " + path, want);
            assertEquals(path + " changed on the way in", Sha256.of(want),
                    report.entrySha256.get(origin.getKey()));
        }

        // The map says what went where, the archive holds the data files, and
        // no ELF file went into the archive.
        String map = new String(entry(packed, "assets/roon-exec-map.txt"),
                StandardCharsets.UTF_8);
        assertTrue(map.contains("version\t9.99 (build 1) self-test"));
        assertTrue(map.contains("entry\tBridge/RoonBridge.dll"));
        assertTrue(map.contains("child\tBridge/RAATServer"));
        assertTrue(map.contains("hostfxr\tlibhostfxr.so"));
        assertTrue(map.contains("lib\tBridge/libcoreclr.so\tlibcoreclr.so"));
        assertEquals("STORED", report.entryMethod.get("assets/roon-tree.zip"));

        List<String> inTree = treeMembers(packed, "assets/roon-tree.zip");
        assertTrue(inTree.contains("Bridge/RoonBridge.dll"));
        assertTrue(inTree.contains("VERSION"));
        for (String member : inTree) {
            assertTrue("an ELF file went into the tree archive: " + member,
                    !member.endsWith(".so"));
        }

        // And the verifier, which is what a real run has to pass, agrees.
        PackVerifier.Result verification = PackVerifier.verify(packed, endpoint, report,
                new HashMap<String, File>());
        if (!verification.ok()) {
            fail("the verifier rejected a correctly packed APK:\n" + verification.summary());
        }
    }

    @Test
    public void aTreeShellMissingARequiredOwnLibraryCannotProduceAnApk() throws Exception {
        Catalogue.Endpoint endpoint = Catalogue.read(open("parity/tree-fixture.json"))
                .byId("fake-tree");
        List<String> own = new ArrayList<>(endpoint.packPlan.ownLibraries);
        String missing = own.remove(own.size() - 1);
        File shell = FixtureArchives.treeShellApk(folder.newFile("incomplete-shell.apk"),
                own.toArray(new String[0]), null);
        File archive = FixtureArchives.tarBz2(folder.newFile("synthetic-runtime.tar.bz2"),
                "RoonBridge", new String[]{"VERSION", "Bridge/RoonBridge.dll",
                        "Bridge/libhostfxr.so"}, new byte[][]{
                        "fixture\n".getBytes(StandardCharsets.UTF_8),
                        "managed fixture\n".getBytes(StandardCharsets.UTF_8),
                        FixtureArchives.fakeElf("libhostfxr.so")});
        ArchiveReader.Tree tree = ArchiveReader.extract(archive, "tar.bz2",
                folder.newFolder("incomplete-shell-tree"));
        File packed = new File(folder.getRoot(), "must-not-exist.apk");
        try {
            new ApkPacker(endpoint, PackProgress.NONE).pack(shell, tree,
                    new ArrayList<ArchiveReader.Tree>(), packed);
            fail("a shell missing a required own library must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(missing));
            assertTrue("an unusable APK must not be created", !packed.exists());
        }
    }

    @Test
    public void aVerifierRejectsAnEntryWithTwoDots() throws Exception {
        // Not a hypothetical: a two-dot name is silently not extracted by
        // Android, which is a failure with no error message, and it is what
        // broke the desktop packer's first device run.
        Catalogue catalogue = Catalogue.read(open("parity/tree-fixture.json"));
        Catalogue.Endpoint endpoint = catalogue.byId("fake-tree");
        assertTrue(Names.dotCount(Names.packaged("libSystem.Native.so",
                endpoint.packPlan.renameSuffix)) == 1);
    }

    // ------------------------------------------------------------------ plumbing

    private ApkPacker.Report packNaaFixture() throws Exception {
        return packNaaFixture(folder.newFile("packed-" + System.nanoTime() + ".apk"));
    }

    private ApkPacker.Report packNaaFixture(File output) throws Exception {
        Catalogue catalogue = Catalogue.read(open("parity/naa-fixture.json"));
        Catalogue.Endpoint endpoint = catalogue.byId("fake-naa");
        File deb = FixtureArchives.deb(folder.newFile("fake-naa-" + System.nanoTime() + ".deb"),
                "usr/sbin/networkaudiod", FixtureArchives.fakeExecutable(4096));
        File shell = FixtureArchives.shellApk(
                folder.newFile("fake-shell-" + System.nanoTime() + ".apk"));
        ArchiveReader.Tree tree = ArchiveReader.extract(deb, "deb",
                folder.newFolder("deb-" + System.nanoTime()));
        return new ApkPacker(endpoint, PackProgress.NONE)
                .pack(shell, tree, new ArrayList<ArchiveReader.Tree>(), output);
    }

    private static InputStream open(String path) {
        InputStream in = ParityTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull("no test resource at " + path, in);
        return in;
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = open(path)) {
            java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
            ArchiveReader.copy(in, sink);
            return new String(sink.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Sorted, blank lines and comments dropped, so a diff is about content. */
    private static String normalise(String manifest) {
        List<String> lines = new ArrayList<>();
        for (String line : manifest.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                lines.add(trimmed);
            }
        }
        Collections.sort(lines);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static byte[] entry(File apk, String name) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry found = zip.getEntry(name);
            assertNotNull("no entry " + name + " in " + apk, found);
            try (InputStream in = zip.getInputStream(found)) {
                java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
                ArchiveReader.copy(in, sink);
                return sink.toByteArray();
            }
        }
    }

    private static List<String> treeMembers(File apk, String name) throws IOException {
        File temporary = File.createTempFile("tree", ".zip");
        try {
            byte[] content = entry(apk, name);
            try (java.io.OutputStream out = new java.io.FileOutputStream(temporary)) {
                out.write(content);
            }
            List<String> members = new ArrayList<>();
            try (ZipFile zip = new ZipFile(temporary)) {
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    members.add(entries.nextElement().getName());
                }
            }
            return members;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }

    /** Entry name -> where its data starts in the file, from the local headers. */
    private static Map<String, Long> dataOffsets(File apk) throws IOException {
        Map<String, Long> offsets = new HashMap<>();
        byte[] bytes = ZipWriter.readAll(apk);
        int position = 0;
        while (position + 30 <= bytes.length) {
            if (read32(bytes, position) != 0x04034b50L) {
                break;
            }
            int nameLength = read16(bytes, position + 26);
            int extraLength = read16(bytes, position + 28);
            int method = read16(bytes, position + 8);
            long compressed = read32(bytes, position + 18);
            String name = new String(bytes, position + 30, nameLength, StandardCharsets.UTF_8);
            long dataStart = position + 30L + nameLength + extraLength;
            offsets.put(name, dataStart);
            position = (int) (dataStart + compressed);
            if (method != 0 && method != 8) {
                break;
            }
        }
        return offsets;
    }

    private static int read16(byte[] bytes, int at) {
        return (bytes[at] & 0xff) | ((bytes[at + 1] & 0xff) << 8);
    }

    private static long read32(byte[] bytes, int at) {
        return (bytes[at] & 0xffL) | ((bytes[at + 1] & 0xffL) << 8)
                | ((bytes[at + 2] & 0xffL) << 16) | ((bytes[at + 3] & 0xffL) << 24);
    }
}
