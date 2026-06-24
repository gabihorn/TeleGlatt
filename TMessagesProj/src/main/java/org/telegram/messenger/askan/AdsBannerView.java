package org.telegram.messenger.askan;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Single ad banner cell — displayed as first item in the dialogs RecyclerView.
 * Shows "פרסום" badge, image (optional), title, and body text.
 * Tap → sends click tracking then opens target URL via existing Browser.openUrl.
 */
public class AdsBannerView extends FrameLayout {

    private static final String IMAGE_BASE = "https://api.askansmart.com";

    private final ImageView imageView;
    private final TextView adBadge;
    private final TextView titleView;
    private final TextView bodyView;
    private final View divider;

    private AskanAdsManager.Ad currentAd;
    private boolean impressionSent = false;

    public AdsBannerView(Context context) {
        super(context);

        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        setMinimumHeight(dp(72));

        // Divider line at the bottom
        divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0.5f, Gravity.BOTTOM));

        // "פרסום" badge — top-left (RTL: top-right visually)
        adBadge = new TextView(context);
        adBadge.setText("פרסום");
        adBadge.setTextSize(10);
        adBadge.setTextColor(0xFF9CA3AF);
        adBadge.setTypeface(AndroidUtilities.bold());
        adBadge.setLetterSpacing(0.05f);
        addView(adBadge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT, 0, 6, 14, 0));

        // Thumbnail image (64×48dp, rounded rect)
        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setBackground(new ColorDrawable(0xFFE5E7EB));
        imageView.setVisibility(View.GONE);
        imageView.setClipToOutline(true);
        addView(imageView, LayoutHelper.createFrame(64, 48,
                Gravity.CENTER_VERTICAL | Gravity.LEFT, 14, 0, 0, 0));

        // Text column (title + body)
        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);
        addView(textCol, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | Gravity.LEFT, 14 + 64 + 10, 0, 14, 0));

        titleView = new TextView(context);
        titleView.setTextSize(14);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        bodyView = new TextView(context);
        bodyView.setTextSize(12);
        bodyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        bodyView.setMaxLines(2);
        bodyView.setEllipsize(TextUtils.TruncateAt.END);
        bodyView.setVisibility(View.GONE);
        textCol.addView(bodyView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        setOnClickListener(v -> {
            if (currentAd == null) return;
            AskanAdsManager.getInstance().sendClick(currentAd.id);
            Browser.openUrl(getContext(), currentAd.targetUrl);
        });
    }

    public void bind(AskanAdsManager.Ad ad) {
        currentAd = ad;
        impressionSent = false;

        if (ad == null) {
            setVisibility(GONE);
            return;
        }

        setVisibility(VISIBLE);

        titleView.setText(ad.title);

        if (ad.bodyText != null) {
            bodyView.setText(ad.bodyText);
            bodyView.setVisibility(VISIBLE);
        } else {
            bodyView.setVisibility(GONE);
        }

        // Load image if present
        if (ad.imageUrl != null) {
            imageView.setVisibility(VISIBLE);
            loadImage(ad.imageUrl);
        } else {
            imageView.setVisibility(GONE);
            imageView.setImageBitmap(null);
        }
    }

    private void loadImage(String relativeUrl) {
        final String fullUrl = relativeUrl.startsWith("http") ? relativeUrl : IMAGE_BASE + relativeUrl;
        imageView.setImageBitmap(null);
        new Thread(() -> {
            try {
                URL url = new URL(fullUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                InputStream in = conn.getInputStream();
                Bitmap bmp = BitmapFactory.decodeStream(in);
                in.close();
                if (bmp != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (currentAd != null && fullUrl.contains(currentAd.imageUrl != null ? currentAd.imageUrl : "")) {
                            imageView.setImageBitmap(bmp);
                        }
                    });
                }
            } catch (Exception ignored) {}
        }).start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (currentAd != null && !impressionSent) {
            impressionSent = true;
            AskanAdsManager.getInstance().sendImpression(currentAd.id);
        }
    }
}
