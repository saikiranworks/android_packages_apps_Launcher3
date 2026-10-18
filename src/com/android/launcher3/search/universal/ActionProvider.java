package com.android.launcher3.search.universal;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Process;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ActionProvider implements SearchProvider {

    private static final int MAX_APPS = 3;
    private static final int MAX_DICTIONARIES = 2;
    private static final String GOOGLE_APP = "com.google.android.googlequicksearchbox";
    private static final Pattern DEFINE = Pattern.compile(
            "^(?:define|definition of|meaning of|what does (\\S+) mean)(?: (.+))?$");

    static Intent webSearch(Context context, String query) {
        String engine = LauncherPrefs.SEARCH_ENGINE.get(context);
        String template = engineUrl(engine);
        Intent intent;
        if (template == null) {
            intent = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query);
        } else if ("google".equals(engine) && isInstalled(context, GOOGLE_APP)) {
            intent = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
                    .setPackage(GOOGLE_APP);
        } else {
            intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(String.format(template, Uri.encode(query))));
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static void applyEngineIcon(Context context, UniversalSearchResult result) {
        PackageManager pm = context.getPackageManager();
        String engine = LauncherPrefs.SEARCH_ENGINE.get(context);
        if ("google".equals(engine) && !GOOGLE_APP.equals(result.intent.getPackage())) {
            result.iconRes = R.drawable.ic_search_engine_google;
            return;
        }
        try {
            ResolveInfo handler = pm.resolveActivity(result.intent,
                    PackageManager.MATCH_DEFAULT_ONLY);
            // "android" is the chooser, which has no icon worth showing.
            if (handler != null && handler.activityInfo != null
                    && !"android".equals(handler.activityInfo.packageName)) {
                result.icon = handler.loadIcon(pm);
            }
        } catch (RuntimeException e) {
            result.icon = null;
        }
    }

    private static boolean isInstalled(Context context, String pkg) {
        try {
            return context.getPackageManager().getApplicationInfo(pkg, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String engineUrl(String engine) {
        switch (engine) {
            case "google":
                return "https://www.google.com/search?q=%s";
            case "duckduckgo":
                return "https://duckduckgo.com/?q=%s";
            case "bing":
                return "https://www.bing.com/search?q=%s";
            case "brave":
                return "https://search.brave.com/search?q=%s";
            default:
                return null;
        }
    }

    static UniversalSearchResult storeSearch(Context context, String query) {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://search?c=apps&q=" + Uri.encode(query)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PackageManager pm = context.getPackageManager();
        ResolveInfo store;
        try {
            store = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        } catch (RuntimeException e) {
            return null;
        }
        if (store == null || store.activityInfo == null
                || "android".equals(store.activityInfo.packageName)) {
            return null;
        }
        UniversalSearchResult result = new UniversalSearchResult(
                UniversalSearchResult.SOURCE_WEB, "store",
                context.getString(R.string.search_action_store, query, store.loadLabel(pm)),
                null, intent, Process.myUserHandle(), 8);
        result.packageName = store.activityInfo.packageName;
        try {
            result.icon = pm.getApplicationIcon(store.activityInfo.packageName);
        } catch (PackageManager.NameNotFoundException e) {
            result.icon = null;
        }
        return result;
    }

    @Override
    public int getSource() {
        return UniversalSearchResult.SOURCE_WEB;
    }

    @Override
    public boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_ACTIONS.get(context);
    }

    @Override
    public List<UniversalSearchResult> query(Context context, String query, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        if (query.trim().isEmpty()) {
            return out;
        }
        UniversalSearchResult web = new UniversalSearchResult(
                UniversalSearchResult.SOURCE_WEB, "web",
                context.getString(R.string.search_action_web, query), null,
                webSearch(context, query), Process.myUserHandle(), 10);
        applyEngineIcon(context, web);
        out.add(web);
        addDefinitions(context, query, out);

        PackageManager pm = context.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_SEARCH);
        List<ResolveInfo> handlers;
        try {
            handlers = pm.queryIntentActivities(probe, 0);
        } catch (RuntimeException e) {
            return out;
        }
        int added = 0;
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : handlers) {
            if (added >= MAX_APPS || out.size() >= max) {
                break;
            }
            if (info.activityInfo == null || !info.activityInfo.exported) {
                continue;
            }
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(context.getPackageName()) || !seen.add(pkg)) {
                continue;
            }
            if (pm.getLaunchIntentForPackage(pkg) == null) {
                continue;
            }
            CharSequence label = info.loadLabel(pm);
            if (label == null || pkg.contentEquals(label)) {
                continue;
            }
            Intent intent = new Intent(Intent.ACTION_SEARCH)
                    .setClassName(pkg, info.activityInfo.name)
                    .putExtra(SearchManager.QUERY, query)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            UniversalSearchResult result = new UniversalSearchResult(
                    UniversalSearchResult.SOURCE_WEB, pkg,
                    context.getString(R.string.search_action_in_app, label), null,
                    intent, Process.myUserHandle(), 5);
            result.packageName = pkg;
            try {
                result.icon = pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                result.icon = null;
            }
            out.add(result);
            added++;
        }
        return out;
    }

    private static void addDefinitions(Context context, String query,
            List<UniversalSearchResult> out) {
        Matcher m = DEFINE.matcher(query.trim().toLowerCase(Locale.getDefault()));
        if (!m.matches()) {
            return;
        }
        String word = m.group(1) != null ? m.group(1) : m.group(2);
        if (word == null || word.isBlank()) {
            return;
        }
        word = word.trim();
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> handlers;
        try {
            handlers = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0);
        } catch (RuntimeException e) {
            handlers = List.of();
        }
        int added = 0;
        for (ResolveInfo info : handlers) {
            if (added >= MAX_DICTIONARIES) {
                break;
            }
            if (info.activityInfo == null || !info.activityInfo.exported
                    || info.activityInfo.packageName.equals(context.getPackageName())) {
                continue;
            }
            Intent intent = new Intent(Intent.ACTION_PROCESS_TEXT)
                    .setType("text/plain")
                    .setClassName(info.activityInfo.packageName, info.activityInfo.name)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, word)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            UniversalSearchResult result = new UniversalSearchResult(
                    UniversalSearchResult.SOURCE_WEB, "define/" + info.activityInfo.name,
                    context.getString(R.string.search_action_define_in, word,
                            info.loadLabel(pm)),
                    null, intent, Process.myUserHandle(), 12);
            result.icon = info.loadIcon(pm);
            out.add(result);
            added++;
        }
        UniversalSearchResult define = new UniversalSearchResult(
                UniversalSearchResult.SOURCE_WEB, "define",
                context.getString(R.string.search_action_define, word), null,
                webSearch(context, "define " + word), Process.myUserHandle(), 11);
        applyEngineIcon(context, define);
        out.add(define);
    }
}
