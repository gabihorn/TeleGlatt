package org.telegram.messenger.askan;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Filter levels — the option catalogue, mirrored from the server
 * (askan-telegram-server/src/filterLevels.js). The server decides the effective
 * value of every option; the app only needs the labels and the strictness order
 * to offer "make stricter" (immediate) vs "ask to loosen" (request).
 */
public final class AskanFilterLevels {
    private AskanFilterLevels() {}

    public static final String FORWARDED_MEDIA = "forwarded_media";
    public static final String PRIVATE_MEDIA   = "private_media";
    public static final String GROUP_MEDIA     = "group_media";
    public static final String VIDEOS          = "videos";
    public static final String MEDIA_BLUR      = "media_blur";
    public static final String STORIES         = "stories";
    public static final String PROFILE_PHOTOS  = "profile_photos";
    public static final String BASIC_GROUPS    = "basic_groups";
    public static final String MINI_APPS       = "mini_apps";
    public static final String FOREIGN_IFRAMES = "foreign_iframes";

    /** option → (value → restriction flags). B is at least as strict as A when B ⊇ A. */
    public static final Map<String, Map<String, List<String>>> OPTIONS = new LinkedHashMap<>();
    /** Today's behaviour; used until the server sends a filter object. */
    public static final Map<String, String> BASIC = new LinkedHashMap<>();
    public static final Map<String, String> OPTION_LABELS = new LinkedHashMap<>();
    public static final Map<String, String> VALUE_LABELS = new LinkedHashMap<>();

    private static void opt(String key, String label, String basic, Object... pairs) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], Arrays.asList((String[]) pairs[i + 1]));
        }
        OPTIONS.put(key, values);
        OPTION_LABELS.put(key, label);
        BASIC.put(key, basic);
    }

    private static String[] f(String... flags) { return flags; }

    static {
        opt(FORWARDED_MEDIA, "מדיה שהועברה מערוץ לא מאושר", "show", "show", f(), "hide", f("hide"));
        opt(PRIVATE_MEDIA, "מדיה בצ׳אטים פרטיים", "all",
                "all", f(), "contacts", f("contacts"), "photos", f("photos"),
                "contacts_photos", f("contacts", "photos"), "none", f("contacts", "photos", "none"));
        opt(GROUP_MEDIA, "מדיה בקבוצות", "all", "all", f(), "photos", f("photos"), "none", f("photos", "none"));
        opt(VIDEOS, "סרטונים והודעות וידאו", "show", "show", f(), "hide", f("hide"));
        opt(MEDIA_BLUR, "טשטוש תמונות וסרטונים", "off", "off", f(), "blur", f("blur"));
        opt(STORIES, "סטוריז", "show", "show", f(), "hide", f("hide"));
        opt(PROFILE_PHOTOS, "תמונות פרופיל", "show", "show", f(), "hide", f("hide"));
        opt(BASIC_GROUPS, "קבוצות קטנות", "open", "open", f(), "request", f("request"));
        opt(MINI_APPS, "מיני-אפליקציות של בוטים", "allow", "allow", f(), "block", f("block"));
        opt(FOREIGN_IFRAMES, "אתרים זרים בתוך מיני-אפליקציה", "allow", "allow", f(), "block", f("block"));

        VALUE_LABELS.put("show", "מוצג");
        VALUE_LABELS.put("hide", "מוסתר");
        VALUE_LABELS.put("all", "הכול");
        VALUE_LABELS.put("contacts", "רק מאנשי קשר");
        VALUE_LABELS.put("photos", "תמונות בלבד");
        VALUE_LABELS.put("contacts_photos", "רק מאנשי קשר, תמונות בלבד");
        VALUE_LABELS.put("none", "ללא מדיה");
        VALUE_LABELS.put("open", "נפתחות לבד");
        VALUE_LABELS.put("request", "דורשות בקשה");
        VALUE_LABELS.put("allow", "מותר");
        VALUE_LABELS.put("block", "חסום");
        VALUE_LABELS.put("off", "כבוי");
        VALUE_LABELS.put("blur", "מטושטש עד לחיצה");
    }

    public static boolean isValid(String option, String value) {
        Map<String, List<String>> v = OPTIONS.get(option);
        return v != null && value != null && v.containsKey(value);
    }

    public static boolean isAtLeastAsStrict(String option, String value, String than) {
        if (!isValid(option, value) || !isValid(option, than)) return false;
        return OPTIONS.get(option).get(value).containsAll(OPTIONS.get(option).get(than));
    }

    public static boolean isStricter(String option, String value, String than) {
        return value != null && !value.equals(than) && isAtLeastAsStrict(option, value, than);
    }

    /** True when this value restricts anything at all. */
    public static boolean restricts(String option, String value) {
        return isValid(option, value) && !OPTIONS.get(option).get(value).isEmpty();
    }

    public static String optionLabel(String option) {
        String l = OPTION_LABELS.get(option);
        return l != null ? l : option;
    }

    public static String valueLabel(String value) {
        String l = VALUE_LABELS.get(value);
        return l != null ? l : value;
    }
}
