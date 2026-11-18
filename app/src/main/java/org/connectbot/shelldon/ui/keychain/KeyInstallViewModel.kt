/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.keychain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.data.HostRepository
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.Pubkey
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalManager
import org.connectbot.shelldon.exec.ExecOutcome
import org.connectbot.shelldon.keyinstall.AuthorizedKeyLine
import org.connectbot.shelldon.keyinstall.KeyInstallResult
import org.connectbot.shelldon.keyinstall.KeyInstaller
import org.connectbot.shelldon.keyinstall.PendingKeyInstall
import org.connectbot.shelldon.tunnels.TunnelBoard
import java.util.UUID
import javax.inject.Inject

/** One SSH host in the install list, and whether it has a live connection that can run the command. */
data class InstallHost(val host: Host, val name: String, val connected: Boolean)

/** One key on one host: the pair a result belongs to. */
data class InstallTarget(val keyId: UUID, val hostId: UUID)

sealed interface InstallStatus {
    data object Running : InstallStatus

    data class Done(val result: KeyInstallResult) : InstallStatus
}

data class KeyInstallUiState(
    val hosts: List<InstallHost> = emptyList(),
    val keys: List<Pubkey> = emptyList(),
    val statuses: Map<InstallTarget, InstallStatus> = emptyMap(),
    val isLoading: Boolean = true,
)

/**
 * Puts a public key into `authorized_keys` on a server, through the server's open SSH session: the login,
 * the host-key check and any prompts already happened there, so this adds no second way to log in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class KeyInstallViewModel @Inject constructor(
    hostRepository: HostRepository,
    pubkeyRepository: PubkeyRepository,
    private val dispatchers: CoroutineDispatchers,
    private val pendingInstall: PendingKeyInstall,
) : ViewModel() {
    private val manager = MutableStateFlow<TerminalManager?>(null)
    private val refresh = MutableStateFlow(0)
    private val statuses = MutableStateFlow<Map<InstallTarget, InstallStatus>>(emptyMap())

    private val keys = pubkeyRepository.observeAll().map { all -> all.sortedBy { it.nickname.lowercase() } }

    val uiState: StateFlow<KeyInstallUiState> = combine(
        hostRepository.observeHosts(),
        keys,
        manager,
        refresh,
        statuses,
    ) { hosts, allKeys, terminalManager, _, results ->
        val ssh = hosts.filter { !it.isTemporary && it.protocol == "ssh" }.sortedBy { TunnelBoard.hostName(it).lowercase() }
        KeyInstallUiState(
            hosts = ssh.map { host ->
                val transport = terminalManager?.bridgesFlow?.value?.find { it.host.id == host.id }?.transport
                InstallHost(host, TunnelBoard.hostName(host), transport?.canExecCommand() == true)
            },
            keys = allKeys,
            statuses = results,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KeyInstallUiState())

    init {
        // A connection coming up or going down changes what can be installed where.
        viewModelScope.launch {
            manager.flatMapLatest { it?.hostStatusChangedFlow ?: emptyFlow() }.collect { refresh.value += 1 }
        }
    }

    /** An install that is waiting for its server's login to finish, see [PendingKeyInstall]. */
    val pending: StateFlow<PendingKeyInstall.Request?> = pendingInstall.request

    /** Called when the user taps Connect: ask to install [keyId] on [hostId] once the login is done. */
    fun installAfterLogin(keyId: UUID, hostId: UUID) = pendingInstall.set(hostId, keyId)

    fun clearPending() = pendingInstall.clear()

    fun setTerminalManager(terminalManager: TerminalManager) {
        manager.value = terminalManager
    }

    /** Forgets earlier results, when a dialog opens. */
    fun clearResults() {
        statuses.value = emptyMap()
    }

    /** Installs [line], the public half of the key [keyId], on the open connection of [hostId]. */
    fun install(keyId: UUID, line: AuthorizedKeyLine, hostId: UUID) {
        val target = InstallTarget(keyId, hostId)
        if (statuses.value[target] == InstallStatus.Running) return
        statuses.update { it + (target to InstallStatus.Running) }
        viewModelScope.launch {
            val transport = manager.value?.bridgesFlow?.value?.find { it.host.id == hostId }?.transport
            val result = KeyInstaller.install(line) { command ->
                if (transport == null || !transport.canExecCommand()) {
                    ExecOutcome.Unavailable
                } else {
                    val ran = withContext(dispatchers.io) { transport.execCommand(command) }
                    if (ran == null) ExecOutcome.Unavailable else ExecOutcome.Finished(ran)
                }
            }
            statuses.update { it + (target to InstallStatus.Done(result)) }
        }
    }
}
