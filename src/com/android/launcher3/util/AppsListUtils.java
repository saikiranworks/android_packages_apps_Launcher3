/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.util;

import android.content.Context;

import com.android.launcher3.R;
import com.android.launcher3.flowerpot.Flowerpot;
import com.android.launcher3.model.data.AppInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Utility functions for working with app lists.
 */
public class AppsListUtils {

    /**
     * Recategorization is computed on each call; kept so All Apps can signal that the installed
     * set changed (Flowerpot also refreshes intent matches at that point).
     */
    public static void clearPackageInfoCache() {
        // No-op: Flowerpot recategorizes from the current app list on every call.
    }

    /**
     * Categorizes apps using Lawnchair's Flowerpot rules (Play-store category lists plus
     * intent/code rules). Use {@link #categorizeAppsHybrid} for the drawer layout that creates
     * folders only for categories with multiple apps.
     */
    public static Map<String, List<AppInfo>> categorizeApps(
            Context context, List<AppInfo> appList) {
        Map<String, List<AppInfo>> categories = new TreeMap<>();
        if (appList == null || appList.isEmpty()) {
            return categories;
        }
        categories.putAll(Flowerpot.Manager.getInstance(context).categorizeApps(appList));
        return categories;
    }

    /**
     * Categorizes apps with Flowerpot first. Leftover apps that did not match a category are then
     * split into System Apps, Google Apps, and Others.
     */
    public static Map<String, List<AppInfo>> categorizeAppsWithSystemAndGoogle(
            Context context, List<AppInfo> appList) {
        Map<String, List<AppInfo>> categorized = categorizeApps(context, appList);
        if (categorized.isEmpty()) {
            return categorized;
        }

        String othersLabel = context.getString(R.string.others_category_label);
        List<AppInfo> leftovers = categorized.remove(othersLabel);
        if (leftovers == null || leftovers.isEmpty()) {
            return categorized;
        }

        List<AppInfo> systemApps = new ArrayList<>();
        List<AppInfo> googleApps = new ArrayList<>();
        List<AppInfo> otherApps = new ArrayList<>();

        for (AppInfo app : leftovers) {
            String packageName = app.getTargetPackage();
            if (packageName != null && packageName.startsWith("com.google.")) {
                googleApps.add(app);
            } else if (isSystemApp(context, app)) {
                systemApps.add(app);
            } else {
                otherApps.add(app);
            }
        }

        if (!systemApps.isEmpty()) {
            categorized.put(context.getString(R.string.system_apps_category_label), systemApps);
        }
        if (!googleApps.isEmpty()) {
            categorized.put(context.getString(R.string.google_apps_category_label), googleApps);
        }
        if (!otherApps.isEmpty()) {
            categorized.put(othersLabel, otherApps);
        }
        return categorized;
    }

    /**
     * Result of hybrid categorization that separates apps into folders and individual apps.
     */
    public static class HybridCategorizationResult {
        /** Categories with 2+ apps that should become folders */
        public final Map<String, List<AppInfo>> folders;

        /** Individual apps (from categories with 1 app or uncategorized) */
        public final List<AppInfo> individualApps;

        public HybridCategorizationResult(Map<String, List<AppInfo>> folders,
                List<AppInfo> individualApps) {
            this.folders = folders;
            this.individualApps = individualApps;
        }
    }

    /**
     * Categorizes apps into folders (for categories with 2+ apps) and individual apps.
     * Categories with multiple apps become folders at the top, while single apps and
     * uncategorized apps are listed individually below.
     */
    public static HybridCategorizationResult categorizeAppsHybrid(
            Context context, List<AppInfo> appList) {
        Map<String, List<AppInfo>> folderMap = new TreeMap<>();
        List<AppInfo> individualApps = new ArrayList<>();

        if (appList == null || appList.isEmpty()) {
            return new HybridCategorizationResult(folderMap, individualApps);
        }

        Map<String, List<AppInfo>> categorized =
                categorizeAppsWithSystemAndGoogle(context, appList);
        String othersLabel = context.getString(R.string.others_category_label);

        for (Map.Entry<String, List<AppInfo>> entry : categorized.entrySet()) {
            List<AppInfo> apps = entry.getValue();
            String categoryName = entry.getKey();

            // Never fold leftovers into an "Others" folder.
            if (othersLabel.equals(categoryName)) {
                individualApps.addAll(apps);
            } else if (apps.size() > 1) {
                folderMap.put(categoryName, apps);
            } else if (apps.size() == 1) {
                individualApps.add(apps.get(0));
            }
        }

        return new HybridCategorizationResult(folderMap, individualApps);
    }

    private static boolean isSystemApp(Context context, AppInfo app) {
        if (app == null || app.getTargetPackage() == null) {
            return false;
        }
        try {
            return new ApplicationInfoWrapper(context, app.getTargetPackage(), app.user)
                    .isSystem();
        } catch (Exception e) {
            return false;
        }
    }
}
