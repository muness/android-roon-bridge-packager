package io.github.muness.roonpackager;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The log the screen shows and {@code GET /logs} serves. In memory, bounded,
 * and the same shape the other two apps' log buffers have, so the same tooling
 * reads it.
 */
public final class ManagerLog {

    private static final String TAG = "RoonPackager";
    private static final int LIMIT = 2000;
    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);

    private ManagerLog() {
    }

    public static void i(String line) {
        add("I", line);
        Log.i(TAG, line);
    }

    public static void w(String line) {
        add("W", line);
        Log.w(TAG, line);
    }

    public static void e(String line, Throwable error) {
        add("E", line + (error == null ? "" : ": " + error));
        Log.e(TAG, line, error);
    }

    private static void add(String level, String line) {
        String stamped = STAMP.format(new Date()) + " " + level + " " + line;
        synchronized (LINES) {
            for (String part : stamped.split("\n")) {
                LINES.addLast(part);
                while (LINES.size() > LIMIT) {
                    LINES.removeFirst();
                }
            }
        }
    }

    /** The whole buffer, oldest first. */
    public static List<String> lines() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }

    public static String text() {
        StringBuilder out = new StringBuilder();
        for (String line : lines()) {
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
