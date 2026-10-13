package io.github.muness.roonpackager.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The download cache, checked without a device: a hit never touches the
 * network, a miss stores what it fetched under the hash the catalogue will
 * ask for next time, a file corrupted on disk is treated as a miss rather than
 * handed to a packing run, pin-on-first-use's download becomes tomorrow's
 * cache hit once the hash is pinned, and eviction only ever removes what the
 * catalogue no longer names.
 */
public final class DownloadCacheTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /**
     * A tiny local server standing in for the vendor's host: plain sockets,
     * not {@code com.sun.net.httpserver}, because AGP's {@code --release}
     * javac invocation only exposes {@code java.se} modules, and that server
     * lives in {@code jdk.httpserver}, outside it.
     *
     * <p>Ignores whatever the request actually says (this app's own
     * {@link Downloader} is what is under test, not HTTP semantics) and
     * always answers the whole body with {@code Content-Length}, which is
     * exactly what a server that does not support resuming looks like.
     */
    private static final class FakeServer implements AutoCloseable {
        private final ServerSocket socket;
        private final byte[] body;
        private final Thread acceptor;
        final AtomicInteger requests = new AtomicInteger();
        private volatile boolean stopped;

        FakeServer(byte[] body) throws IOException {
            this.body = body;
            socket = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
            acceptor = new Thread(this::accept, "fake-server");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private void accept() {
            while (!stopped) {
                try {
                    Socket client = socket.accept();
                    requests.incrementAndGet();
                    serveOne(client);
                } catch (IOException closed) {
                    return;
                }
            }
        }

        private void serveOne(Socket client) {
            try (Socket open = client) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(open.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    // Drain the request headers; nothing about them changes
                    // the answer.
                }
                OutputStream out = open.getOutputStream();
                String head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                        + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
            } catch (IOException ignored) {
                // A dropped connection is not this test's concern.
            }
        }

        String url() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/file";
        }

        @Override
        public void close() {
            stopped = true;
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing is how the accept loop is told to end.
            }
        }
    }

    private static final class RecordingProgress implements PackProgress {
        final List<String> notes = new ArrayList<>();

        @Override
        public void step(Step step, String detail) {
        }

        @Override
        public void bytes(long done, long total) {
        }

        @Override
        public void note(String line) {
            notes.add(line);
        }
    }

    private static byte[] fakeContent(int size, int seed) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) {
            bytes[index] = (byte) ((index * 37 + seed) % 256);
        }
        return bytes;
    }

    private DownloadCache cache() throws IOException {
        return new DownloadCache(folder.newFolder("cache"));
    }

    @Test
    public void aCacheHitNeverTouchesTheNetwork() throws Exception {
        DownloadCache cache = cache();
        byte[] content = fakeContent(4096, 1);
        String hash = Sha256.of(content);
        writeFile(cache.fileFor(hash), content);

        RecordingProgress progress = new RecordingProgress();
        // A URL nothing is listening on: if this were contacted, the fetch
        // would throw a connection error instead of returning.
        File result = cache.fetch("http://127.0.0.1:1/unreachable", hash, "naa vendor archive",
                "6.1.4-71", progress);

        assertEquals(cache.fileFor(hash), result);
        assertTrue("a hit must be logged as one",
                progress.notes.stream().anyMatch(line -> line.startsWith("cached: ")
                        && line.contains(hash)));
    }

    @Test
    public void aMissDownloadsAndStoresUnderTheHash() throws Exception {
        byte[] content = fakeContent(8192, 2);
        String hash = Sha256.of(content);
        try (FakeServer server = new FakeServer(content)) {
            DownloadCache cache = cache();
            File result = cache.fetch(server.url(), hash, "roon vendor archive", "2.71-1683",
                    PackProgress.NONE);

            assertEquals(1, server.requests.get());
            assertEquals(hash, Sha256.of(result));
            assertEquals(cache.fileFor(hash), result);

            List<DownloadCache.Entry> entries = cache.list();
            assertEquals(1, entries.size());
            assertEquals(hash, entries.get(0).sha256);
            assertEquals("roon vendor archive", entries.get(0).label);
            assertEquals("2.71-1683", entries.get(0).version);
            assertEquals(content.length, entries.get(0).sizeBytes);
        }
    }

    @Test
    public void aFileThatNoLongerHashesToItsOwnNameIsDeletedAndRefetched() throws Exception {
        byte[] correct = fakeContent(2048, 3);
        String hash = Sha256.of(correct);
        try (FakeServer server = new FakeServer(correct)) {
            DownloadCache cache = cache();
            // A cache file present under the right name, wrong content --
            // corruption on disk, not a hash the app ever verified.
            writeFile(cache.fileFor(hash), fakeContent(2048, 99));

            RecordingProgress progress = new RecordingProgress();
            File result = cache.fetch(server.url(), hash, "naa vendor archive", "6.1.4-71",
                    progress);

            assertEquals(1, server.requests.get());
            assertEquals(hash, Sha256.of(result));
            assertTrue("a corrupt cache file must be reported at WARN",
                    progress.notes.stream().anyMatch(line -> line.startsWith("WARN")
                            && line.contains("no longer hashes to its own name")));
        }
    }

    @Test
    public void aDownloadThatFailsItsHashCheckIsDeletedNotKeptForResume() throws Exception {
        byte[] served = fakeContent(2048, 12);
        String expected = Sha256.of(fakeContent(2048, 13));
        try (FakeServer server = new FakeServer(served)) {
            DownloadCache cache = cache();
            try {
                cache.fetch(server.url(), expected, "naa vendor archive", "6.1.4-71",
                        PackProgress.NONE);
                fail("a download that does not hash to what the catalogue names must be refused");
            } catch (IOException refused) {
                assertTrue(refused.getMessage().contains("not the file the catalogue names"));
            }
            assertFalse("the refused file must not be filed under the expected hash",
                    cache.fileFor(expected).isFile());
            File[] leftovers = cache.fileFor(expected).getParentFile()
                    .listFiles((dir, name) -> name.endsWith(".part"));
            assertEquals("the partial file must be gone so the next run starts clean",
                    0, leftovers == null ? 0 : leftovers.length);
        }
    }

    @Test
    public void pinOnFirstUseStoresUnderTheHashItObservedSoTheSecondRunIsAHit()
            throws Exception {
        byte[] content = fakeContent(1024, 4);
        try (FakeServer server = new FakeServer(content)) {
            DownloadCache cache = cache();

            DownloadCache.Observed observed = cache.fetchObserving(server.url(),
                    "roon vendor archive", "2.71-1683", PackProgress.NONE);
            assertEquals(1, server.requests.get());
            assertEquals(Sha256.of(content), observed.sha256);
            assertTrue(cache.fileFor(observed.sha256).isFile());

            // The hash is now "pinned": a normal fetch asking for exactly that
            // hash must be a cache hit, not a second request to the server.
            File hit = cache.fetch(server.url(), observed.sha256, "roon vendor archive",
                    "2.71-1683", PackProgress.NONE);
            assertEquals(1, server.requests.get());
            assertEquals(observed.file, hit);
        }
    }

    @Test
    public void evictionRemovesOnlyWhatTheCatalogueNoLongerNames() throws Exception {
        DownloadCache cache = cache();
        byte[] keepContent = fakeContent(512, 5);
        byte[] goneContent = fakeContent(512, 6);
        String keepHash = Sha256.of(keepContent);
        String goneHash = Sha256.of(goneContent);
        writeFile(cache.fileFor(keepHash), keepContent);
        writeFile(cache.fileFor(goneHash), goneContent);
        // Only the index makes a file "known" to the cache -- write it via a
        // hit fetch so eviction has metadata to work from.
        cache.fetch("http://127.0.0.1:1/unreachable", keepHash, "naa shell", "1.27",
                PackProgress.NONE);
        cache.fetch("http://127.0.0.1:1/unreachable", goneHash, "old naa shell", "1.20",
                PackProgress.NONE);

        Set<String> stillReferenced = new HashSet<>();
        stillReferenced.add(keepHash);
        cache.evictUnreferenced(stillReferenced);

        assertTrue("a hash the catalogue still names must survive eviction",
                cache.fileFor(keepHash).isFile());
        assertFalse("a hash no catalogue entry names any more must be evicted",
                cache.fileFor(goneHash).isFile());
        List<DownloadCache.Entry> remaining = cache.list();
        assertEquals(1, remaining.size());
        assertEquals(keepHash, remaining.get(0).sha256);
    }

    @Test
    public void evictionNeverRunsDuringAPackItIsOnlyAMethodTheCallerInvokesAfterOne()
            throws Exception {
        // There is no automatic timer or background sweep in this class: the
        // only way a file is evicted is a caller calling evictUnreferenced,
        // which PackService does exactly once, after a pack has already
        // succeeded without needing anything else. A cache with no eviction
        // call must keep every file it was given, no matter how old.
        DownloadCache cache = cache();
        byte[] content = fakeContent(256, 7);
        String hash = Sha256.of(content);
        writeFile(cache.fileFor(hash), content);
        cache.fetch("http://127.0.0.1:1/unreachable", hash, "naa vendor archive", "6.1.4-71",
                PackProgress.NONE);

        assertTrue(cache.fileFor(hash).isFile());
        assertEquals(1, cache.list().size());
    }

    @Test
    public void listingReportsWhatStatusAndTheUiShowTotalSizeAndPerFileDetail()
            throws Exception {
        DownloadCache cache = cache();
        byte[] first = fakeContent(1000, 8);
        byte[] second = fakeContent(2000, 9);
        String firstHash = Sha256.of(first);
        String secondHash = Sha256.of(second);
        writeFile(cache.fileFor(firstHash), first);
        writeFile(cache.fileFor(secondHash), second);
        cache.fetch("http://127.0.0.1:1/unreachable", firstHash, "naa vendor archive",
                "6.1.4-71", PackProgress.NONE);
        Thread.sleep(5); // so the two entries do not race to the same millisecond
        cache.fetch("http://127.0.0.1:1/unreachable", secondHash, "roon shell", "0.11-poc",
                PackProgress.NONE);

        assertEquals(3000, cache.totalBytes());
        List<DownloadCache.Entry> entries = cache.list();
        assertEquals(2, entries.size());
        // Most recently used first, which is what a "cached downloads" list
        // should show at the top.
        assertEquals(secondHash, entries.get(0).sha256);
        assertEquals("roon shell", entries.get(0).label);
        assertEquals("0.11-poc", entries.get(0).version);
        assertEquals(2000L, entries.get(0).sizeBytes);
    }

    @Test
    public void perFileAndClearAllDeleteFromDisk() throws Exception {
        DownloadCache cache = cache();
        byte[] first = fakeContent(300, 10);
        byte[] second = fakeContent(400, 11);
        String firstHash = Sha256.of(first);
        String secondHash = Sha256.of(second);
        writeFile(cache.fileFor(firstHash), first);
        writeFile(cache.fileFor(secondHash), second);
        cache.fetch("http://127.0.0.1:1/unreachable", firstHash, "naa vendor archive",
                "6.1.4-71", PackProgress.NONE);
        cache.fetch("http://127.0.0.1:1/unreachable", secondHash, "roon shell", "0.11-poc",
                PackProgress.NONE);

        assertTrue(cache.delete(firstHash));
        assertFalse(cache.fileFor(firstHash).isFile());
        assertEquals(1, cache.list().size());

        long[] freed = cache.clearAll();
        assertEquals(1, freed[0]);
        assertEquals(400, freed[1]);
        assertTrue(cache.list().isEmpty());
        assertFalse(cache.fileFor(secondHash).isFile());
    }

    private static void writeFile(File file, byte[] content) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content);
        }
    }
}
