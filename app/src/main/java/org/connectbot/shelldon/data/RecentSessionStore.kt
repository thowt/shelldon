/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.connectbot.shelldon.ui.hostlist.QuickConnectTarget
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers the last quick-connect destinations so they can be listed after the session ends and
 * saved as hosts. Only user, host, port and time are kept, in app-private storage that is not
 * backed up (`allowBackup` is off). Saved hosts are not tracked here: the host list already has
 * their last-connected time.
 */
@Singleton
class RecentSessionStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(RecentSessionsFormat.normalize(RecentSessionsFormat.decode(prefs.getString(KEY, null)), MAX_ENTRIES))
    val entries: StateFlow<List<RecentTarget>> = _entries.asStateFlow()

    /** Notes that [target] was just connected to. */
    @Synchronized
    fun record(target: QuickConnectTarget, nowMs: Long = System.currentTimeMillis()) {
        update(listOf(RecentTarget(target, nowMs)) + _entries.value)
    }

    /** Forgets [target], for example after it was saved as a host. */
    @Synchronized
    fun remove(target: QuickConnectTarget) {
        update(_entries.value.filterNot { it.target == target })
    }

    private fun update(entries: List<RecentTarget>) {
        val normalized = RecentSessionsFormat.normalize(entries, MAX_ENTRIES)
        prefs.edit().putString(KEY, RecentSessionsFormat.encode(normalized)).apply()
        _entries.value = normalized
    }

    private companion object {
        const val PREFS_NAME = "shelldon_recent_sessions"
        const val KEY = "entries"
        const val MAX_ENTRIES = 10
    }
}
