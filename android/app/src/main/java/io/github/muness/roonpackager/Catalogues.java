package io.github.muness.roonpackager;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import io.github.muness.roonpackager.core.Catalogue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Where the catalogue comes from, and what the device already has installed.
 *
 * <p>The packaged {@code assets/catalogue.json} is the catalogue. A file called
 * {@code catalogue.json} in the app's own files directory overrides it, which is
 * how a new vendor version or a new shell URL is tried without rebuilding the
 * app -- {@code adb push} it, or drop it there. Nothing else may write it.
 */
public final class Catalogues {

    private static Catalogue cached;

    private Catalogues() {
    }

    public static synchronized Catalogue load(Context context) {
        if (cached != null) {
            return cached;
        }
        File override = new File(context.getFilesDir(), "catalogue.json");
        if (override.isFile()) {
            try (InputStream in = new FileInputStream(override)) {
                cached = io.github.muness.roonpackager.core.RoonPolicy.validate(Catalogue.read(in));
                ManagerLog.i("catalogue: the override at " + override + " (" + override.length()
                        + " bytes)");
                return cached;
            } catch (IOException | RuntimeException error) {
                ManagerLog.w("catalogue: the override at " + override + " is unusable ("
                        + error.getMessage() + "); using the packaged one");
            }
        }
        try (InputStream in = context.getAssets().open("catalogue.json")) {
            cached = io.github.muness.roonpackager.core.RoonPolicy.validate(Catalogue.read(in));
            return cached;
        } catch (IOException error) {
            throw new IllegalStateException("the packaged catalogue is unreadable", error);
        }
    }

    public static synchronized void reload() {
        cached = null;
    }

    /** The installed version of a packed endpoint, or null. */
    public static String installedVersion(Context context, String packageId) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageId, 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException absent) {
            return null;
        }
    }
}
