package io.github.muness.roonpackager.core;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One whole run, from a URL to an APK ready for the installer.
 *
 * <p>Download, verify, extract, pack, sign, verify -- the seven step names the
 * progress view shows, in this order, with the eighth (installing) belonging to
 * the caller because only the Android layer can talk to
 * {@code PackageInstaller}.
 *
 * <p>No Android imports, on purpose. The same class does the desktop parity
 * run and the on-device run, which is the only way "the manager produces the
 * same bytes the desktop scripts produce" can be checked rather than asserted.
 */
public final class PackRun {

    public static final class Outcome {
        public final File signedApk;
        public final ApkPacker.Report report;
        public final PackVerifier.Result verification;
        public final String signature;
        public final String vendorArchiveSha256;
        public final boolean keyWasCreated;

        Outcome(File signedApk, ApkPacker.Report report, PackVerifier.Result verification,
                String signature, String vendorArchiveSha256, boolean keyWasCreated) {
            this.signedApk = signedApk;
            this.report = report;
            this.verification = verification;
            this.signature = signature;
            this.vendorArchiveSha256 = vendorArchiveSha256;
            this.keyWasCreated = keyWasCreated;
        }
    }

    private final Catalogue.Endpoint endpoint;
    private final File workDir;
    private final DownloadCache cache;
    private final File keystore;
    private final int minSdk;
    private final PackProgress progress;

    /**
     * @param workDir scratch space, emptied at the start of each run
     * @param cache   where downloads are kept, by the hash they were verified
     *                against, so a repeated or resumed run does not re-fetch
     *                what it already has
     * @param keystore the persisted signing key
     */
    public PackRun(Catalogue.Endpoint endpoint, File workDir, DownloadCache cache, File keystore,
            int minSdk, PackProgress progress) {
        this.endpoint = endpoint;
        this.workDir = workDir;
        this.cache = cache;
        this.keystore = keystore;
        this.minSdk = minSdk;
        this.progress = progress == null ? PackProgress.NONE : progress;
    }

