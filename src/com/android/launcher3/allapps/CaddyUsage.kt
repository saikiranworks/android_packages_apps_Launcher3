/*
 * Copyright (C) 2026 The Android Open Source Project
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

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.SystemClock
import com.android.launcher3.util.Executors

object CaddyUsage {

    private const val REFRESH_MS = 15 * 60 * 1000L
    private const val WINDOW_MS = 7 * 24 * 60 * 60 * 1000L

    @Volatile private var scores: Map<String, Long> = emptyMap()
    @Volatile private var lastQuery = 0L

    @JvmStatic
    fun scores(context: Context, onRefreshed: Runnable?): Map<String, Long> {
        val now = SystemClock.elapsedRealtime()
        if (lastQuery == 0L || now - lastQuery > REFRESH_MS) {
            lastQuery = now
            val app = context.applicationContext
            Executors.MODEL_EXECUTOR.execute {
                val fresh = query(app)
                if (fresh != scores) {
                    scores = fresh
                    onRefreshed?.let { Executors.MAIN_EXECUTOR.execute(it) }
                }
            }
        }
        return scores
    }

    private fun query(context: Context): Map<String, Long> {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val end = System.currentTimeMillis()
        return try {
            manager
                .queryAndAggregateUsageStats(end - WINDOW_MS, end)
                .mapValues { it.value.totalTimeInForeground }
                .filterValues { it > 0L }
        } catch (e: SecurityException) {
            emptyMap()
        }
    }
}
