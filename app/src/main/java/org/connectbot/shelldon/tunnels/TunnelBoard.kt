/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.tunnels

import org.connectbot.data.entity.Host
import org.connectbot.data.entity.PortForward
import java.util.UUID

enum class TunnelState {
    /** The forward is up. */
    RUNNING,

    /** The host is connected and the forward is off. */
    STOPPED,

    /** There is no connection to the host, so nothing can run. */
    HOST_OFFLINE,
}

/** What the live connection to one host says about its forwards. */
data class LiveForwards(val connected: Boolean, val enabled: Set<UUID>)

data class TunnelItem(val forward: PortForward, val state: TunnelState) {
    /** The bind address was set to something other than this phone's loopback. */
    val exposed: Boolean get() = !TunnelBoard.isLoopback(forward.sourceAddr)
}

data class TunnelHost(val host: Host, val connected: Boolean, val items: List<TunnelItem>) {
    val name: String get() = TunnelBoard.hostName(host)
    val runningCount: Int get() = items.count { it.state == TunnelState.RUNNING }
}

/** Every port forward, grouped by host, with what is running. Pure, so it can be tested without a phone. */
object TunnelBoard {
    fun hostName(host: Host): String = host.nickname.ifBlank { host.hostname }

    /**
     * @param live what the open connection of a host says, or null if there is none
     * @return hosts that have at least one forward, by name; each host's forwards by name
     */
    fun build(hosts: List<Host>, forwards: List<PortForward>, live: (UUID) -> LiveForwards?): List<TunnelHost> {
        val byHost = forwards.groupBy { it.hostId }
        return hosts
            .filter { byHost.containsKey(it.id) }
            .map { host ->
                val state = live(host.id)
                val connected = state?.connected == true
                val items = byHost.getValue(host.id)
                    .sortedWith(compareBy({ it.nickname.lowercase() }, { it.sourcePort }))
                    .map { forward ->
                        TunnelItem(
                            forward,
                            when {
                                !connected -> TunnelState.HOST_OFFLINE
                                forward.id in state!!.enabled -> TunnelState.RUNNING
                                else -> TunnelState.STOPPED
                            },
                        )
                    }
                TunnelHost(host, connected, items)
            }
            .sortedWith(compareBy({ hostName(it.host).lowercase() }, { it.host.id }))
    }

    private val ipv4Loopback = Regex("127(\\.\\d{1,3}){3}")

    /** Loopback only: `localhost`, `::1` and `127.x.y.z`. A name that merely starts with 127 is not. */
    fun isLoopback(address: String): Boolean {
        val a = address.trim().lowercase().removePrefix("[").removeSuffix("]")
        return a == "localhost" || a == "::1" || ipv4Loopback.matches(a)
    }
}
