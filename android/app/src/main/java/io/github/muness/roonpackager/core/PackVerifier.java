package io.github.muness.roonpackager.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What a run proves before it offers the result to the installer.
 *
 * <p>This is {@code verify_result} out of {@code pack-roon-apk.sh} and the two
 * hash lines out of {@code pack-naa-apk.sh}, in one place and applied to both
 * shapes. It is deliberately paranoid about the one failure that has no error
 * message: an entry Android silently does not extract, because its name has
 * two dots. The desktop packer learned that the hard way and the comment there
 * says so; the check exists here for the same reason.
 */
public final class PackVerifier {

    public static final class Result {
        public final List<String> checked = new ArrayList<>();
        public final List<String> problems = new ArrayList<>();

        public boolean ok() {
            return problems.isEmpty();
        }

        public String summary() {
            StringBuilder text = new StringBuilder();
            for (String line : checked) {
                text.append("ok   ").append(line).append('\n');
            }
            for (String line : problems) {
                text.append("FAIL ").append(line).append('\n');
            }
            return text.toString();
        }
    }

    private PackVerifier() {
    }

    /**
     * @param packed the signed APK
     * @param origin native entry name -> the archive member it came from
     * @param sources native entry name -> the file that member was written to
     */
    public static Result verify(File packed, Catalogue.Endpoint endpoint,
            ApkPacker.Report report, Map<String, File> sources) throws IOException {
        Result result = new Result();
        Catalogue.PackPlan plan = endpoint.packPlan;

        try (ZipFile zip = new ZipFile(packed)) {
            // 1. Every entry this run added is present exactly once, stored,
            //    and byte-identical to the archive member it came from.
            for (String name : report.added) {
                List<ZipEntry> matches = matching(zip, name);
                if (matches.size() != 1) {
                    result.problems.add(name + " appears " + matches.size() + " times");
                    continue;
                }
                ZipEntry entry = matches.get(0);
                boolean shouldBeStored = name.startsWith(plan.nativeDir)
                        || name.equals(plan.treeAsset);
                if (shouldBeStored && entry.getMethod() != ZipEntry.STORED) {
                    result.problems.add(name + " is not stored uncompressed");
                }
                String want = report.entrySha256.get(name);
                String got = sha256(zip, entry);
                if (!want.equals(got)) {
                    result.problems.add(name + " is NOT byte-identical inside the APK: "
                            + "packed " + want + ", read back " + got);
                    continue;
                }
                File source = sources.get(name);
                if (source != null) {
                    String archive = Sha256.of(source);
                    if (!archive.equals(got)) {
                        result.problems.add(name + " differs from " + report.origin.get(name)
                                + " in the archive: " + archive + " vs " + got);
                        continue;
                    }
                }
                if (name.startsWith(plan.nativeDir)) {
                    String base = name.substring(plan.nativeDir.length());
                    if (Names.dotCount(base) != 1) {
                        result.problems.add(base + " has more than one dot: Android would not "
                                + "extract it, and the app's symlink would point at nothing");
                    }
                }
            }
            result.checked.add(report.added.size()
                    + " added entries: present once, stored where they must be, byte-identical");

            // 2. The shell's own libraries are still there. Packing must not
            //    have replaced the loader, the launcher or the shim.
            for (String own : plan.ownLibraries) {
                if (zip.getEntry(plan.nativeDir + own) == null) {
                    result.problems.add(own + " is missing from the packed APK");
                }
            }
            if (!plan.ownLibraries.isEmpty()) {
                result.checked.add("the shell's own runtime is intact ("
                        + plan.ownLibraries.size() + " libraries)");
            }

            // 3. The map and the APK agree, where there is a map.
            if (plan.isTree() && plan.mapAsset != null) {
                ZipEntry mapEntry = zip.getEntry(plan.mapAsset);
                if (mapEntry == null) {
                    result.problems.add(plan.mapAsset + " is not in the packed APK");
                } else {
                    String text = new String(read(zip, mapEntry), java.nio.charset.StandardCharsets.UTF_8);
                    int named = 0;
                    for (String line : text.split("\n")) {
                        String[] parts = line.split("\t");
                        if (parts.length < 3 || !parts[0].equals("lib")) {
                            continue;
                        }
                        String packagedName = parts[2].trim();
                        named++;
                        if (!Names.extractable(packagedName)) {
                            result.problems.add("the map names " + packagedName
                                    + ", which Android would not extract");
                        }
                        if (plan.ownLibraries.contains(packagedName)) {
                            result.problems.add("the map names " + packagedName
                                    + ", which is one of the shell's own libraries");
                        }
                        if (zip.getEntry(plan.nativeDir + packagedName) == null) {
                            result.problems.add("the map names " + packagedName
                                    + " but the APK has no such entry");
                        }
                    }
                    result.checked.add("the map and the APK agree on " + named + " entries, and "
                            + "every entry name has exactly one dot");
                }
                if (zip.getEntry(plan.treeAsset) == null) {
                    result.problems.add(plan.treeAsset + " is not in the packed APK");
                }
            }

            // 4. No stale signature file survived the surgery.
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (ApkPacker.isStaleSignature(name)) {
                    result.problems.add("a stale signature file survived: " + name);
                }
            }
        }
        return result;
    }

    private static List<ZipEntry> matching(ZipFile zip, String name) {
        List<ZipEntry> found = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.getName().equals(name)) {
                found.add(entry);
            }
        }
        return found;
    }

    private static String sha256(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return Sha256.of(in);
        }
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            ArchiveReader.copy(in, sink);
            return sink.toByteArray();
        }
    }
}
