/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.data

import org.connectbot.shelldon.ui.hostlist.QuickConnectTarget

/** A quick-connect destination that was used, and when. Holds no password or key material. */
data class RecentTarget(
    val target: QuickConnectTarget,
    val lastConnectedMs: Long,
)

/**
 * Plain-text form of the recent list, one `lastConnectedMs<TAB>port<TAB>host<TAB>user` line each.
 * User names and host names are validated by [org.connectbot.shelldon.ui.hostlist.parseQuickConnect]
 * so they cannot contain tabs or newlines. Lines that do not parse are dropped.
 */
internal object RecentSessionsFormat {
    private const val SEPARATOR = '\t'

    fun encode(entries: List<RecentTarget>): String = entries.joinToString("\n") {
        listOf(it.lastConnectedMs, it.target.port, it.target.hostname, it.target.username).joinToString(SEPARATOR.toString())
    }

    fun decode(text: String?): List<RecentTarget> = text.orEmpty().lineSequence().mapNotNull { line ->
        val parts = line.split(SEPARATOR)
        if (parts.size != 4) return@mapNotNull null
        val time = parts[0].toLongOrNull() ?: return@mapNotNull null
        val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
        val host = parts[2].takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        RecentTarget(QuickConnectTarget(username = parts[3], hostname = host, port = port), time)
    }.toList()

    /** Newest first, one entry per destination, at most [limit] entries. */
    fun normalize(entries: List<RecentTarget>, limit: Int): List<RecentTarget> = entries
        .sortedByDescending { it.lastConnectedMs }
        .distinctBy { it.target }
        .take(limit)
}
