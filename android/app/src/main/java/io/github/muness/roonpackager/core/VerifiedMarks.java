package io.github.muness.roonpackager.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The listening answers an endpoint carries, fetched before it is replaced and
 * posted back afterwards.
 *
 * <h2>Why the manager keeps a copy</h2>
 *
 * <p>Because installing over an endpoint is exactly the moment its answers are
 * at risk, and this app is what does the installing. The NAA app's own
 * verification marks -- which DSD multiples this DAC was heard playing, which
 * byte layout its HAL wants, which rates a real open granted -- are the result
 * of a person sitting with headphones on, and they are not recoverable from
 * anything the platform will tell you. Moving that app to a manager-signed
 * build meant an uninstall, and every one of those answers went with the app's
 * data. The endpoint went back to advertising PCM only and the answers had to
 * be re-entered by hand over the wire.
 *
 * <p>The app now writes its own copy to its external files directory, which
 * survives the app's data being cleared. It does not survive an uninstall with
 * "delete app data" ticked, which is what a signature change asks for. So the
 * two copies are for two different failures, and this is the one that covers
 * the failure this app causes.
 *
 * <h2>The rules</h2>
 *
 * <p><b>Best effort, never fatal.</b> A backup that could fail an install
 * would be worse than no backup: the install is what the owner asked for and
 * the marks can be re-entered. Every failure here is a note in the progress
 * view, never an exception the run stops on.
 *
 * <p><b>Restore only into an endpoint reporting no answers.</b> The fresh
 * install case, and no other. Posting a kept document over answers the owner
 * has since given would make this class the thing that lost them.
 *
 * <p><b>The document is opaque.</b> It is stored byte for byte and handed back
 * unaltered. The endpoint's own {@code VerifiedMarksExport} owns the format,
 * the version line and the identity rule; this app reads two header lines to
 * put a count in the progress view and parses nothing else. A manager that
 * understood the format would be a second implementation of it to keep in
 * step.
 *
 * <p>No Android imports, so the fetch, the store and the "does this endpoint
 * have answers" decision are unit-tested against a fake endpoint.
 */
public final class VerifiedMarks {

    /** {@code GET} this to read an endpoint's answers. */
    public static final String EXPORT_PATH = "/verified/export";
    /** {@code POST} the document here to put them back. */
    public static final String IMPORT_PATH = "/verified/import";
    /** {@code GET} this to see whether it has any. */
    public static final String VERIFIED_PATH = "/verified";

    /** Short: a device that is not answering must not hold up an install. */
    static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    static final int READ_TIMEOUT_MILLIS = 5_000;
    /** The largest document accepted, matching the endpoint's own import cap. */
    static final int MAX_BYTES = 64 * 1024;

    private VerifiedMarks() {
    }

    /** What one fetch or post did, in one line for the progress view. */
    public static final class Result {
        public final boolean ok;
        /** The document, for a successful fetch; null otherwise. */
        public final String document;
        /** The answered-row count the document declares, or 0. */
        public final int answers;
        /** One line: what happened, or why it did not. */
        public final String note;

        Result(boolean ok, String document, int answers, String note) {
            this.ok = ok;
            this.document = document;
            this.answers = answers;
            this.note = note;
        }
    }

    /** Where one endpoint's kept document lives. Stable, per endpoint id. */
    public static File file(File directory, String endpointId) {
        return new File(directory, "verified-marks-" + endpointId + ".txt");
    }

