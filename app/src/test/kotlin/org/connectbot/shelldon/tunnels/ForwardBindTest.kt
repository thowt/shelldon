/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.tunnels

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class ForwardBindTest {
    private fun bind(address: String, port: Int = 8080) = ForwardBind.socketAddress(address, port)

    @Test
    fun localhostIsTheIpv4LoopbackWhichIsWhatEveryEarlierForwardUsed() {
        val address = checkNotNull(bind("localhost"))
        assertThat(address.address.hostAddress).isEqualTo("127.0.0.1")
        assertThat(address.port).isEqualTo(8080)
        assertThat(ForwardBind.isExposed("localhost")).isFalse()
    }

    @Test
    fun loopbackLiteralsStayOnThePhone() {
        assertThat(checkNotNull(bind("127.0.0.1")).address.isLoopbackAddress).isTrue()
        assertThat(checkNotNull(bind("127.1.2.3")).address.isLoopbackAddress).isTrue()
        assertThat(checkNotNull(bind("::1")).address.isLoopbackAddress).isTrue()
        assertThat(checkNotNull(bind("[::1]")).address.isLoopbackAddress).isTrue()
        assertThat(ForwardBind.isExposed("::1")).isFalse()
        assertThat(ForwardBind.isExposed("127.0.0.1")).isFalse()
    }

    @Test
    fun anEmptyStarOrZeroAddressListensOnEveryInterface() {
        for (value in listOf("", "  ", "*", "0.0.0.0", "::")) {
            val address = checkNotNull(bind(value))
            assertThat(address.address.isAnyLocalAddress).describedAs(value).isTrue()
            assertThat(ForwardBind.isExposed(value)).describedAs(value).isTrue()
        }
        assertThat(checkNotNull(bind("0.0.0.0")).address.address.size).isEqualTo(4)
        assertThat(checkNotNull(bind("::")).address.address.size).isEqualTo(16)
    }

    @Test
    fun anInterfaceAddressIsExposedAndKeptAsWritten() {
        val v4 = checkNotNull(bind("192.168.1.20", 2222))
        assertThat(v4.address.hostAddress).isEqualTo("192.168.1.20")
        assertThat(v4.port).isEqualTo(2222)
        assertThat(ForwardBind.isExposed("192.168.1.20")).isTrue()
        assertThat(checkNotNull(bind("fd00::20")).address.address.toList()).isEqualTo(listOf<Byte>(0xfd.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x20))
        assertThat(ForwardBind.isExposed("fd00::20")).isTrue()
    }

    @Test
    fun ipv6ShortFormsAndAnIpv4TailAreRead() {
        assertThat(checkNotNull(bind("2001:db8::1")).address.address.toList().take(4)).isEqualTo(listOf<Byte>(0x20, 0x01, 0x0d, 0xb8.toByte()))
        assertThat(checkNotNull(bind("1:2:3:4:5:6:7:8")).address.address.toList().last()).isEqualTo(8.toByte())
        assertThat(checkNotNull(bind("1:2:3:4:5:6:7::")).address.address.size).isEqualTo(16)
        val mapped = checkNotNull(bind("::ffff:10.1.2.3")).address.address
        assertThat(mapped.takeLast(4)).isEqualTo(listOf<Byte>(10, 1, 2, 3).toByteArray().toList())
    }

    @Test
    fun hostNamesAndMalformedAddressesAreRefusedWithoutAnyLookup() {
        for (value in listOf("example.com", "my-laptop.local", "localhost.example", "256.1.1.1", "1.2.3", "1.2.3.4.5", "01.2.3.4x", "::g", ":::", "1::2::3", "1:2:3:4:5:6:7:8:9", "fe80::1%wlan0", "1:2:3:4:5:6:7", "12345::1", "0x7f.0.0.1", "-1.0.0.1")) {
            assertThat(bind(value)).describedAs(value).isNull()
        }
    }

    @Test
    fun anUnreadableAddressCountsAsExposedSoNothingIsAssumedSafe() {
        assertThat(ForwardBind.isExposed("example.com")).isTrue()
    }

    @Test
    fun thePortMustBeValid() {
        assertThat(bind("localhost", -1)).isNull()
        assertThat(bind("localhost", 65536)).isNull()
        assertThat(bind("localhost", 0)).isNotNull()
    }

    @Test
    fun theBoardAndTheBinderAgreeOnWhatIsLoopback() {
        for (value in listOf("localhost", "127.0.0.1", "127.9.9.9", "::1", "[::1]", "", "*", "0.0.0.0", "::", "192.168.1.1", "10.0.0.5", "fd00::1", "1270.0.0.1")) {
            if (bind(value) == null) continue
            assertThat(TunnelBoard.isLoopback(value)).describedAs(value).isEqualTo(!ForwardBind.isExposed(value))
        }
    }
}
