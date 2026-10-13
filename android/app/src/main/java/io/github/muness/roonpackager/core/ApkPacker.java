package io.github.muness.roonpackager.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The two desktop packers, ported, so the same bytes come out on a phone.
 *
 * <p>{@code scripts/pack-naa-apk.sh} and {@code scripts/pack-roon-apk.sh} do
 * the same three things in shell that this does in Java: take one or many
 * members out of an archive the user downloaded, put them into an APK that
 * shipped without them under the names Android's packaging rule forces, and
 * prove nothing changed on the way by comparing sha256 either side. What is
 * different here is only where it runs.
 *
 * <p>Android forbids executing a file out of an app's data directory, and that
 * is the whole reason for the shape: an app may write an APK and hand it to
 * {@code PackageInstaller}, and the installer extracts that APK's
 * {@code lib/arm64-v8a/lib*.so} entries into the installed app's library
 * directory, where they <em>are</em> executable. No old {@code targetSdk}, no
 * root, no {@code exec} out of {@code filesDir}.
 *
 * <p>Nothing in this class writes a vendor byte anywhere but into the APK it
 * was asked to build, and nothing modifies one. There is no patchelf, no strip,
 * no relinking: a member is copied and, where Android's naming rule requires
 * it, renamed.
 */
public final class ApkPacker {

    /** What one run put where, and what the verifier then checked. */
    public static final class Report {
        public final File packedApk;
        /** APK entry name -> sha256 of its content. */
        public final Map<String, String> entrySha256 = new LinkedHashMap<>();
        /** APK entry name -> STORED or DEFLATED. */
        public final Map<String, String> entryMethod = new LinkedHashMap<>();
        /** The entries this run added, in the order it added them. */
        public final List<String> added = new ArrayList<>();
        /** Native entry name -> the archive member it came from. */
        public final Map<String, String> origin = new LinkedHashMap<>();
        public String vendorVersion = "";
        public final List<String> notes = new ArrayList<>();

        Report(File packedApk) {
            this.packedApk = packedApk;
        }

        /**
         * The parity manifest: one line per entry, {@code <method> <sha256>
         * <name>}, sorted. This is what the desktop run and the device run are
         * compared on. Alignment padding lives in a local header's extra field
         * and the signature lives in a block of its own, so neither appears
         * here -- which is exactly right: what has to match is the entry list,
         * how each entry is stored, and the bytes of each entry's content.
         */
        public String parityManifest() {
            List<String> names = new ArrayList<>(entrySha256.keySet());
            Collections.sort(names);
            StringBuilder text = new StringBuilder();
            for (String name : names) {
                text.append(entryMethod.get(name)).append(' ')
                        .append(entrySha256.get(name)).append(' ')
                        .append(name).append('\n');
            }
            return text.toString();
        }
    }

    private static final String[] SIGNATURE_PREFIXES = {"META-INF/"};

    private final Catalogue.Endpoint endpoint;
    private final PackProgress progress;

    public ApkPacker(Catalogue.Endpoint endpoint, PackProgress progress) {
        this.endpoint = endpoint;
        this.progress = progress == null ? PackProgress.NONE : progress;
    }

