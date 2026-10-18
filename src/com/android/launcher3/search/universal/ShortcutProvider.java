package com.android.launcher3.search.universal;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.os.Process;
import android.os.SystemClock;

import com.android.launcher3.LauncherPrefs;

import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ShortcutProvider implements SearchProvider {

    private static final int QUERY_FLAGS = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
            | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
            | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            | LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED;
    // Short enough that a new conversation shows up while the drawer is still open.
    private static final long CACHE_MS = 15_000;

    private List<ShortcutInfo> mShortcuts;
    private long mLoadedAt;

    @Override
    public int getSource() {
        return UniversalSearchResult.SOURCE_SHORTCUT;
    }

    @Override
    public boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_SHORTCUTS.get(context)
                || LauncherPrefs.SEARCH_CONVERSATIONS.get(context);
    }

    @Override
    public List<UniversalSearchResult> query(Context context, String query, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        if (query.isEmpty()) {
            return out;
        }
        boolean wantShortcuts = LauncherPrefs.SEARCH_SHORTCUTS.get(context);
        boolean wantConversations = LauncherPrefs.SEARCH_CONVERSATIONS.get(context);
        LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
        if (launcherApps == null) {
            return out;
        }
        List<ShortcutInfo> shortcuts = shortcuts(launcherApps);
        String lower = query.toLowerCase();
        Map<String, CharSequence> labels = new HashMap<>();
        Map<String, Drawable> appIcons = new HashMap<>();
        for (ShortcutInfo info : shortcuts) {
            if (out.size() >= max) {
                break;
            }
            boolean conversation = info.isCached();
            if (conversation ? !wantConversations : !wantShortcuts) {
                continue;
            }
            CharSequence label = info.getShortLabel();
            if (label == null) {
                label = info.getLongLabel();
            }
            int score = label == null ? 0 : FuzzyMatcher.score(lower, label.toString());
            if (score == 0) {
                continue;
            }
            UniversalSearchResult result = new UniversalSearchResult(
                    conversation ? UniversalSearchResult.SOURCE_CONVERSATION
                            : UniversalSearchResult.SOURCE_SHORTCUT,
                    info.getPackage() + "/" + info.getId(),
                    label,
                    appLabel(context, info.getPackage(), labels),
                    null,
                    info.getUserHandle(),
                    score);
            result.packageName = info.getPackage();
            result.shortcutId = info.getId();
            try {
                result.icon = launcherApps.getShortcutIconDrawable(
                        info, context.getResources().getDisplayMetrics().densityDpi);
            } catch (RuntimeException e) {
                result.icon = null;
            }
            if (!usable(result.icon)) {
                result.icon = appIcon(context, info.getPackage(), appIcons);
            }
            out.add(result);
        }
        return out;
    }

    private synchronized List<ShortcutInfo> shortcuts(LauncherApps launcherApps) {
        long now = SystemClock.elapsedRealtime();
        if (mShortcuts != null && now - mLoadedAt < CACHE_MS) {
            return mShortcuts;
        }
        LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
        q.setQueryFlags(QUERY_FLAGS);
        List<ShortcutInfo> shortcuts;
        try {
            shortcuts = launcherApps.getShortcuts(q, Process.myUserHandle());
        } catch (SecurityException | IllegalStateException e) {
            shortcuts = null;
        }
        mShortcuts = shortcuts == null ? List.of() : shortcuts;
        mLoadedAt = now;
        return mShortcuts;
    }

    private static boolean usable(Drawable icon) {
        return icon != null && icon.getIntrinsicWidth() > 0 && icon.getIntrinsicHeight() > 0;
    }

    private static CharSequence appLabel(Context context, String pkg,
            Map<String, CharSequence> cache) {
        if (cache.containsKey(pkg)) {
            return cache.get(pkg);
        }
        CharSequence label = null;
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            label = pm.getApplicationLabel(info);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            label = null;
        }
        cache.put(pkg, label);
        return label;
    }

    private static Drawable appIcon(Context context, String pkg, Map<String, Drawable> cache) {
        if (cache.containsKey(pkg)) {
            return cache.get(pkg);
        }
        Drawable icon = null;
        try {
            icon = context.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            icon = null;
        }
        cache.put(pkg, icon);
        return icon;
    }

    static int score(String query, String title) {
        String lower = title.toLowerCase();
        if (lower.equals(query)) {
            return 100;
        }
        return lower.startsWith(query) ? 80 : 50;
    }
}
