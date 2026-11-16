/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.hostlist

import org.connectbot.data.entity.Host
import org.connectbot.shelldon.data.RecentTarget
import java.util.UUID

/** How many sessions the "Last sessions" section shows. */
const val LAST_SESSIONS_LIMIT = 3

/** One row of the "Last sessions" section. */
sealed interface LastSession {
    val lastConnectedMs: Long

    /** A host that is saved in the host list. */
    data class Saved(val host: Host) : LastSession {
        override val lastConnectedMs: Long get() = host.lastConnect
    }

    /** A quick-connect destination that is not saved yet. */
    data class Unsaved(val target: QuickConnectTarget, override val lastConnectedMs: Long) : LastSession
}

/** The destination a saved SSH host points at, in the same shape as a quick-connect one. */
internal fun Host.asTarget(): QuickConnectTarget = QuickConnectTarget(username = username, hostname = hostname, port = port)

/**
 * The most recent sessions that are not running right now, newest first.
 *
 * Saved hosts count when they have been connected to; quick-connect destinations count unless a
 * saved SSH host already covers the same user, host and port. Anything currently connected is left
 * out because it is already in the "Active sessions" section.
 */
fun buildLastSessions(
    savedHosts: List<Host>,
    recents: List<RecentTarget>,
    activeHostIds: Set<UUID>,
    activeTargets: Set<QuickConnectTarget>,
    limit: Int = LAST_SESSIONS_LIMIT,
): List<LastSession> {
    val savedSshTargets = savedHosts.filter { it.protocol == "ssh" }.map { it.asTarget() }.toSet()
    val saved = savedHosts
        .filter { it.lastConnect > 0 && it.id !in activeHostIds }
        .map { LastSession.Saved(it) }
    val unsaved = recents
        .filter { it.target !in activeTargets && it.target !in savedSshTargets }
        .map { LastSession.Unsaved(it.target, it.lastConnectedMs) }
    return (saved + unsaved).sortedByDescending { it.lastConnectedMs }.take(limit)
}
