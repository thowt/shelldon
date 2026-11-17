/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.tunnels

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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.data.HostRepository
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalManager
import org.connectbot.shelldon.tunnels.LiveForwards
import org.connectbot.shelldon.tunnels.TunnelBoard
import org.connectbot.shelldon.tunnels.TunnelHost
import org.connectbot.shelldon.tunnels.TunnelItem
import timber.log.Timber
import javax.inject.Inject

data class TunnelsUiState(
    val hosts: List<TunnelHost> = emptyList(),
    /** Hosts a new forward can be added to. */
    val sshHosts: List<Host> = emptyList(),
    val isLoading: Boolean = true,
)

/** Something the screen should tell the user once. [serial] makes two equal notices distinct. */
data class TunnelNotice(val serial: Long, val kind: Kind, val name: String) {
    enum class Kind { START_FAILED, STOP_FAILED, NOT_CONNECTED }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TunnelsViewModel @Inject constructor(
    private val repository: HostRepository,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {
    private val manager = MutableStateFlow<TerminalManager?>(null)
    private val refresh = MutableStateFlow(0)
    private val _notice = MutableStateFlow<TunnelNotice?>(null)
    val notice: StateFlow<TunnelNotice?> = _notice

    val uiState: StateFlow<TunnelsUiState> = combine(
        repository.observeHosts(),
        repository.observeAllPortForwards(),
        manager,
        refresh,
    ) { hosts, forwards, terminalManager, _ ->
        val real = hosts.filterNot { it.isTemporary }
        val board = TunnelBoard.build(real, forwards) { hostId ->
            val bridge = terminalManager?.bridgesFlow?.value?.find { it.host.id == hostId }
            val connected = bridge?.transport?.isConnected() == true
            if (bridge == null) {
                null
            } else {
                LiveForwards(
                    connected = connected,
                    enabled = if (connected) bridge.portForwards.filter { it.isEnabled() }.map { it.id }.toSet() else emptySet(),
                )
            }
        }
        TunnelsUiState(
            hosts = board,
            sshHosts = real.filter { it.protocol == "ssh" }.sortedBy { TunnelBoard.hostName(it).lowercase() },
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TunnelsUiState())

    init {
        // A connection coming up or going down changes what runs.
        viewModelScope.launch {
            manager.flatMapLatest { it?.hostStatusChangedFlow ?: emptyFlow() }.collect { refresh.value += 1 }
        }
    }

    fun setTerminalManager(terminalManager: TerminalManager) {
        manager.value = terminalManager
    }

    fun refreshState() {
        refresh.value += 1
    }

    fun clearNotice() {
        _notice.value = null
    }

    /** Starts or stops one forward on the open connection of its host. */
    fun setRunning(item: TunnelItem, run: Boolean) {
        viewModelScope.launch {
            val name = item.forward.nickname
            val bridge = manager.value?.bridgesFlow?.value?.find { it.host.id == item.forward.hostId }
            val live = bridge?.portForwards?.find { it.id == item.forward.id }
            if (bridge == null || live == null || bridge.transport?.isConnected() != true) {
                notify(TunnelNotice.Kind.NOT_CONNECTED, name)
                refresh.value += 1
                return@launch
            }
            val done = try {
                withContext(dispatchers.io) { if (run) bridge.enablePortForward(live) else bridge.disablePortForward(live) }
            } catch (e: Exception) {
                Timber.e(e, "Tunnel %s could not be %s", name, if (run) "started" else "stopped")
                false
            }
            if (!done) notify(if (run) TunnelNotice.Kind.START_FAILED else TunnelNotice.Kind.STOP_FAILED, name)
            refresh.value += 1
        }
    }

    private fun notify(kind: TunnelNotice.Kind, name: String) {
        _notice.value = TunnelNotice(System.nanoTime(), kind, name)
    }
}