    /**
     * Fetch an endpoint's answers and keep them.
     *
     * <p>An endpoint whose port does not answer is the ordinary case for a
     * first install, and it is a note rather than a failure. An endpoint that
     * answers with an empty document is kept anyway: it is a true statement
     * about that endpoint, and a kept empty document reads differently from a
     * missing file, which is what a failed fetch leaves.
     */
    public static Result backUp(String host, int port, String endpointId, File directory) {
        if (port <= 0) {
            return new Result(false, null, 0,
                    "no diagnostics port is declared for " + endpointId + ", so there are no"
                    + " listening answers to back up");
        }
        String document;
        try {
            document = get("http://" + host + ":" + port + EXPORT_PATH);
        } catch (IOException error) {
            return new Result(false, null, 0,
                    "could not read the listening answers from " + host + ":" + port
                    + EXPORT_PATH + " (" + error.getMessage() + "). Nothing was backed up; the"
                    + " install goes ahead. This is the ordinary case for a first install, and"
                    + " for an endpoint whose service is not running.");
        }
        int answers = answers(document);
        try {
            File kept = file(directory, endpointId);
            File parent = kept.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create " + parent);
            }
            try (OutputStream out = new FileOutputStream(kept)) {
                out.write(document.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException error) {
            return new Result(false, document, answers,
                    "read the listening answers but could not keep them: "
                    + error.getMessage());
        }
        return new Result(true, document, answers,
                "marks backed up (" + answers + " rows) from " + host + ":" + port
                + ", filed under " + sink(document));
    }

    /**
     * Put the kept answers back, but only into an endpoint that reports none.
     *
     * <p>The check is a read of the endpoint's own {@code /verified}, not this
     * app's memory of what it installed: what matters is what the app that is
     * running right now has, and after a reinstall that is either everything
     * (its own external copy restored itself) or nothing (the uninstall took
     * that too). Only the second case is this app's business.
     */
    public static Result restore(String host, int port, String endpointId, File directory) {
        File kept = file(directory, endpointId);
        if (!kept.isFile()) {
            return new Result(false, null, 0,
                    "no kept listening answers for " + endpointId + ", so nothing was"
                    + " restored");
        }
        String document;
        try {
            document = read(kept);
        } catch (IOException error) {
            return new Result(false, null, 0,
                    "could not read the kept listening answers: " + error.getMessage());
        }
        int answers = answers(document);
        if (answers == 0) {
            return new Result(false, document, 0,
                    "the kept document carries no answers, so there is nothing to restore --"
                    + " nothing on this endpoint had been listened to when it was backed up");
        }
        String verified;
        try {
            verified = get("http://" + host + ":" + port + VERIFIED_PATH);
        } catch (IOException error) {
            return new Result(false, document, answers,
                    "the endpoint's diagnostics are not answering yet (" + error.getMessage()
                    + "), so the " + answers + " kept answers were not restored. POST them to"
                    + " " + IMPORT_PATH + " when it is up, or tap Restore last verified marks"
                    + " in Verify outputs.");
        }
        if (hasAnswers(verified)) {
            return new Result(false, document, answers,
                    "the endpoint already reports listening answers of its own, so the "
                    + answers + " kept ones were not posted over them. Its own external copy"
                    + " restored itself, which is the better of the two paths.");
        }
        String reply;
        try {
            reply = post("http://" + host + ":" + port + IMPORT_PATH, document);
        } catch (IOException error) {
            return new Result(false, document, answers,
                    "the endpoint refused the restore: " + error.getMessage() + ". The kept"
                    + " document is still at " + kept.getName() + "; POST it to " + IMPORT_PATH
                    + " to try again.");
        }
        return new Result(true, document, answers,
                "marks restored (" + answers + " rows) to " + host + ":" + port + IMPORT_PATH
                + ". " + firstLine(reply));
    }

    // -------------------------------------------------------- reading the doc

    /**
     * The {@code answers:} count the document declares, or 0.
     *
     * <p>Two header lines is all this app reads of the format, deliberately.
     * The count goes in the progress view because "marks backed up" with no
     * number is a claim the owner cannot check.
     */
    public static int answers(String document) {
        return (int)number(document, "answers:");
    }

    /** The sink identity the document is filed under, or "an unnamed sink". */
    public static String sink(String document) {
        String value = line(document, "sink:");
        return value == null ? "an unnamed sink" : value;
    }

    /**
     * True when a {@code GET /verified} body shows at least one answered row.
     *
     * <p>Read off the {@code answer:} lines the endpoint's own history block
     * writes, which is the endpoint saying what it has rather than this app
     * inferring it from the grid.
     */
    public static boolean hasAnswers(String verified) {
        if (verified == null) return false;
        for (String raw : verified.split("\n", -1)) {
            String line = raw.trim();
            if (!line.startsWith("answer:")) continue;
            String value = line.substring("answer:".length()).trim();
            if (!value.startsWith("none")) return true;
        }
        return false;
    }

    private static String line(String document, String name) {
        if (document == null) return null;
        for (String raw : document.split("\n", -1)) {
            String trimmed = raw.trim();
            if (!trimmed.startsWith(name)) continue;
            String value = trimmed.substring(name.length()).trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    private static long number(String document, String name) {
        String value = line(document, name);
        if (value == null) return 0L;
        try {
            return Long.parseLong(value.split("\\s+")[0]);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        int end = text.indexOf('\n');
        return end < 0 ? text.trim() : text.substring(0, end).trim();
    }

    // ------------------------------------------------------------------ http

    static String get(String url) throws IOException {
        HttpURLConnection connection = open(url, "GET");
        try {
            return body(connection);
        } finally {
            connection.disconnect();
        }
    }

    static String post(String url, String document) throws IOException {
        HttpURLConnection connection = open(url, "POST");
        connection.setDoOutput(true);
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
        try {
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            return body(connection);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setUseCaches(false);
        return connection;
    }

    private static String body(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream()
                : connection.getInputStream();
        String text = stream == null ? "" : drain(stream);
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + ": " + firstLine(text));
        }
        return text;
    }

    private static String drain(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = stream.read(buffer)) > 0) {
            total += read;
            if (total > MAX_BYTES) {
                throw new IOException("the document is larger than " + MAX_BYTES + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String read(File file) throws IOException {
        byte[] bytes = new byte[(int)Math.min(file.length(), MAX_BYTES)];
        try (InputStream in = new java.io.FileInputStream(file)) {
            int read = 0;
            while (read < bytes.length) {
                int got = in.read(bytes, read, bytes.length - read);
                if (got < 0) break;
                read += got;
            }
            return new String(bytes, 0, read, StandardCharsets.UTF_8);
        }
    }
}
