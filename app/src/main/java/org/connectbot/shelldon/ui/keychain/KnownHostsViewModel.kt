/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.keychain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.data.HostRepository
import org.connectbot.data.entity.KnownHost
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.shelldon.data.groups.ShelldonGroupRepository
import org.connectbot.sshlib.KeyFingerprint
import javax.inject.Inject

data class KnownHostRow(
    val knownHost: KnownHost,
    val fingerprint: String,
    val endpoint: String,
)

/**
 * Search matches the host and port, the key fingerprint or the key type, ignoring case. A pasted
 * `SHA256:` fingerprint finds the host that presented it.
 */
internal fun KnownHostRow.matchesQuery(query: String): Boolean {
    val text = query.trim()
    if (text.isEmpty()) return true
    return endpoint.contains(text, ignoreCase = true) ||
        knownHost.hostname.contains(text, ignoreCase = true) ||
        fingerprint.contains(text, ignoreCase = true) ||
        knownHost.hostKeyAlgo.contains(text, ignoreCase = true)
}

/** Every saved host key across all hosts, so a changed or stale key can be found and forgotten in one place. */
@HiltViewModel
class KnownHostsOverviewViewModel @Inject constructor(
    groups: ShelldonGroupRepository,
    private val hosts: HostRepository,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {
    /** Null until the first list has loaded. */
    val rows: StateFlow<List<KnownHostRow>?> = groups.observeAllKnownHosts()
        .map { list ->
            list.map { known ->
                KnownHostRow(
                    knownHost = known,
                    fingerprint = KeyFingerprint.sha256(known.hostKey),
                    endpoint = if (known.port == 22) known.hostname else "${known.hostname}:${known.port}",
                )
            }
        }
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun forget(knownHost: KnownHost) {
        viewModelScope.launch {
            withContext(dispatchers.io) { hosts.deleteKnownHost(knownHost) }
        }
    }
}
