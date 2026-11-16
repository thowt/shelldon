/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.data

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.shelldon.ui.hostlist.QuickConnectTarget
import org.junit.Test

class RecentSessionsFormatTest {
    private fun entry(user: String, host: String, port: Int, ms: Long) = RecentTarget(QuickConnectTarget(user, host, port), ms)

    @Test
    fun encodeThenDecode_roundTrips() {
        val entries = listOf(entry("root", "10.0.0.2", 2222, 5), entry("", "nas.lan", 22, 3))

        assertThat(RecentSessionsFormat.decode(RecentSessionsFormat.encode(entries))).isEqualTo(entries)
    }

    @Test
    fun decode_dropsBrokenLinesAndKeepsGoodOnes() {
        val text = listOf(
            "not a line",
            "1\t22\thost.lan\tuser",
            "x\t22\thost.lan\tuser",
            "1\t70000\thost.lan\tuser",
            "1\t22\t\tuser",
            "2\t22\tok.lan\t",
        ).joinToString("\n")

        assertThat(RecentSessionsFormat.decode(text))
            .containsExactly(entry("user", "host.lan", 22, 1), entry("", "ok.lan", 22, 2))
    }

    @Test
    fun decode_ofNothingIsEmpty() {
        assertThat(RecentSessionsFormat.decode(null)).isEmpty()
        assertThat(RecentSessionsFormat.decode("")).isEmpty()
    }

    @Test
    fun normalize_sortsNewestFirstDeduplicatesAndLimits() {
        val result = RecentSessionsFormat.normalize(
            listOf(
                entry("a", "a.lan", 22, 1),
                entry("b", "b.lan", 22, 5),
                entry("a", "a.lan", 22, 9),
                entry("c", "c.lan", 22, 3),
            ),
            limit = 2,
        )

        assertThat(result).containsExactly(entry("a", "a.lan", 22, 9), entry("b", "b.lan", 22, 5))
    }
}
