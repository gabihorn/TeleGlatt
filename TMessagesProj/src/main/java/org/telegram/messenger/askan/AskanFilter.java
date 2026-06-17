package org.telegram.messenger.askan;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

public class AskanFilter {

    private static final String SERVER_URL = "http://3.76.36.243:3000";
    private static final String PREFS_NAME = "askan_filter";

    private static volatile AskanFilter instance;

    private boolean showProfilePhotos = true;
    private boolean showStories = true;
    private int maxMegagroupSize = 250;
    private final Set<String> globalAllow = new HashSet<>();
    private final Set<String> userAllow = new HashSet<>();
    private final Set<String> blockedWords = new HashSet<>();

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

    private void parseAndSave(String json) {
        try {
            JSONObject root = new JSONObject(json);

            boolean photosFlag = root.optBoolean("show_profile_photos", true);
            boolean storiesFlag = root.optBoolean("show_stories", true);
            int megagroupSize = root.optInt("max_megagroup_size", 250);

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

            // Apply in-memory
            synchronized (this) {
                showProfilePhotos = photosFlag;
                showStories = storiesFlag;
                maxMegagroupSize = megagroupSize;
                globalAllow.clear();
                globalAllow.addAll(newGlobal);
                userAllow.clear();
                userAllow.addAll(newUser);
                blockedWords.clear();
                blockedWords.addAll(newBlocked);
            }

            // Persist to SharedPreferences
            SharedPreferences prefs = ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean("show_profile_photos", photosFlag);
            editor.putBoolean("show_stories", storiesFlag);
            editor.putInt("max_megagroup_size", megagroupSize);
            editor.putStringSet("global_allow", newGlobal);
            editor.putStringSet("user_allow", newUser);
            editor.putStringSet("blocked_words", newBlocked);
            editor.apply();

            FileLog.d("AskanFilter: permissions loaded — global=" + newGlobal.size()
                    + " user=" + newUser.size() + " words=" + newBlocked.size());

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

                Set<String> cachedGlobal = prefs.getStringSet("global_allow", null);
                globalAllow.clear();
                if (cachedGlobal != null) globalAllow.addAll(cachedGlobal);

                Set<String> cachedUser = prefs.getStringSet("user_allow", null);
                userAllow.clear();
                if (cachedUser != null) userAllow.addAll(cachedUser);

                Set<String> cachedWords = prefs.getStringSet("blocked_words", null);
                blockedWords.clear();
                if (cachedWords != null) blockedWords.addAll(cachedWords);
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
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase();
        for (String word : blockedWords) {
            if (lower.contains(word)) return true;
        }
        return false;
    }

    // חיפוש חסום אם אין מילים מותרות (ריזרב לשימוש עתידי)
    public synchronized boolean isSearchBlocked() {
        return !blockedWords.isEmpty();
    }

    public synchronized int getMaxMegagroupSize() {
        return maxMegagroupSize;
    }
}