    /**
     * Builds the unsigned packed APK.
     *
     * @param shellApk    the app shell, carrying no vendor byte
     * @param vendorTree  the extracted vendor archive
     * @param supplements extracted supplement archives, in catalogue order
     * @param outputApk   where the result goes
     */
    public Report pack(File shellApk, ArchiveReader.Tree vendorTree,
            List<ArchiveReader.Tree> supplements, File outputApk) throws IOException {
        Catalogue.PackPlan plan = endpoint.packPlan;
        progress.step(PackProgress.Step.PACKING, endpoint.displayName);
        Report report = new Report(outputApk);

        // What this run will add, name -> the file whose bytes go in, plus how.
        Map<String, File> nativeEntries = new LinkedHashMap<>();
        Map<String, byte[]> deflatedAssets = new LinkedHashMap<>();
        Map<String, byte[]> storedAssets = new LinkedHashMap<>();
        Set<String> remove = new LinkedHashSet<>(plan.removeEntries);

        if (plan.isTree()) {
            planTree(plan, vendorTree, supplements, nativeEntries, deflatedAssets, storedAssets,
                    remove, report);
        } else {
            planSingleMembers(plan, vendorTree, nativeEntries, deflatedAssets, report);
        }

        // A literal asset is confirmed rather than rewritten. The desktop
        // packer's words for assets/runtime-variant are "confirmed to say
        // unpatched, and written if it is somehow missing", and rewriting an
        // entry that already says the right thing would change how it is
        // stored for no reason -- and would show up as a difference in the
        // parity check for no reason.
        for (Catalogue.AssetLiteral literal : plan.assets) {
            byte[] content = (literal.value + "\n").getBytes(StandardCharsets.UTF_8);
            byte[] existing = entryContent(shellApk, literal.entry);
            if (existing != null && sameText(existing, content)) {
                progress.note(literal.entry + " already says " + literal.value);
                continue;
            }
            progress.note((existing == null ? "adding " : "correcting ") + literal.entry
                    + " = " + literal.value);
            if (literal.compress) {
                deflatedAssets.put(literal.entry, content);
            } else {
                storedAssets.put(literal.entry, content);
            }
        }

        remove.addAll(nativeEntries.keySet());
        remove.addAll(deflatedAssets.keySet());
        remove.addAll(storedAssets.keySet());

        write(shellApk, outputApk, remove, nativeEntries, storedAssets, deflatedAssets,
                plan, report);
        return report;
    }

    // ------------------------------------------------------------------ plans

    /**
     * The NAA shape: exactly the named members, at exactly the named entries.
     * One line of the desktop script, and the only rename in it.
     */
    private void planSingleMembers(Catalogue.PackPlan plan, ArchiveReader.Tree tree,
            Map<String, File> nativeEntries, Map<String, byte[]> deflatedAssets, Report report)
            throws IOException {
        for (Catalogue.Member member : plan.members) {
            File source = tree.file(member.path);
            if (!source.isFile()) {
                throw new IOException("no " + member.path + " inside the archive. Is that the "
                        + "right package for " + endpoint.displayName + "?");
            }
            if ("aarch64".equals(member.requireElf)) {
                ArchiveReader.requireAarch64Executable(source);
            }
            if (member.executable()) {
                nativeEntries.put(member.apkEntry, source);
            } else {
                deflatedAssets.put(member.apkEntry, ZipWriter.readAll(source));
            }
            report.origin.put(member.apkEntry, member.path);
            progress.note(member.path + " -> " + member.apkEntry + " ("
                    + Sha256.of(source) + ")");
        }
    }

