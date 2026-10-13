package io.github.muness.roonpackager;

import android.content.Context;

import io.github.muness.roonpackager.core.VerifiedMarks;

import java.io.File;

/**
 * Waits for a freshly installed endpoint's diagnostics to come up, then puts
 * its listening answers back.
 *
 * <h2>Why it waits, and why it is not the pack run</h2>
 *
 * <p>Because the install is not finished when {@code PackRun} is. The session
 * is committed and the platform is showing its own confirmation screen; the
 * owner may take a minute over it, and the new app's service is not up until
 * he has tapped Install and something has started it. So the restore is a
 * short-lived background wait that polls the endpoint's own {@code /verified}
 * and stops as soon as it gets an answer either way.
 *
 * <p>It gives up rather than nagging. The kept document stays on disk under
 * the endpoint's id, {@code POST /verified/import} takes it at any later time,
 * and the endpoint has a one-tap Restore of its own -- so a wait that ran out
 * has cost the owner one button press, not the answers.
 *
 * <h2>Only into an endpoint with nothing</h2>
 *
 * <p>{@link VerifiedMarks#restore} checks that. The good case is the one where
 * this does nothing: the NAA app keeps its own copy in its external files
 * directory and restores from it automatically at start, and when that
 * survived the install this app finds answers already there and leaves them
 * alone. This exists for the case that copy did not survive, which is an
 * uninstall with "delete app data" -- exactly what a signature change asks
 * for.
 */
final class MarkRestore {

    /** How long to keep looking for the new endpoint's diagnostics. */
    private static final long WINDOW_MILLIS = 180_000L;
    /** How often, which is slow: nothing here is urgent. */
    private static final long POLL_MILLIS = 5_000L;

    private MarkRestore() {
    }

    /** Where the kept documents live. Same directory {@code PackService} writes. */
    static File directory(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "verified-marks");
    }

    /**
     * Start the wait, unless there is nothing kept for this endpoint.
     *
     * <p>Checked first so an endpoint nobody has ever backed up does not spend
     * three minutes polling a port to find out there was nothing to send.
     */
    static void after(Context context, String endpointId, int port) {
        if (port <= 0) return;
        final Context application = context.getApplicationContext();
        final File directory = directory(application);
        if (!VerifiedMarks.file(directory, endpointId).isFile()) {
            ManagerLog.i("no kept listening answers for " + endpointId + ", so nothing will be"
                    + " restored after this install");
            return;
        }
        Thread waiter = new Thread(() -> wait(endpointId, port, directory),
                "marks-restore-" + endpointId);
        waiter.setDaemon(true);
        waiter.start();
    }

    private static void wait(String endpointId, int port, File directory) {
        long deadline = System.currentTimeMillis() + WINDOW_MILLIS;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            VerifiedMarks.Result result =
                    VerifiedMarks.restore("127.0.0.1", port, endpointId, directory);
            if (result.ok) {
                ManagerLog.i(result.note);
                JobState.marks(result.note);
                return;
            }
            last = result.note;
            // "Already has answers of its own" and "nothing to restore" are
            // final, not transient: polling again would only produce the same
            // sentence three minutes later. Only a port that is not answering
            // yet is worth waiting on.
            if (!result.note.contains("not answering yet")) {
                ManagerLog.i(result.note);
                JobState.marks(result.note);
                return;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        String note = "the kept listening answers for " + endpointId + " were not restored:"
                + " the endpoint's diagnostics did not come up within "
                + (WINDOW_MILLIS / 1000) + "s. They are still kept here -- POST "
                + VerifiedMarks.file(directory, endpointId).getName() + " to "
                + VerifiedMarks.IMPORT_PATH + " on port " + port + ", or tap Restore last"
                + " verified marks in the endpoint's Verify outputs screen."
                + (last == null ? "" : " Last attempt: " + last);
        ManagerLog.w(note);
        JobState.marks("marks not restored (the endpoint's diagnostics did not come up)");
    }
}
