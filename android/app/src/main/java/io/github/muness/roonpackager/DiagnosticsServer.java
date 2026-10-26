package io.github.muness.roonpackager;

import android.content.Context;
import android.content.pm.PackageManager;

import io.github.muness.roonpackager.core.Catalogue;
import io.github.muness.roonpackager.core.DownloadCache;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The read-only diagnostics endpoint, on TCP 48630.
 *
 * <p>Same conventions as the two apps this one installs, so the same tooling
 * works: plain HTTP on the LAN, {@code text/plain} answers, {@code GET /health}
 * one pollable line, {@code GET /status} the current state, {@code GET /logs}
 * the buffer. The NAA app is on 48610 and the Roon app on 48620; this is 48630.
 *
 * <p>Almost no write surface: every route but one is a GET that reads memory.
 * The one thing this app can do that the other two cannot -- install another
 * app -- is reachable only by someone holding the device, because the
 * platform's own confirmation screen stands in front of it. Adding a route
 * that started a packing run would put an app install behind an unauthenticated
 * LAN request, so there is not one. The one exception is {@code POST /action
 * do=clear_cache}, which only deletes cached downloads -- the same "Clear
 * cache" button on the main screen does the same thing -- so the worst an
 * unauthenticated LAN request can do through it is force a re-download.
 *
 * <p>It is a home-network debug tool. Do not forward the port.
 */
public final class DiagnosticsServer {

    public static final int PORT = 48630;

    private final Context context;
    private ServerSocket socket;
    private ExecutorService workers;
    private Thread acceptor;

    public DiagnosticsServer(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start() {
        if (socket != null) {
            return;
        }
        try {
            ServerSocket listener = new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress("0.0.0.0", PORT));
            socket = listener;
        } catch (IOException error) {
            ManagerLog.w("diagnostics: cannot listen on " + PORT + " (" + error.getMessage()
                    + ")");
            return;
        }
        workers = Executors.newFixedThreadPool(2);
        acceptor = new Thread(this::accept, "diagnostics-48630");
        acceptor.setDaemon(true);
        acceptor.start();
        ManagerLog.i("diagnostics: listening on 0.0.0.0:" + PORT
                + " (GET /health, /status, /logs)");
    }

