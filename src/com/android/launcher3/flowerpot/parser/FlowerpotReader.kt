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
package com.android.launcher3.flowerpot.parser

import com.android.launcher3.flowerpot.Flowerpot
import com.android.launcher3.flowerpot.FlowerpotFormatException
import com.android.launcher3.flowerpot.rules.Rules
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/** Reads and parses a Flowerpot rule file line by line. Ported from Lawnchair. */
class FlowerpotReader(inputStream: InputStream) :
    BufferedReader(InputStreamReader(inputStream)) {
    private var version: Int? = null

    /**
     * Read the next rule from the stream.
     * @return the parsed rule or null if the end of the file has been reached
     */
    fun readRule(): Rules? {
        val line = readLine() ?: return null
        val filter = LineParser.parse(line, version)
        if (filter is Rules.Version) {
            if (version != null) {
                throw FlowerpotFormatException("Version declaration can only appear once")
            }
            if (!Flowerpot.SUPPORTED_VERSIONS.contains(filter.version)) {
                throw FlowerpotFormatException(
                    "Unsupported version ${filter.version} (supported are " +
                        "${Flowerpot.SUPPORTED_VERSIONS.joinToString()})",
                )
            }
            version = filter.version
        }
        return filter
    }

    /** Read all rules contained in the file with None and Version rules already filtered out. */
    fun readRules(): List<Rules> {
        val rules = mutableListOf<Rules>()
        while (true) {
            val rule = readRule() ?: break
            rules.add(rule)
        }
        return rules.filterNot { it is Rules.None || it is Rules.Version }
    }
}
