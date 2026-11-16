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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.Pubkey
import org.connectbot.shelldon.data.groups.Identity
import org.connectbot.shelldon.data.groups.Outcome
import org.connectbot.shelldon.data.groups.Rejection
import org.connectbot.shelldon.data.groups.ShelldonGroupRepository
import java.util.UUID
import javax.inject.Inject

/** One identity with what the Keychain shows about it. */
data class IdentityRow(
    val identity: Identity,
    /** Nickname of the key the identity names, or null when it names none or the key is gone. */
    val keyName: String?,
    /** The identity names a key that no longer exists. */
    val keyMissing: Boolean,
    val hostCount: Int,
    val groupCount: Int,
)

data class KeychainUiState(
    val identities: List<IdentityRow> = emptyList(),
    val keys: List<Pubkey> = emptyList(),
    val knownHostCount: Int = 0,
    val isLoading: Boolean = true,
)

@HiltViewModel
class KeychainViewModel @Inject constructor(
    private val groups: ShelldonGroupRepository,
    pubkeys: PubkeyRepository,
) : ViewModel() {
    val uiState: StateFlow<KeychainUiState> = combine(
        groups.observeSnapshot(),
        pubkeys.observeAll(),
        groups.observeAllKnownHosts(),
        groups.observeHostIdentityIds(),
    ) { snapshot, keys, knownHosts, hostIdentityIds ->
        val keysById = keys.associateBy { it.id }
        val hostCounts = hostIdentityIds.groupingBy { it }.eachCount()
        val groupCounts = snapshot.groups.mapNotNull { it.identityId }.groupingBy { it }.eachCount()
        KeychainUiState(
            identities = snapshot.identities.sortedBy { it.name.lowercase() }.map { identity ->
                val key = identity.keyId?.let { keysById[it] }
                IdentityRow(
                    identity = identity,
                    keyName = key?.nickname,
                    keyMissing = identity.keyId != null && key == null,
                    hostCount = hostCounts[identity.id] ?: 0,
                    groupCount = groupCounts[identity.id] ?: 0,
                )
            },
            keys = keys.sortedBy { it.nickname.lowercase() },
            knownHostCount = knownHosts.size,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KeychainUiState())

    /** Creates the identity, or changes [existing]. [onResult] gets null on success or the reason it was refused. */
    fun saveIdentity(existing: Identity?, name: String, username: String, keyId: UUID?, onResult: (Rejection?) -> Unit) {
        viewModelScope.launch {
            val outcome = if (existing == null) {
                groups.createIdentity(name, username, keyId)
            } else {
                groups.editIdentity(existing.id, name, username, keyId)
            }
            onResult((outcome as? Outcome.Rejected)?.reason)
        }
    }

    fun deleteIdentity(id: UUID) {
        viewModelScope.launch { groups.deleteIdentity(id) }
    }
}