    /**
     * The Roon shape: every ELF file becomes a native entry under the
     * single-dot rule, everything else goes into one asset archive, and a map
     * records what went where so the app can put the original paths back as
     * symlinks on first run.
     */
    private void planTree(Catalogue.PackPlan plan, ArchiveReader.Tree tree,
            List<ArchiveReader.Tree> supplements, Map<String, File> nativeEntries,
            Map<String, byte[]> deflatedAssets, Map<String, byte[]> storedAssets,
            Set<String> remove, Report report) throws IOException {
        String root = treeRoot(tree);
        StringBuilder map = new StringBuilder();
        map.append("# written by the on-device packer -- what went into the APK, and where\n");

        String version = readVersion(tree, root, plan);
        report.vendorVersion = version;
        String entryAssembly = find(tree, root, plan.entryAssembly);
        if (entryAssembly == null) {
            throw new IOException("no " + plan.entryAssembly + " in this archive. Is it "
                    + endpoint.displayName + "?");
        }
        map.append("version\t").append(version).append('\n');
        map.append("entry\t").append(entryAssembly).append('\n');

        List<String> plain = new ArrayList<>();
        String hostfxr = null;
        for (String path : tree.paths) {
            if (!path.startsWith(root)) {
                continue;
            }
            String relative = path.substring(root.length());
            String base = relative.substring(relative.lastIndexOf('/') + 1);
            File file = tree.file(path);
            if (!ArchiveReader.isElf(file)) {
                plain.add(relative);
                continue;
            }
            if (plan.apphostChildren.contains(base)) {
                // A stock .NET apphost. It cannot run here at all -- its
                // PT_INTERP is /lib/ld-linux-aarch64.so.1 -- so it is recorded
                // as a child the app's own shim stands in for, and not packed.
                map.append("child\t").append(relative).append('\n');
                continue;
            }
            if (plan.apphostSupervisors.contains(base)) {
                // The one the app launches itself, through its own launcher.
                continue;
            }
            String packaged = Names.packaged(base, plan.renameSuffix);
            if (plan.ownLibraries.contains(packaged)) {
                throw new IOException(relative + " would be packaged as " + packaged
                        + ", which is one of the shell's own libraries. The packer will not "
                        + "overwrite the runtime it needs in order to run anything.");
            }
            String entry = plan.nativeDir + packaged;
            if (nativeEntries.containsKey(entry)) {
                throw new IOException("two files in the archive would be packaged as " + packaged
                        + ": " + report.origin.get(entry) + " and " + relative
                        + ". The packer will not guess which one an entry is.");
            }
            nativeEntries.put(entry, file);
            report.origin.put(entry, relative);
            map.append("lib\t").append(relative).append('\t').append(packaged).append('\n');
            if (base.equals(plan.hostfxrName)) {
                hostfxr = packaged;
            }
        }
        if (plan.hostfxrName != null && hostfxr == null) {
            throw new IOException("no " + plan.hostfxrName + " in this archive, so there is "
                    + "nothing to hand the launcher. Is this the self-contained Linux ARMv8 "
                    + "build?");
        }
        if (hostfxr != null) {
            map.append("hostfxr\t").append(hostfxr).append('\n');
        }

        // The supplements: libraries the vendor program needs that the shell
        // does not carry, out of their own distribution's packages. They go in
        // under the same rule, and the map names them so the app can put them
        // first on the loader's search path under their real SONAMEs.
        for (int index = 0; index < supplements.size(); index++) {
            Catalogue.Supplement supplement = plan.supplements.get(index);
            ArchiveReader.Tree supplementTree = supplements.get(index);
            for (Catalogue.Member member : supplement.members) {
                File source = supplementTree.file(member.path);
                if (!source.isFile()) {
                    if (supplement.required) {
                        throw new IOException("no " + member.path + " in " + supplement.url);
                    }
                    progress.note("missing, and not required: " + member.path);
                    continue;
                }
                String soname = member.effectiveName();
                String packaged = Names.packaged(soname, plan.renameSuffix);
                if (plan.ownLibraries.contains(packaged)) {
                    throw new IOException(soname + " would be packaged as " + packaged
                            + ", which is one of the shell's own libraries.");
                }
                String entry = plan.nativeDir + packaged;
                if (nativeEntries.containsKey(entry)) {
                    progress.note(packaged + " came out of the vendor archive already; the "
                            + "supplement copy is not used");
                    continue;
                }
                nativeEntries.put(entry, source);
                report.origin.put(entry, member.path);
                map.append("lib\tlib/").append(soname).append('\t').append(packaged).append('\n');
            }
        }

        // Everything that is not an ELF file, in one archive, deterministically
        // ordered so two runs produce the same asset.
        Collections.sort(plain);
        File treeZip = new File(tree.root.getParentFile(), "vendor-tree.zip");
        try (ZipWriter writer = new ZipWriter(treeZip)) {
            for (String relative : plain) {
                writer.deflated(relative, ZipWriter.readAll(tree.file(root + relative)));
            }
        }
        storedAssets.put(plan.treeAsset, ZipWriter.readAll(treeZip));
        //noinspection ResultOfMethodCallIgnored
        treeZip.delete();
        deflatedAssets.put(plan.mapAsset, map.toString().getBytes(StandardCharsets.UTF_8));

        progress.note(nativeEntries.size() + " executable entries, " + plain.size()
                + " plain files, entry " + entryAssembly + ", version " + version);
    }

