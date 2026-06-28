package org.telegram.messenger.askan;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AskanFilter {

    private static final String SERVER_URL = "https://api.askansmart.com";
    private static final String PREFS_NAME = "askan_filter";

    public static long lastPermissionsFetch = 0;

    private static volatile AskanFilter instance;

    private boolean showProfilePhotos = true;
    private boolean showStories = true;
    private int maxMegagroupSize = 250;
    private boolean contentFilterEnabled = false;
    // "ok" | "soft" | "hard" — null means no check received yet (fail-open)
    private volatile String updateCheckStatus = null;
    private volatile String updateCheckUrl = "";
    private volatile String updateCheckMinVersion = "";
    private final Set<String> globalAllow = new HashSet<>();
    private final Set<String> userAllow = new HashSet<>();
    private final Set<String> blockedWords = new HashSet<>();
    private final Set<String> blockedChats = new HashSet<>();
    // Persisted map: numericId → username, built opportunistically when TLRPC.Chat is available.
    // Used by isExplicitlyAllowedById in FCM cold-start to resolve username from numeric ID.
    private final HashMap<String, String> usernameById = new HashMap<>();

    // Device token — persisted across sessions. Issued via POST /api/auth/device.
    // Stored in SharedPreferences key "device_token".
    private volatile String deviceToken = null;

    private AskanFilter() {}

    public static AskanFilter getInstance() {
        if (instance == null) {
            synchronized (AskanFilter.class) {
                if (instance == null) {
                    instance = new AskanFilter();
                }
            }
        }
        return instance;
    }

    // ─── HTTP result container (avoids shared mutable fields) ─────────────────

    private static class HttpResult {
        final int code;
        final String body;
        HttpResult(int code, String body) { this.code = code; this.body = body != null ? body : ""; }
    }

    // ─── Token management ─────────────────────────────────────────────────────

    /**
     * Returns the stored token if present, or issues a new one via POST /api/auth/device.
     * Blocks — call only from background threads.
     */
    private String acquireToken(String phone, long telegramId) {
        String existing = deviceToken;
        if (existing != null) return existing;
        return issueNewToken(phone, telegramId);
    }

    /** Clears the cached token (call on 401 to force re-issuance). */
    private void clearToken() {
        deviceToken = null;
        try {
            ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().remove("device_token").apply();
        } catch (Exception ignored) {}
    }

    /**
     * Issues a new device token via POST /api/auth/device.
     * Blocks — call only from background threads.
     */
    private String issueNewToken(String phone, long telegramId) {
        try {
            JSONObject body = new JSONObject();
            body.put("phone", phone);
            body.put("telegram_id", telegramId);
            HttpResult result = postJson(SERVER_URL + "/api/auth/device", body, null);

            if (result.code != 200) {
                FileLog.e("AskanFilter: issueNewToken returned " + result.code);
                return null;
            }
            String tok = new JSONObject(result.body).optString("device_token", null);
            if (tok == null || tok.isEmpty()) {
                FileLog.e("AskanFilter: issueNewToken — empty token in response");
                return null;
            }
            deviceToken = tok;
            ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString("device_token", tok).apply();
            Log.d("AskanFilter", "device_token acquired");
            return tok;
        } catch (Exception e) {
            FileLog.e("AskanFilter: issueNewToken failed", e);
            return null;
        }
    }

    /**
     * Finds telegram_id for a phone across all active UserConfig accounts.
     * Used for token re-issuance in methods that don't receive telegramId explicitly.
     */
    private long getCachedTelegramId(String phone) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                TLRPC.User user = UserConfig.getInstance(a).getCurrentUser();
                if (user != null && phone != null && phone.equals(user.phone)) {
                    return user.id;
                }
            }
        }
        return -1;
    }

    // ─── HTTP helpers ─────────────────────────────────────────────────────────

    /** POSTs JSON; token may be null for public endpoints. */
    private HttpResult postJson(String urlStr, JSONObject body, String token) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            if (token != null) conn.setRequestProperty("X-Device-Token", token);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.toString().getBytes("UTF-8"));

            int code = conn.getResponseCode();
            java.io.InputStream stream = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = "";
            if (stream != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                responseBody = sb.toString();
            }
            return new HttpResult(code, responseBody);
        } catch (Exception e) {
            FileLog.e("AskanFilter: postJson failed to " + urlStr, e);
            return new HttpResult(-1, "");
        }
    }

    /** GETs a URL with the device token. */
    private HttpResult getWithToken(String urlStr, String token) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            if (token != null) conn.setRequestProperty("X-Device-Token", token);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            int code = conn.getResponseCode();
            if (code != 200) return new HttpResult(code, "");

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return new HttpResult(code, sb.toString());
        } catch (Exception e) {
            FileLog.e("AskanFilter: getWithToken failed: " + urlStr, e);
            return new HttpResult(-1, "");
        }
    }

    // ─── Fetch from server ────────────────────────────────────────────────────

    public void fetchPermissions(String phone, long telegramId) {
        new Thread(() -> {
            try {
                // Step 1: GET /api/permissions — send cached token if available (stage B).
                // Server currently accepts requests without token (rate-limited only).
                // Stage C: token will become mandatory; remove the fallback then.
                URL url = new URL(SERVER_URL + "/api/permissions/" + phone + "?telegram_id=" + telegramId);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                // Send cached token if present — allows server to validate identity even before stage C
                String cachedToken = deviceToken;
                if (cachedToken != null) {
                    conn.setRequestProperty("X-Device-Token", cachedToken);
                }
                // Send app version so server can enforce min_version
                try {
                    android.content.pm.PackageInfo pi = ApplicationLoader.applicationContext
                            .getPackageManager()
                            .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
                    conn.setRequestProperty("X-App-Version-Code", String.valueOf(pi.versionCode));
                    conn.setRequestProperty("X-App-Version-Name", pi.versionName != null ? pi.versionName : "");
                } catch (Exception ignored) {}

                int status = conn.getResponseCode();
                if (status != 200) {
                    FileLog.e("AskanFilter: permissions returned " + status);
                    return;
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                parseAndSave(sb.toString());

                // Step 2: Ensure device token before authenticated calls
                String tok = acquireToken(phone, telegramId);
                if (tok == null) {
                    FileLog.e("AskanFilter: token unavailable — skipping authenticated piggyback calls");
                    return;
                }

                // Step 3: Authenticated calls (only after token confirmed)
                checkRequestStatusChanges(phone, telegramId);
                reportVpnApps(phone, telegramId);
                AskanAdsManager.getInstance().fetchNow();

            } catch (Exception e) {
                FileLog.e("AskanFilter: fetchPermissions failed", e);
                try {
                    com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(e);
                } catch (Exception ignored) {}
            }
        }).start();
    }

    // ─── Send access request ─────────────────────────────────────────────────

    public interface AccessRequestCallback {
        void onResult(String status);
    }

    public void sendAccessRequest(String phone, String chatUsername,
                                   String chatName, String note,
                                   Runnable onSuccess, Runnable onRejected) {
        sendAccessRequest(phone, chatUsername, chatName, note, status -> {
            if ("pending".equals(status) || "already_pending".equals(status)) {
                if (onSuccess != null) onSuccess.run();
            } else {
                if (onRejected != null) onRejected.run();
            }
        });
    }

    public void sendAccessRequest(String phone, String chatUsername,
                                   String chatName, String note,
                                   AccessRequestCallback callback) {
        new Thread(() -> {
            try {
                long telegramId = getCachedTelegramId(phone);
                String token = acquireToken(phone, telegramId);
                if (token == null) {
                    FileLog.e("AskanFilter: sendAccessRequest — no token");
                    AndroidUtilities.runOnUIThread(() -> { if (callback != null) callback.onResult("error"); });
                    return;
                }

                JSONObject body = new JSONObject();
                body.put("phone", phone);
                body.put("chat_username", chatUsername);
                body.put("chat_name", chatName != null ? chatName : "");
                body.put("note", note != null ? note : "");

                HttpResult r = postJson(SERVER_URL + "/api/requests", body, token);
                if (r.code == 401) {
                    clearToken();
                    token = issueNewToken(phone, telegramId);
                    r = (token != null)
                            ? postJson(SERVER_URL + "/api/requests", body, token)
                            : new HttpResult(401, "");
                }

                final HttpResult result = r;
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        if (result.code >= 200 && result.code < 300) {
                            String s = new JSONObject(result.body).optString("status", "error");
                            if (callback != null) callback.onResult(s);
                        } else {
                            if (callback != null) callback.onResult("error");
                        }
                    } catch (Exception e) {
                        if (callback != null) callback.onResult("error");
                    }
                });
            } catch (Exception e) {
                FileLog.e("AskanFilter: sendAccessRequest failed", e);
                AndroidUtilities.runOnUIThread(() -> { if (callback != null) callback.onResult("error"); });
            }
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void parseAndSave(String json) {
        try {
            JSONObject root = new JSONObject(json);

            boolean photosFlag = root.optBoolean("show_profile_photos", true);
            boolean storiesFlag = root.optBoolean("show_stories", true);
            int megagroupSize = root.optInt("max_megagroup_size", 250);
            boolean filterEnabled = root.optBoolean("content_filter_enabled", false);

            Set<String> newGlobal = new HashSet<>();
            JSONArray allowedArr = root.optJSONArray("allowed_chats");
            if (allowedArr != null) {
                for (int i = 0; i < allowedArr.length(); i++) {
                    String chatId = allowedArr.getJSONObject(i).optString("chat_id", "");
                    if (!chatId.isEmpty()) newGlobal.add(norm(chatId));
                }
            }

            Set<String> newUser = new HashSet<>();

            Set<String> newBlocked = new HashSet<>();
            JSONArray wordsArr = root.optJSONArray("blocked_words");
            if (wordsArr != null) {
                for (int i = 0; i < wordsArr.length(); i++) {
                    newBlocked.add(wordsArr.getString(i).toLowerCase());
                }
            }

            Set<String> newBlockedChats = new HashSet<>();
            JSONArray blockedArr = root.optJSONArray("blocked_chats");
            if (blockedArr != null) {
                for (int i = 0; i < blockedArr.length(); i++) {
                    String chatId = blockedArr.optString(i, "");
                    if (!chatId.isEmpty()) newBlockedChats.add(norm(chatId));
                }
            }

            // ── version_check (fail-open: absent field = no enforcement) ─────────
            String newUpdateStatus = null;
            String newUpdateUrl = "";
            String newUpdateMinVersion = "";
            JSONObject vc = root.optJSONObject("version_check");
            if (vc != null) {
                newUpdateStatus     = vc.optString("status", "ok");
                newUpdateUrl        = vc.optString("update_url", "");
                newUpdateMinVersion = vc.optString("min_version", "");
            }

            synchronized (this) {
                showProfilePhotos = photosFlag;
                showStories = storiesFlag;
                maxMegagroupSize = megagroupSize;
                contentFilterEnabled = filterEnabled;
                globalAllow.clear(); globalAllow.addAll(newGlobal);
                userAllow.clear();   userAllow.addAll(newUser);
                blockedWords.clear(); blockedWords.addAll(newBlocked);
                blockedChats.clear(); blockedChats.addAll(newBlockedChats);
                updateCheckStatus     = newUpdateStatus;
                updateCheckUrl        = newUpdateUrl;
                updateCheckMinVersion = newUpdateMinVersion;
            }

            ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("show_profile_photos", photosFlag)
                    .putBoolean("show_stories", storiesFlag)
                    .putInt("max_megagroup_size", megagroupSize)
                    .putBoolean("content_filter_enabled", filterEnabled)
                    .putStringSet("global_allow", newGlobal)
                    .putStringSet("user_allow", newUser)
                    .putStringSet("blocked_words", newBlocked)
                    .putStringSet("blocked_chats", newBlockedChats)
                    .putString("update_status",      newUpdateStatus != null ? newUpdateStatus : "")
                    .putString("update_url",          newUpdateUrl)
                    .putString("update_min_version",  newUpdateMinVersion)
                    .apply();

            FileLog.d("AskanFilter: permissions loaded — global=" + newGlobal.size()
                    + " words=" + newBlocked.size());

            AndroidUtilities.runOnUIThread(() -> {
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (UserConfig.getInstance(a).isClientActivated()) {
                        NotificationCenter.getInstance(a)
                                .postNotificationName(NotificationCenter.dialogsNeedReload);
                    }
                }
            });

        } catch (Exception e) {
            FileLog.e("AskanFilter: parseAndSave failed", e);
        }
    }

    // ─── Load from cache ──────────────────────────────────────────────────────

    public void loadFromCache() {
        try {
            SharedPreferences prefs = ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            synchronized (this) {
                showProfilePhotos = prefs.getBoolean("show_profile_photos", true);
                showStories = prefs.getBoolean("show_stories", true);
                maxMegagroupSize = prefs.getInt("max_megagroup_size", 250);
                contentFilterEnabled = prefs.getBoolean("content_filter_enabled", false);

                // Normalize on load so case-insensitive matching holds even for
                // pre-fix cached data (before the first fresh fetchPermissions).
                Set<String> g = prefs.getStringSet("global_allow", null);
                globalAllow.clear(); if (g != null) for (String e : g) globalAllow.add(norm(e));

                Set<String> u = prefs.getStringSet("user_allow", null);
                userAllow.clear(); if (u != null) for (String e : u) userAllow.add(norm(e));

                Set<String> w = prefs.getStringSet("blocked_words", null);
                blockedWords.clear(); if (w != null) blockedWords.addAll(w);

                Set<String> bc = prefs.getStringSet("blocked_chats", null);
                blockedChats.clear(); if (bc != null) for (String e : bc) blockedChats.add(norm(e));

                Set<String> idMapSet = prefs.getStringSet("id_username_map", null);
                usernameById.clear();
                if (idMapSet != null) {
                    for (String entry : idMapSet) {
                        int sep = entry.indexOf('|');
                        if (sep > 0) usernameById.put(entry.substring(0, sep), norm(entry.substring(sep + 1)));
                    }
                }
            }

            // Restore cached device token — avoids re-issuance on every app start
            deviceToken = prefs.getString("device_token", null);

            // Restore cached version check status (from last successful fetch)
            String cachedStatus = prefs.getString("update_status", "");
            if (!cachedStatus.isEmpty()) {
                updateCheckStatus     = cachedStatus;
                updateCheckUrl        = prefs.getString("update_url", "");
                updateCheckMinVersion = prefs.getString("update_min_version", "");
            }

            FileLog.d("AskanFilter: loaded from cache — global=" + globalAllow.size()
                    + " token=" + (deviceToken != null ? "present" : "none"));

        } catch (Exception e) {
            FileLog.e("AskanFilter: loadFromCache failed", e);
        }
    }

    // ─── Query methods ────────────────────────────────────────────────────────

    /** Returns the device token for use by other Askan components (e.g. AskanAdsManager). */
    public String getDeviceToken() { return deviceToken; }

    /** Returns "ok", "soft", "hard", or null if no check received yet (fail-open). */
    public String getUpdateCheckStatus()     { return updateCheckStatus; }
    public String getUpdateCheckUrl()        { return updateCheckUrl; }
    public String getUpdateCheckMinVersion() { return updateCheckMinVersion; }

    public synchronized boolean isChannelAllowed(String chatId) {
        if (chatId == null) return false;
        String c = norm(chatId);
        return globalAllow.contains(c) || userAllow.contains(c);
    }

    /**
     * Checks the allow-list by both numeric ID and username.
     * Use this instead of isExplicitlyAllowedById when the username is available,
     * since the server stores allows by username (not numeric ID).
     */
    public synchronized boolean isExplicitlyAllowed(String id, String username) {
        if (globalAllow.contains(id) || userAllow.contains(id)) return true;
        String u = norm(username);
        return u != null && (globalAllow.contains(u) || userAllow.contains(u));
    }

    public synchronized boolean shouldShowProfilePhotos() { return showProfilePhotos; }
    public synchronized boolean shouldShowStories() { return showStories; }
    public synchronized boolean isContentFilterEnabled() { return contentFilterEnabled; }
    public synchronized boolean isSearchBlocked() { return !blockedWords.isEmpty(); }
    public synchronized int getMaxMegagroupSize() { return maxMegagroupSize; }

    /**
     * Returns true if text contains a blocked word at a word boundary.
     * Blocked words override all allow-lists — no channel is exempt.
     * Uses character-level boundary check (works for Hebrew and Latin).
     */
    public synchronized boolean containsBlockedWordForChat(String text, String chatId, String username) {
        if (!contentFilterEnabled) return false;
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase();
        for (String word : blockedWords) {
            if (matchesWordBoundary(lower, word.toLowerCase())) return true;
        }
        return false;
    }

    /** Same word-boundary check without a chat context (e.g. message text). */
    public synchronized boolean containsBlockedWord(String text) {
        if (!contentFilterEnabled) return false;
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase();
        for (String word : blockedWords) {
            if (matchesWordBoundary(lower, word.toLowerCase())) return true;
        }
        return false;
    }

    /**
     * Word-boundary match that works for Hebrew and Latin text.
     * Java's \b only handles ASCII; this checks that the characters
     * immediately before and after the match are not letters.
     */
    private boolean matchesWordBoundary(String text, String word) {
        int idx = text.indexOf(word);
        while (idx >= 0) {
            boolean startOk = idx == 0 || !Character.isLetter(text.charAt(idx - 1));
            boolean endOk   = idx + word.length() >= text.length()
                              || !Character.isLetter(text.charAt(idx + word.length()));
            if (startOk && endOk) return true;
            idx = text.indexOf(word, idx + 1);
        }
        return false;
    }

    // ─── Self-block ───────────────────────────────────────────────────────────

    public void sendBlock(String phone, long telegramId, String chatUsername) {
        new Thread(() -> {
            try {
                String token = acquireToken(phone, telegramId);
                if (token == null) { FileLog.e("AskanFilter: sendBlock — no token"); return; }

                JSONObject body = new JSONObject();
                body.put("chat_username", chatUsername);
                body.put("action", "block");

                HttpResult r = postJson(SERVER_URL + "/api/block", body, token);
                if (r.code == 401) {
                    clearToken();
                    token = issueNewToken(phone, telegramId);
                    if (token != null) r = postJson(SERVER_URL + "/api/block", body, token);
                }

                Log.d("AskanFilter", "sendBlock → " + r.code);
                if (r.code == 200) fetchPermissions(phone, telegramId);

            } catch (Exception e) {
                FileLog.e("AskanFilter: sendBlock failed", e);
            }
        }).start();
    }

    // ─── My requests ─────────────────────────────────────────────────────────────

    public static class RequestInfo {
        public final int id;
        public final String chatUsername;
        public final String chatName;
        public final String status;
        public final String kind;
        public final String privacyTarget;

        public RequestInfo(int id, String chatUsername, String chatName, String status,
                           String kind, String privacyTarget) {
            this.id = id;
            this.chatUsername = chatUsername;
            this.chatName = chatName;
            this.status = status;
            this.kind = kind;
            this.privacyTarget = privacyTarget;
        }
    }

    public interface RequestsCallback {
        void onResult(List<RequestInfo> requests);
    }

    public void fetchMyRequests(String phone, long telegramId, RequestsCallback callback) {
        new Thread(() -> {
            try {
                String token = acquireToken(phone, telegramId);
                if (token == null) {
                    FileLog.e("AskanFilter: fetchMyRequests — no token");
                    AndroidUtilities.runOnUIThread(() -> { if (callback != null) callback.onResult(new ArrayList<>()); });
                    return;
                }

                // Route requires phone (and telegram_id) as query params — without them it returns 400.
                String mineUrl = SERVER_URL + "/api/requests/mine?phone="
                        + java.net.URLEncoder.encode(phone, "UTF-8")
                        + "&telegram_id=" + telegramId;
                HttpResult r = getWithToken(mineUrl, token);
                if (r.code == 401) {
                    clearToken();
                    token = issueNewToken(phone, telegramId);
                    r = (token != null)
                            ? getWithToken(mineUrl, token)
                            : new HttpResult(401, "");
                }

                if (r.code != 200) {
                    AndroidUtilities.runOnUIThread(() -> { if (callback != null) callback.onResult(new ArrayList<>()); });
                    return;
                }

                JSONArray arr = new JSONArray(r.body);
                List<RequestInfo> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String privTarget = obj.isNull("privacy_target") ? null : obj.optString("privacy_target", null);
                    list.add(new RequestInfo(
                            obj.optInt("id", 0),
                            obj.optString("chat_username", ""),
                            obj.optString("chat_name", ""),
                            obj.optString("status", ""),
                            obj.optString("kind", "access"),
                            privTarget));
                }
                AndroidUtilities.runOnUIThread(() -> { if (callback != null) callback.onResult(list); });

            } catch (Exception e) {
                FileLog.e("AskanFilter: fetchMyRequests failed", e);
                AndroidUtilities.runOnUIThread(() -> { if (callback != null) callback.onResult(new ArrayList<>()); });
            }
        }).start();
    }

    // ─── Self-serve privacy hide ──────────────────────────────────────────────

    public void selfHidePrivacy(String phone, long telegramId,
                                 boolean hideProfilePhotos, boolean hideStories,
                                 Runnable onSuccess, Runnable onError) {
        new Thread(() -> {
            try {
                String token = acquireToken(phone, telegramId);
                if (token == null) {
                    FileLog.e("AskanFilter: selfHidePrivacy — no token");
                    AndroidUtilities.runOnUIThread(() -> { if (onError != null) onError.run(); });
                    return;
                }

                JSONObject body = new JSONObject();
                if (hideProfilePhotos) body.put("hide_profile_photos", true);
                if (hideStories)       body.put("hide_stories", true);

                HttpResult r = postJson(SERVER_URL + "/api/privacy/hide", body, token);
                if (r.code == 401) {
                    clearToken();
                    token = issueNewToken(phone, telegramId);
                    r = (token != null)
                            ? postJson(SERVER_URL + "/api/privacy/hide", body, token)
                            : new HttpResult(401, "");
                }

                final HttpResult result = r;
                if (result.code == 200) {
                    try {
                        JSONObject resp = new JSONObject(result.body);
                        boolean photos  = resp.optBoolean("show_profile_photos", true);
                        boolean stories = resp.optBoolean("show_stories", true);
                        synchronized (AskanFilter.this) {
                            showProfilePhotos = photos;
                            showStories = stories;
                        }
                        ApplicationLoader.applicationContext
                                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("show_profile_photos", photos)
                                .putBoolean("show_stories", stories)
                                .apply();
                        AndroidUtilities.runOnUIThread(() -> { if (onSuccess != null) onSuccess.run(); });
                    } catch (Exception e) {
                        AndroidUtilities.runOnUIThread(() -> { if (onError != null) onError.run(); });
                    }
                } else {
                    AndroidUtilities.runOnUIThread(() -> { if (onError != null) onError.run(); });
                }
            } catch (Exception e) {
                FileLog.e("AskanFilter: selfHidePrivacy failed", e);
                AndroidUtilities.runOnUIThread(() -> { if (onError != null) onError.run(); });
            }
        }).start();
    }

    // ─── Privacy restore request ──────────────────────────────────────────────

    public void sendPrivacyRestoreRequest(String phone, long telegramId, String target,
                                           Runnable onSuccess, Runnable onError) {
        new Thread(() -> {
            try {
                String token = acquireToken(phone, telegramId);
                if (token == null) {
                    FileLog.e("AskanFilter: sendPrivacyRestoreRequest — no token");
                    AndroidUtilities.runOnUIThread(() -> { if (onError != null) onError.run(); });
                    return;
                }

                JSONObject body = new JSONObject();
                body.put("target", target);

                HttpResult r = postJson(SERVER_URL + "/api/requests/privacy", body, token);
                if (r.code == 401) {
                    clearToken();
                    token = issueNewToken(phone, telegramId);
                    r = (token != null)
                            ? postJson(SERVER_URL + "/api/requests/privacy", body, token)
                            : new HttpResult(401, "");
                }

                final boolean ok = r.code >= 200 && r.code < 300;
                AndroidUtilities.runOnUIThread(() -> {
                    if (ok) { if (onSuccess != null) onSuccess.run(); }
                    else    { if (onError != null)   onError.run();   }
                });
            } catch (Exception e) {
                FileLog.e("AskanFilter: sendPrivacyRestoreRequest failed", e);
                AndroidUtilities.runOnUIThread(() -> { if (onError != null) onError.run(); });
            }
        }).start();
    }

    // ─── Request status polling — in-app notifications ────────────────────────

    private static final String PREFS_REQ_STATUSES = "askan_req_statuses";
    private static final String NOTIF_CHANNEL_ASKAN = "askan_requests";

    public void checkRequestStatusChanges(String phone, long telegramId) {
        fetchMyRequests(phone, telegramId, requests -> {
            SharedPreferences prefs = ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS_REQ_STATUSES, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            for (RequestInfo req : requests) {
                String key = "req_" + req.id;
                String savedStatus = prefs.getString(key, "pending");
                if ("pending".equals(savedStatus) && !"pending".equals(req.status)) {
                    String title;
                    String text;
                    String tapUrl = null;
                    if ("privacy".equals(req.kind)) {
                        String target = "profile_photos".equals(req.privacyTarget) ? "תמונות פרופיל" : "סטוריז";
                        if ("approved".equals(req.status)) {
                            title = "בקשת " + target + " אושרה";
                            text = "בקשתך לשחרור " + target + " אושרה בהצלחה.";
                        } else {
                            title = "בקשת " + target + " לא אושרה";
                            text = "לאחר בדיקה, הבקשה לשחרור " + target + " לא אושרה.";
                        }
                    } else {
                        String subject = prefs.getString("subj_" + req.chatUsername, "ערוץ");
                        if ("approved".equals(req.status)) {
                            title = "גישה ל" + subject + " אושרה";
                            text = "בקשתך נבדקה ואושרה בהצלחה. כעת ניתן להיכנס ולצפות בתוכן ה" + subject + ".";
                            if (req.chatUsername != null && !req.chatUsername.isEmpty()) {
                                tapUrl = "https://t.me/" + req.chatUsername;
                                // Unblock immediately in local cache so the channel opens without waiting for next fetchPermissions
                                synchronized (AskanFilter.this) {
                                    userAllow.add(req.chatUsername);
                                }
                            }
                        } else {
                            title = "הגישה ל" + subject + " לא אושרה";
                            text = "לאחר בדיקה, נמצא כי תוכן ה" + subject + " אינו תואם את מדיניות הסינון של TeleGlatt.";
                        }
                    }
                    postLocalNotification(req.id, title, text, tapUrl);
                }
                // Clear local "pending" flag once the request is no longer pending
                if (!"pending".equals(req.status) && req.chatUsername != null && !req.chatUsername.isEmpty()) {
                    editor.remove("pending_" + req.chatUsername);
                }
                editor.putString(key, req.status);
            }
            editor.apply();
        });
    }

    private void postLocalNotification(int id, String title, String text, String tapUrl) {
        Context ctx = ApplicationLoader.applicationContext;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    NOTIF_CHANNEL_ASKAN, "TeleGlatt — עדכונים", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }

        android.app.Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new android.app.Notification.Builder(ctx, NOTIF_CHANNEL_ASKAN);
        } else {
            builder = new android.app.Notification.Builder(ctx);
        }
        builder.setSmallIcon(org.telegram.messenger.R.drawable.notification)
               .setContentTitle(title)
               .setContentText(text)
               .setStyle(new android.app.Notification.BigTextStyle().bigText(text))
               .setAutoCancel(true);

        if (tapUrl != null) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(tapUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, 10000 + id, intent, flags);
            builder.setContentIntent(pi);
        }

        nm.notify(10000 + id, builder.build());
    }

    // ─── VPN detection & reporting ────────────────────────────────────────────

    private static final String[] VPN_PACKAGES = {
            "com.askan.vpn",           // NetSpark (Askan-branded)
            "com.netspark.mobile",     // NetSpark standard
            "com.canopy.vpn.filter",   // Canopy
    };

    // Canopy uses activity-alias icons per distributor; only the active one is ENABLED at runtime.
    // Package is com.canopy.vpn.filter; class names are in com.netspark.android.netsvpn namespace.
    private static final String[][] CANOPY_DISTRIBUTOR_ALIASES = {
            {"com.netspark.android.netsvpn.Iconaskan",    "askan"},
            {"com.netspark.android.netsvpn.Iconnativ",    "nativ"},
            {"com.netspark.android.netsvpn.Iconsafetec",  "safetec"},
            {"com.netspark.android.netsvpn.Iconnetsmart", "netsmart"},
            {"com.netspark.android.netsvpn.Iconnetspark", "netspark"},
            {"com.netspark.android.netsvpn.Iconcanopy",   "canopy"},
    };

    /** Returns the active Canopy distributor name, or null if Canopy is not installed or distributor unknown. */
    public static String detectCanopyDistributor() {
        PackageManager pm = ApplicationLoader.applicationContext.getPackageManager();
        for (String[] alias : CANOPY_DISTRIBUTOR_ALIASES) {
            ComponentName cn = new ComponentName("com.canopy.vpn.filter", alias[0]);
            try {
                int state = pm.getComponentEnabledSetting(cn);
                if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    return alias[1];
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    public JSONArray detectVpnApps() {
        JSONArray result = new JSONArray();
        PackageManager pm = ApplicationLoader.applicationContext.getPackageManager();
        for (String pkg : VPN_PACKAGES) {
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA);
                JSONObject entry = new JSONObject();
                entry.put("package", pkg);
                entry.put("version_name", pm.getPackageInfo(pkg, 0).versionName);
                entry.put("version_code", pm.getPackageInfo(pkg, 0).versionCode);
                if (info.metaData != null) {
                    JSONObject meta = new JSONObject();
                    for (String key : info.metaData.keySet()) {
                        Object val = info.metaData.get(key);
                        meta.put(key, val != null ? val.toString() : JSONObject.NULL);
                    }
                    entry.put("meta", meta);
                }
                if ("com.canopy.vpn.filter".equals(pkg)) {
                    String distributor = detectCanopyDistributor();
                    if (distributor != null) entry.put("canopy_distributor", distributor);
                }
                result.put(entry);
            } catch (PackageManager.NameNotFoundException ignored) {
            } catch (Exception e) {
                FileLog.e("AskanFilter: detectVpnApps " + pkg, e);
            }
        }
        return result;
    }

    /**
     * Reports detected VPN apps. Must be called AFTER token is confirmed (via fetchPermissions).
     * Fire-and-forget with one 401 retry.
     */
    public void reportVpnApps(String phone, long telegramId) {
        JSONArray apps = detectVpnApps();
        if (apps.length() == 0) return;

        new Thread(() -> {
            try {
                String token = deviceToken != null ? deviceToken : acquireToken(phone, telegramId);
                if (token == null) { FileLog.e("AskanFilter: reportVpnApps — no token"); return; }

                JSONObject body = new JSONObject();
                body.put("vpn_apps", apps);

                HttpResult r = postJson(SERVER_URL + "/api/report", body, token);
                if (r.code == 401) {
                    clearToken();
                    token = issueNewToken(phone, telegramId);
                    if (token != null) r = postJson(SERVER_URL + "/api/report", body, token);
                }
                Log.d("AskanFilter", "reportVpnApps → " + r.code);
            } catch (Exception e) {
                FileLog.e("AskanFilter: reportVpnApps failed", e);
            }
        }).start();
    }

    // ─── Username/id normalization ────────────────────────────────────────────
    // Telegram usernames are case-insensitive (@MyChannel == @mychannel). The server
    // stores allow/block usernames lowercased (+ @-stripped); the app must match the
    // same way or approved channels stay blocked. Numeric ids are unaffected by this.
    private static String norm(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("@")) s = s.substring(1);
        return s.toLowerCase();
    }

    // ─── Central block checks ─────────────────────────────────────────────────

    /**
     * Returns true if the chat should be blocked.
     *
     * Check order (explicit before type-based defaults):
     *   0. Explicit user-block (blockedChats) — overrides everything, ALL chat types including basic groups.
     *   1. Basic groups — not filtered by Askan beyond explicit blocks.
     *   2. Megagroups — blocked unless on allow list or linked to an approved channel.
     *   3. Channels/bots — blocked unless on allow list.
     */
    public synchronized boolean isChatBlocked(TLRPC.Chat chat, TLRPC.ChatFull chatFull) {
        if (chat == null) return false;

        // Rule 0: explicit user-block — checked FIRST for ALL types, including basic groups
        String idStr = String.valueOf(chat.id);
        String uname = norm(chat.username); // case-insensitive match (server stores lowercased)
        // Opportunistic: record id→username so FCM cold-start can resolve the allow-list by username
        recordIdMapping(idStr, uname);
        if (blockedChats.contains(idStr) || (uname != null && blockedChats.contains(uname)))
            return true;

        // Rule 1: basic groups — not filtered by Askan (explicit block above is the only check)
        if (!ChatObject.isMegagroup(chat) && !ChatObject.isChannelAndNotMegaGroup(chat)) {
            return false;
        }

        // Rule 2: megagroup
        if (ChatObject.isMegagroup(chat)) {
            if (globalAllow.contains(idStr) || userAllow.contains(idStr)
                    || (uname != null && (globalAllow.contains(uname) || userAllow.contains(uname)))) {
                return false;
            }
            // Discussion group of an approved channel
            if (chatFull != null && chatFull.linked_chat_id != 0) {
                String linkedId = String.valueOf(chatFull.linked_chat_id);
                if (globalAllow.contains(linkedId) || userAllow.contains(linkedId)) {
                    return false;
                }
            }
            return true;
        }

        // Rule 3: channel
        boolean allowed = globalAllow.contains(idStr) || userAllow.contains(idStr)
                || (uname != null && (globalAllow.contains(uname) || userAllow.contains(uname)));
        return !allowed;
    }

    /**
     * Records numericId→username in the persisted map so FCM cold-start can resolve allow-list
     * lookups. Called within synchronized context; .apply() is non-blocking.
     * Only writes to SharedPreferences when the mapping is new or changed.
     */
    private void recordIdMapping(String idStr, String username) {
        if (username == null || username.isEmpty()) return;
        if (username.equals(usernameById.get(idStr))) return;
        usernameById.put(idStr, username);
        Set<String> snapshot = new HashSet<>(usernameById.size());
        for (Map.Entry<String, String> e : usernameById.entrySet()) {
            snapshot.add(e.getKey() + "|" + e.getValue());
        }
        ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putStringSet("id_username_map", snapshot).apply();
    }

    // ─── Cold-start helpers ───────────────────────────────────────────────────
    // Used when TLRPC.Chat is not yet in MessagesController cache (FCM push, app killed).
    // usernameById is populated opportunistically by isChatBlocked whenever a TLRPC.Chat is seen.
    // Channels seen at least once resolve correctly; never-seen channels remain fail-CLOSED (safe).

    public synchronized boolean isExplicitlyBlockedById(String id) {
        return blockedChats.contains(id);
    }

    /** Used by swipe-between-channels to skip blocked channels without opening them. */
    public synchronized boolean isChannelBlockedForNav(String chatId, String username) {
        if (chatId != null && blockedChats.contains(chatId)) return true;
        String u = norm(username);
        return u != null && blockedChats.contains(u);
    }

    public synchronized boolean isExplicitlyAllowedById(String id) {
        if (globalAllow.contains(id) || userAllow.contains(id)) return true;
        // Resolve numeric ID → username via the persisted mapping built by isChatBlocked
        String username = norm(usernameById.get(id));
        if (username != null) {
            return globalAllow.contains(username) || userAllow.contains(username);
        }
        return false; // channel never seen → remain fail-CLOSED
    }

    public synchronized boolean isUserBlocked(TLRPC.User user) {
        if (user == null) return false;

        String idStr = String.valueOf(user.id);
        String uname = norm(user.username); // case-insensitive match (server stores lowercased)
        if (blockedChats.contains(idStr) || (uname != null && blockedChats.contains(uname)))
            return true;

        if (!user.bot) return false;
        boolean allowed = globalAllow.contains(idStr) || userAllow.contains(idStr)
                || (uname != null && (globalAllow.contains(uname) || userAllow.contains(uname)));
        return !allowed;
    }
}
