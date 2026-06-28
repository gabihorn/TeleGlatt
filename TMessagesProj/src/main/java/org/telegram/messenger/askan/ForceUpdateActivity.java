package org.telegram.messenger.askan;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.UpdateAvailability;

import org.telegram.messenger.AndroidUtilities;

/**
 * Mandatory update screen — launched when server enforces hard/soft update.
 *
 * Routing:
 *   Installed from Play Store  → Play In-App Updates (IMMEDIATE)
 *   Sideloaded / unknown       → ApkUpdater (direct APK download)
 *
 * Play Store migration: already handled — no extra steps needed when publishing.
 */
public class ForceUpdateActivity extends Activity {

    public static final String EXTRA_UPDATE_URL  = "update_url";
    public static final String EXTRA_MIN_VERSION = "min_version";

    // Request codes
    public static final int REQ_UNKNOWN_SOURCES = 1001; // sideload: "install unknown apps" settings
    public static final int REQ_INSTALL         = 1002; // sideload: OS installer dialog
    private static final int REQ_PLAY_UPDATE    = 1003; // Play: in-app update flow

    // ── UI refs ───────────────────────────────────────────────────────────────
    private FrameLayout updateBtn;
    private TextView    btnLabel;
    private ProgressBar progressBar;
    private TextView    progressText;
    private TextView    errorText;

    // ── State ─────────────────────────────────────────────────────────────────
    private String         updateUrl;
    private boolean        isPlayInstall;
    private AppUpdateManager playUpdateManager;
    private ApkUpdater     updater;
    private boolean        downloading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        updateUrl = getIntent().getStringExtra(EXTRA_UPDATE_URL);
        String minVersion = getIntent().getStringExtra(EXTRA_MIN_VERSION);
        if (minVersion == null) minVersion = "";
        if (updateUrl  == null) updateUrl  = "";

        isPlayInstall = isInstalledFromPlayStore();

        if (isPlayInstall) {
            playUpdateManager = AppUpdateManagerFactory.create(this);
        } else {
            updater = new ApkUpdater();
        }

