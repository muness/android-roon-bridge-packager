package io.github.muness.roonpackager.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Downloads kept by the hash they were verified against, so a pack that has
 * already fetched a vendor archive or a shell APK does not fetch it again.
 *
 * <p>The catalogue's expected sha256 is the lookup key, on purpose: this app's
 * whole trust model is "the file that hashes to what the catalogue names", so
 * the cache is keyed on the same fact the download is already checked against,
 * not on a URL or a filename either of which a vendor can change under an
 * unrelated version bump.
 *
 * <p>One file per hash, named by the hash itself, in this app's own files
 * directory -- not {@code getCacheDir()}, which the platform is free to clear
 * whenever it likes, defeating the point. A pack that finds the expected hash
 * already here skips the download entirely. A file whose content no longer
 * hashes to its own name is corrupt on disk; it is deleted and treated as a
 * miss rather than handed to a packing run.
 *
 * <p>No Android imports, on purpose, same reason as {@link PackRun}: this runs
 * unchanged in the JVM tests and on the device.
 */
public final class DownloadCache {

    /** One cached file, for the UI and {@code GET /status}. */
    public static final class Entry {
        public final String sha256;
        /** What this file is, e.g. "naa vendor archive" or "roon shell". */
        public final String label;
        /** The version it was fetched for, e.g. "6.1.4-71". */
        public final String version;
        public final long sizeBytes;
        public final long lastUsedMillis;

        Entry(String sha256, String label, String version, long sizeBytes,
                long lastUsedMillis) {
            this.sha256 = sha256;
            this.label = label;
            this.version = version;
            this.sizeBytes = sizeBytes;
            this.lastUsedMillis = lastUsedMillis;
        }
    }

    /** What a pin-on-first-use download turned out to hash to. */
    public static final class Observed {
        public final File file;
        public final String sha256;

        Observed(File file, String sha256) {
            this.file = file;
            this.sha256 = sha256;
        }
    }

    private static final Type INDEX_TYPE =
            new TypeToken<LinkedHashMap<String, Meta>>() { }.getType();

    /** The metadata kept per hash; the file's own bytes are the cache entry. */
    private static final class Meta {
        String label;
        String version;
        long sizeBytes;
        long lastUsedMillis;
    }

    private final File dir;
    private final File index;
    private final Gson gson = new Gson();

