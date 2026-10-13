package io.github.muness.roonpackager;

import android.content.Context;

import io.github.muness.roonpackager.core.DownloadCache;

import java.io.File;

/**
 * The one download cache this app keeps, and where it lives.
 *
 * <p>{@code getFilesDir()}, not {@code getCacheDir()}: the platform is free to
 * clear the latter whenever it likes, which would defeat the point of not
 * re-fetching a 13 MB vendor archive on every pack. Uninstalling the manager
 * still clears it, same as the signing key.
 */
public final class Downloads {

    private static DownloadCache instance;

    private Downloads() {
    }

    public static synchronized DownloadCache cache(Context context) {
        if (instance == null) {
            instance = new DownloadCache(new File(context.getApplicationContext().getFilesDir(),
                    "download-cache"));
        }
        return instance;
    }
}
