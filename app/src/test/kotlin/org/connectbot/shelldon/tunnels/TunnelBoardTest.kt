/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.tunnels

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.PortForward
import org.connectbot.util.HostConstants
import org.junit.Test
import java.util.UUID

class TunnelBoardTest {
    private val web = Host(id = UUID(0, 1), nickname = "web", hostname = "web.example.com")
    private val db = Host(id = UUID(0, 2), nickname = "", hostname = "db.example.com")
    private val unused = Host(id = UUID(0, 3), nickname = "idle", hostname = "idle.example.com")

    private fun forward(host: Host, name: String, port: Int, addr: String = "localhost") = PortForward(
        id = UUID.nameUUIDFromBytes("${host.id}/$name".toByteArray()),
        hostId = host.id,
        nickname = name,
        type = HostConstants.PORTFORWARD_LOCAL,
        sourceAddr = addr,
        sourcePort = port,
        destAddr = "127.0.0.1",
        destPort = 80,
    )

    private val webAdmin = forward(web, "admin", 8081)
    private val webApi = forward(web, "Api", 8080)
    private val dbShell = forward(db, "psql", 5432)

    @Test
    fun hostsWithoutForwardsAreLeftOut() {
        val board = TunnelBoard.build(listOf(web, db, unused), listOf(webAdmin, dbShell)) { null }
        assertThat(board.map { it.host.id }).containsExactly(db.id, web.id)
    }

    @Test
    fun hostsAreSortedByNameAndNameFallsBackToTheHostname() {
        val board = TunnelBoard.build(listOf(web, db), listOf(webAdmin, dbShell)) { null }
        assertThat(board.map { it.name }).containsExactly("db.example.com", "web")
    }

    @Test
    fun forwardsAreSortedByNameWithoutCase() {
        val board = TunnelBoard.build(listOf(web), listOf(webAdmin, webApi)) { null }
        assertThat(board.single().items.map { it.forward.nickname }).containsExactly("admin", "Api")
    }

    @Test
    fun withoutAConnectionEverythingIsOffline() {
        val board = TunnelBoard.build(listOf(web), listOf(webAdmin, webApi)) { null }
        assertThat(board.single().connected).isFalse()
        assertThat(board.single().items.map { it.state }).containsExactly(TunnelState.HOST_OFFLINE, TunnelState.HOST_OFFLINE)
    }

    @Test
    fun aDisconnectedSessionIsOfflineEvenIfForwardsWereEnabled() {
        val board = TunnelBoard.build(listOf(web), listOf(webAdmin)) { LiveForwards(connected = false, enabled = setOf(webAdmin.id)) }
        assertThat(board.single().items.single().state).isEqualTo(TunnelState.HOST_OFFLINE)
    }

    @Test
    fun aConnectedHostShowsWhichForwardsRun() {
        val board = TunnelBoard.build(listOf(web), listOf(webAdmin, webApi)) { LiveForwards(connected = true, enabled = setOf(webApi.id)) }
        val states = board.single().items.associate { it.forward.nickname to it.state }
        assertThat(states.getValue("Api")).isEqualTo(TunnelState.RUNNING)
        assertThat(states.getValue("admin")).isEqualTo(TunnelState.STOPPED)
        assertThat(board.single().runningCount).isEqualTo(1)
    }

    @Test
    fun theLiveStateOfOneHostDoesNotLeakToAnother() {
        val board = TunnelBoard.build(listOf(web, db), listOf(webApi, dbShell)) { id -> if (id == web.id) LiveForwards(true, setOf(webApi.id)) else null }
        assertThat(board.first { it.host.id == web.id }.items.single().state).isEqualTo(TunnelState.RUNNING)
        assertThat(board.first { it.host.id == db.id }.items.single().state).isEqualTo(TunnelState.HOST_OFFLINE)
    }

    @Test
    fun onlyLoopbackAddressesAreLoopback() {
        for (ok in listOf("localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3", "::1", "[::1]", " 127.0.0.1 ")) {
            assertThat(TunnelBoard.isLoopback(ok)).describedAs(ok).isTrue()
        }
        for (open in listOf("0.0.0.0", "::", "*", "", "192.168.1.5", "127.evil.example.com", "127.0.0", "127.0.0.1.example.com", "10.0.0.1")) {
            assertThat(TunnelBoard.isLoopback(open)).describedAs(open).isFalse()
        }
    }

    @Test
    fun aForwardOnAnOpenAddressIsMarkedExposed() {
        val open = forward(web, "open", 9000, addr = "0.0.0.0")
        val board = TunnelBoard.build(listOf(web), listOf(webAdmin, open)) { null }
        val exposed = board.single().items.associate { it.forward.nickname to it.exposed }
        assertThat(exposed.getValue("open")).isTrue()
        assertThat(exposed.getValue("admin")).isFalse()
    }
}
