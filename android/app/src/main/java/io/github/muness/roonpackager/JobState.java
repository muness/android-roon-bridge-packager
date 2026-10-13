package io.github.muness.roonpackager;

import io.github.muness.roonpackager.core.PackProgress;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * What the one running job is doing, for the screen and for {@code GET
 * /status}. One job at a time, on purpose: two packing runs on a phone would
 * fight over the same work directory and the same 40 MB of Wi-Fi.
 */
public final class JobState {

    public interface Listener {
        void onJobChanged();
    }

    private static final List<Listener> LISTENERS = new ArrayList<>();

    private static String endpointId;
    private static PackProgress.Step step;
    private static String detail = "";
    private static long done;
    private static long total;
    private static String failure;
    private static File result;
    private static boolean running;
    private static String parityManifest = "";
    /**
     * What happened to the endpoint's listening answers around this install,
     * in one line: "marks backed up (3 rows)", "marks restored", or the reason
     * neither happened.
     *
     * <p>On the progress view and in {@code /status} because it is the one
     * part of an install whose failure is silent and expensive. An install
     * that quietly dropped two evenings of listening answers is what happened
     * once, and the only evidence was an endpoint advertising PCM only.
     */
    private static String marksNote = "";

    private JobState() {
    }

    public static synchronized void start(String id) {
        endpointId = id;
        step = null;
        detail = "";
        done = 0;
        total = 0;
        failure = null;
        result = null;
        running = true;
        parityManifest = "";
        marksNote = "";
        notifyChanged();
    }

    public static synchronized void step(PackProgress.Step newStep, String newDetail) {
        step = newStep;
        detail = newDetail == null ? "" : newDetail;
        done = 0;
        total = 0;
        notifyChanged();
    }

    public static synchronized void bytes(long newDone, long newTotal) {
        done = newDone;
        total = newTotal;
        notifyChanged();
    }

    public static synchronized void finished(File apk, String manifest) {
        running = false;
        result = apk;
        parityManifest = manifest;
        notifyChanged();
    }

    public static synchronized void failed(String message) {
        running = false;
        failure = message;
        notifyChanged();
    }

    public static synchronized boolean running() {
        return running;
    }

    public static synchronized String endpointId() {
        return endpointId;
    }

    public static synchronized File result() {
        return result;
    }

    public static synchronized String failure() {
        return failure;
    }

    public static synchronized String parityManifest() {
        return parityManifest;
    }

    /** Sets the listening-answer line. Kept across a finish, unlike the step. */
    public static synchronized void marks(String note) {
        marksNote = note == null ? "" : note;
        notifyChanged();
    }

    public static synchronized String marksNote() {
        return marksNote;
    }

    /** One line, cheap enough to poll, in the other two apps' shape. */
    public static synchronized String line() {
        if (endpointId == null) {
            return "idle";
        }
        if (failure != null) {
            return "failed " + endpointId + ": " + failure.split("\n")[0];
        }
        if (!running) {
            return "packed " + endpointId + " " + (result == null ? "?" : result.getName())
                    + (marksNote.isEmpty() ? "" : "; " + marksNote);
        }
        StringBuilder text = new StringBuilder();
        text.append(step == null ? "starting" : step.label).append(' ').append(endpointId);
        if (!detail.isEmpty()) {
            text.append(" (").append(detail).append(')');
        }
        if (total > 0) {
            text.append(' ').append(done * 100 / total).append('%');
        } else if (done > 0) {
            text.append(' ').append(done / 1024).append(" kB");
        }
        if (!marksNote.isEmpty()) {
            text.append("; ").append(marksNote);
        }
        return text.toString();
    }

    public static synchronized void listen(Listener listener) {
        LISTENERS.add(listener);
    }

    public static synchronized void forget(Listener listener) {
        LISTENERS.remove(listener);
    }

    private static void notifyChanged() {
        for (Listener listener : new ArrayList<>(LISTENERS)) {
            listener.onJobChanged();
        }
    }
}
