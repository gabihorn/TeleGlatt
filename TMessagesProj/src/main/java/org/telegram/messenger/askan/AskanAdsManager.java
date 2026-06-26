package org.telegram.messenger.askan;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fetches ads from /api/ads (behind device-token auth).
 * Fails silently — the dialogs list is never blocked by this.
 * Refresh interval: 30 minutes. Returns the highest-priority active ad.
 */
public class AskanAdsManager {

    private static final String TAG = "AskanAds";
    private static final String ADS_URL = "https://api.askansmart.com/api/ads";
    private static final String IMAGE_BASE = "https://api.askansmart.com";
    private static final long REFRESH_MS = 30 * 60 * 1000L; // 30 min
    private static final String PREFS_NAME = "askan_ads";
    private static final String KEY_AD_JSON = "ad_json";

    private static String resolveUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        return url.startsWith("http") ? url : IMAGE_BASE + url;
    }

    public static class Ad {
        public final int id;
        public final String title;
        public final String imageUrl;   // wide banner for list header
        public final String logoUrl;    // square logo for in-channel sponsored
        public final String bodyText;
        public final String targetUrl;
        public final String placement;  // "banner" | "channels" | "both"

        public Ad(int id, String title, String imageUrl, String logoUrl, String bodyText, String targetUrl, String placement) {
            this.id = id;
            this.title = title;
            this.imageUrl  = resolveUrl(imageUrl);
            this.logoUrl   = resolveUrl(logoUrl);
            this.bodyText  = (bodyText  != null && !bodyText.isEmpty())  ? bodyText  : null;
            this.targetUrl = targetUrl;
            this.placement = (placement != null && !placement.isEmpty()) ? placement : "both";
        }

        public boolean showInBanner()   { return "both".equals(placement) || "banner".equals(placement); }
        public boolean showInChannels() { return "both".equals(placement) || "channels".equals(placement); }

        /** Image for the wide banner (list header). Falls back to logo if no banner image. */
        public String bannerImageUrl() { return imageUrl != null ? imageUrl : logoUrl; }

        /** Image for in-channel sponsored. Falls back to banner image if no logo. */
        public String channelLogoUrl() { return logoUrl != null ? logoUrl : imageUrl; }
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
    // Chats where the user dismissed the in-channel banner this session
    private final Set<Long> dismissedChats = new HashSet<>();

    private AskanAdsManager() {
        loadCachedAd();
    }

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
                done(null, false); // not a real server response
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
                done(null, false); // server error, keep cached ad
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONArray arr = new JSONArray(sb.toString());
            if (arr.length() == 0) {
                done(null, true); // real "no ads" from server — clear cached
                return;
            }

            // Take the first (highest priority) ad
            JSONObject obj = arr.getJSONObject(0);
            Ad ad = new Ad(
                obj.getInt("id"),
                obj.optString("title", ""),
                obj.isNull("image_url")  ? null : obj.optString("image_url",  ""),
                obj.isNull("logo_url")   ? null : obj.optString("logo_url",   ""),
                obj.isNull("body_text")  ? null : obj.optString("body_text",  ""),
                obj.optString("target_url", ""),
                obj.optString("placement", "both")
            );
            Log.d(TAG, "Fetched ad id=" + ad.id + " title=" + ad.title);
            done(ad, true);

        } catch (Exception e) {
            Log.d(TAG, "Ad fetch failed (silent): " + e.getMessage());
            done(null, false); // network error, keep cached ad
        }
    }

    private void done(Ad ad, boolean persistUpdate) {
        mainHandler.post(() -> {
            synchronized (AskanAdsManager.this) {
                fetching = false;
                lastFetchMs = System.currentTimeMillis();
                if (persistUpdate) saveCachedAd(ad);
                if (isDifferent(currentAd, ad)) {
                    currentAd = ad;
                    for (AdsListener l : new ArrayList<>(listeners)) l.onAdChanged(ad);
                }
            }
        });
    }

    private void loadCachedAd() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) return;
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_AD_JSON, null);
            if (json == null) return;
            JSONObject obj = new JSONObject(json);
            currentAd = new Ad(
                obj.getInt("id"),
                obj.optString("title", ""),
                obj.isNull("image_url") ? null : obj.optString("image_url", ""),
                obj.isNull("logo_url")  ? null : obj.optString("logo_url",  ""),
                obj.isNull("body_text") ? null : obj.optString("body_text", ""),
                obj.optString("target_url", ""),
                obj.optString("placement", "both")
            );
            Log.d(TAG, "Loaded cached ad id=" + currentAd.id);
        } catch (Exception e) {
            Log.d(TAG, "loadCachedAd failed: " + e.getMessage());
        }
    }

    private void saveCachedAd(Ad ad) {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) return;
            SharedPreferences.Editor editor = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
            if (ad == null) {
                editor.remove(KEY_AD_JSON);
            } else {
                JSONObject obj = new JSONObject();
                obj.put("id", ad.id);
                obj.put("title", ad.title != null ? ad.title : "");
                if (ad.imageUrl != null) obj.put("image_url", ad.imageUrl); else obj.put("image_url", JSONObject.NULL);
                if (ad.logoUrl  != null) obj.put("logo_url",  ad.logoUrl);  else obj.put("logo_url",  JSONObject.NULL);
                if (ad.bodyText != null) obj.put("body_text", ad.bodyText); else obj.put("body_text", JSONObject.NULL);
                obj.put("target_url", ad.targetUrl != null ? ad.targetUrl : "");
                obj.put("placement", ad.placement);
                editor.putString(KEY_AD_JSON, obj.toString());
            }
            editor.apply();
        } catch (Exception e) {
            Log.d(TAG, "saveCachedAd failed: " + e.getMessage());
        }
    }

    private static boolean isDifferent(Ad a, Ad b) {
        if (a == null && b == null) return false;
        if (a == null || b == null) return true;
        return a.id != b.id;
    }

    // ── In-channel dismiss (per session) ─────────────────────────────────────

    public synchronized void dismissForChat(long chatId) {
        dismissedChats.add(chatId);
    }

    public synchronized boolean isDismissedForChat(long chatId) {
        return dismissedChats.contains(chatId);
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
