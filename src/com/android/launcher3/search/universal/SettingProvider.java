package com.android.launcher3.search.universal;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;

import com.android.launcher3.LauncherPrefs;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SettingProvider implements SearchProvider {

    private static final Uri INDEXABLES_RAW =
            Uri.parse("content://com.android.settings/settings/indexables_raw");
    private static final String COLUMN_TITLE = "title";
    private static final String COLUMN_SCREEN_TITLE = "screenTitle";
    private static final String COLUMN_KEY = "key";
    private static final String COLUMN_ACTION = "intentAction";
    private static final String COLUMN_TARGET_PACKAGE = "intentTargetPackage";
    private static final String COLUMN_TARGET_CLASS = "intentTargetClass";

    @Override
    public int getSource() {
        return UniversalSearchResult.SOURCE_SETTING;
    }

    @Override
    public boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_SETTINGS.get(context);
    }

    private static final long CACHE_MS = TimeUnit.MINUTES.toMillis(10);

    private record Entry(String title, String screenTitle, String key, Intent intent) {}

    private List<Entry> mEntries;
    private long mLoadedAt;

    @Override
    public List<UniversalSearchResult> query(Context context, String query, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        if (query.isEmpty()) {
            return out;
        }
        String lower = query.toLowerCase();
        for (Entry entry : entries(context)) {
            int score = FuzzyMatcher.score(lower, entry.title);
            // With hundreds of entries, letters-in-order matches are almost all noise.
            if (score <= FuzzyMatcher.SUBSEQUENCE) {
                continue;
            }
            out.add(new UniversalSearchResult(
                    UniversalSearchResult.SOURCE_SETTING,
                    entry.key,
                    entry.title,
                    entry.screenTitle,
                    new Intent(entry.intent),
                    Process.myUserHandle(),
                    score));
        }
        // The index is not ordered by relevance, so rank before cutting down.
        out.sort((a, b) -> b.score - a.score);
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private synchronized List<Entry> entries(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (mEntries != null && now - mLoadedAt < CACHE_MS) {
            return mEntries;
        }
        List<Entry> entries = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(
                INDEXABLES_RAW, null, null, null, null)) {
            if (c == null) {
                return entries;
            }
            int titleIndex = c.getColumnIndex(COLUMN_TITLE);
            int screenIndex = c.getColumnIndex(COLUMN_SCREEN_TITLE);
            int keyIndex = c.getColumnIndex(COLUMN_KEY);
            int actionIndex = c.getColumnIndex(COLUMN_ACTION);
            int pkgIndex = c.getColumnIndex(COLUMN_TARGET_PACKAGE);
            int classIndex = c.getColumnIndex(COLUMN_TARGET_CLASS);
            if (titleIndex < 0) {
                return entries;
            }
            while (c.moveToNext()) {
                String title = c.getString(titleIndex);
                if (TextUtils.isEmpty(title)) {
                    continue;
                }
                Intent intent = buildIntent(c, actionIndex, pkgIndex, classIndex);
                if (intent == null) {
                    continue;
                }
                entries.add(new Entry(title,
                        screenIndex < 0 ? null : c.getString(screenIndex),
                        keyIndex < 0 ? title : c.getString(keyIndex),
                        intent));
            }
        } catch (SecurityException | IllegalArgumentException e) {
            return entries;
        }
        mEntries = entries;
        mLoadedAt = now;
        return entries;
    }

    private static Intent buildIntent(Cursor c, int actionIndex, int pkgIndex, int classIndex) {
        String action = actionIndex < 0 ? null : c.getString(actionIndex);
        String pkg = pkgIndex < 0 ? null : c.getString(pkgIndex);
        String cls = classIndex < 0 ? null : c.getString(classIndex);
        Intent intent = null;
        if (!TextUtils.isEmpty(action)) {
            intent = new Intent(action);
        } else if (!TextUtils.isEmpty(pkg) && !TextUtils.isEmpty(cls)) {
            intent = new Intent().setComponent(new ComponentName(pkg, cls));
        }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return intent;
    }
}
