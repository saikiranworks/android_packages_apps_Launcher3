/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.quickstep.views;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.MODEL_EXECUTOR;

import android.app.ActivityOptions;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.launcher3.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A row of the apps used the most over the last week, shown under the overview actions. */
public class FrequentAppsView extends LinearLayout {

    private static final String TAG = "FrequentAppsView";

    private static final int MAX_APPS = 4;
    private static final long USAGE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000;
    // Overview opens many times a minute; the ranking barely moves in that time
    private static final long REFRESH_INTERVAL_MS = 30 * 1000;

    private final LauncherApps mLauncherApps;
    private final UsageStatsManager mUsageStatsManager;
    private final int mIconSize;
    private final int mIconSpacing;

    private long mLastRefresh;
    private boolean mRefreshing;

    public FrequentAppsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLauncherApps = context.getSystemService(LauncherApps.class);
        mUsageStatsManager = context.getSystemService(UsageStatsManager.class);
        mIconSize = getResources().getDimensionPixelSize(R.dimen.frequent_apps_icon_size);
        mIconSpacing = getResources().getDimensionPixelSize(R.dimen.frequent_apps_icon_spacing);
        setOrientation(HORIZONTAL);
    }

    /** Experimental features in Settings turns this on. */
    public static final String SETTING = "recents_frequent_apps";

    public static boolean isEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(), SETTING, 0) == 1;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this && visibility == VISIBLE) {
            refresh(false);
        }
    }

    /** Reloads the ranking, skipping the work if it ran moments ago unless forced. */
    public void refresh(boolean force) {
        if (!isEnabled(getContext()) || mRefreshing) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && mLastRefresh != 0 && now - mLastRefresh < REFRESH_INTERVAL_MS) return;
        mRefreshing = true;
        mLastRefresh = now;
        MODEL_EXECUTOR.execute(() -> {
            List<LauncherActivityInfo> apps = loadTopApps();
            List<Drawable> icons = new ArrayList<>();
            for (LauncherActivityInfo app : apps) {
                icons.add(app.getBadgedIcon(0));
            }
            MAIN_EXECUTOR.execute(() -> {
                mRefreshing = false;
                bind(apps, icons);
            });
        });
    }

    private List<LauncherActivityInfo> loadTopApps() {
        List<LauncherActivityInfo> result = new ArrayList<>();
        if (mUsageStatsManager == null || mLauncherApps == null) return result;

        long end = System.currentTimeMillis();
        Map<String, UsageStats> stats;
        try {
            stats = mUsageStatsManager.queryAndAggregateUsageStats(end - USAGE_WINDOW_MS, end);
        } catch (SecurityException e) {
            Log.w(TAG, "No usage access", e);
            return result;
        }
        if (stats == null || stats.isEmpty()) return result;

        List<UsageStats> ranked = new ArrayList<>(stats.values());
        ranked.sort((a, b) -> Long.compare(
                b.getTotalTimeInForeground(), a.getTotalTimeInForeground()));

        String self = getContext().getPackageName();
        UserHandle user = Process.myUserHandle();
        for (UsageStats usage : ranked) {
            if (result.size() >= MAX_APPS) break;
            if (usage.getTotalTimeInForeground() <= 0) break;
            String pkg = usage.getPackageName();
            if (pkg.equals(self)) continue;
            List<LauncherActivityInfo> activities = mLauncherApps.getActivityList(pkg, user);
            if (!activities.isEmpty()) {
                result.add(activities.get(0));
            }
        }
        return result;
    }

    private void bind(List<LauncherActivityInfo> apps, List<Drawable> icons) {
        removeAllViews();
        for (int i = 0; i < apps.size(); i++) {
            LauncherActivityInfo app = apps.get(i);
            ImageView icon = new ImageView(getContext());
            icon.setImageDrawable(icons.get(i));
            icon.setContentDescription(app.getLabel());
            icon.setBackgroundResource(R.drawable.frequent_app_ripple);
            LayoutParams lp = new LayoutParams(mIconSize, mIconSize);
            if (i > 0) lp.setMarginStart(mIconSpacing);
            ComponentName component = app.getComponentName();
            UserHandle user = app.getUser();
            icon.setOnClickListener(v -> launch(v, component, user));
            addView(icon, lp);
        }
    }

    private void launch(View v, ComponentName component, UserHandle user) {
        Rect bounds = new Rect();
        v.getGlobalVisibleRect(bounds);
        ActivityOptions options = ActivityOptions.makeScaleUpAnimation(
                v, 0, 0, v.getWidth(), v.getHeight());
        try {
            mLauncherApps.startMainActivity(component, user, bounds, options.toBundle());
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't launch " + component, e);
        }
    }
}