    public synchronized void stop() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // Closing is how the accept loop is told to end.
        }
        socket = null;
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
    }

    private void accept() {
        while (true) {
            ServerSocket listener;
            synchronized (this) {
                listener = socket;
            }
            if (listener == null || listener.isClosed()) {
                return;
            }
            try {
                Socket client = listener.accept();
                ExecutorService pool;
                synchronized (this) {
                    pool = workers;
                }
                if (pool == null) {
                    client.close();
                    return;
                }
                pool.execute(() -> serve(client));
            } catch (IOException error) {
                return;
            }
        }
    }

    private void serve(Socket client) {
        try (Socket open = client) {
            open.setSoTimeout(10_000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(open.getInputStream(), StandardCharsets.UTF_8));
            String request = reader.readLine();
            if (request == null) {
                return;
            }
            String[] parts = request.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";
            String query = "";
            int questionMark = path.indexOf('?');
            if (questionMark >= 0) {
                query = path.substring(questionMark + 1);
                path = path.substring(0, questionMark);
            }
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim()
                        .equalsIgnoreCase("Content-Length")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {
                        contentLength = 0;
                    }
                }
            }
            if ("POST".equals(method)) {
                char[] body = new char[Math.max(0, contentLength)];
                if (body.length > 0) {
                    reader.read(body);
                }
                servePost(open, path, query + "&" + new String(body));
                return;
            }
            if (!"GET".equals(method)) {
                write(open, 405, "this endpoint reads only, except POST /action. Every other "
                        + "route is a GET.\n");
                return;
            }
            switch (path) {
                case "/health":
                    write(open, 200, health() + "\n");
                    break;
                case "/status":
                    write(open, 200, status());
                    break;
                case "/logs":
                    write(open, 200, ManagerLog.text());
                    break;
                case "/":
                    write(open, 200, "roon packager " + version()
                            + "\nGET /health, /status, /logs\nPOST /action do=clear_cache\n");
                    break;
                default:
                    write(open, 404, "no such route: " + path
                            + "\nGET /health, /status, /logs\nPOST /action do=clear_cache\n");
                    break;
            }
        } catch (IOException error) {
            // A client that hung up mid-answer is not an event.
        }
    }

    /**
     * The one write route this app has: {@code POST /action do=clear_cache}
     * deletes every cached download, the same as the "Clear cache" button on
     * the main screen. There is deliberately no route that starts a packing
     * run or an install -- those stay behind the platform's own confirmation
     * screen -- but a cache is just disk space, so clearing it from the LAN
     * (the same trust boundary the rest of this port already accepts) costs
     * nothing worse than a re-download.
     */
    private void servePost(Socket client, String path, String body) throws IOException {
        if (!"/action".equals(path)) {
            write(client, 404, "no such route: " + path + "\nPOST /action do=clear_cache\n");
            return;
        }
        String action = null;
        for (String pair : body.split("&")) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2 && "do".equals(keyValue[0])) {
                action = keyValue[1].trim();
            }
        }
        if (!"clear_cache".equals(action)) {
            write(client, 400, "unknown action" + (action == null ? "" : ": " + action)
                    + "\nPOST /action do=clear_cache\n");
            return;
        }
        long[] freed = Downloads.cache(context).clearAll();
        String result = "cleared " + freed[0] + " file" + (freed[0] == 1 ? "" : "s") + ", "
                + freed[1] + " bytes\n";
        ManagerLog.i("cache: " + result.trim());
        write(client, 200, result);
    }

    /** {@code ok <state> <version>}: one line, cheap enough to poll. */
    private String health() {
        return "ok " + JobState.line().split(" ")[0] + " " + version();
    }

    private String status() {
        StringBuilder text = new StringBuilder();
        text.append("roon packager ").append(version()).append('\n');
        text.append("job: ").append(JobState.line()).append('\n');
        String failure = JobState.failure();
        if (failure != null) {
            text.append("failure:\n  ").append(failure.replace("\n", "\n  ")).append('\n');
        }
        // The one part of an install whose failure is silent and expensive.
        String marks = JobState.marksNote();
        if (!marks.isEmpty()) {
            text.append("marks: ").append(marks).append('\n');
        }
        text.append("signing key: ").append(
                new java.io.File(context.getFilesDir(), "packing-key.p12").isFile()
                        ? "present in this app's data directory"
                        : "not made yet").append('\n');
        text.append('\n');
        try {
            Catalogue catalogue = Catalogues.load(context);
            for (Catalogue.Endpoint endpoint : catalogue.endpoints) {
                text.append(endpoint.id).append(": ").append(endpoint.displayName).append('\n');
                text.append("  vendor:    ").append(endpoint.vendor).append(' ')
                        .append(endpoint.vendorVersion).append('\n');
                text.append("  archive:   ").append(endpoint.vendorArchiveUrl()).append('\n');
                text.append("  shell:     ").append(endpoint.shell.versionName).append(' ')
                        .append(endpoint.shell.url).append('\n');
                String installed = Catalogues.installedVersion(context,
                        endpoint.shell.packageId);
                text.append("  installed: ")
                        .append(installed == null ? "not installed" : installed).append('\n');
                // What is kept for this endpoint, so an owner can tell before
                // an install whether the answers are safe. The port is named
                // because the endpoint's own /verified/export is where the
                // document comes from and where it goes back.
                java.io.File kept = io.github.muness.roonpackager.core.VerifiedMarks
                        .file(MarkRestore.directory(context), endpoint.id);
                text.append("  marks:     ").append(kept.isFile()
                                ? kept.length() + " bytes kept here, from port "
                                        + endpoint.endpointDiagnosticsPort
                                        + " -- fetched before an install and posted back after"
                                        + " one, when the endpoint reports none of its own"
                                : "none kept; the next install fetches port "
                                        + endpoint.endpointDiagnosticsPort
                                        + io.github.muness.roonpackager.core.VerifiedMarks.EXPORT_PATH + " first")
                        .append('\n');
            }
        } catch (RuntimeException error) {
            text.append("catalogue unreadable: ").append(error.getMessage()).append('\n');
        }
        text.append('\n').append(cacheListing());
        String manifest = JobState.parityManifest();
        if (!manifest.isEmpty()) {
            text.append("\nlast packed APK, one line per entry (method sha256 name):\n");
            text.append(manifest);
        }
        return text.toString();
    }

    /** "Cached downloads": what is on disk, which catalogue entry it is for, when it was last used. */
    private String cacheListing() {
        List<DownloadCache.Entry> entries = Downloads.cache(context).list();
        StringBuilder text = new StringBuilder();
        text.append("cached downloads: ").append(entries.size()).append(" file")
                .append(entries.size() == 1 ? "" : "s").append(", ")
                .append(Downloads.cache(context).totalBytes()).append(" bytes total\n");
        for (DownloadCache.Entry entry : entries) {
            text.append("  ").append(entry.label).append(' ').append(entry.version)
                    .append(": ").append(entry.sizeBytes).append(" bytes, sha256 ")
                    .append(entry.sha256).append(", last used ")
                    .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                                    java.util.Locale.US)
                            .format(new java.util.Date(entry.lastUsedMillis)))
                    .append('\n');
        }
        return text.toString();
    }

    private String version() {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException absent) {
            return "unknown";
        }
    }

    private void write(Socket client, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = client.getOutputStream();
        String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }
}