    public Outcome run() throws IOException, GeneralSecurityException {
        ArchiveReader.deleteTree(workDir);
        if (!workDir.mkdirs()) {
            if (!workDir.isDirectory()) {
                throw new IOException("cannot create " + workDir);
            }
        }

        // 1. The app shell: this project's own APK, carrying no vendor byte.
        progress.step(PackProgress.Step.DOWNLOADING_SHELL, endpoint.shell.url);
        File shell = cache.fetch(endpoint.shell.url, endpoint.shell.sha256,
                endpoint.id + " shell", endpoint.shell.versionName, progress);

        // 2. The vendor's archive, from the vendor's own URL, under the
        //    vendor's own terms, to this device and nowhere else.
        String vendorUrl = endpoint.vendorArchiveUrl();
        progress.step(PackProgress.Step.DOWNLOADING_VENDOR, vendorUrl);
        String vendorName = vendorUrl.substring(vendorUrl.lastIndexOf('/') + 1);
        if (!endpoint.vendorArchive.hasHashFor(endpoint.vendorVersion)) {
            // pin-on-first-use: fetch, say what it hashes to, and stop. An
            // archive nobody has recognised is never packed. It is still kept
            // in the cache under the hash it turned out to have, so once that
            // hash is pinned in the catalogue the next run is a cache hit, not
            // a second download.
            DownloadCache.Observed observed = cache.fetchObserving(vendorUrl,
                    endpoint.id + " vendor archive", endpoint.vendorVersion, progress);
            progress.note("PIN " + endpoint.id + " " + endpoint.vendorVersion + " "
                    + observed.sha256);
            throw new IOException("the catalogue has no sha256 for " + endpoint.displayName
                    + " " + endpoint.vendorVersion + ".\n"
                    + "What that URL served just now hashes to:\n  " + observed.sha256 + "\n"
                    + "Check it against what the vendor published, put it in the catalogue, and "
                    + "run this again. Nothing was packed.");
        }
        File archive = cache.fetch(vendorUrl, endpoint.vendorArchiveSha256(),
                endpoint.id + " vendor archive", endpoint.vendorVersion, progress);
        String vendorSha = Sha256.of(archive);

        // 3. Open it. ar + tar.xz for a .deb, bzip2 + tar for Roon's tarball.
        progress.step(PackProgress.Step.EXTRACTING, vendorName);
        ArchiveReader.Tree tree = ArchiveReader.extract(archive, endpoint.vendorArchive.format,
                new File(workDir, "vendor"));
        progress.note(tree.paths.size() + " files out of " + vendorName);

        List<ArchiveReader.Tree> supplements = new ArrayList<>();
        for (int index = 0; index < endpoint.packPlan.supplements.size(); index++) {
            Catalogue.Supplement supplement = endpoint.packPlan.supplements.get(index);
            progress.step(PackProgress.Step.DOWNLOADING_VENDOR, supplement.url);
            String name = supplement.url.substring(supplement.url.lastIndexOf('/') + 1);
            try {
                File file = cache.fetch(supplement.url, supplement.sha256,
                        endpoint.id + " supplement " + name, endpoint.vendorVersion, progress);
                progress.step(PackProgress.Step.EXTRACTING, name);
                supplements.add(ArchiveReader.extract(file, supplement.format,
                        new File(workDir, "supplement-" + index)));
            } catch (IOException error) {
                if (supplement.required) {
                    throw error;
                }
                progress.note("skipping " + name + ": " + error.getMessage());
                supplements.add(new ArchiveReader.Tree(new File(workDir, "empty-" + index),
                        new ArrayList<String>()));
            }
        }

        // 4. Pack: the same entry names, the same stored entries, the same
        //    assets the desktop scripts produce.
        File unsigned = new File(workDir, endpoint.id + "-unsigned.apk");
        ApkPacker packer = new ApkPacker(endpoint, progress);
        ApkPacker.Report report = packer.pack(shell, tree, supplements, unsigned);
        Map<String, File> sources = sourceFiles(report, tree, supplements);

        // 5. Sign with the persisted key, so a later run can update in place.
        progress.step(PackProgress.Step.SIGNING, keystore.getName());
        SigningKey key = SigningKey.loadOrCreate(keystore);
        if (key.created) {
            progress.note("made a signing key. Keep it: an update installs over the "
                    + "packed endpoint only while it is signed by this same key.");
        }
        File signed = new File(workDir, endpoint.id + "-packed.apk");
        ApkSigning.sign(unsigned, signed, key, minSdk);
        String signature = ApkSigning.verify(signed, minSdk);
        progress.note("signed: " + signature);

        // 6. Prove it before offering it.
        progress.step(PackProgress.Step.VERIFYING_RESULT, signed.getName());
        PackVerifier.Result verification = PackVerifier.verify(signed, endpoint, report, sources);
        progress.note(verification.summary().trim());
        if (!verification.ok()) {
            throw new IOException("the packed APK did not pass its own checks, so it is not "
                    + "being offered for installation:\n" + verification.summary());
        }
        //noinspection ResultOfMethodCallIgnored
        unsigned.delete();
        return new Outcome(signed, report, verification, signature, vendorSha, key.created);
    }

    /** APK entry name -> the extracted file its bytes came from. */
    private Map<String, File> sourceFiles(ApkPacker.Report report, ArchiveReader.Tree tree,
            List<ArchiveReader.Tree> supplements) {
        Map<String, File> sources = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : report.origin.entrySet()) {
            File file = tree.file(entry.getValue());
            if (!file.isFile()) {
                // A tree plan's origins are relative to the archive's own top
                // directory, and a supplement's are inside its own tree.
                for (String path : tree.paths) {
                    if (path.endsWith("/" + entry.getValue()) || path.equals(entry.getValue())) {
                        file = tree.file(path);
                        break;
                    }
                }
            }
            if (!file.isFile()) {
                for (ArchiveReader.Tree supplement : supplements) {
                    File candidate = supplement.file(entry.getValue());
                    if (candidate.isFile()) {
                        file = candidate;
                        break;
                    }
                }
            }
            if (file.isFile()) {
                sources.put(entry.getKey(), file);
            }
        }
        return sources;
    }
}
