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
package com.android.launcher3.flowerpot

import android.content.Context
import android.content.Intent
import com.android.launcher3.flowerpot.rules.CodeRules
import com.android.launcher3.flowerpot.rules.Rules
import com.android.launcher3.model.data.AppInfo

/** Resolves which apps match a single [Flowerpot]'s rules. Ported from Lawnchair. */
class FlowerpotApps(private val context: Context, private val pot: Flowerpot) {
    private val intentMatches = mutableSetOf<String>()
    private val codeRules = mutableListOf<CodeRules>()
    val categorizedApps = mutableMapOf<String, MutableList<AppInfo>>()

    init {
        populateIntentMatches()
        populateCodeRules()
    }

    fun updateAppList(appList: List<AppInfo?>?) {
        categorizedApps.clear()

        val categoryTitle = pot.displayName
        val matched = categorizedApps.getOrPut(categoryTitle) { mutableListOf() }
        appList?.forEach { app ->
            if (app == null) return@forEach
            val packageName = app.targetPackage ?: return@forEach
            if (matchesRules(packageName)) {
                matched.add(app)
            }
        }
        if (matched.isEmpty()) {
            categorizedApps.remove(categoryTitle)
        }
    }

    private fun matchesRules(packageName: String): Boolean =
        packageName in intentMatches ||
            pot.rules.contains(Rules.Package(packageName)) ||
            (
                codeRules.isNotEmpty() &&
                    runCatching {
                        codeRules.any {
                            it.matches(context.packageManager.getApplicationInfo(packageName, 0))
                        }
                    }.getOrDefault(false)
                )

    private fun populateIntentMatches() {
        intentMatches.clear()

        pot.rules.forEach { rule ->
            val intent = when (rule) {
                is Rules.IntentCategory -> Intent(Intent.ACTION_MAIN).addCategory(rule.category)
                is Rules.IntentAction -> Intent(rule.action)
                else -> return@forEach
            }

            context.packageManager.queryIntentActivities(intent, 0)
                .mapNotNullTo(intentMatches) { it.activityInfo?.packageName }
        }
    }

    private fun populateCodeRules() {
        codeRules.clear()
        pot.rules.filterIsInstance<Rules.CodeRule>()
            .mapNotNull { runCatching { CodeRules.get(it.rule, *it.args) }.getOrNull() }
            .forEach { codeRules.add(it) }
    }
}
