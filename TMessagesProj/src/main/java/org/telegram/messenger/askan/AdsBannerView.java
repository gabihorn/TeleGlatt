package org.telegram.messenger.askan;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Full-width image-only ad banner. Height = 56dp (same as a chat row).
 * Click handled in DialogsActivity.onItemClick() — no setOnClickListener here.
 */
public class AdsBannerView extends FrameLayout {

    private static final android.util.LruCache<String, Bitmap> imageCache;
    static {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        imageCache = new android.util.LruCache<String, Bitmap>(maxKb) {
            @Override protected int sizeOf(String key, Bitmap v) { return v.getByteCount() / 1024; }
        };
    }

    private static final String IMAGE_BASE = "https://api.askansmart.com";
    private static final int HEIGHT_DP = 56;

    private final ImageView imageView;
    private final TextView adBadge;
    private final View divider;

    AskanAdsManager.Ad currentAd;
    private boolean impressionSent = false;

    public AdsBannerView(Context context) {
        super(context);
        setClipChildren(true);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0.5f, Gravity.BOTTOM));

        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setAdjustViewBounds(false);
        imageView.setBackground(new ColorDrawable(0xFFE5E7EB));
        addView(imageView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, HEIGHT_DP, Gravity.TOP));

        adBadge = new TextView(context);
        adBadge.setText("פרסום");
        adBadge.setTextSize(9);
        adBadge.setTextColor(0xCCFFFFFF);
        adBadge.setTypeface(AndroidUtilities.bold());
        adBadge.setBackground(new ColorDrawable(0x88000000));
        adBadge.setPadding(dp(4), dp(2), dp(4), dp(2));
        addView(adBadge, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT, 6, 5, 0, 0));
    }

    public void bind(AskanAdsManager.Ad ad) {
        currentAd = ad;
        impressionSent = false;

        if (ad == null || !ad.showInBanner()) {
            setVisibility(GONE);
            return;
        }

        // bannerImageUrl() = imageUrl ?? logoUrl ?? null (fallback matrix)
        String bannerUrl = ad.bannerImageUrl();
        if (bannerUrl == null) {
            setVisibility(GONE);
            return;
        }

        setVisibility(VISIBLE);
        loadImage(bannerUrl);
    }

    private void loadImage(String relativeUrl) {
        final String fullUrl = relativeUrl.startsWith("http") ? relativeUrl : IMAGE_BASE + relativeUrl;

        // Cache hit → show immediately, no flash
        Bitmap cached = imageCache.get(fullUrl);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

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
                    imageCache.put(fullUrl, bmp);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (currentAd != null && currentAd.bannerImageUrl() != null
                                && fullUrl.contains(currentAd.bannerImageUrl())) {
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
