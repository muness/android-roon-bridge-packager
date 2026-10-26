package io.github.muness.roonpackager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import io.github.muness.roonpackager.core.ApkPacker;
import io.github.muness.roonpackager.core.Catalogue;
import io.github.muness.roonpackager.core.DownloadCache;
import io.github.muness.roonpackager.core.PackProgress;
import io.github.muness.roonpackager.core.PackRun;
import io.github.muness.roonpackager.core.VerifiedMarks;

import java.io.File;

/**
 * One packing run, in a foreground service so the system does not kill it
 * halfway through a 40 MB download.
 *
 * <p>Everything it does is in {@link PackRun}, which has no Android imports.
 * This class exists to keep that work alive, to put the step names where the
 * screen can read them, and to hand the finished APK to
 * {@link EndpointInstaller}.
 */
public final class PackService extends Service {

    public static final String EXTRA_ENDPOINT = "endpoint";
    private static final String CHANNEL = "packing";
    private static final int NOTIFICATION = 1;

    private Thread worker;

    public static void start(Context context, String endpointId) {
        Intent intent = new Intent(context, PackService.class);
        intent.putExtra(EXTRA_ENDPOINT, endpointId);
        context.startForegroundService(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String id = intent == null ? null : intent.getStringExtra(EXTRA_ENDPOINT);
        if (id == null || JobState.running()) {
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION, notification("Packing " + id));
        JobState.start(id);
        worker = new Thread(() -> run(id), "pack-" + id);
        worker.start();
        return START_NOT_STICKY;
    }

    private void run(String id) {
        try {
            Catalogue catalogue = Catalogues.load(this);
            Catalogue.Endpoint endpoint = catalogue.byId(id);
            File work = new File(getCacheDir(), "work");
            DownloadCache cache = Downloads.cache(this);
            File keystore = new File(getFilesDir(), "packing-key.p12");
            ManagerLog.i("packing " + endpoint.displayName + " " + endpoint.vendorVersion
                    + " into " + endpoint.shell.packageId);

            PackRun.Outcome outcome = new PackRun(endpoint, work, cache, keystore,
                    Build.VERSION_CODES.O, progress()).run();

            ApkPacker.Report report = outcome.report;
            ManagerLog.i("packed " + outcome.signedApk.getName() + " ("
                    + outcome.signedApk.length() + " bytes), "
                    + report.entrySha256.size() + " entries, "
                    + report.added.size() + " added");
            ManagerLog.i("parity manifest follows");
            ManagerLog.i(report.parityManifest().trim());
            JobState.finished(outcome.signedApk, report.parityManifest());

            // The pack just finished needing exactly the files the catalogue
            // still names, so this is the one safe moment to drop any others:
            // never mid-pack, only after one has succeeded without them.
            cache.evictUnreferenced(catalogue.referencedHashes());

            // The listening answers, before the install replaces the app that
            // holds them. Best effort and never fatal: the install is what the
            // owner asked for, and an endpoint whose port is not answering is
            // the ordinary case for a first install. Loopback, because the
            // endpoint is on this device -- the NAA app allows 127.0.0.1
            // without a prompt for exactly this call, since a prompt raised
            // behind the platform's install confirmation cannot be answered.
            VerifiedMarks.Result backup = VerifiedMarks.backUp("127.0.0.1",
                    endpoint.endpointDiagnosticsPort, endpoint.id, marksDir());
            ManagerLog.i(backup.note);
            JobState.marks(backup.note);

            JobState.step(PackProgress.Step.INSTALLING, endpoint.shell.packageId);
            EndpointInstaller.install(this, outcome.signedApk, endpoint.shell.packageId);
            // And after: the restore waits for the new app's diagnostics to
            // come up, and only posts into an endpoint that reports no answers
            // of its own. The install itself is asynchronous -- the platform is
            // still asking the user -- so this waits rather than running now.
            MarkRestore.after(this, endpoint.id, endpoint.endpointDiagnosticsPort);
        } catch (Throwable error) {
            String message = error.getMessage() == null ? error.toString() : error.getMessage();
            ManagerLog.e("packing failed", error);
            JobState.failed(message);
        } finally {
            stopForeground(true);
            stopSelf();
        }
    }

    /** Where the kept documents live: one per endpoint id, in this app's files. */
    private File marksDir() {
        return new File(getFilesDir(), "verified-marks");
    }

    private PackProgress progress() {
        return new PackProgress() {
            @Override
            public void step(Step step, String detail) {
                ManagerLog.i(step.label + (detail == null || detail.isEmpty()
                        ? "" : ": " + detail));
                JobState.step(step, detail);
                notifyManager().notify(NOTIFICATION, notification(step.label));
            }

            @Override
            public void bytes(long done, long total) {
                JobState.bytes(done, total);
            }

            @Override
            public void note(String line) {
                ManagerLog.i(line);
            }
        };
    }

    private NotificationManager notifyManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private Notification notification(String text) {
        NotificationManager manager = notifyManager();
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "Packing", NotificationManager.IMPORTANCE_LOW));
        }
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Roon Bridge Packager")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
