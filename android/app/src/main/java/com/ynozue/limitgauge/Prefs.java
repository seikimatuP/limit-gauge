package com.ynozue.limitgauge;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings and the last fetched data, kept in one private SharedPreferences file. */
final class Prefs {
    static final String FILE = "limit_gauge";

    static final String URL = "relay_url";
    static final String TOKEN = "relay_token";
    static final String SHOW_USED = "show_used";
    static final String NOTIFY = "notify";
    static final String LIVE_UPDATE = "live_update";

    /** Raw JSON of the last successful response. */
    static final String DATA = "data_json";
    /** Epoch millis of the last successful fetch. */
    static final String FETCHED_AT = "fetched_at";
    /** Message of the last failed fetch; removed again on success. */
    static final String ERROR = "error";
    static final String ERROR_AT = "error_at";

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String url(Context c) {
        return get(c).getString(URL, "");
    }

    static String token(Context c) {
        return get(c).getString(TOKEN, "");
    }

    static boolean configured(Context c) {
        return !url(c).isEmpty() && !token(c).isEmpty();
    }

    static boolean showUsed(Context c) {
        return get(c).getBoolean(SHOW_USED, false);
    }

    static boolean notifyEnabled(Context c) {
        return get(c).getBoolean(NOTIFY, false);
    }

    static boolean liveUpdate(Context c) {
        return get(c).getBoolean(LIVE_UPDATE, false);
    }

    /** The last fetched data, or null if there is none (or it no longer parses). */
    static UsageData data(Context c) {
        String json = get(c).getString(DATA, null);
        if (json == null) return null;
        try {
            return UsageData.parse(json);
        } catch (Exception e) {
            return null;
        }
    }
}
