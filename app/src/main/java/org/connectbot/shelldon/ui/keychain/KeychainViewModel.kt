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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.KeyStorageType
import org.connectbot.data.entity.Pubkey
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.shelldon.data.groups.Identity
import org.connectbot.shelldon.data.groups.Outcome
import org.connectbot.shelldon.data.groups.Rejection
import org.connectbot.shelldon.data.groups.ShelldonGroupRepository
import org.connectbot.shelldon.vault.VaultController
import org.connectbot.shelldon.vault.VaultState
import java.util.UUID
import javax.inject.Inject

/** One identity with what the Keychain shows about it. */
data class IdentityRow(
    val identity: Identity,
    /** Nickname of the key the identity names, or null when it names none or the key is gone. */
    val keyName: String?,
    /** The identity names a key that no longer exists. */
    val keyMissing: Boolean,
    /** Where the named key lives, or null when the identity names no key (or the key is gone). */
    val keyStorage: KeyStorageType?,
    val hostCount: Int,
    val groupCount: Int,
)

/** What the vault card at the top of the Keychain says. */
data class VaultStatus(
    val state: VaultState,
    /** Seconds in the background before the vault locks itself; 0 means as soon as the app is left. */
    val autoLockSeconds: Int,
    val biometricEnabled: Boolean,
)

data class KeychainUiState(
    val identities: List<IdentityRow> = emptyList(),
    val keys: List<Pubkey> = emptyList(),
    val vault: VaultStatus? = null,
    val isLoading: Boolean = true,
)

/** Keys whose private part stays on a security key or a smart card, never on the phone. */
internal val KeyStorageType?.isHardwareBacked: Boolean
    get() = this == KeyStorageType.FIDO2_RESIDENT_KEY || this == KeyStorageType.PIV

@HiltViewModel
class KeychainViewModel @Inject constructor(
    private val groups: ShelldonGroupRepository,
    private val vault: VaultController,
    pubkeys: PubkeyRepository,
    dispatchers: CoroutineDispatchers,
) : ViewModel() {
    val uiState: StateFlow<KeychainUiState> = combine(
        groups.observeSnapshot(),
        pubkeys.observeAll(),
        groups.observeHostIdentityIds(),
        vault.state,
    ) { snapshot, keys, hostIdentityIds, vaultState ->
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
                    keyStorage = key?.storageType,
                    hostCount = hostCounts[identity.id] ?: 0,
                    groupCount = groupCounts[identity.id] ?: 0,
                )
            },
            keys = keys.sortedBy { it.nickname.lowercase() },
            vault = VaultStatus(
                state = vaultState,
                autoLockSeconds = vault.autoLockSeconds,
                biometricEnabled = vault.biometric.isEnabled(),
            ),
            isLoading = false,
        )
    }
        // The biometric check asks the Android keystore, so it stays off the main thread.
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KeychainUiState())

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

    /** Locks the vault now. Open sessions keep running; keys and saved passwords need the master password again. */
    fun lockVault() = vault.lock()
}
