/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.hostlist

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import org.connectbot.data.HostRepository
import org.connectbot.data.entity.Host
import org.connectbot.shelldon.data.RecentSessionStore
import org.connectbot.shelldon.data.RecentTarget
import javax.inject.Inject

/**
 * Remembers quick-connect destinations and turns them into saved hosts. Kept apart from the
 * upstream `HostListViewModel` so that class stays untouched.
 */
@HiltViewModel
class ShelldonRecentsViewModel @Inject constructor(
    private val store: RecentSessionStore,
    private val repository: HostRepository,
) : ViewModel() {
    val recents: StateFlow<List<RecentTarget>> = store.entries

    /** Call when a quick-connect session was opened. */
    fun recordQuickConnect(target: QuickConnectTarget) = store.record(target)

    /** Saves a destination from the Last sessions list as a new host. */
    suspend fun saveRecent(session: LastSession.Unsaved): Host {
        val host = Host
            .createSshHost(freeNickname(session.target.nickname), session.target.hostname, session.target.port, session.target.username)
            .copy(lastConnect = session.lastConnectedMs)
        val saved = repository.saveHost(host)
        store.remove(session.target)
        return saved
    }

    /**
     * Saves a quick-connect session that is still running. The host keeps its id, so the running
     * session carries over to the saved entry.
     */
    suspend fun saveActive(host: Host): Host {
        val saved = repository.saveHost(
            host.copy(nickname = freeNickname(host.nickname), lastConnect = System.currentTimeMillis()),
        )
        store.remove(host.asTarget())
        return saved
    }

    private suspend fun freeNickname(wanted: String): String = uniqueNickname(wanted, repository.getHosts().map { it.nickname }.toSet())
}

/** [wanted] if it is free, otherwise `wanted (2)`, `wanted (3)`, ... Nicknames are unique per repository. */
internal fun uniqueNickname(wanted: String, taken: Set<String>): String {
    if (wanted !in taken) return wanted
    var n = 2
    while ("$wanted ($n)" in taken) n++
    return "$wanted ($n)"
}
