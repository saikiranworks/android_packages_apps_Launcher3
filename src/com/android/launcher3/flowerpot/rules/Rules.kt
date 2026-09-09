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

/**
 * A single parsed line of a Flowerpot rule file. Ported from Lawnchair's Flowerpot engine, which
 * powers the app-drawer "Caddy" auto-categorization.
 */
sealed class Rules {
    object None : Rules()

    data class Version(val version: Int) : Rules()

    data class Package(val filter: String) : Rules()

    data class IntentAction(val action: String) : Rules()

    data class IntentCategory(val category: String) : Rules()

    data class CodeRule(val rule: String, val args: Array<String>) : Rules() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CodeRule) return false
            return rule == other.rule && args.contentEquals(other.args)
        }

        override fun hashCode(): Int = 31 * rule.hashCode() + args.contentHashCode()
    }

    companion object {
        val NONE = None
    }
}
