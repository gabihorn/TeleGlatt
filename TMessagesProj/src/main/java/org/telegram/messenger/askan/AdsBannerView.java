package org.telegram.messenger.askan;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Full-width image-only ad banner. Height = 56dp (same as a chat row).
 * Click handled in DialogsActivity.onItemClick() — no setOnClickListener here.
 */
public class AdsBannerView extends FrameLayout {

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

    /** Returns the ad currently bound to this banner — used by the click handler so
     *  clicks always target the displayed ad (correct even while ads rotate). */
    public AskanAdsManager.Ad getBoundAd() {
        return currentAd;
    }

    private void loadImage(String relativeUrl) {
        final String fullUrl = relativeUrl.startsWith("http") ? relativeUrl : IMAGE_BASE + relativeUrl;

        // Disk cache hit (< 24h) → decode from disk, no network round-trip.
        // Raw bytes are stored (not re-encoded) so animated GIF/WebP survives.
        File diskFile = getDiskCacheFile(fullUrl);
        if (diskFile != null && diskFile.exists()
                && System.currentTimeMillis() - diskFile.lastModified() < 24 * 60 * 60 * 1000L) {
            showFromFile(diskFile);
            return;
        }

        imageView.setImageDrawable(null);
        new Thread(() -> {
            try {
                URL url = new URL(fullUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                final File target = diskFile;
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[16_384];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (currentAd != null && currentAd.bannerImageUrl() != null
                            && fullUrl.contains(currentAd.bannerImageUrl())) {
                        showFromFile(target);
                    }
                });
            } catch (Exception ignored) {}
        }).start();
    }

    /** Decodes a cached file. Animated GIF/WebP play on API 28+; static fallback below. */
    private void showFromFile(File file) {
        if (file == null || !file.exists()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                Drawable d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file));
                imageView.setImageDrawable(d);
                if (d instanceof AnimatedImageDrawable) {
                    ((AnimatedImageDrawable) d).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                    ((AnimatedImageDrawable) d).start();
                }
                return;
            } catch (Exception ignored) { /* fall through to static decode */ }
        }
        Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bmp != null) imageView.setImageBitmap(bmp);
    }

    private File getDiskCacheFile(String url) {
        try {
            File dir = new File(getContext().getCacheDir(), "askan_ads");
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, Integer.toHexString(url.hashCode()) + ".img");
        } catch (Exception e) {
            return null;
        }
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