    /**
     * A previous packing run's entries, from that run's own map: not every name
     * it added ends in the rename suffix, so the map is the only record. Read
     * in {@link #readPreviousMap} and applied in {@link #write}.
     */
    private List<String> previousMapNames = new ArrayList<>();

    /** Reads the shell's own map, if a previous run left one. */
    private void readPreviousMap(File shellApk, Catalogue.PackPlan plan) {
        previousMapNames = new ArrayList<>();
        if (!plan.isTree() || plan.mapAsset == null) {
            return;
        }
        try (ZipFile zip = new ZipFile(shellApk)) {
            ZipEntry entry = zip.getEntry(plan.mapAsset);
            if (entry == null) {
                return;
            }
            String text;
            try (InputStream in = zip.getInputStream(entry)) {
                ByteArrayOutputStream sink = new ByteArrayOutputStream();
                ArchiveReader.copy(in, sink);
                text = new String(sink.toByteArray(), StandardCharsets.UTF_8);
            }
            for (String line : text.split("\n")) {
                String[] parts = line.split("\t");
                if (parts.length >= 3 && parts[0].equals("lib")) {
                    previousMapNames.add(parts[2].trim());
                }
            }
        } catch (IOException ignored) {
            // A shell with no readable map simply had no previous run.
        }
    }

    private static String treeRoot(ArchiveReader.Tree tree) throws IOException {
        // The Roon tarball has one top-level directory and the app's tree is
        // inside it. A .deb has no such wrapper, and neither does a tree that
        // was already unwrapped.
        Set<String> tops = new LinkedHashSet<>();
        for (String path : tree.paths) {
            int slash = path.indexOf('/');
            tops.add(slash < 0 ? "" : path.substring(0, slash));
        }
        if (tops.size() == 1) {
            String only = tops.iterator().next();
            if (!only.isEmpty()) {
                return only + "/";
            }
        }
        return "";
    }

