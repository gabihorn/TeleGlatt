package org.telegram.messenger.askan;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Soft-update banner shown at the top of DialogsActivity.
 * Dismissed by the user (X button); reappears on next launch until updated.
 */
public class UpdateBannerView extends FrameLayout {

    public UpdateBannerView(Context context, BaseFragment fragment, String minVersion, String updateUrl) {
        super(context);
        final String _minVersion = minVersion != null ? minVersion : "";
        final String _updateUrl  = updateUrl  != null ? updateUrl  : "";

        int orange = Theme.getColor(Theme.key_color_orange);

        // Orange left accent bar
        View accent = new View(context);
        accent.setBackground(new ColorDrawable(orange));
        addView(accent, LayoutHelper.createFrame(4, LayoutHelper.MATCH_PARENT, Gravity.START | Gravity.TOP));

        // Background — soft orange tint, blends in both light and dark
        setBackgroundColor((orange & 0x00FFFFFF) | 0x1A000000);

        // Text block
        TextView title = new TextView(context);
        title.setText("יש גרסה חדשה (" + _minVersion + ")");
        title.setTextSize(13);
        title.setTextColor(orange);
        title.setTypeface(AndroidUtilities.bold());
        addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | Gravity.END, 0, 10, 12, 0));

        TextView sub = new TextView(context);
        sub.setText("הגרסה שלך ישנה — מומלץ לעדכן");
        sub.setTextSize(11);
        sub.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        addView(sub, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | Gravity.END, 0, 28, 12, 0));

        // "עדכן עכשיו" link
        TextView updateBtn = new TextView(context);
        updateBtn.setText("עדכן עכשיו");
        updateBtn.setTextSize(11);
        updateBtn.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        updateBtn.setTypeface(AndroidUtilities.bold());
        updateBtn.setPaintFlags(updateBtn.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        updateBtn.setClickable(true);
        updateBtn.setOnClickListener(v -> {
            android.app.Activity activity = fragment.getParentActivity();
            if (activity == null) return;
            Intent intent = new Intent(activity, ForceUpdateActivity.class);
            intent.putExtra(ForceUpdateActivity.EXTRA_UPDATE_URL, _updateUrl);
            intent.putExtra(ForceUpdateActivity.EXTRA_MIN_VERSION, _minVersion);
            activity.startActivity(intent);
        });
        addView(updateBtn, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END, 0, 0, 12, 10));

        // X dismiss button
        TextView close = new TextView(context);
        close.setText("×");
        close.setTextSize(20);
        close.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        close.setClickable(true);
        close.setOnClickListener(v -> setVisibility(GONE));
        addView(close, LayoutHelper.createFrame(32, 32, Gravity.TOP | Gravity.START, 8, 6, 0, 0));
    }

    /** Height in dp */
    public static int heightDp() { return 56; }
}
