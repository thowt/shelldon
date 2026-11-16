/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.hostlist

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.entity.Host
import org.connectbot.shelldon.data.RecentTarget
import org.junit.Test
import java.util.UUID

class LastSessionsTest {
    private fun host(n: Long, user: String = "u$n", name: String = "h$n.lan", port: Int = 22, lastConnect: Long = 0, protocol: String = "ssh") = Host(
        id = UUID(0, n),
        nickname = "host$n",
        protocol = protocol,
        username = user,
        hostname = name,
        port = port,
        lastConnect = lastConnect,
    )

    private fun recent(user: String, name: String, ms: Long, port: Int = 22) = RecentTarget(QuickConnectTarget(user, name, port), ms)

    private fun build(
        saved: List<Host> = emptyList(),
        recents: List<RecentTarget> = emptyList(),
        activeIds: Set<UUID> = emptySet(),
        activeTargets: Set<QuickConnectTarget> = emptySet(),
    ) = buildLastSessions(saved, recents, activeIds, activeTargets)

    @Test
    fun savedAndQuickConnectAreMergedNewestFirst() {
        val result = build(
            saved = listOf(host(1, lastConnect = 100), host(2, lastConnect = 300)),
            recents = listOf(recent("root", "new.lan", 200)),
        )

        assertThat(result.map { it.lastConnectedMs }).containsExactly(300L, 200L, 100L)
        assertThat(result[1]).isInstanceOf(LastSession.Unsaved::class.java)
    }

    @Test
    fun onlyTheLastThreeAreKept() {
        val result = build(
            saved = listOf(host(1, lastConnect = 10), host(2, lastConnect = 40)),
            recents = listOf(recent("a", "a.lan", 20), recent("b", "b.lan", 30), recent("c", "c.lan", 50)),
        )

        assertThat(result.map { it.lastConnectedMs }).containsExactly(50L, 40L, 30L)
    }

    @Test
    fun savedHostsThatWereNeverConnectedAreNotListed() {
        assertThat(build(saved = listOf(host(1, lastConnect = 0)))).isEmpty()
    }

    @Test
    fun runningSessionsAreLeftOut() {
        val running = host(1, lastConnect = 100)
        val result = build(
            saved = listOf(running, host(2, lastConnect = 50)),
            recents = listOf(recent("root", "live.lan", 90)),
            activeIds = setOf(running.id),
            activeTargets = setOf(QuickConnectTarget("root", "live.lan", 22)),
        )

        assertThat(result.map { it.lastConnectedMs }).containsExactly(50L)
    }

    @Test
    fun quickConnectThatIsAlreadySavedIsNotListedTwice() {
        val saved = host(1, user = "root", name = "web.lan", port = 2222, lastConnect = 100)
        val result = build(saved = listOf(saved), recents = listOf(recent("root", "web.lan", 200, port = 2222)))

        assertThat(result).hasSize(1)
        assertThat(result[0]).isEqualTo(LastSession.Saved(saved))
    }

    @Test
    fun aSavedTelnetHostDoesNotHideAQuickSshConnectToTheSameAddress() {
        val telnet = host(1, user = "root", name = "sw.lan", lastConnect = 100, protocol = "telnet")
        val result = build(saved = listOf(telnet), recents = listOf(recent("root", "sw.lan", 200)))

        assertThat(result).hasSize(2)
    }

    @Test
    fun nothingToShowGivesAnEmptyList() {
        assertThat(build()).isEmpty()
    }

    @Test
    fun uniqueNickname_addsACounterOnlyWhenTaken() {
        assertThat(uniqueNickname("root@host", emptySet())).isEqualTo("root@host")
        assertThat(uniqueNickname("root@host", setOf("root@host"))).isEqualTo("root@host (2)")
        assertThat(uniqueNickname("root@host", setOf("root@host", "root@host (2)"))).isEqualTo("root@host (3)")
    }
}
