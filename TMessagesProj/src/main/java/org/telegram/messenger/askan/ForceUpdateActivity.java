package org.telegram.messenger.askan;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.browser.Browser;

/**
 * Hard-update screen. Shown when server enforces "hard" and app version is below min.
 * No back navigation — the only action is "עדכן עכשיו".
 * Fail-open: if update_url is empty, shows the screen but without a functional button.
 */
public class ForceUpdateActivity extends Activity {

    public static final String EXTRA_UPDATE_URL     = "update_url";
    public static final String EXTRA_MIN_VERSION    = "min_version";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String updateUrl   = getIntent().getStringExtra(EXTRA_UPDATE_URL);
        String minVersion  = getIntent().getStringExtra(EXTRA_MIN_VERSION);
        if (minVersion == null) minVersion = "";

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF1E293B);
        setContentView(root);

        // Warning icon
        TextView icon = new TextView(this);
        icon.setText("⚠");
        icon.setTextSize(48);
        icon.setTextColor(0xFFFBBF24);
        icon.setGravity(Gravity.CENTER);
        root.addView(icon, frameParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 120, 0, 0));

        // Title
        TextView title = new TextView(this);
        title.setText("נדרש עדכון");
        title.setTextSize(22);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        root.addView(title, frameParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 32, 200, 32, 0));

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
        root.addView(sub, frameParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 32, 250, 32, 0));

        // Update button
        FrameLayout btn = new FrameLayout(this);
        btn.setBackground(new ColorDrawable(0xFF2563EB));
        btn.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14));

        TextView btnText = new TextView(this);
        btnText.setText("עדכן עכשיו");
        btnText.setTextSize(16);
        btnText.setTextColor(0xFFFFFFFF);
        btnText.setTypeface(AndroidUtilities.bold());
        btnText.setGravity(Gravity.CENTER);
        btn.addView(btnText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        if (updateUrl != null && !updateUrl.isEmpty()) {
            final String url = updateUrl;
            btn.setOnClickListener(v -> Browser.openUrl(ForceUpdateActivity.this, url));
        }

        FrameLayout.LayoutParams btnLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        btnLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        btnLp.leftMargin  = AndroidUtilities.dp(32);
        btnLp.rightMargin = AndroidUtilities.dp(32);
        btnLp.bottomMargin = AndroidUtilities.dp(80);
        root.addView(btn, btnLp);
    }

    @Override
    public void onBackPressed() {
        // Block back navigation — user must update
    }

    private FrameLayout.LayoutParams frameParams(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = gravity;
        lp.leftMargin   = AndroidUtilities.dp(l);
        lp.topMargin    = AndroidUtilities.dp(t);
        lp.rightMargin  = AndroidUtilities.dp(r);
        lp.bottomMargin = AndroidUtilities.dp(b);
        return lp;
    }
}
