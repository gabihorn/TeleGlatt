package org.telegram.messenger.askan;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches ads from /api/ads (behind device-token auth).
 * Fails silently — the dialogs list is never blocked by this.
 * Refresh interval: 30 minutes. Returns the highest-priority active ad.
 */
public class AskanAdsManager {

    private static final String TAG = "AskanAds";
    private static final String ADS_URL = "https://api.askansmart.com/api/ads";
    private static final long REFRESH_MS = 30 * 60 * 1000L; // 30 min

    public static class Ad {
        public final int id;
        public final String title;
        public final String imageUrl;  // null if no image
        public final String bodyText;  // null if no text
        public final String targetUrl;

        public Ad(int id, String title, String imageUrl, String bodyText, String targetUrl) {
            this.id = id;
            this.title = title;
            this.imageUrl = (imageUrl != null && !imageUrl.isEmpty()) ? imageUrl : null;
            this.bodyText = (bodyText != null && !bodyText.isEmpty()) ? bodyText : null;
            this.targetUrl = targetUrl;
        }
    }

    public interface AdsListener {
        void onAdChanged(Ad ad); // ad == null means no ads
    }

    private static volatile AskanAdsManager instance;

    private Ad currentAd = null;
    private long lastFetchMs = 0;
    private boolean fetching = false;
    private final List<AdsListener> listeners = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AskanAdsManager() {}

    public static AskanAdsManager getInstance() {
        if (instance == null) {
            synchronized (AskanAdsManager.class) {
                if (instance == null) instance = new AskanAdsManager();
            }
        }
        return instance;
    }

    public Ad getCurrentAd() {
        return currentAd;
    }

    public void addListener(AdsListener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(AdsListener l) {
        listeners.remove(l);
    }

    /** Call from UI thread after login / onResume. Fetches in background; no-ops if already fresh. */
    public synchronized void fetchIfStale() {
        if (fetching) return;
        long now = System.currentTimeMillis();
        if (now - lastFetchMs < REFRESH_MS) return;
        fetching = true;
        new Thread(this::doFetch).start();
    }

    /** Force a fresh fetch regardless of cache age (e.g., on first login). */
    public synchronized void fetchNow() {
        if (fetching) return;
        fetching = true;
        new Thread(this::doFetch).start();
    }

    private void doFetch() {
        try {
            AskanFilter filter = AskanFilter.getInstance();
            String token = filter.getDeviceToken();
            if (token == null || token.isEmpty()) {
                Log.d(TAG, "No device token — skipping ad fetch");
                done(null);
                return;
            }

            URL url = new URL(ADS_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-Device-Token", token);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.d(TAG, "GET /api/ads returned " + code);
                done(null);
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONArray arr = new JSONArray(sb.toString());
            if (arr.length() == 0) { done(null); return; }

            // Take the first (highest priority) ad
            JSONObject obj = arr.getJSONObject(0);
            Ad ad = new Ad(
                obj.getInt("id"),
                obj.optString("title", ""),
                obj.optString("image_url", null),
                obj.optString("body_text", null),
                obj.optString("target_url", "")
            );
            Log.d(TAG, "Fetched ad id=" + ad.id + " title=" + ad.title);
            done(ad);

        } catch (Exception e) {
            Log.d(TAG, "Ad fetch failed (silent): " + e.getMessage());
            done(null);
        }
    }

    private void done(Ad ad) {
        mainHandler.post(() -> {
            synchronized (AskanAdsManager.this) {
                fetching = false;
                lastFetchMs = System.currentTimeMillis();
                if (isDifferent(currentAd, ad)) {
                    currentAd = ad;
                    for (AdsListener l : new ArrayList<>(listeners)) l.onAdChanged(ad);
                }
            }
        });
    }

    private static boolean isDifferent(Ad a, Ad b) {
        if (a == null && b == null) return false;
        if (a == null || b == null) return true;
        return a.id != b.id;
    }

    // ── Tracking ──────────────────────────────────────────────────────────────

    public void sendImpression(int adId) {
        new Thread(() -> {
            try {
                String token = AskanFilter.getInstance().getDeviceToken();
                if (token == null) return;
                URL url = new URL(ADS_URL + "/" + adId + "/impression");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("X-Device-Token", token);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.getResponseCode(); // fire and ignore result
            } catch (Exception ignored) {}
        }).start();
    }

    public void sendClick(int adId) {
        new Thread(() -> {
            try {
                String token = AskanFilter.getInstance().getDeviceToken();
                if (token == null) return;
                URL url = new URL(ADS_URL + "/" + adId + "/click");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("X-Device-Token", token);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.getResponseCode();
            } catch (Exception ignored) {}
        }).start();
    }
}
