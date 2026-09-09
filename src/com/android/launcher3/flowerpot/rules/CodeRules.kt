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
package com.android.launcher3.flowerpot.rules

import android.content.pm.ApplicationInfo

/**
 * Code-defined Flowerpot rules (the `&`-prefixed rules), ported from Lawnchair. These match against
 * an app's [ApplicationInfo] rather than its package name.
 */
sealed class CodeRules(vararg val args: String) {
    abstract fun matches(info: ApplicationInfo): Boolean

    class IsGame(vararg args: String) : CodeRules(*args) {
        override fun matches(info: ApplicationInfo) =
            (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0
    }

    class Category(vararg args: String) : CodeRules(*args) {
        private val category: Int

        init {
            require(args.size == 1) { "Expected exactly one argument" }
            category = when (args[0]) {
                "undefined" -> ApplicationInfo.CATEGORY_UNDEFINED
                "game" -> ApplicationInfo.CATEGORY_GAME
                "audio" -> ApplicationInfo.CATEGORY_AUDIO
                "video" -> ApplicationInfo.CATEGORY_VIDEO
                "image" -> ApplicationInfo.CATEGORY_IMAGE
                "social" -> ApplicationInfo.CATEGORY_SOCIAL
                "news" -> ApplicationInfo.CATEGORY_NEWS
                "maps" -> ApplicationInfo.CATEGORY_MAPS
                "productivity" -> ApplicationInfo.CATEGORY_PRODUCTIVITY
                else -> throw IllegalArgumentException(
                    "Expected a known category, got '${args[0]}' instead",
                )
            }
        }

        override fun matches(info: ApplicationInfo) = info.category == category
    }

    companion object {
        private val cache = mutableMapOf<Pair<String, List<String>>, CodeRules>()

        fun get(name: String, vararg args: String): CodeRules =
            cache.getOrPut(Pair(name, args.toList())) {
                when (name) {
                    "isGame" -> IsGame(*args)
                    "category" -> Category(*args)
                    else -> throw IllegalArgumentException("Unknown Code Rule '$name'")
                }
            }
    }
}
