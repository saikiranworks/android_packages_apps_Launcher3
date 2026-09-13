/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.allapps

import android.content.Context
import com.android.launcher3.flowerpot.Flowerpot
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.util.ApplicationInfoWrapper

/**
 * Categorizes app-drawer apps into "System Apps", "Google Apps", and Flowerpot categories for the
 * app-drawer "Caddy" mode. Ported from Lawnchair's `categorizeAppsWithSystemAndGoogle`.
 */
object CaddyCategorizer {

    /**
     * @return an ordered map of category display name -> apps in that category. Iteration order is
     *   System Apps, Google Apps, then the Flowerpot categories (alphabetical, with "Other" last).
     */
    @JvmStatic
    fun categorize(apps: List<AppInfo>, context: Context): Map<String, List<AppInfo>> {
        val systemApps = mutableListOf<AppInfo>()
        val googleApps = mutableListOf<AppInfo>()
        val otherApps = mutableListOf<AppInfo>()

        apps.forEach { app ->
            val packageName = app.targetPackage ?: return@forEach
            val intent = app.intent
            when {
                packageName.startsWith("com.google.") -> googleApps.add(app)
                intent != null && ApplicationInfoWrapper(context, intent).isSystem() ->
                    systemApps.add(app)
                else -> otherApps.add(app)
            }
        }

        // Use flowerpot to categorize the remaining (non-system, non-Google) apps.
        val categorizedApps = Flowerpot.Manager.getInstance(context).categorizeApps(otherApps)

        val finalCategorizedApps = LinkedHashMap<String, List<AppInfo>>()
        if (systemApps.isNotEmpty()) finalCategorizedApps["System Apps"] = systemApps
        if (googleApps.isNotEmpty()) finalCategorizedApps["Google Apps"] = googleApps
        finalCategorizedApps.putAll(categorizedApps)
        return finalCategorizedApps
    }
}
