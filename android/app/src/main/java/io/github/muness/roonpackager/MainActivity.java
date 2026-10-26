package io.github.muness.roonpackager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.muness.roonpackager.core.Catalogue;
import io.github.muness.roonpackager.core.DownloadCache;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/**
 * The whole screen: the catalogue as a list, and one action per entry.
 *
 * <p>Minimal and honest, in the two other apps' style. Per entry it says what
 * version the vendor is on, what version the app shell is, what is installed if
 * anything, and offers the one action that makes sense. While a run is going it
 * shows the real step names, because a phone spends real seconds on each of
 * them and "please wait" would be a lie about what is happening.
 */
public final class MainActivity extends Activity implements JobState.Listener {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView jobLine;
    private TextView logView;
    private TextView cacheSummary;
    private TextView versionDetails;
    private LinearLayout cacheList;
    private DiagnosticsServer diagnostics;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        root.addView(body("Play music from Roon through this device's built-in DAC or a USB DAC."
                + " You need a Roon server on the same network.\n\n"
                + "Tap Download and install, read the download notice, then confirm installation."
                + " Open Roon Bridge to choose your output and start listening."));
        jobLine = body("idle");
        jobLine.setTypeface(Typeface.MONOSPACE);
        root.addView(jobLine);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        Button technical = new Button(this);
        technical.setText("Technical details");
        root.addView(technical);
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(View.GONE);
        root.addView(details);
        technical.setOnClickListener(view -> {
            boolean show = details.getVisibility() != View.VISIBLE;
            details.setVisibility(show ? View.VISIBLE : View.GONE);
            technical.setText(show ? "Hide technical details" : "Technical details");
        });
        versionDetails = body("");
        details.addView(versionDetails);
        details.addView(body(keyNote()));
        details.addView(body("Local diagnostics are available while this screen is open on TCP "
                + DiagnosticsServer.PORT + "."));

