package io.github.muness.roonpackager.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * A download that survives a dropped connection and refuses a file it does not
 * recognise.
 *
 * <p>Resume matters more here than it would elsewhere: these are 5-to-40 MB
 * files on a phone's Wi-Fi, and the failure mode without resume is starting
 * over. So a partial file is kept, its length becomes a {@code Range} request,
 * and a server that ignores the range is handled by starting again rather than
 * by producing a corrupt file.
 *
 * <p>The hash check is not an integrity nicety, it is the whole trust model:
 * this app signs what it downloads with a key that Android will accept as an
 * update to an installed endpoint. A vendor archive whose sha256 is not the one
 * the catalogue names is refused, and so is an app shell's.
 */
public final class Downloader {

    /** {@code file:} and a local path both mean "already on this device". */
    public static boolean isLocal(String url) {
        return url.startsWith("file:") || url.startsWith("/");
    }

    /**
     * Fetches {@code url} to {@code target} and checks its sha256.
     *
     * @param expectedSha256 lower-case hex; the download is refused if it does
     *                       not match
     * @return the file, which is {@code target} unless the URL was local
     */
    public static File fetch(String url, String expectedSha256, File target,
            PackProgress progress) throws IOException {
        File source = local(url);
        if (source != null) {
            if (!source.isFile()) {
                throw new IOException("no such file: " + source);
            }
            check(source, expectedSha256, progress);
            return source;
        }
        download(url, target, progress);
        try {
            check(target, expectedSha256, progress);
        } catch (IOException wrong) {
            // Keeping it would make the next run resume a file that can never
            // hash right; a clean start is the only way it can.
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw wrong;
        }
        return target;
    }

    /**
     * Fetches {@code url} without checking it against anything, for the
     * pin-on-first-use path: the caller reports the hash and stops. Nothing
     * downloaded this way is ever packed.
     */
    public static File fetchObserving(String url, File target, PackProgress progress)
            throws IOException {
        File source = local(url);
        if (source != null) {
            if (!source.isFile()) {
                throw new IOException("no such file: " + source);
            }
            return source;
        }
        download(url, target, progress);
        return target;
    }

    static File local(String url) {
        if (url.startsWith("file://")) {
            return new File(url.substring("file://".length()));
        }
        if (url.startsWith("file:")) {
            return new File(url.substring("file:".length()));
        }
        if (url.startsWith("/")) {
            return new File(url);
        }
        return null;
    }

    private static void check(File file, String expected, PackProgress progress)
            throws IOException {
        progress.step(PackProgress.Step.VERIFYING_DOWNLOAD, file.getName());
        String actual = Sha256.of(file);
        if (expected == null || expected.isEmpty()) {
            throw new IOException("nothing says what " + file.getName() + " should hash to, and "
                    + "this app will not sign a file it cannot recognise");
        }
        if (!actual.equals(expected.toLowerCase(Locale.ROOT))) {
            throw new IOException(file.getName() + " is not the file the catalogue names.\n"
                    + "  expected sha256 " + expected + "\n"
                    + "  got             " + actual + "\n"
                    + "Either the vendor published a new build under the same URL, in which case "
                    + "the catalogue needs its hash, or the download is not what it claims to be. "
                    + "Nothing was packed.");
        }
        progress.note(file.getName() + ": sha256 " + actual + " as expected");
    }

    private static void download(String url, File target, PackProgress progress)
            throws IOException {
        long already = target.isFile() ? target.length() : 0;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        if (already > 0) {
            connection.setRequestProperty("Range", "bytes=" + already + "-");
        }
        connection.connect();
        int status = connection.getResponseCode();
        boolean append;
        if (status == HttpURLConnection.HTTP_PARTIAL) {
            append = true;
            progress.note("resuming " + url + " at " + already + " bytes");
        } else if (status == HttpURLConnection.HTTP_OK) {
            // A server that ignored the range is sending the whole file, so
            // the partial file is not a head start, it is in the way.
            append = false;
            already = 0;
        } else if (status == 416 && already > 0) {
            // Already complete as far as the server is concerned; the hash
            // check is what decides whether that is true.
            connection.disconnect();
            return;
        } else {
            connection.disconnect();
            throw new IOException("the server answered HTTP " + status + " for " + url);
        }
        long length = connection.getContentLengthLong();
        long total = length < 0 ? -1 : length + already;
        long done = already;
        try (InputStream in = connection.getInputStream();
                OutputStream out = new FileOutputStream(target, append)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            long lastReport = 0;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                done += read;
                if (done - lastReport > 256 * 1024) {
                    progress.bytes(done, total);
                    lastReport = done;
                }
            }
            progress.bytes(done, total);
        } finally {
            connection.disconnect();
        }
    }
}
