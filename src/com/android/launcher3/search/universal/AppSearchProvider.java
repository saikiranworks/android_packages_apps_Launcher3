package com.android.launcher3.search.universal;

import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GlobalSearchSession;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResults;
import android.app.appsearch.SearchSpec;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Process;
import android.text.TextUtils;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.util.Executors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Readable because the home role holds READ_HOME_APP_SEARCH_DATA. */
public class AppSearchProvider implements SearchProvider {

    private static final long TIMEOUT_MS = 800;
    // The platform indexes installed apps and contacts itself; those already have sources here.
    private static final String PLATFORM_PACKAGE = "android";
    private static final String[] TITLE_PROPERTIES =
            {"name", "title", "subject", "displayName", "label"};
    private static final String[] SUBTITLE_PROPERTIES =
            {"description", "snippet", "text", "body", "summary"};
    private static final String[] LINK_PROPERTIES = {"url", "uri", "deepLink", "webUrl"};

    private GlobalSearchSession mSession;

    @Override
    public int getSource() {
        return UniversalSearchResult.SOURCE_APP_CONTENT;
    }

    @Override
    public boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_APP_CONTENT.get(context);
    }

    @Override
    public List<UniversalSearchResult> query(Context context, String query, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        if (query.trim().length() < 2) {
            return out;
        }
        GlobalSearchSession session = session(context);
        if (session == null) {
            return out;
        }
        SearchSpec spec = new SearchSpec.Builder()
                .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                .setResultCountPerPage(max * 2)
                .build();
        List<SearchResult> page = await(latch -> {
            SearchResults results = session.search(query.trim(), spec);
            AtomicReference<List<SearchResult>> value = new AtomicReference<>();
            results.getNextPage(Executors.UI_HELPER_EXECUTOR, result -> {
                if (result.isSuccess()) {
                    value.set(result.getResultValue());
                }
                results.close();
                latch.accept(value.get());
            });
        });
        if (page == null) {
            return out;
        }
        PackageManager pm = context.getPackageManager();
        Map<String, CharSequence> labels = new HashMap<>();
        String lower = query.trim().toLowerCase();
        for (SearchResult hit : page) {
            if (out.size() >= max) {
                break;
            }
            String pkg = hit.getPackageName();
            if (PLATFORM_PACKAGE.equals(pkg)) {
                continue;
            }
            GenericDocument doc = hit.getGenericDocument();
            String title = firstString(doc, TITLE_PROPERTIES);
            Intent intent = intentFor(pm, pkg, firstString(doc, LINK_PROPERTIES));
            if (TextUtils.isEmpty(title) || intent == null) {
                continue;
            }
            CharSequence app = labels.computeIfAbsent(pkg, p -> {
                try {
                    return pm.getApplicationLabel(pm.getApplicationInfo(p, 0));
                } catch (PackageManager.NameNotFoundException e) {
                    return p;
                }
            });
            String detail = firstString(doc, SUBTITLE_PROPERTIES);
            UniversalSearchResult result = new UniversalSearchResult(
                    UniversalSearchResult.SOURCE_APP_CONTENT,
                    pkg + "/" + doc.getNamespace() + "/" + doc.getId(),
                    title,
                    TextUtils.isEmpty(detail) ? app : app + " · " + detail,
                    intent, Process.myUserHandle(),
                    Math.max(FuzzyMatcher.score(lower, title), FuzzyMatcher.WORD / 2));
            result.packageName = pkg;
            try {
                result.icon = pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException e) {
                result.icon = null;
            }
            out.add(result);
        }
        return out;
    }

    private static String firstString(GenericDocument doc, String[] properties) {
        for (String property : properties) {
            try {
                String value = doc.getPropertyString(property);
                if (!TextUtils.isEmpty(value)) {
                    return value;
                }
            } catch (RuntimeException e) {
                // Property exists with a non-string type in this schema.
            }
        }
        return null;
    }

    private static Intent intentFor(PackageManager pm, String pkg, String link) {
        if (!TextUtils.isEmpty(link)) {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pm.resolveActivity(view, 0) != null) {
                return view;
            }
        }
        Intent launch = pm.getLaunchIntentForPackage(pkg);
        return launch == null ? null : launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private synchronized GlobalSearchSession session(Context context) {
        if (mSession != null) {
            return mSession;
        }
        AppSearchManager manager = context.getSystemService(AppSearchManager.class);
        if (manager == null) {
            return null;
        }
        mSession = await(latch -> manager.createGlobalSearchSession(
                Executors.UI_HELPER_EXECUTOR,
                (AppSearchResult<GlobalSearchSession> result) ->
                        latch.accept(result.isSuccess() ? result.getResultValue() : null)));
        return mSession;
    }

    private interface Async<T> {
        void start(java.util.function.Consumer<T> done);
    }

    private static <T> T await(Async<T> call) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        try {
            call.start(result -> {
                value.set(result);
                latch.countDown();
            });
            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | RuntimeException e) {
            return null;
        }
        return value.get();
    }
}
