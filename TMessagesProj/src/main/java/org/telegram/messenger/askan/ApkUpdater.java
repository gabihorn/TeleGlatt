package org.telegram.messenger.askan;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads an APK from a URL and triggers the system installer.
 *
 * States: idle → downloading → installing → (app replaced by OS, or error/cancelled)
 *
 * Play Store migration path: implement UpdateProvider interface and swap
 * SideloadUpdateProvider for a PlayUpdateProvider in ForceUpdateActivity.
 */
public class ApkUpdater {

    public interface Listener {
        void onProgress(int percent);    // 0–100 during download
        void onInstalling();             // download done, installer launched
        void onError(String message);    // user-visible error; retry is safe
        void onNeedPermission();         // API 26+: must grant "install unknown apps"
    }

    private static final String APK_DIR = "askan_updates";
    private static final String APK_FILE = "latest.apk";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final long MIN_VALID_SIZE = 1024; // < 1KB = definitely corrupt

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled = false;
    private Thread thread;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start the update flow. Checks install permission on API 26+, then downloads.
     * Safe to call multiple times — cancels any in-progress download first.
     */
    public void start(Activity activity, String url, Listener listener) {
        cancel(); // cancel any previous attempt
        cancelled = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                listener.onNeedPermission();
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                // Use startActivityForResult so onActivityResult can retry
                activity.startActivityForResult(intent, ForceUpdateActivity.REQ_UNKNOWN_SOURCES);
                return;
            }
        }
        download(activity, url, listener);
    }

    /** Call after user returns from Unknown Sources settings (onActivityResult REQ_UNKNOWN_SOURCES). */
    public void retryAfterPermission(Activity activity, String url, Listener listener) {
        start(activity, url, listener);
    }

    /** Interrupt a running download. Leaves no partial file on disk. */
    public void cancel() {
        cancelled = true;
        if (thread != null) thread.interrupt();
    }

    /** Delete leftover APK — call from onActivityResult when installer returns CANCELLED. */
    public static void cleanup(Activity activity) {
        apkFile(activity).delete();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void download(Activity activity, String url, Listener listener) {
        File apk = apkFile(activity);
        apk.getParentFile().mkdirs();
        apk.delete(); // remove any leftover from previous attempt

        thread = new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.connect();

                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    apk.delete();
                    postError(listener, "שגיאת שרת " + code + ". נסה שוב.");
                    return;
                }

                long total = conn.getContentLengthLong(); // -1 if unknown

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(apk)) {

                    byte[] buf = new byte[16_384];
                    long downloaded = 0;
                    int n;
                    int lastPct = -1;

                    while ((n = in.read(buf)) != -1) {
                        if (cancelled) { apk.delete(); return; }
                        out.write(buf, 0, n);
                        downloaded += n;
                        if (total > 0) {
                            int pct = (int) (downloaded * 100L / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                mainHandler.post(() -> listener.onProgress(pct));
                            }
                        }
                    }
                    out.flush();
                }

                if (apk.length() < MIN_VALID_SIZE) {
                    apk.delete();
                    postError(listener, "הקובץ שהורד פגום. נסה שוב.");
                    return;
                }

                // Hand off to OS installer (Android verifies APK signature)
                mainHandler.post(() -> {
                    listener.onInstalling();
                    install(activity, apk, listener);
                });

            } catch (IOException e) {
                apk.delete();
                if (!cancelled) {
                    postError(listener, "שגיאת רשת: " + e.getMessage() + "\nבדוק חיבור ונסה שוב.");
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "apk-downloader");
        thread.start();
    }

    private void install(Activity activity, File apk, Listener listener) {
        try {
            String authority = activity.getPackageName() + ".provider";
            Uri uri = FileProvider.getUriForFile(activity, authority, apk);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // Don't add FLAG_ACTIVITY_NEW_TASK — we want onActivityResult to fire
            activity.startActivityForResult(intent, ForceUpdateActivity.REQ_INSTALL);
        } catch (Exception e) {
            apk.delete();
            postError(listener, "שגיאת התקנה: " + e.getMessage());
        }
    }

    private void postError(Listener listener, String msg) {
        mainHandler.post(() -> listener.onError(msg));
    }

    private static File apkFile(Activity activity) {
        return new File(new File(activity.getCacheDir(), APK_DIR), APK_FILE);
    }
}
