package org.telegram.messenger.askan;

import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.HashSet;
import java.util.List;
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
    private final Set<String> globalAllow = new HashSet<>();
    private final Set<String> userAllow = new HashSet<>();
    private final Set<String> blockedWords = new HashSet<>();
    private final Set<String> blockedChats = new HashSet<>();

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

    // ─── Fetch from server ────────────────────────────────────────────────────

    public void fetchPermissions(String phone, long telegramId) {
        new Thread(() -> {
            try {
                String urlStr = SERVER_URL + "/api/permissions/" + phone + "?telegram_id=" + telegramId;
                Log.d("AskanFilter", "fetchPermissions → GET " + urlStr);

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                int status = conn.getResponseCode();
                Log.d("AskanFilter", "response code: " + status);

                if (status != 200) {
                    FileLog.e("AskanFilter: server returned " + status + " for phone " + phone);
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                String json = sb.toString();
                Log.d("AskanFilter", "response JSON: " + json);

                parseAndSave(json);

            } catch (Exception e) {
                FileLog.e("AskanFilter: fetchPermissions failed", e);
            }
        }).start();
    }

    // ─── Send access request ─────────────────────────────────────────────────

    public void sendAccessRequest(String phone, String chatUsername,
                                   String chatName, String note,
                                   Runnable onSuccess, Runnable onRejected) {
        new Thread(() -> {
            try {
                URL url = new URL(SERVER_URL + "/api/requests");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("phone", phone);
                body.put("chat_username", chatUsername);
                body.put("chat_name", chatName != null ? chatName : "");
                body.put("note", note != null ? note : "");

                byte[] bodyBytes = body.toString().getBytes("UTF-8");
                conn.getOutputStream().write(bodyBytes);

                int status = conn.getResponseCode();
                Log.d("AskanFilter", "sendAccessRequest → status " + status);

                // getInputStream() throws on 4xx/5xx — use getErrorStream() for those.
                java.io.InputStream stream = (status >= 200 && status < 300)
                        ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream != null ? stream : conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                String json = sb.toString();
                Log.d("AskanFilter", "sendAccessRequest ← " + json);

                android.os.Handler mainHandler = new android.os.Handler(
                        android.os.Looper.getMainLooper());
                if (status >= 200 && status < 300) {
                    JSONObject resp = new JSONObject(json);
                    String respStatus = resp.optString("status", "");
                    if ("pending".equals(respStatus)) {
                        if (onSuccess != null) mainHandler.post(onSuccess);
                    } else {
                        if (onRejected != null) mainHandler.post(onRejected);
                    }
                } else {
                    Log.e("AskanFilter", "sendAccessRequest server error " + status + ": " + json);
                    if (onRejected != null) mainHandler.post(onRejected);
                }

            } catch (Exception e) {
                FileLog.e("AskanFilter: sendAccessRequest failed", e);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (onRejected != null) onRejected.run();
                });
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

            // Server returns merged list as "allowed_chats"
            Set<String> newGlobal = new HashSet<>();
            JSONArray allowedArr = root.optJSONArray("allowed_chats");
            if (allowedArr != null) {
                for (int i = 0; i < allowedArr.length(); i++) {
                    JSONObject item = allowedArr.getJSONObject(i);
                    String chatId = item.optString("chat_id", "");
                    if (!chatId.isEmpty()) newGlobal.add(chatId);
                }
            }

            Set<String> newUser = new HashSet<>(); // reserved for future per-user lists

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
                    // server returns plain string array: ["uh1221", ...]
                    String chatId = blockedArr.optString(i, "");
                    if (!chatId.isEmpty()) newBlockedChats.add(chatId);
                }
            }

            // Apply in-memory
            synchronized (this) {
                showProfilePhotos = photosFlag;
                showStories = storiesFlag;
                maxMegagroupSize = megagroupSize;
                contentFilterEnabled = filterEnabled;
                globalAllow.clear();
                globalAllow.addAll(newGlobal);
                userAllow.clear();
                userAllow.addAll(newUser);
                blockedWords.clear();
                blockedWords.addAll(newBlocked);
                blockedChats.clear();
                blockedChats.addAll(newBlockedChats);
            }

            // Persist to SharedPreferences
            SharedPreferences prefs = ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean("show_profile_photos", photosFlag);
            editor.putBoolean("show_stories", storiesFlag);
            editor.putInt("max_megagroup_size", megagroupSize);
            editor.putBoolean("content_filter_enabled", filterEnabled);
            editor.putStringSet("global_allow", newGlobal);
            editor.putStringSet("user_allow", newUser);
            editor.putStringSet("blocked_words", newBlocked);
            editor.putStringSet("blocked_chats", newBlockedChats);
            editor.apply();

            FileLog.d("AskanFilter: permissions loaded — global=" + newGlobal.size()
                    + " user=" + newUser.size() + " words=" + newBlocked.size());

            // Notify the dialog list to refresh so newly approved chats appear immediately.
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

                Set<String> cachedGlobal = prefs.getStringSet("global_allow", null);
                globalAllow.clear();
                if (cachedGlobal != null) globalAllow.addAll(cachedGlobal);

                Set<String> cachedUser = prefs.getStringSet("user_allow", null);
                userAllow.clear();
                if (cachedUser != null) userAllow.addAll(cachedUser);

                Set<String> cachedWords = prefs.getStringSet("blocked_words", null);
                blockedWords.clear();
                if (cachedWords != null) blockedWords.addAll(cachedWords);

                Set<String> cachedBlockedChats = prefs.getStringSet("blocked_chats", null);
                blockedChats.clear();
                if (cachedBlockedChats != null) blockedChats.addAll(cachedBlockedChats);
            }

            FileLog.d("AskanFilter: loaded from cache — global=" + globalAllow.size()
                    + " user=" + userAllow.size() + " words=" + blockedWords.size());

        } catch (Exception e) {
            FileLog.e("AskanFilter: loadFromCache failed", e);
        }
    }

    // ─── Query methods ────────────────────────────────────────────────────────

    public synchronized boolean isChannelAllowed(String chatId) {
        if (chatId == null) return false;
        return globalAllow.contains(chatId) || userAllow.contains(chatId);
    }

    public synchronized boolean shouldShowProfilePhotos() {
        return showProfilePhotos;
    }

    public synchronized boolean shouldShowStories() {
        return showStories;
    }

    public synchronized boolean containsBlockedWord(String text) {
        if (!contentFilterEnabled) return false; // zero-cost guard when filter is off
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase();
        for (String word : blockedWords) {
            if (lower.contains(word)) return true;
        }
        return false;
    }

    public synchronized boolean isContentFilterEnabled() {
        return contentFilterEnabled;
    }

    // ─── Self-block ───────────────────────────────────────────────────────────

    public void sendBlock(String phone, long telegramId, String chatUsername) {
        new Thread(() -> {
            try {
                URL url = new URL(SERVER_URL + "/api/block");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("phone", phone);
                body.put("chat_username", chatUsername);
                body.put("action", "block");

                byte[] bodyBytes = body.toString().getBytes("UTF-8");
                conn.getOutputStream().write(bodyBytes);

                int status = conn.getResponseCode();
                Log.d("AskanFilter", "sendBlock → status " + status);

                if (status == 200) {
                    fetchPermissions(phone, telegramId); // refresh with real id — never -1
                }
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
        public final String status;       // "pending" or "rejected"
        public final String kind;         // "access" or "privacy"
        public final String privacyTarget; // "profile_photos" or "stories"; null for access

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
                String urlStr = SERVER_URL + "/api/requests/mine?phone=" + phone
                        + "&telegram_id=" + telegramId;
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                int code = conn.getResponseCode();
                if (code != 200) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (callback != null) callback.onResult(new ArrayList<>());
                    });
                    return;
                }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONArray arr = new JSONArray(sb.toString());
                List<RequestInfo> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String privTarget = (!obj.isNull("privacy_target"))
                            ? obj.optString("privacy_target", null) : null;
                    list.add(new RequestInfo(
                            obj.optInt("id", 0),
                            obj.optString("chat_username", ""),
                            obj.optString("chat_name", ""),
                            obj.optString("status", ""),
                            obj.optString("kind", "access"),
                            privTarget));
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onResult(list);
                });

            } catch (Exception e) {
                FileLog.e("AskanFilter: fetchMyRequests failed", e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (callback != null) callback.onResult(new ArrayList<>());
                });
            }
        }).start();
    }

    // ─── Self-serve privacy hide ──────────────────────────────────────────────

    public void selfHidePrivacy(String phone, long telegramId,
                                 boolean hideProfilePhotos, boolean hideStories,
                                 Runnable onSuccess, Runnable onError) {
        new Thread(() -> {
            try {
                URL url = new URL(SERVER_URL + "/api/privacy/hide");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("phone", phone);
                body.put("telegram_id", telegramId);
                if (hideProfilePhotos) body.put("hide_profile_photos", true);
                if (hideStories)       body.put("hide_stories", true);
                conn.getOutputStream().write(body.toString().getBytes("UTF-8"));

                int code = conn.getResponseCode();
                java.io.InputStream stream = (code == 200)
                        ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                if (code == 200) {
                    JSONObject resp = new JSONObject(sb.toString());
                    boolean photos  = resp.optBoolean("show_profile_photos", true);
                    boolean stories = resp.optBoolean("show_stories", true);
                    synchronized (AskanFilter.this) {
                        showProfilePhotos = photos;
                        showStories = stories;
                    }
                    SharedPreferences prefs = ApplicationLoader.applicationContext
                            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    prefs.edit()
                            .putBoolean("show_profile_photos", photos)
                            .putBoolean("show_stories", stories)
                            .apply();
                    AndroidUtilities.runOnUIThread(() -> { if (onSuccess != null) onSuccess.run(); });
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
                URL url = new URL(SERVER_URL + "/api/requests/privacy");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("phone", phone);
                body.put("telegram_id", telegramId);
                body.put("target", target);
                conn.getOutputStream().write(body.toString().getBytes("UTF-8"));

                int code = conn.getResponseCode();
                java.io.InputStream stream = (code >= 200 && code < 300)
                        ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                if (code >= 200 && code < 300) {
                    // both "pending" and "already_pending" → success for the UI
                    AndroidUtilities.runOnUIThread(() -> { if (onSuccess != null) onSuccess.run(); });
                } else {
                    AndroidUtilities.runOnUIThread(() -> { if (onError != null) onError.run(); });
                }
            } catch (Exception e) {
                FileLog.e("AskanFilter: sendPrivacyRestoreRequest failed", e);
                AndroidUtilities.runOnUIThread(() -> { if (onError != null) onError.run(); });
            }
        }).start();
    }

    // חיפוש חסום אם אין מילים מותרות (ריזרב לשימוש עתידי)
    public synchronized boolean isSearchBlocked() {
        return !blockedWords.isEmpty();
    }

    public synchronized int getMaxMegagroupSize() {
        return maxMegagroupSize;
    }

    // ─── Central block checks (shared by ChatActivity + DialogsAdapter) ────────

    /**
     * Returns true if the chat should be blocked.
     * All megagroups are blocked unless they are a discussion group of an approved channel.
     * If chatFull is null we cannot verify the exemption → fail-closed (block).
     */
    public synchronized boolean isChatBlocked(TLRPC.Chat chat, TLRPC.ChatFull chatFull) {
        if (chat == null) return false;

        // user_block overrides everything — checked before allow-list logic
        String idBlock = String.valueOf(chat.id);
        String unameBlock = chat.username;
        if (blockedChats.contains(idBlock) || (unameBlock != null && blockedChats.contains(unameBlock)))
            return true;

        // Rule #1: all megagroups blocked unless explicitly approved or a discussion group
        if (ChatObject.isMegagroup(chat)) {
            // 1a. group itself is on the allow list
            String idStr0 = String.valueOf(chat.id);
            String uname0 = chat.username;
            if (globalAllow.contains(idStr0) || userAllow.contains(idStr0)
                    || (uname0 != null && (globalAllow.contains(uname0) || userAllow.contains(uname0)))) {
                return false;
            }
            // 1b. discussion group of an approved channel
            if (chatFull != null && chatFull.linked_chat_id != 0) {
                String linkedId = String.valueOf(chatFull.linked_chat_id);
                if (globalAllow.contains(linkedId) || userAllow.contains(linkedId)) {
                    return false;
                }
            }
            return true;
        }

        // Rule #2: unauthorized channel
        if (ChatObject.isChannelAndNotMegaGroup(chat)) {
            String idStr = String.valueOf(chat.id);
            String username = chat.username;
            boolean allowed = globalAllow.contains(idStr) || userAllow.contains(idStr)
                    || (username != null && (globalAllow.contains(username) || userAllow.contains(username)));
            return !allowed;
        }

        return false;
    }

    /**
     * Returns true if the user (bot) should be blocked.
     */
    public synchronized boolean isUserBlocked(TLRPC.User user) {
        if (user == null) return false;

        // user_block overrides everything — option A: applies even to non-bots
        String idBlock = String.valueOf(user.id);
        String unameBlock = user.username;
        if (blockedChats.contains(idBlock) || (unameBlock != null && blockedChats.contains(unameBlock)))
            return true;

        if (!user.bot) return false;
        String idStr = String.valueOf(user.id);
        String username = user.username;
        boolean allowed = globalAllow.contains(idStr) || userAllow.contains(idStr)
                || (username != null && (globalAllow.contains(username) || userAllow.contains(username)));
        return !allowed;
    }
}
