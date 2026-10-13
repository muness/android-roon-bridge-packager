package io.github.muness.roonpackager.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Backing up an endpoint's listening answers and putting them back, against a
 * fake endpoint on a loopback port.
 *
 * <p>A real socket rather than an injected transport, because what is being
 * checked includes the two failures that actually happen: a port that is not
 * answering, and an endpoint that answers and already has answers of its own.
 * Both of those are about the wire.
 */
public class VerifiedMarksTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private FakeEndpoint endpoint;

    @After
    public void stop() throws IOException {
        if (endpoint != null) endpoint.close();
    }

    private static final String DOCUMENT =
            "# androidnaa verified marks export\n"
            + "version: 1\n"
            + "sink: internal/Cayin/N7/build\n"
            + "exported: 1772000000000 2026-02-25 06:13 UTC\n"
            + "answers: 3\n"
            + "answer: dsd512  clean  2026-09-03 21:40 UTC  on DSD512 native, packing u8\n"
            + "packing: u8 (bytes alternate left, right), verified 2026-09-03 21:40 UTC\n"
            + "--- granted_formats\n"
            + "internal/Cayin/N7/build\n"
            + "352800|S32_LE|YES|352800|CLEAN|1772000000000|352800 Hz S32_LE\n"
            + "--- verified_extra_rows\n"
            + "internal/Cayin/N7/build\n"
            + "dsd512|YES|1411200|CLEAN|1772000000000|DSD512 native, packing u8\n"
            + "--- dsd_packing\n"
            + "internal/Cayin/N7/build\n"
            + "u8\n"
            + "1772000000000\n";

    /** The happy path: fetched, kept, and the count reported. */
    @Test
    public void aBackupKeepsTheDocumentByEndpointIdAndReportsTheCount() throws Exception {
        endpoint = new FakeEndpoint(DOCUMENT, "answer: none\n");
        File directory = folder.newFolder("marks");
        VerifiedMarks.Result result = VerifiedMarks.backUp("127.0.0.1", endpoint.port(), "naa",
                directory);
        assertTrue(result.note, result.ok);
        assertEquals(3, result.answers);
        assertTrue(result.note, result.note.contains("marks backed up (3 rows)"));
        assertTrue(result.note, result.note.contains("internal/Cayin/N7/build"));
        File kept = VerifiedMarks.file(directory, "naa");
        assertTrue(kept.isFile());
        // Byte for byte. The endpoint owns the format; this app stores it.
        assertEquals(DOCUMENT, new String(java.nio.file.Files.readAllBytes(kept.toPath()),
                StandardCharsets.UTF_8));
        assertEquals("GET " + VerifiedMarks.EXPORT_PATH, endpoint.requests().get(0));
    }

    /**
     * A port that does not answer is a note, not a failure. That is the
     * ordinary case for a first install, and a backup must never be able to
     * stop the install the owner asked for.
     */
    @Test
    public void aSilentPortDoesNotStopAnything() throws Exception {
        File directory = folder.newFolder("marks");
        int dead = freePort();
        VerifiedMarks.Result result = VerifiedMarks.backUp("127.0.0.1", dead, "naa", directory);
        assertFalse(result.ok);
        assertNull(result.document);
        assertTrue(result.note, result.note.contains("Nothing was backed up"));
        assertTrue(result.note, result.note.contains("the install goes ahead"));
        assertFalse(VerifiedMarks.file(directory, "naa").isFile());
    }

    /** No declared port is the same: a note naming the endpoint. */
    @Test
    public void anEndpointWithNoDeclaredPortIsSkipped() throws Exception {
        VerifiedMarks.Result result = VerifiedMarks.backUp("127.0.0.1", 0, "roon",
                folder.newFolder("marks"));
        assertFalse(result.ok);
        assertTrue(result.note, result.note.contains("roon"));
    }

    /** An empty export is kept: it is a true statement about that endpoint. */
    @Test
    public void anEmptyExportIsStillKept() throws Exception {
        String empty = "# androidnaa verified marks export\nversion: 1\n"
                + "sink: internal/Cayin/N7/build\nanswers: 0\n--- granted_formats\n";
        endpoint = new FakeEndpoint(empty, "answer: none\n");
        File directory = folder.newFolder("marks");
        VerifiedMarks.Result result = VerifiedMarks.backUp("127.0.0.1", endpoint.port(), "naa",
                directory);
        assertTrue(result.note, result.ok);
        assertEquals(0, result.answers);
        assertTrue(VerifiedMarks.file(directory, "naa").isFile());
    }

    /** The restore: posted, and the document is what was kept. */
    @Test
    public void aRestorePostsTheKeptDocumentIntoAnEndpointWithNoAnswers() throws Exception {
        endpoint = new FakeEndpoint(DOCUMENT, "answer: none. Nothing on this sink\n");
        File directory = folder.newFolder("marks");
        assertTrue(VerifiedMarks.backUp("127.0.0.1", endpoint.port(), "naa", directory).ok);
        VerifiedMarks.Result restored = VerifiedMarks.restore("127.0.0.1", endpoint.port(),
                "naa", directory);
        assertTrue(restored.note, restored.ok);
        assertEquals(3, restored.answers);
        assertTrue(restored.note, restored.note.contains("marks restored (3 rows)"));
        assertEquals(DOCUMENT, endpoint.posted());
        List<String> requests = endpoint.requests();
        assertEquals("GET " + VerifiedMarks.VERIFIED_PATH,
                requests.get(requests.size() - 2));
        assertEquals("POST " + VerifiedMarks.IMPORT_PATH, requests.get(requests.size() - 1));
    }

    /**
     * An endpoint that already has answers keeps them.
     *
     * <p>The good case, and the reason the check is a read of the endpoint's
     * own {@code /verified} rather than this app's memory of what it
     * installed: the app's own external copy restored itself, which is the
     * better of the two paths, and posting a kept document over it would make
     * this class the thing that lost an answer.
     */
    @Test
    public void aRestoreWillNotPostOverAnswersTheEndpointAlreadyHas() throws Exception {
        endpoint = new FakeEndpoint(DOCUMENT,
                "answer: dsd512  clean  2026-09-03 21:40 UTC\n");
        File directory = folder.newFolder("marks");
        assertTrue(VerifiedMarks.backUp("127.0.0.1", endpoint.port(), "naa", directory).ok);
        VerifiedMarks.Result restored = VerifiedMarks.restore("127.0.0.1", endpoint.port(),
                "naa", directory);
        assertFalse(restored.ok);
        assertTrue(restored.note, restored.note.contains("already reports listening answers"));
        assertNull("nothing may be posted", endpoint.posted());
    }

    /** Nothing kept, nothing restored, and it says so. */
    @Test
    public void aRestoreWithNothingKeptSaysSo() throws Exception {
        VerifiedMarks.Result result = VerifiedMarks.restore("127.0.0.1", 48610, "naa",
                folder.newFolder("marks"));
        assertFalse(result.ok);
        assertTrue(result.note, result.note.contains("no kept listening answers"));
    }

    /** A kept document with no answers in it is not worth posting. */
    @Test
    public void aKeptDocumentWithNoAnswersIsNotPosted() throws Exception {
        File directory = folder.newFolder("marks");
        write(VerifiedMarks.file(directory, "naa"),
                "# androidnaa verified marks export\nversion: 1\nsink: x\nanswers: 0\n");
        VerifiedMarks.Result result = VerifiedMarks.restore("127.0.0.1", freePort(), "naa",
                directory);
        assertFalse(result.ok);
        assertTrue(result.note, result.note.contains("carries no answers"));
    }

    /**
     * A port that is not up yet is distinguished from a final answer, because
     * {@code MarkRestore} polls on the first and gives up on the second.
     */
    @Test
    public void aPortThatIsNotUpYetIsTheOneCaseWorthWaitingOn() throws Exception {
        File directory = folder.newFolder("marks");
        write(VerifiedMarks.file(directory, "naa"), DOCUMENT);
        VerifiedMarks.Result result = VerifiedMarks.restore("127.0.0.1", freePort(), "naa",
                directory);
        assertFalse(result.ok);
        assertTrue(result.note, result.note.contains("not answering yet"));
        assertTrue(result.note, result.note.contains(VerifiedMarks.IMPORT_PATH));
    }

    /** An endpoint that refuses the import says why, and the document is kept. */
    @Test
    public void aRefusedImportLeavesTheDocumentWhereItIs() throws Exception {
        endpoint = new FakeEndpoint(DOCUMENT, "answer: none\n");
        endpoint.refuseImport("the document's answers were given on another sink");
        File directory = folder.newFolder("marks");
        write(VerifiedMarks.file(directory, "naa"), DOCUMENT);
        VerifiedMarks.Result result = VerifiedMarks.restore("127.0.0.1", endpoint.port(),
                "naa", directory);
        assertFalse(result.ok);
        assertTrue(result.note, result.note.contains("refused the restore"));
        assertTrue(result.note, result.note.contains("another sink"));
        assertTrue(VerifiedMarks.file(directory, "naa").isFile());
    }

    /** The two header lines this app reads of the format, and no more. */
    @Test
    public void onlyTheCountAndTheSinkAreReadOutOfTheDocument() {
        assertEquals(3, VerifiedMarks.answers(DOCUMENT));
        assertEquals("internal/Cayin/N7/build", VerifiedMarks.sink(DOCUMENT));
        assertEquals(0, VerifiedMarks.answers("nothing here"));
        assertEquals("an unnamed sink", VerifiedMarks.sink("nothing here"));
        assertEquals(0, VerifiedMarks.answers(null));
        assertTrue(VerifiedMarks.hasAnswers("answer: dsd512  clean\n"));
        assertFalse(VerifiedMarks.hasAnswers("answer: none. Nothing on this sink\n"));
        assertFalse(VerifiedMarks.hasAnswers("sink: x\npacking: untried\n"));
        assertFalse(VerifiedMarks.hasAnswers(null));
    }

    // ------------------------------------------------------------- the fake

    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        try (OutputStream out = new java.io.FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1,
                InetAddress.getByName("127.0.0.1"))) {
            return probe.getLocalPort();
        }
    }

    /** Three routes, one connection at a time, in the endpoint's own shape. */
    private static final class FakeEndpoint implements AutoCloseable {
        private final ServerSocket socket;
        private final String export;
        private final String verified;
        private final List<String> requests = new ArrayList<>();
        private final AtomicReference<String> posted = new AtomicReference<>();
        private volatile String importRefusal;
        private final Thread acceptor;

        FakeEndpoint(String export, String verified) throws IOException {
            this.export = export;
            this.verified = verified;
            this.socket = new ServerSocket();
            this.socket.bind(new InetSocketAddress("127.0.0.1", 0));
            this.acceptor = new Thread(this::accept, "fake-endpoint");
            this.acceptor.setDaemon(true);
            this.acceptor.start();
        }

        void refuseImport(String reason) {
            importRefusal = reason;
        }

        int port() {
            return socket.getLocalPort();
        }

        synchronized List<String> requests() {
            return new ArrayList<>(requests);
        }

        String posted() {
            return posted.get();
        }

        private void accept() {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    serve(client);
                } catch (IOException error) {
                    return;
                }
            }
        }

        private void serve(Socket client) throws IOException {
            java.io.InputStream in = client.getInputStream();
            StringBuilder head = new StringBuilder();
            int last = 0;
            int value;
            while ((value = in.read()) >= 0) {
                head.append((char)value);
                if (value == '\n' && last == '\n') break;
                if (value != '\r') last = value;
            }
            String[] lines = head.toString().split("\r?\n");
            String[] request = lines[0].split(" ");
            String method = request[0];
            String path = request.length > 1 ? request[1] : "/";
            int length = 0;
            for (String line : lines) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            // The query is not part of what these tests assert about, so the
            // recorded request is method and path only.
            int query = path.indexOf('?');
            synchronized (this) {
                requests.add(method + " " + (query < 0 ? path : path.substring(0, query)));
            }
            if (length > 0) {
                byte[] body = new byte[length];
                int read = 0;
                while (read < length) {
                    int got = in.read(body, read, length - read);
                    if (got < 0) break;
                    read += got;
                }
                posted.set(new String(body, 0, read, StandardCharsets.UTF_8));
            }
            String bodyText;
            int status = 200;
            if (path.startsWith(VerifiedMarks.EXPORT_PATH)) {
                bodyText = export;
            } else if (path.startsWith(VerifiedMarks.IMPORT_PATH)) {
                if (importRefusal != null) {
                    status = 409;
                    bodyText = importRefusal + "\n";
                } else {
                    bodyText = "Restored 3 of 4 sections.\n";
                }
            } else if (path.startsWith(VerifiedMarks.VERIFIED_PATH)) {
                bodyText = verified;
            } else {
                status = 404;
                bodyText = "no such route\n";
            }
            byte[] bytes = bodyText.getBytes(StandardCharsets.UTF_8);
            OutputStream out = client.getOutputStream();
            out.write(("HTTP/1.1 " + status + " x\r\nContent-Type: text/plain\r\n"
                    + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