    private static String readVersion(ArchiveReader.Tree tree, String root,
            Catalogue.PackPlan plan) throws IOException {
        if (plan.versionFile == null) {
            return "unknown";
        }
        File file = tree.file(root + plan.versionFile);
        if (!file.isFile()) {
            return "unknown";
        }
        String text = new String(ZipWriter.readAll(file), StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.matches(".*[0-9]+\\.[0-9]+.*")) {
                return trimmed;
            }
        }
        return "unknown";
    }

    private static String find(ArchiveReader.Tree tree, String root, String base) {
        for (String path : tree.paths) {
            if (path.startsWith(root) && path.endsWith("/" + base)) {
                return path.substring(root.length());
            }
        }
        return null;
    }

    // ------------------------------------------------------------- zip surgery

    private void write(File shellApk, File outputApk, Set<String> remove,
            Map<String, File> nativeEntries, Map<String, byte[]> storedAssets,
            Map<String, byte[]> deflatedAssets, Catalogue.PackPlan plan, Report report)
            throws IOException {
        readPreviousMap(shellApk, plan);
        if (plan.isTree()) {
            // A previous packing run's entries go two ways, both of them the
            // desktop packer's: by the suffix it invents names with, and by
            // that run's own map -- because not every name it added ends in the
            // suffix, a name Android already extracts having been kept as it
            // was. The shell's own libraries are never deleted by either rule,
            // so a wrong map cannot take the runtime with it.
            try (ZipFile shell = new ZipFile(shellApk)) {
                Enumeration<? extends ZipEntry> existing = shell.entries();
                while (existing.hasMoreElements()) {
                    String name = existing.nextElement().getName();
                    if (!name.startsWith(plan.nativeDir)) {
                        continue;
                    }
                    String base = name.substring(plan.nativeDir.length());
                    if (base.contains("/") || plan.ownLibraries.contains(base)) {
                        continue;
                    }
                    if (base.endsWith(plan.renameSuffix)) {
                        remove.add(name);
                    }
                }
            }
            for (String name : previousMapNames) {
                if (name.contains("/") || name.startsWith(".")
                        || plan.ownLibraries.contains(name)) {
                    continue;
                }
                remove.add(plan.nativeDir + name);
            }
        }

        int align = plan.nativeAlignment > 0 ? plan.nativeAlignment : 4;
        List<String> keptOwn = new ArrayList<>();

        try (ZipFile shell = new ZipFile(shellApk);
                ZipWriter writer = new ZipWriter(outputApk)) {
            Enumeration<? extends ZipEntry> entries = shell.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (isStaleSignature(name)) {
                    // Any earlier signature is void the moment the zip changes.
                    continue;
                }
                if (remove.contains(name)) {
                    continue;
                }
                byte[] content;
                try (InputStream in = shell.getInputStream(entry)) {
                    ByteArrayOutputStream sink = new ByteArrayOutputStream(
                            (int) Math.max(64, entry.getSize()));
                    ArchiveReader.copy(in, sink);
                    content = sink.toByteArray();
                }
                if (entry.getMethod() == ZipEntry.STORED) {
                    writer.stored(name, content, align);
                    record(report, name, "STORED", content);
                } else {
                    writer.deflated(name, content, false);
                    record(report, name, "DEFLATED", content);
                }
                if (name.startsWith(plan.nativeDir)) {
                    keptOwn.add(name.substring(plan.nativeDir.length()));
                }
            }

            for (Map.Entry<String, File> native_ : nativeEntries.entrySet()) {
                byte[] content = ZipWriter.readAll(native_.getValue());
                writer.stored(native_.getKey(), content, align);
                record(report, native_.getKey(), "STORED", content);
                report.added.add(native_.getKey());
            }
            for (Map.Entry<String, byte[]> asset : storedAssets.entrySet()) {
                writer.stored(asset.getKey(), asset.getValue(), align);
                record(report, asset.getKey(), "STORED", asset.getValue());
                report.added.add(asset.getKey());
            }
            for (Map.Entry<String, byte[]> asset : deflatedAssets.entrySet()) {
                writer.deflated(asset.getKey(), asset.getValue());
                record(report, asset.getKey(), "DEFLATED", asset.getValue());
                report.added.add(asset.getKey());
            }
        }

        if (plan.isTree()) {
            for (String own : plan.ownLibraries) {
                if (!keptOwn.contains(own)) {
                    report.notes.add("the shell does not carry " + own);
                }
            }
        }
    }

    /** The content of one entry in an APK, or null when it is not there. */
    static byte[] entryContent(File apk, String name) {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                ByteArrayOutputStream sink = new ByteArrayOutputStream();
                ArchiveReader.copy(in, sink);
                return sink.toByteArray();
            }
        } catch (IOException error) {
            return null;
        }
    }

    /** Equal once trailing whitespace is ignored, which is what a marker is. */
    static boolean sameText(byte[] left, byte[] right) {
        String one = new String(left, StandardCharsets.UTF_8).trim();
        String two = new String(right, StandardCharsets.UTF_8).trim();
        return one.equals(two);
    }

    private static void record(Report report, String name, String method, byte[] content) {
        report.entryMethod.put(name, method);
        report.entrySha256.put(name, Sha256.of(content));
    }

    static boolean isStaleSignature(String name) {
        for (String prefix : SIGNATURE_PREFIXES) {
            if (!name.startsWith(prefix)) {
                continue;
            }
            String upper = name.toUpperCase(Locale.ROOT);
            if (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                    || upper.endsWith(".EC") || upper.endsWith("/MANIFEST.MF")) {
                return true;
            }
        }
        return false;
    }
}
