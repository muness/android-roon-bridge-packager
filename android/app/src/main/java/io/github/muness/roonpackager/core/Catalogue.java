package io.github.muness.roonpackager.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * What this app knows about, as data.
 *
 * <p>The whole point of the shape: adding a third endpoint is a change to
 * {@code assets/catalogue.json} and nothing else. Every fact a packing run
 * needs -- where the vendor's archive comes from, what its hash must be, what
 * the user is acknowledging, where the app shell comes from, and which archive
 * members are executable and which are data -- is a field here, not a branch in
 * code. The two pack plans ({@code single-member} and {@code tree}) are the two
 * shapes the two desktop packers implement; a third endpoint that is either
 * shape needs no code.
 *
 * <p>Nothing here is a vendor byte. A URL and a hash are the opposite of
 * redistribution: they are how this app can say "that is the file" without ever
 * having carried it.
 */
public final class Catalogue {

    /** Bumped when a field's meaning changes, not when one is added. */
    public int schema;

    public List<Endpoint> endpoints = new ArrayList<>();

    public static Catalogue read(InputStream in) {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Catalogue catalogue = gson().fromJson(reader, Catalogue.class);
            if (catalogue == null) {
                throw new IllegalArgumentException("the catalogue is empty");
            }
            catalogue.validate();
            return catalogue;
        } catch (java.io.IOException error) {
            throw new IllegalStateException("cannot read the catalogue", error);
        }
    }

    public static Gson gson() {
        return new GsonBuilder().setPrettyPrinting().create();
    }

    /**
     * Every sha256 this catalogue currently names -- every version of every
     * endpoint's vendor archive, every shell, every supplement -- lower-cased.
     * This is "still referenced" for the download cache: a cached file whose
     * hash is not in this set belongs to no catalogue entry any more and is
     * safe to evict.
     */
    public java.util.Set<String> referencedHashes() {
        java.util.Set<String> hashes = new java.util.LinkedHashSet<>();
        for (Endpoint endpoint : endpoints) {
            if (endpoint.shell != null && endpoint.shell.sha256 != null) {
                hashes.add(endpoint.shell.sha256.toLowerCase(java.util.Locale.ROOT));
            }
            if (endpoint.vendorArchive != null) {
                for (String hash : endpoint.vendorArchive.sha256ByVersion.values()) {
                    if (hash != null && !hash.isEmpty()) {
                        hashes.add(hash.toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
            if (endpoint.packPlan != null) {
                for (Supplement supplement : endpoint.packPlan.supplements) {
                    if (supplement.sha256 != null && !supplement.sha256.isEmpty()) {
                        hashes.add(supplement.sha256.toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
        }
        return hashes;
    }

    public Endpoint byId(String id) {
        for (Endpoint endpoint : endpoints) {
            if (endpoint.id.equals(id)) {
                return endpoint;
            }
        }
        throw new IllegalArgumentException("no endpoint called " + id + " in the catalogue");
    }

    public void validate() {
        if (schema != 1) {
            throw new IllegalArgumentException("catalogue schema " + schema + " is not 1");
        }
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("the catalogue names no endpoints");
        }
        for (Endpoint endpoint : endpoints) {
            endpoint.validate();
        }
    }

    /** One installable audio endpoint. */
    public static final class Endpoint {
        public String id;
        public String displayName;
        public String vendor;
        /** The version the URL pattern is filled in with, and hashed against. */
        public String vendorVersion;
        public Archive vendorArchive;
        public Licence licence;
        public Shell shell;
        public PackPlan packPlan;
        /** The packed app's own diagnostics port, for the UI to name. */
        public int endpointDiagnosticsPort;

        public String vendorArchiveUrl() {
            return vendorArchive.urlFor(vendorVersion);
        }

        public String vendorArchiveSha256() {
            return vendorArchive.sha256For(vendorVersion);
        }

        public void validate() {
            require(id, "id");
            require(displayName, "displayName");
            require(vendorVersion, "vendorVersion");
            if (vendorArchive == null || licence == null || shell == null || packPlan == null) {
                throw new IllegalArgumentException(id + ": vendorArchive, licence, shell and "
                        + "packPlan are all required");
            }
            vendorArchive.validate(id, vendorVersion);
            licence.validate(id);
            shell.validate(id);
            packPlan.validate(id);
        }
    }

    /** A downloadable archive: where it is, what shape it is, what it hashes to. */
    public static final class Archive {
        /** {@code {version}} is the only placeholder. */
        public String urlPattern;
        /** {@code deb} or {@code tar.bz2}. */
        public String format;
        /** version -> sha256. A version with no hash here is refused. */
        public java.util.Map<String, String> sha256ByVersion = new java.util.LinkedHashMap<>();
        /**
         * What to do about a version with no recorded hash. The default is to
         * refuse it. With this set, the run downloads the archive, reports the
         * sha256 it got, and stops -- it still does not pack an archive nobody
         * has recognised, but it tells whoever is adding a new vendor release
         * exactly what to write in the catalogue. That is the honest shape of
         * "the vendor shipped a new build this morning".
         */
        public boolean pinOnFirstUse;

        public boolean hasHashFor(String version) {
            String hash = sha256ByVersion.get(version);
            return hash != null && !hash.isEmpty();
        }

        public String urlFor(String version) {
            return urlPattern.replace("{version}", version);
        }

        public String sha256For(String version) {
            String hash = sha256ByVersion.get(version);
            if (hash == null || hash.isEmpty()) {
                throw new IllegalArgumentException("no expected sha256 for version " + version
                        + ". This app refuses to pack an archive it cannot recognise.");
            }
            return hash.toLowerCase(java.util.Locale.ROOT);
        }

        public void validate(String id, String version) {
            require(urlPattern, id + ".vendorArchive.urlPattern");
            require(format, id + ".vendorArchive.format");
            if (!ArchiveReader.knowsFormat(format)) {
                throw new IllegalArgumentException(id + ": unknown archive format " + format);
            }
            if (!pinOnFirstUse) {
                sha256For(version);
            }
        }
    }

    /**
     * What the user is told, and must acknowledge, before the first download.
     * A plain statement: they are downloading the vendor's software under the
     * vendor's terms, and this app redistributes nothing.
     */
    public static final class Licence {
        public String title;
        public String text;
        public String vendorUrl;

        public void validate(String id) {
            require(title, id + ".licence.title");
            require(text, id + ".licence.text");
        }
    }

    /**
     * The unpacked app shell: this project's own APK, carrying no vendor byte.
     * A GitHub release asset once releases exist; a {@code file:} path or a LAN
     * {@code http://} URL until then, which is how this gets tested before
     * there is anything to release.
     */
    public static final class Shell {
        public String url;
        public String sha256;
        public String packageId;
        public String versionName;

        public void validate(String id) {
            require(url, id + ".shell.url");
            require(packageId, id + ".shell.packageId");
            if (sha256 == null || sha256.isEmpty()) {
                throw new IllegalArgumentException(id + ".shell.sha256 is required: an app "
                        + "shell this app cannot recognise is one it will not sign.");
            }
        }
    }

    /**
     * How the archive becomes APK entries. Two shapes, one per desktop packer.
     *
     * <ul>
     *   <li>{@code single-member}: exactly the named members are copied to the
     *       named APK entries, stored uncompressed. The NAA packer.</li>
     *   <li>{@code tree}: every ELF file in the archive becomes a native entry
     *       under the single-dot rule, everything else goes into one asset
     *       archive, and a map records what went where. The Roon packer.</li>
     * </ul>
     */
    public static final class PackPlan {
        public String kind;
        /** Where native entries live. Always {@code lib/arm64-v8a/} here. */
        public String nativeDir = "lib/arm64-v8a/";
        /**
         * What a stored native entry's data is aligned to. 4 is what both
         * desktop packers produce as they are actually run, because both shells
         * set {@code useLegacyPackaging = true} and Android therefore extracts
         * the libraries at install time rather than mapping them out of the
         * APK. 4096 page-aligns them for a shell that does not.
         */
        public int nativeAlignment = 4;
        /** Entries removed from the shell before anything is added. */
        public List<String> removeEntries = new ArrayList<>();
        /** Plain-text assets the packed APK must carry, e.g. a runtime marker. */
        public List<AssetLiteral> assets = new ArrayList<>();

        // ---- single-member
        public List<Member> members = new ArrayList<>();

        // ---- tree
        public String renameSuffix;
        public String treeAsset;
        public String mapAsset;
        public String entryAssembly;
        public String versionFile;
        public String hostfxrName;
        public List<String> apphostChildren = new ArrayList<>();
        public List<String> apphostSupervisors = new ArrayList<>();
        /**
         * The shell's own native entries. Two uses, both from the desktop
         * packer: nothing out of the vendor tree may land on one of these, and
         * all of them must still be there when the run is finished.
         */
        public List<String> ownLibraries = new ArrayList<>();
        /**
         * Libraries the vendor program needs that the shell does not carry,
         * from their own distribution's archives. The desktop Roon packer takes
         * these out of a Debian container, which a phone cannot run; the same
         * files come out of the same distribution's {@code .deb} files, which
         * it can download.
         */
        public List<Supplement> supplements = new ArrayList<>();

        public boolean isTree() {
            return "tree".equals(kind);
        }

        public void validate(String id) {
            require(kind, id + ".packPlan.kind");
            if ("single-member".equals(kind)) {
                if (members.isEmpty()) {
                    throw new IllegalArgumentException(id + ": a single-member plan needs members");
                }
                for (Member member : members) {
                    member.validate(id);
                }
            } else if (isTree()) {
                require(renameSuffix, id + ".packPlan.renameSuffix");
                require(treeAsset, id + ".packPlan.treeAsset");
                require(mapAsset, id + ".packPlan.mapAsset");
                require(entryAssembly, id + ".packPlan.entryAssembly");
                if (ownLibraries.isEmpty()) {
                    throw new IllegalArgumentException(id + ": a tree plan must name the shell's "
                            + "own libraries, or a packing run could overwrite the runtime it "
                            + "needs in order to run anything");
                }
            } else {
                throw new IllegalArgumentException(id + ": unknown packPlan kind " + kind);
            }
            for (Supplement supplement : supplements) {
                supplement.validate(id);
            }
        }
    }

    /** One named member of the archive, and where it goes. */
    public static final class Member {
        /** Path inside the archive, without a leading {@code ./}. */
        public String path;
        /** {@code executable} or {@code data}. */
        public String role;
        /** The APK entry it becomes. */
        public String apkEntry;
        /** {@code aarch64} to require an ELF64 AArch64 executable. */
        public String requireElf;
        /**
         * The name the loader will ask for, when it is not the member's own
         * basename. Debian ships {@code libz.so.1} as a symlink to
         * {@code libz.so.1.3.1}, and a tar member that is a symlink carries no
         * bytes, so the member is the real file and this is the name the APK
         * entry and the map are derived from.
         */
        public String soname;

        /** The name this member is carried and recorded under. */
        public String effectiveName() {
            if (soname != null && !soname.isEmpty()) {
                return soname;
            }
            return path.substring(path.lastIndexOf('/') + 1);
        }

        public boolean executable() {
            return "executable".equals(role);
        }

        public void validate(String id) {
            require(path, id + ".member.path");
            require(role, id + ".member.role");
            require(apkEntry, id + ".member.apkEntry");
            if (!executable() && !"data".equals(role)) {
                throw new IllegalArgumentException(id + ": member role " + role
                        + " is neither executable nor data");
            }
            if (executable()) {
                String base = apkEntry.substring(apkEntry.lastIndexOf('/') + 1);
                if (!Names.extractable(base)) {
                    throw new IllegalArgumentException(id + ": " + apkEntry + " is not a name "
                            + "Android extracts (lib*.so with exactly one dot)");
                }
            }
        }
    }

    /**
     * A short text file the packed APK must carry. The NAA shell's
     * {@code assets/runtime-variant} is the only one so far, and it is how
     * {@code NaaService} knows to create the symlink directory the unmodified
     * binary needs.
     */
    public static final class AssetLiteral {
        public String entry;
        public String value;
        public boolean compress = true;
    }

    /** A distribution package the vendor program needs alongside its own tree. */
    public static final class Supplement {
        public String url;
        public String sha256;
        public String format;
        public List<Member> members = new ArrayList<>();
        /** False when the pack may proceed without it, with a warning. */
        public boolean required = true;

        public void validate(String id) {
            require(url, id + ".supplement.url");
            if (sha256 == null || sha256.isEmpty()) {
                throw new IllegalArgumentException(id + ".supplement.sha256 is required for "
                        + url + ": a package this app cannot recognise is one it will not pack.");
            }
            for (Member member : members) {
                member.validate(id);
            }
        }
    }

    private static void require(String value, String what) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(what + " is required");
        }
    }
}
