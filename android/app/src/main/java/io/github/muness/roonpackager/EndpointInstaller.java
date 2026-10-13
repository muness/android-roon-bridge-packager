package io.github.muness.roonpackager;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The whole trick, in one class.
 *
 * <p>Android will not let an app execute a file out of its own data directory.
 * It will let an app <em>write an APK</em> and hand it to
 * {@link PackageInstaller}, and the installer extracts that APK's
 * {@code lib/arm64-v8a/lib*.so} entries into the installed app's library
 * directory, where they are executable. That is why this app exists in this
 * shape, and it needs no old {@code targetSdk} and no root.
 *
 * <p>The user confirms. The session is committed, the platform shows its own
 * confirmation screen, and until someone taps Install nothing is installed --
 * which is right: an app that could silently install other apps would be a
 * different and much worse thing than this.
 */
public final class EndpointInstaller {

    private static final String ACTION_STATUS =
            "io.github.muness.roonpackager.INSTALL_STATUS";

    private EndpointInstaller() {
    }

    public static void install(Context context, File apk, String expectedPackage)
            throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(expectedPackage);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED);
        }
        int sessionId = installer.createSession(params);
        ManagerLog.i("install session " + sessionId + " for " + expectedPackage + ", "
                + apk.length() + " bytes");
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk);
                    OutputStream out = session.openWrite("endpoint.apk", 0, apk.length())) {
                byte[] buffer = new byte[256 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                session.fsync(out);
            }
            Intent status = new Intent(ACTION_STATUS).setPackage(context.getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, status,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
            ManagerLog.i("session committed; the platform is asking the user now");
        }
    }

    /**
     * Logs what the installer said, and re-raises the platform's own
     * confirmation activity when it asks for one.
     */
    public static final class StatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                    ManagerLog.i("the platform is asking the user to confirm the install");
                }
                return;
            }
            if (status == PackageInstaller.STATUS_SUCCESS) {
                ManagerLog.i("installed: " + message);
            } else {
                ManagerLog.w("install status " + status + ": " + message);
                if (message != null && message.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
                    ManagerLog.w("an endpoint signed by a different key is already installed. "
                            + "Uninstall it first: this app's signing key lives in its own data "
                            + "directory, and reinstalling this app made a new one.");
                }
            }
            Catalogues.reload();
        }
    }
}
