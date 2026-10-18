package com.android.launcher3.search.universal;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserHandle;
import android.text.TextUtils;

import com.android.launcher3.LauncherPrefs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class SearchHistory {

    private static final String PREFS = "universal_search_history";
    private static final String KEY_QUERIES = "queries";
    private static final String KEY_LAUNCHES = "launches";
    private static final int MAX_QUERIES = 6;
    private static final int MAX_LAUNCHES = 200;
    private static final double HALF_LIFE_DAYS = 14;
    private static final int MAX_BOOST = 60;

    private static final Map<String, long[]> sLaunches = new HashMap<>();
    private static final List<String> sQueries = new ArrayList<>();
    private static boolean sLoaded;
    private static volatile String sCurrentQuery = "";

    private SearchHistory() {}

    public static boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_HISTORY.get(context);
    }

    static void setCurrentQuery(String query) {
        sCurrentQuery = query == null ? "" : query.trim();
    }

    public static String currentQuery() {
        return sCurrentQuery;
    }

    public static String appKey(ComponentName component, UserHandle user) {
        return "app:" + (component == null ? "" : component.flattenToShortString())
                + "#" + (user == null ? 0 : user.hashCode());
    }

    static String resultKey(UniversalSearchResult result) {
        return result.source + ":" + result.id;
    }

    public static void recordLaunch(Context context, String key) {
        if (!isEnabled(context)) {
            return;
        }
        synchronized (sLaunches) {
            load(context);
            long[] entry = sLaunches.get(key);
            long now = System.currentTimeMillis();
            sLaunches.put(key, new long[]{entry == null ? 1 : entry[0] + 1, now});
            if (sLaunches.size() > MAX_LAUNCHES) {
                String oldest = null;
                long oldestTime = Long.MAX_VALUE;
                for (Map.Entry<String, long[]> e : sLaunches.entrySet()) {
                    if (e.getValue()[1] < oldestTime) {
                        oldestTime = e.getValue()[1];
                        oldest = e.getKey();
                    }
                }
                sLaunches.remove(oldest);
            }
            String query = sCurrentQuery;
            if (!TextUtils.isEmpty(query)) {
                sQueries.remove(query);
                sQueries.add(0, query);
                while (sQueries.size() > MAX_QUERIES) {
                    sQueries.remove(sQueries.size() - 1);
                }
            }
            save(context);
        }
    }

    static int boost(Context context, String key) {
        if (!isEnabled(context)) {
            return 0;
        }
        synchronized (sLaunches) {
            load(context);
            long[] entry = sLaunches.get(key);
            if (entry == null) {
                return 0;
            }
            double days = (System.currentTimeMillis() - entry[1])
                    / (double) TimeUnit.DAYS.toMillis(1);
            double weight = entry[0] * Math.pow(0.5, Math.max(0, days) / HALF_LIFE_DAYS);
            return (int) Math.min(MAX_BOOST, Math.round(weight * 12));
        }
    }

    static List<String> recentQueries(Context context) {
        if (!isEnabled(context)) {
            return List.of();
        }
        synchronized (sLaunches) {
            load(context);
            return new ArrayList<>(sQueries);
        }
    }

    static void removeQuery(Context context, String query) {
        synchronized (sLaunches) {
            load(context);
            if (sQueries.remove(query)) {
                save(context);
            }
        }
    }

    static void clear(Context context) {
        synchronized (sLaunches) {
            sLaunches.clear();
            sQueries.clear();
            sLoaded = true;
            prefs(context).edit().clear().apply();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void load(Context context) {
        if (sLoaded) {
            return;
        }
        sLoaded = true;
        SharedPreferences prefs = prefs(context);
        try {
            JSONArray queries = new JSONArray(prefs.getString(KEY_QUERIES, "[]"));
            for (int i = 0; i < queries.length(); i++) {
                sQueries.add(queries.getString(i));
            }
            JSONObject launches = new JSONObject(prefs.getString(KEY_LAUNCHES, "{}"));
            for (Iterator<String> it = launches.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONArray value = launches.getJSONArray(key);
                sLaunches.put(key, new long[]{value.getLong(0), value.getLong(1)});
            }
        } catch (JSONException e) {
            sQueries.clear();
            sLaunches.clear();
        }
    }

    private static void save(Context context) {
        JSONObject launches = new JSONObject();
        try {
            for (Map.Entry<String, long[]> e : sLaunches.entrySet()) {
                launches.put(e.getKey(),
                        new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
            }
        } catch (JSONException e) {
            return;
        }
        prefs(context).edit()
                .putString(KEY_QUERIES, new JSONArray(sQueries).toString())
                .putString(KEY_LAUNCHES, launches.toString())
                .apply();
    }
}