        buildUi(minVersion);
    }

    // ── Installer detection ───────────────────────────────────────────────────

    private boolean isInstalledFromPlayStore() {
        try {
            String installer = getPackageManager().getInstallerPackageName(getPackageName());
            return "com.android.vending".equals(installer);
        } catch (Exception e) {
            return false;
        }
    }

    // ── onActivityResult ──────────────────────────────────────────────────────

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_PLAY_UPDATE) {
            setDownloadingState(false);
            if (resultCode == RESULT_CANCELED) {
                showError("העדכון בוטל. לחץ \"עדכן שוב\" לנסות מחדש.");
            } else if (resultCode != RESULT_OK) {
                // RESULT_IN_APP_UPDATE_FAILED or other failure
                showError("עדכון ה-Play Store נכשל. נסה שוב מאוחר יותר.");
            }
            // RESULT_OK → app is being replaced; this activity is dead
            return;
        }

        // Sideload paths
        if (requestCode == REQ_UNKNOWN_SOURCES) {
            startDownload();
            return;
        }
        if (requestCode == REQ_INSTALL) {
            ApkUpdater.cleanup(this);
            if (resultCode == RESULT_CANCELED) {
                showError("ההתקנה בוטלה. לחץ \"עדכן שוב\" לנסות מחדש.");
            }
        }
    }

    @Override
    public void onBackPressed() {
        // Block — user must update
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (updater != null) updater.cancel();
    }

    // ── Download / update trigger ─────────────────────────────────────────────

    private void startDownload() {
        if (isPlayInstall) {
            startPlayUpdate();
        } else {
            startSideloadUpdate();
        }
    }

    private void startPlayUpdate() {
        setDownloadingState(true);
        playUpdateManager.getAppUpdateInfo()
            .addOnSuccessListener(this::launchPlayUpdate)
            .addOnFailureListener(e -> {
                setDownloadingState(false);
                // Play unavailable — offer direct Play Store link as fallback
                showPlayStoreFallback();
            });
    }

    private void launchPlayUpdate(AppUpdateInfo info) {
        if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
            try {
                playUpdateManager.startUpdateFlowForResult(
                        info, AppUpdateType.IMMEDIATE, this, REQ_PLAY_UPDATE);
            } catch (IntentSender.SendIntentException e) {
                setDownloadingState(false);
                showPlayStoreFallback();
            }
        } else {
            // Play reports no update, or update type not allowed.
            // Possible if version is not yet rolled out on Play.
            setDownloadingState(false);
            showPlayStoreFallback();
        }
    }

    private void showPlayStoreFallback() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + getPackageName())));
        }
        showError("פתח את Google Play ועדכן ידנית.");
    }

    private void startSideloadUpdate() {
        if (updateUrl.isEmpty()) {
            showError("כתובת העדכון אינה זמינה. פנה לתמיכה.");
            return;
        }
        setDownloadingState(true);

        updater.start(this, updateUrl, new ApkUpdater.Listener() {
            @Override public void onNeedPermission() {
                setDownloadingState(false);
                showInfo("אפשר התקנת אפליקציות ממקורות לא ידועים, ולאחר מכן לחץ שוב.");
            }
            @Override public void onProgress(int percent) {
                progressBar.setProgress(percent);
                progressText.setText("מוריד... " + percent + "%");
            }
            @Override public void onInstalling() {
                progressText.setText("מתקין...");
            }
            @Override public void onError(String message) {
                setDownloadingState(false);
                showError(message);
            }
        });
    }

    // ── UI builders ───────────────────────────────────────────────────────────

    private void buildUi(String minVersion) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0D0D14);
        setContentView(root);

        // Warning icon
        TextView icon = new TextView(this);
        icon.setText("⚠");
        icon.setTextSize(48);
        icon.setTextColor(0xFFFBBF24);
        icon.setGravity(Gravity.CENTER);
        root.addView(icon, fp(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 100, 0, 0));

        // Title
        TextView title = new TextView(this);
        title.setText("נדרש עדכון");
        title.setTextSize(22);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        root.addView(title, fp(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP, 32, 180, 32, 0));

        // Subtitle
        String versionText = minVersion.isEmpty()
                ? "גרסה חדשה נדרשת להמשך שימוש."
                : "גרסה " + minVersion + " נדרשת להמשך שימוש.";
        TextView sub = new TextView(this);
        sub.setText(versionText + "\nעדכן את האפליקציה כדי להמשיך.");
        sub.setTextSize(14);
        sub.setTextColor(0xFF94A3B8);
        sub.setGravity(Gravity.CENTER);
        sub.setLineSpacing(AndroidUtilities.dp(4), 1f);
        root.addView(sub, fp(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP, 32, 240, 32, 0));

        // Progress bar (hidden until download starts — sideload only)
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, fp(FrameLayout.LayoutParams.MATCH_PARENT,
                AndroidUtilities.dp(8),
                Gravity.CENTER_HORIZONTAL | Gravity.TOP, 32, 330, 32, 0));

        // Progress text
        progressText = new TextView(this);
        progressText.setTextSize(13);
        progressText.setTextColor(0xFF94A3B8);
        progressText.setGravity(Gravity.CENTER);
        progressText.setVisibility(View.GONE);
        root.addView(progressText, fp(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP, 32, 348, 32, 0));

        // Error / info text
        errorText = new TextView(this);
        errorText.setTextSize(13);
        errorText.setTextColor(0xFFEF4444);
        errorText.setGravity(Gravity.CENTER);
        errorText.setLineSpacing(AndroidUtilities.dp(3), 1f);
        errorText.setVisibility(View.GONE);
        root.addView(errorText, fp(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP, 32, 348, 32, 0));

        // Update button
        updateBtn = new FrameLayout(this);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xFF1A1A6E);
        btnBg.setCornerRadius(AndroidUtilities.dp(12));
        updateBtn.setBackground(btnBg);
        updateBtn.setPadding(0, AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16));

        btnLabel = new TextView(this);
        btnLabel.setText("עדכן עכשיו");
        btnLabel.setTextSize(16);
        btnLabel.setTextColor(0xFFFFFFFF);
        btnLabel.setTypeface(AndroidUtilities.bold());
        btnLabel.setGravity(Gravity.CENTER);
        updateBtn.addView(btnLabel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        updateBtn.setOnClickListener(v -> {
            if (!downloading) startDownload();
        });

        FrameLayout.LayoutParams btnLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        btnLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        btnLp.leftMargin   = AndroidUtilities.dp(32);
        btnLp.rightMargin  = AndroidUtilities.dp(32);
        btnLp.bottomMargin = AndroidUtilities.dp(80);
        root.addView(updateBtn, btnLp);
    }

    // ── State helpers ─────────────────────────────────────────────────────────

    private void setDownloadingState(boolean active) {
        downloading = active;
        // Show progress bar only for sideload (Play handles its own UI)
        progressBar.setVisibility(active && !isPlayInstall ? View.VISIBLE : View.GONE);
        progressText.setVisibility(active && !isPlayInstall ? View.VISIBLE : View.GONE);
        errorText.setVisibility(View.GONE);
        if (active) {
            progressBar.setProgress(0);
            progressText.setText(isPlayInstall ? "" : "מתחבר לשרת...");
            btnLabel.setText(isPlayInstall ? "פותח Play Store..." : "מוריד...");
            updateBtn.setEnabled(false);
            ((GradientDrawable) updateBtn.getBackground()).setColor(0xFF475569);
        } else {
            btnLabel.setText("נסה שוב");
            updateBtn.setEnabled(true);
            ((GradientDrawable) updateBtn.getBackground()).setColor(0xFF1A1A6E);
        }
    }

    private void showError(String msg) {
        progressBar.setVisibility(View.GONE);
        progressText.setVisibility(View.GONE);
        errorText.setTextColor(0xFFEF4444);
        errorText.setText(msg);
        errorText.setVisibility(View.VISIBLE);
        btnLabel.setText("עדכן שוב");
        updateBtn.setEnabled(true);
        ((GradientDrawable) updateBtn.getBackground()).setColor(0xFF1A1A6E);
        downloading = false;
    }

    private void showInfo(String msg) {
        progressBar.setVisibility(View.GONE);
        progressText.setVisibility(View.GONE);
        errorText.setTextColor(0xFFFBBF24);
        errorText.setText(msg);
        errorText.setVisibility(View.VISIBLE);
        btnLabel.setText("עדכן שוב");
        updateBtn.setEnabled(true);
        ((GradientDrawable) updateBtn.getBackground()).setColor(0xFF1A1A6E);
        downloading = false;
    }

    private FrameLayout.LayoutParams fp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = gravity;
        lp.leftMargin   = AndroidUtilities.dp(l);
        lp.topMargin    = AndroidUtilities.dp(t);
        lp.rightMargin  = AndroidUtilities.dp(r);
        lp.bottomMargin = AndroidUtilities.dp(b);
        return lp;
    }
}
