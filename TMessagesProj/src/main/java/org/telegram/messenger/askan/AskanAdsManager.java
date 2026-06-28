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

    private Ad currentAd = null;            // highest-priority ad (channels + fallback)
    private final List<Ad> allAds = new ArrayList<>(); // every active ad from server
    private int bannerIndex = 0;            // round-robin pointer for the banner
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

    /** Banner ads (placement banner/both), in priority order. */
    private List<Ad> bannerAds() {
        List<Ad> b = new ArrayList<>();
        for (Ad a : allAds) if (a.showInBanner()) b.add(a);
        if (b.isEmpty() && currentAd != null && currentAd.showInBanner()) b.add(currentAd);
        return b;
    }

    /** The banner ad to display right now (stable until rotateBanner() is called). */
    public synchronized Ad getCurrentBannerAd() {
        List<Ad> b = bannerAds();
        if (b.isEmpty()) return null;
        if (bannerIndex >= b.size()) bannerIndex = 0;
        return b.get(bannerIndex);
    }

    /** Advance to the next banner ad — call on each return to the main screen. */
    public synchronized void rotateBanner() {
        int n = bannerAds().size();
        if (n > 1) bannerIndex = (bannerIndex + 1) % n;
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
                doneError(); // not a real server response — keep cached ads
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
                doneError(); // server error, keep cached ads
                return;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONArray arr = new JSONArray(sb.toString());
            List<Ad> parsed = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                parsed.add(new Ad(
                    obj.getInt("id"),
                    obj.optString("title", ""),
                    obj.isNull("image_url")  ? null : obj.optString("image_url",  ""),
                    obj.isNull("logo_url")   ? null : obj.optString("logo_url",   ""),
                    obj.isNull("body_text")  ? null : obj.optString("body_text",  ""),
                    obj.optString("target_url", ""),
                    obj.optString("placement", "both")
                ));
            }
            Log.d(TAG, "Fetched " + parsed.size() + " ad(s)");
            applyAds(parsed); // empty list = real "no ads" → clears

        } catch (Exception e) {
            Log.d(TAG, "Ad fetch failed (silent): " + e.getMessage());
            doneError(); // network error — keep whatever ads we already have
        }
    }

    /** Resets the fetching flag without touching ads (used on network/token errors). */
    private void doneError() {
        mainHandler.post(() -> {
            synchronized (AskanAdsManager.this) { fetching = false; }
        });
    }

    private void applyAds(List<Ad> ads) {
        mainHandler.post(() -> {
            synchronized (AskanAdsManager.this) {
                fetching = false;
                lastFetchMs = System.currentTimeMillis();
                Ad newTop = ads.isEmpty() ? null : ads.get(0);
                allAds.clear();
                allAds.addAll(ads);
                if (bannerIndex >= ads.size()) bannerIndex = 0;
                saveCachedAds(ads);
                if (isDifferent(currentAd, newTop)) {
                    currentAd = newTop;
                    for (AdsListener l : new ArrayList<>(listeners)) l.onAdChanged(newTop);
                } else {
                    currentAd = newTop;
                }
            }
        });
    }

    private Ad adFromJson(JSONObject obj) throws Exception {
        return new Ad(
            obj.getInt("id"),
            obj.optString("title", ""),
            obj.isNull("image_url") ? null : obj.optString("image_url", ""),
            obj.isNull("logo_url")  ? null : obj.optString("logo_url",  ""),
            obj.isNull("body_text") ? null : obj.optString("body_text", ""),
            obj.optString("target_url", ""),
            obj.optString("placement", "both")
        );
    }

    private void loadCachedAd() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) return;
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_AD_JSON, null);
            if (json == null) return;
            String trimmed = json.trim();
            allAds.clear();
            if (trimmed.startsWith("[")) {
                JSONArray arr = new JSONArray(trimmed);
                for (int i = 0; i < arr.length(); i++) allAds.add(adFromJson(arr.getJSONObject(i)));
            } else {
                // Backward-compat: old single-object cache
                allAds.add(adFromJson(new JSONObject(trimmed)));
            }
            currentAd = allAds.isEmpty() ? null : allAds.get(0);
            Log.d(TAG, "Loaded " + allAds.size() + " cached ad(s)");
        } catch (Exception e) {
            Log.d(TAG, "loadCachedAd failed: " + e.getMessage());
        }
    }

    private void saveCachedAds(List<Ad> ads) {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) return;
            SharedPreferences.Editor editor = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
            if (ads == null || ads.isEmpty()) {
                editor.remove(KEY_AD_JSON);
            } else {
                JSONArray arr = new JSONArray();
                for (Ad ad : ads) {
                    JSONObject obj = new JSONObject();
                    obj.put("id", ad.id);
                    obj.put("title", ad.title != null ? ad.title : "");
                    if (ad.imageUrl != null) obj.put("image_url", ad.imageUrl); else obj.put("image_url", JSONObject.NULL);
                    if (ad.logoUrl  != null) obj.put("logo_url",  ad.logoUrl);  else obj.put("logo_url",  JSONObject.NULL);
                    if (ad.bodyText != null) obj.put("body_text", ad.bodyText); else obj.put("body_text", JSONObject.NULL);
                    obj.put("target_url", ad.targetUrl != null ? ad.targetUrl : "");
                    obj.put("placement", ad.placement);
                    arr.put(obj);
                }
                editor.putString(KEY_AD_JSON, arr.toString());
            }
            editor.apply();
        } catch (Exception e) {
            Log.d(TAG, "saveCachedAds failed: " + e.getMessage());
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
