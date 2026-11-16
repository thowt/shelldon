/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.hostlist

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class QuickConnectTest {
    @Test
    fun userHostAndPort_areParsed() {
        assertThat(parseQuickConnect("root@10.0.0.2:2222"))
            .isEqualTo(QuickConnectTarget("root", "10.0.0.2", 2222))
    }

    @Test
    fun hostOnly_usesDefaultPortAndNoUser() {
        assertThat(parseQuickConnect("nas.lan"))
            .isEqualTo(QuickConnectTarget("", "nas.lan", 22))
    }

    @Test
    fun surroundingSpaces_areTrimmed() {
        assertThat(parseQuickConnect("  pi@raspberrypi.local  "))
            .isEqualTo(QuickConnectTarget("pi", "raspberrypi.local", 22))
    }

    @Test
    fun usernameMayContainDotsAndDashes() {
        assertThat(parseQuickConnect("first.last-1@host.example.com")?.username).isEqualTo("first.last-1")
    }

    @Test
    fun nickname_isUserAtHostOrJustHost() {
        assertThat(QuickConnectTarget("pi", "pi.local", 22).nickname).isEqualTo("pi@pi.local")
        assertThat(QuickConnectTarget("", "pi.local", 22).nickname).isEqualTo("pi.local")
    }

    @Test
    fun invalidInput_isRejected() {
        val invalid = listOf(
            "",
            "   ",
            "two words",
            "@host",
            "user@",
            "user@@host",
            "host:",
            "host:0",
            "host:65536",
            "host:abc",
            "host:22:33",
            "::1",
            "[::1]:22",
            "bad!host",
            "-leading.dash",
            "trailing-.dash",
            "double..dot",
            "us er@host",
        )
        invalid.forEach { input ->
            assertThat(parseQuickConnect(input)).describedAs("input: \"%s\"", input).isNull()
        }
    }

    @Test
    fun portBoundaries_areAccepted() {
        assertThat(parseQuickConnect("host:1")?.port).isEqualTo(1)
        assertThat(parseQuickConnect("host:65535")?.port).isEqualTo(65535)
    }

    @Test
    fun looksLikeAddress_ignoresPlainSearchTerms() {
        assertThat(looksLikeAddress("nas")).isFalse()
        assertThat(looksLikeAddress("nas.lan")).isTrue()
        assertThat(looksLikeAddress("root@nas")).isTrue()
        assertThat(looksLikeAddress("nas:2222")).isTrue()
    }

    @Test
    fun temporaryHost_keepsUserHostAndPortSeparate() {
        val host = parseQuickConnect("user@host.example:2022")!!.toTemporaryHost()

        assertThat(host.protocol).isEqualTo("ssh")
        assertThat(host.username).isEqualTo("user")
        assertThat(host.hostname).isEqualTo("host.example")
        assertThat(host.port).isEqualTo(2022)
        assertThat(host.nickname).isEqualTo("user@host.example")
        assertThat(host.isTemporary).isTrue()
    }

    @Test
    fun temporaryHost_withoutUserHasEmptyUsername() {
        val host = parseQuickConnect("nas.lan")!!.toTemporaryHost()

        assertThat(host.username).isEmpty()
        assertThat(host.hostname).isEqualTo("nas.lan")
        assertThat(host.port).isEqualTo(22)
    }
}