    public DownloadCache(File dir) {
        this.dir = dir;
        this.index = new File(dir, "index.json");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /**
     * Fetches a file whose sha256 must be {@code expectedSha256}, using the
     * cache first. A local {@code file:} or {@code /} URL is not cached --
     * there is nothing to save a second fetch of, it is already on the device.
     */
    public File fetch(String url, String expectedSha256, String label, String version,
            PackProgress progress) throws IOException {
        if (Downloader.isLocal(url)) {
            return Downloader.fetch(url, expectedSha256, new File(dir, "local"), progress);
        }
        String hash = expectedSha256.toLowerCase(Locale.ROOT);
        File cached = fileFor(hash);
        if (cached.isFile()) {
            String actual = Sha256.of(cached);
            if (actual.equals(hash)) {
                touch(hash, label, version, cached.length());
                progress.note("cached: " + label + " " + hash + " " + cached.length());
                return cached;
            }
            progress.note("WARN cached file for " + hash + " no longer hashes to its own name "
                    + "(got " + actual + "); deleting and re-downloading");
            //noinspection ResultOfMethodCallIgnored
            cached.delete();
            remove(hash);
        }
        File partial = partFor(hash);
        Downloader.fetch(url, expectedSha256, partial, progress);
        moveIntoPlace(partial, cached);
        record(hash, label, version, cached.length());
        return cached;
    }

    /**
     * The pin-on-first-use path: the hash is not known ahead of the download,
     * so the partial file is kept under a name derived from the URL instead,
     * and the finished file is filed under the hash it turned out to have --
     * which is exactly the hash a later, pinned run will ask this cache for,
     * so pinning the catalogue turns the second run into a cache hit.
     */
    public Observed fetchObserving(String url, String label, String version,
            PackProgress progress) throws IOException {
        if (Downloader.isLocal(url)) {
            File local = Downloader.fetchObserving(url, new File(dir, "local-observed"), progress);
            return new Observed(local, Sha256.of(local));
        }
        File partial = new File(dir, Sha256.of(url.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + ".observing.part");
        File downloaded = Downloader.fetchObserving(url, partial, progress);
        String hash = Sha256.of(downloaded);
        File cached = fileFor(hash);
        moveIntoPlace(downloaded, cached);
        record(hash, label, version, cached.length());
        return new Observed(cached, hash);
    }

    public File fileFor(String sha256) {
        return new File(dir, sha256.toLowerCase(Locale.ROOT));
    }

    private File partFor(String sha256) {
        return new File(dir, sha256.toLowerCase(Locale.ROOT) + ".part");
    }

    private void moveIntoPlace(File from, File to) throws IOException {
        if (to.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            from.delete();
            return;
        }
        if (!from.renameTo(to)) {
            throw new IOException("could not move " + from + " into the download cache at " + to);
        }
    }

    /** Every cached file, most recently used first. */
    public synchronized List<Entry> list() {
        Map<String, Meta> loaded = readIndex();
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, Meta> row : loaded.entrySet()) {
            File file = fileFor(row.getKey());
            if (!file.isFile()) {
                continue;
            }
            Meta meta = row.getValue();
            entries.add(new Entry(row.getKey(), meta.label, meta.version, meta.sizeBytes,
                    meta.lastUsedMillis));
        }
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(b.lastUsedMillis, a.lastUsedMillis);
            }
        });
        return entries;
    }

    public long totalBytes() {
        long total = 0;
        for (Entry entry : list()) {
            total += entry.sizeBytes;
        }
        return total;
    }

    /** Deletes one cached file. Returns whether one was there to delete. */
    public synchronized boolean delete(String sha256) {
        String hash = sha256.toLowerCase(Locale.ROOT);
        boolean existed = fileFor(hash).isFile();
        //noinspection ResultOfMethodCallIgnored
        fileFor(hash).delete();
        remove(hash);
        return existed;
    }

    /** Deletes every cached file and every partial download. Returns count and bytes freed. */
    public synchronized long[] clearAll() {
        long count = 0;
        long bytes = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.equals(index)) {
                    continue;
                }
                bytes += file.length();
                if (file.isFile() && file.delete()) {
                    count++;
                }
            }
        }
        writeIndex(new LinkedHashMap<String, Meta>());
        return new long[] { count, bytes };
    }

    /**
     * Deletes every cached file whose hash is not in {@code keep}. Meant to be
     * called only after a pack finishes successfully, and only with the set of
     * hashes the current catalogue still names -- never mid-pack, or a run
     * could evict a file another step is about to read.
     */
    public synchronized void evictUnreferenced(Set<String> keep) {
        Map<String, Meta> loaded = readIndex();
        List<String> gone = new ArrayList<>();
        for (String hash : loaded.keySet()) {
            if (!keep.contains(hash)) {
                gone.add(hash);
            }
        }
        for (String hash : gone) {
            //noinspection ResultOfMethodCallIgnored
            fileFor(hash).delete();
            loaded.remove(hash);
        }
        writeIndex(loaded);
    }

    private synchronized void record(String sha256, String label, String version, long size) {
        Map<String, Meta> loaded = readIndex();
        Meta meta = new Meta();
        meta.label = label;
        meta.version = version;
        meta.sizeBytes = size;
        meta.lastUsedMillis = System.currentTimeMillis();
        loaded.put(sha256, meta);
        writeIndex(loaded);
    }

    private synchronized void touch(String sha256, String label, String version, long size) {
        record(sha256, label, version, size);
    }

    private synchronized void remove(String sha256) {
        Map<String, Meta> loaded = readIndex();
        if (loaded.remove(sha256) != null) {
            writeIndex(loaded);
        }
    }

    private Map<String, Meta> readIndex() {
        if (!index.isFile()) {
            return new LinkedHashMap<>();
        }
        try (FileReader reader = new FileReader(index)) {
            Map<String, Meta> loaded = gson.fromJson(reader, INDEX_TYPE);
            return loaded == null ? new LinkedHashMap<>() : loaded;
        } catch (IOException | RuntimeException error) {
            return new LinkedHashMap<>();
        }
    }

    private void writeIndex(Map<String, Meta> loaded) {
        try (FileWriter writer = new FileWriter(index)) {
            gson.toJson(loaded, INDEX_TYPE, writer);
        } catch (IOException ignored) {
            // The cache still works without an index; it just cannot label
            // its files until the next successful write.
        }
    }
}