        details.addView(heading("Cached downloads"));
        details.addView(body("Verified downloads are saved here for the next installation."));
        cacheSummary = body("");
        details.addView(cacheSummary);
        Button clearCache = new Button(this);
        clearCache.setText("Clear cache");
        clearCache.setOnClickListener(view -> onClearCache());
        details.addView(clearCache);
        cacheList = new LinearLayout(this);
        cacheList.setOrientation(LinearLayout.VERTICAL);
        details.addView(cacheList);

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(10);
        logView.setTextIsSelectable(true);
        logView.setMovementMethod(new ScrollingMovementMethod());
        details.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        diagnostics = new DiagnosticsServer(this);
        diagnostics.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        JobState.listen(this);
        render();
    }

    @Override
    protected void onPause() {
        JobState.forget(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (diagnostics != null) {
            diagnostics.stop();
        }
        super.onDestroy();
    }

    @Override
    public void onJobChanged() {
        handler.post(this::render);
    }

    private void render() {
        jobLine.setText(JobState.line());
        String failure = JobState.failure();
        logView.setText((failure == null ? "" : failure + "\n\n") + tail(ManagerLog.text(), 60));
        list.removeAllViews();
        Catalogue catalogue;
        try {
            catalogue = Catalogues.load(this);
        } catch (RuntimeException error) {
            list.addView(body("The catalogue is unreadable: " + error.getMessage()));
            return;
        }
        StringBuilder versions = new StringBuilder();
        for (Catalogue.Endpoint endpoint : catalogue.endpoints) {
            list.addView(entryView(endpoint));
            versions.append("Roon Bridge component: ").append(endpoint.vendorVersion)
                    .append("\nAndroid app: ").append(endpoint.shell.versionName).append("\n");
        }
        versionDetails.setText(versions.toString().trim());
        renderCache();
    }

    private void renderCache() {
        List<DownloadCache.Entry> entries = Downloads.cache(this).list();
        long total = 0;
        for (DownloadCache.Entry entry : entries) {
            total += entry.sizeBytes;
        }
        cacheSummary.setText(entries.isEmpty() ? "nothing cached"
                : entries.size() + " file" + (entries.size() == 1 ? "" : "s") + ", "
                        + humanBytes(total) + " total");
        cacheList.removeAllViews();
        SimpleDateFormat lastUsedFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (DownloadCache.Entry entry : entries) {
            cacheList.addView(cacheRow(entry, lastUsedFormat));
        }
    }

    private View cacheRow(DownloadCache.Entry entry, SimpleDateFormat lastUsedFormat) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView text = new TextView(this);
        text.setTextSize(12);
        text.setText(entry.label + " " + entry.version + "\n" + humanBytes(entry.sizeBytes)
                + ", last used " + lastUsedFormat.format(new java.util.Date(entry.lastUsedMillis))
                + "\nsha256 " + entry.sha256);
        text.setTextIsSelectable(true);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(text, textParams);

        Button remove = new Button(this);
        remove.setText("Remove");
        remove.setOnClickListener(view -> {
            Downloads.cache(this).delete(entry.sha256);
            ManagerLog.i("cache: removed " + entry.label + " " + entry.version + " ("
                    + entry.sha256 + ") from the UI");
            renderCache();
        });
        row.addView(remove);
        return row;
    }

    private void onClearCache() {
        new AlertDialog.Builder(this)
                .setTitle("Clear cache")
                .setMessage("Delete saved and unfinished downloads? Your installed apps stay in place."
                        + " The next installation will download the files again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    long[] freed = Downloads.cache(this).clearAll();
                    ManagerLog.i("cache: cleared " + freed[0] + " file(s), " + freed[1]
                            + " bytes (from the UI)");
                    new AlertDialog.Builder(this)
                            .setTitle("Cache cleared")
                            .setMessage("Deleted " + freed[0] + " file" + (freed[0] == 1 ? "" : "s")
                                    + ", " + humanBytes(freed[1]) + ".")
                            .setPositiveButton("OK", null)
                            .show();
                    renderCache();
                })
                .show();
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        return String.format(Locale.US, "%.1f MB", kb / 1024.0);
    }

    private View entryView(Catalogue.Endpoint endpoint) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(16), 0, dp(8));

        TextView name = new TextView(this);
        name.setText(endpoint.displayName);
        name.setTextSize(18);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(name);

        String installed = Catalogues.installedVersion(this, endpoint.shell.packageId);
        card.addView(body(installed == null ? "Ready to install" : "Installed version: " + installed));

        if (installed != null) {
            card.addView(body("Open Roon Bridge to choose your built-in DAC or USB DAC."));
            Button open = new Button(this);
            open.setText("Open Roon Bridge — set up audio");
            open.setMinHeight(dp(48));
            open.setOnClickListener(view -> {
                android.content.Intent launch = getPackageManager().getLaunchIntentForPackage(endpoint.shell.packageId);
                if (launch != null) startActivity(launch);
                else android.widget.Toast.makeText(this, "Roon Bridge cannot be opened. Try reinstalling it.", android.widget.Toast.LENGTH_LONG).show();
            });
            card.addView(open);
        }
        Button action = new Button(this);
        action.setText(installed == null ? "Download and install" : "Update installation");
        action.setEnabled(!JobState.running() && !endpoint.shell.url.contains("example.invalid"));
        if (endpoint.shell.url.contains("example.invalid")) {
            card.addView(body("This installer has no app download configured. Get the latest release."));
        }
        action.setOnClickListener(view -> onAction(endpoint));
        card.addView(action);
        return card;
    }

    private void onAction(Catalogue.Endpoint endpoint) {
        String message = endpoint.licence.text;
        if (endpoint.licence.vendorUrl != null && !endpoint.licence.vendorUrl.isEmpty()) {
            message += "\n\nTerms: " + endpoint.licence.vendorUrl;
        }
        new AlertDialog.Builder(this)
                .setTitle(endpoint.licence.title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("I understand, download it", (dialog, which) -> {
                    ManagerLog.i("licence acknowledged for " + endpoint.id + " ("
                            + endpoint.licence.vendorUrl + ")");
                    PackService.start(this, endpoint.id);
                })
                .show();
    }

    private String keyNote() {
        boolean present = new java.io.File(getFilesDir(), "packing-key.p12").isFile();
        return "Installation signing key: " + (present ? "saved" : "created on first install")
                + ". Android needs this key to update your copy of Roon Bridge."
                + " Keep this app installed and keep its app data. If you delete the key,"
                + " you must uninstall Roon Bridge before installing it again.";
    }

    private static String tail(String text, int lines) {
        String[] all = text.split("\n");
        if (all.length <= lines) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        for (int index = all.length - lines; index < all.length; index++) {
            out.append(all[index]).append('\n');
        }
        return out.toString();
    }

    private TextView heading(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(22);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setPadding(0, dp(8), 0, 0);
        view.setTextIsSelectable(true);
        return view;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
