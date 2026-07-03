package org.telegram.messenger.askan;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Soft-update banner shown at the top of DialogsActivity.
 * Dismissed by the user (X button); reappears on next launch until updated.
 *
 * Layout is a single horizontal row (RTL): [X] [solid update button] [text block →].
 * The text block takes the remaining width (weight 1) so nothing overlaps, and the
 * action is a real solid button (not a faint text link) so it's clearly tappable.
 */
public class UpdateBannerView extends FrameLayout {

    public UpdateBannerView(Context context, BaseFragment fragment, String minVersion, String updateUrl) {
        super(context);
        final String _minVersion = minVersion != null ? minVersion : "";
        final String _updateUrl  = updateUrl  != null ? updateUrl  : "";

        int orange = Theme.getColor(Theme.key_color_orange);

        // Background — soft orange tint, blends in both light and dark
        setBackgroundColor((orange & 0x00FFFFFF) | 0x1A000000);

        // Orange left (start) accent bar
        View accent = new View(context);
        accent.setBackground(new ColorDrawable(orange));
        addView(accent, LayoutHelper.createFrame(4, LayoutHelper.MATCH_PARENT, Gravity.START | Gravity.TOP));

        // Single horizontal row filling the banner
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(6), AndroidUtilities.dp(10), AndroidUtilities.dp(6));
        addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Text block (title + subtitle), takes the remaining width so it never overlaps
        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setText("יש גרסה חדשה (" + _minVersion + ")");
        title.setTextSize(13);
        title.setTextColor(orange);
        title.setTypeface(AndroidUtilities.bold());
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        TextView sub = new TextView(context);
        sub.setText("הגרסה שלך ישנה — מומלץ לעדכן");
        sub.setTextSize(11);
        sub.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(sub, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.START, 0, 2, 0, 0));

        row.addView(textCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        // Solid "עדכן עכשיו" button — high-contrast, clearly tappable
        TextView updateBtn = new TextView(context);
        updateBtn.setText("עדכן עכשיו");
        updateBtn.setTextSize(13);
        updateBtn.setTextColor(0xFFFFFFFF);
        updateBtn.setTypeface(AndroidUtilities.bold());
        updateBtn.setGravity(Gravity.CENTER);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(orange);
        btnBg.setCornerRadius(AndroidUtilities.dp(8));
        updateBtn.setBackground(btnBg);
        updateBtn.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        updateBtn.setClickable(true);
        updateBtn.setOnClickListener(v -> {
            android.app.Activity activity = fragment.getParentActivity();
            if (activity == null) return;
            Intent intent = new Intent(activity, ForceUpdateActivity.class);
            intent.putExtra(ForceUpdateActivity.EXTRA_UPDATE_URL, _updateUrl);
            intent.putExtra(ForceUpdateActivity.EXTRA_MIN_VERSION, _minVersion);
            activity.startActivity(intent);
        });
        row.addView(updateBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

        // X dismiss button
        TextView close = new TextView(context);
        close.setText("×");
        close.setTextSize(20);
        close.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        close.setGravity(Gravity.CENTER);
        close.setClickable(true);
        close.setOnClickListener(v -> setVisibility(GONE));
        row.addView(close, LayoutHelper.createLinear(28, 28, 0f, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));
    }

    /** Height in dp */
    public static int heightDp() { return 60; }
}
