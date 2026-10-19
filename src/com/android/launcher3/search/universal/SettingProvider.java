package com.android.launcher3.search.universal;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;

import com.android.launcher3.LauncherPrefs;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Reads the same raw and XML index sources consumed by SettingsIntelligence. */
public class SettingProvider implements SearchProvider {
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String INDEX = "content://com.android.settings/settings/";
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final long CACHE_MS = TimeUnit.MINUTES.toMillis(10);

    private record Entry(String title, String screenTitle, String key, Intent intent) {}

    private List<Entry> mEntries;
    private long mLoadedAt;
    private String mLocale;

    @Override
    public int getSource() {
        return UniversalSearchResult.SOURCE_SETTING;
    }

    @Override
    public boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_SETTINGS.get(context);
    }

    @Override
    public List<UniversalSearchResult> query(Context context, String query, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        if (query.trim().isEmpty()) return out;
        String lower = query.trim().toLowerCase(Locale.getDefault());
        for (Entry entry : entries(context)) {
            int score = FuzzyMatcher.score(lower, entry.title);
            if (score <= FuzzyMatcher.SUBSEQUENCE) continue;
            out.add(new UniversalSearchResult(UniversalSearchResult.SOURCE_SETTING,
                    entry.key, entry.title, entry.screenTitle, new Intent(entry.intent),
                    Process.myUserHandle(), score));
        }
        out.sort((a, b) -> Integer.compare(b.score, a.score));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private synchronized List<Entry> entries(Context context) {
        long now = SystemClock.elapsedRealtime();
        String locale = context.getResources().getConfiguration().getLocales().toLanguageTags();
        if (mEntries != null && locale.equals(mLocale) && now - mLoadedAt < CACHE_MS) {
            return mEntries;
        }
        List<Entry> entries = new ArrayList<>();
        Set<String> excluded = new HashSet<>();
        Set<String> seen = new HashSet<>();
        try {
            // Device-specific and unavailable preferences must stay out of launcher search too.
            try (Cursor c = queryIndex(context, "non_indexables_key")) {
                if (c == null) return entries;
                while (c.moveToNext()) excluded.add(string(c, "key"));
            }
            Map<String, ComponentName> destinations = exportedDestinations(context);
            loadRaw(context, "indexables_raw", destinations, excluded, seen, entries);
            loadRaw(context, "dynamic_indexables_raw", destinations, excluded, seen, entries);
            Resources resources = context.getPackageManager()
                    .getResourcesForApplication(SETTINGS_PACKAGE);
            try (Cursor c = queryIndex(context, "indexables_xml_res")) {
                if (c == null) return entries;
                int resourceColumn = c.getColumnIndex("xmlResId");
                if (resourceColumn < 0) return entries;
                while (c.moveToNext()) {
                    int resourceId = c.getInt(resourceColumn);
                    if (resourceId == 0) continue;
                    loadXml(resources, resourceId, c, destinations, excluded, seen, entries);
                }
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException
                | SecurityException | IllegalArgumentException e) {
            // Retry on the next query if the Settings provider is unavailable during startup.
            return entries;
        }
        mEntries = entries;
        mLocale = locale;
        mLoadedAt = now;
        return entries;
    }

    private static Cursor queryIndex(Context context, String path) {
        return context.getContentResolver().query(Uri.parse(INDEX + path),
                null, null, null, null);
    }

    private static void loadRaw(Context context, String path,
            Map<String, ComponentName> destinations, Set<String> excluded,
            Set<String> seen, List<Entry> entries) {
        try (Cursor c = queryIndex(context, path)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String key = string(c, "key");
                int enabled = c.getColumnIndex("enabled");
                if (excluded.contains(key) || (enabled >= 0 && c.getInt(enabled) == 0)) continue;
                String title = string(c, "title");
                String screen = string(c, "screenTitle");
                add(entries, seen, title, screen, key, buildIntent(c, key, screen, destinations));
            }
        }
    }

    private static void loadXml(Resources resources, int resourceId, Cursor row,
            Map<String, ComponentName> destinations, Set<String> excluded, Set<String> seen, List<Entry> entries) {
        try (XmlResourceParser parser = resources.getXml(resourceId)) {
            String screen = null;
            for (int type = parser.next(); type != XmlPullParser.END_DOCUMENT;
                    type = parser.next()) {
                if (type != XmlPullParser.START_TAG) continue;
                String title = attribute(resources, parser, "title");
                if (parser.getDepth() == 1) screen = title;
                String key = attribute(resources, parser, "key");
                if (TextUtils.isEmpty(key) || excluded.contains(key)) continue;
                add(entries, seen, title, screen, key, buildIntent(row, key, screen, destinations));
            }
        } catch (Resources.NotFoundException | XmlPullParserException | IOException e) {
            // One bad preference XML must not discard the rest of the Settings index.
        }
    }

    private static String attribute(Resources resources, XmlResourceParser parser, String name) {
        int id = parser.getAttributeResourceValue(ANDROID_NS, name, 0);
        return id != 0 ? resources.getString(id) : parser.getAttributeValue(ANDROID_NS, name);
    }

    private static void add(List<Entry> entries, Set<String> seen, String title,
            String screen, String key, Intent intent) {
        if (TextUtils.isEmpty(title) || intent == null) return;
        String identity = (TextUtils.isEmpty(key) ? title : key) + ":" + intent.toUri(0);
        if (seen.add(identity)) entries.add(new Entry(title, screen, identity, intent));
    }

    private static String string(Cursor c, String name) {
        int index = c.getColumnIndex(name);
        return index < 0 ? null : c.getString(index);
    }

    private static Map<String, ComponentName> exportedDestinations(Context context)
            throws PackageManager.NameNotFoundException {
        Map<String, ComponentName> destinations = new HashMap<>();
        ActivityInfo[] activities = context.getPackageManager().getPackageInfo(SETTINGS_PACKAGE,
                PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA).activities;
        if (activities == null) return destinations;
        for (ActivityInfo activity : activities) {
            if (!activity.exported || !activity.enabled || activity.metaData == null) continue;
            if (activity.permission != null && context.checkSelfPermission(activity.permission)
                    != PackageManager.PERMISSION_GRANTED) continue;
            String fragment = activity.metaData.getString("com.android.settings.FRAGMENT_CLASS");
            if (!TextUtils.isEmpty(fragment)) {
                destinations.putIfAbsent(fragment, new ComponentName(activity.packageName,
                        activity.name));
            }
        }
        return destinations;
    }

    private static Intent buildIntent(Cursor c, String key, String screen,
            Map<String, ComponentName> destinations) {
        String action = string(c, "intentAction");
        String pkg = string(c, "intentTargetPackage");
        String cls = string(c, "intentTargetClass");
        Intent intent;
        if (!TextUtils.isEmpty(action) || (!TextUtils.isEmpty(pkg) && !TextUtils.isEmpty(cls))) {
            intent = new Intent(TextUtils.isEmpty(action) ? Intent.ACTION_MAIN : action);
            if (!TextUtils.isEmpty(pkg) && !TextUtils.isEmpty(cls)) {
                intent.setComponent(new ComponentName(pkg, cls));
            } else if (!TextUtils.isEmpty(pkg)) {
                intent.setPackage(pkg);
            }
        } else {
            String fragment = string(c, "className");
            if (TextUtils.isEmpty(fragment)) return null;
            // Settings' search trampoline only accepts SettingsIntelligence. Use an exported
            // activity already mapped to this fragment; do not bypass that caller restriction.
            ComponentName destination = destinations.get(fragment);
            if (destination == null) return null;
            Bundle args = new Bundle();
            args.putString(":settings:fragment_args_key", key);
            intent = new Intent(Intent.ACTION_MAIN).setComponent(destination)
                    .putExtra(":settings:show_fragment_args", args);
        }
        return intent.putExtra(":settings:fragment_args_key", key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
