/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("NewApi") // JVM unit tests run on the desktop JDK, not on a device

package org.connectbot.shelldon.container

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.shelldon.tunnels.ForwardBind
import org.connectbot.sshlib.PortForwarder
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Where a local (`-L`) and a dynamic (`-D`) forward listens, checked on real sockets with a real
 * OpenSSH server behind them. A fake cannot show the two things that matter: that a forward bound
 * to loopback really refuses a connection to the phone's network address, and that one the person
 * explicitly bound to the network really answers there. The forwards are started with the address
 * the transport builds from a stored `sourceAddr` ([ForwardBind]), through the same client calls
 * `SSH.enablePortForward` makes. Same switch as the other container tests: off unless
 * `SHELLDON_CONTAINER_TESTS=1`, and with it set a missing Docker is a failure.
 */
class ForwardBindContainerTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun requireDocker() = LiveServer.requireDocker()
    }

    @get:Rule
    val jdkProviders = JdkProvidersRule()

    /** An IPv4 address of this machine that is not loopback, which is what another device on the network would connect to. */
    private fun networkAddress(): InetAddress {
        val found = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        return checkNotNull(found) { "This machine has no non-loopback IPv4 address, so exposure cannot be tested" }
    }

    /** The first line the other end sends, or null if the connection is refused or times out. */
    private suspend fun banner(address: InetAddress, port: Int): String? = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), 5_000)
                socket.soTimeout = 10_000
                val line = StringBuilder()
                while (line.length < 255) {
                    val next = socket.getInputStream().read()
                    if (next < 0 || next == '\n'.code) break
                    line.append(next.toChar())
                }
                line.toString().trimEnd('\r')
            }
        } catch (_: IOException) {
            null
        }
    }

    /** The reply to a SOCKS5 greeting that offers "no authentication", or null if the connection is refused. */
    private suspend fun socksReply(address: InetAddress, port: Int): List<Int>? = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), 5_000)
                socket.soTimeout = 10_000
                socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                socket.getOutputStream().flush()
                val reply = ByteArray(2)
                var read = 0
                while (read < 2) {
                    val count = socket.getInputStream().read(reply, read, 2 - read)
                    if (count < 0) break
                    read += count
                }
                reply.take(read).map { it.toInt() and 0xFF }
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun isSshBanner(line: String?) = line != null && line.startsWith("SSH-2.0-")

    private fun bind(sourceAddr: String) = checkNotNull(ForwardBind.socketAddress(sourceAddr, 0)) { "not a listen address: $sourceAddr" }

    private suspend fun LiveServer.Connection.local(sourceAddr: String): PortForwarder = checkNotNull(client.localPortForward(bind(sourceAddr), "127.0.0.1", 22)) { "the forward did not start on '$sourceAddr'" }

    private suspend fun LiveServer.Connection.dynamic(sourceAddr: String): PortForwarder = checkNotNull(client.dynamicPortForward(bind(sourceAddr))) { "the SOCKS forward did not start on '$sourceAddr'" }

    @Test
    fun theDefaultForwardCarriesTrafficOnLoopbackAndRefusesTheNetworkAddress() = LiveServer.withConnection { server ->
        val lan = networkAddress()
        server.local("localhost").use { forward ->
            assertThat(forward.boundPort).isGreaterThan(0)
            assertThat(isSshBanner(banner(InetAddress.getLoopbackAddress(), forward.boundPort))).describedAs("through loopback").isTrue()
            assertThat(banner(lan, forward.boundPort)).describedAs("through %s", lan).isNull()
        }
    }

    @Test
    fun aForwardBoundToEveryInterfaceAnswersOnTheNetworkAddressAndOnLoopback() = LiveServer.withConnection { server ->
        val lan = networkAddress()
        for (stored in listOf("", "0.0.0.0")) {
            server.local(stored).use { forward ->
                assertThat(isSshBanner(banner(lan, forward.boundPort))).describedAs("'%s' through %s", stored, lan).isTrue()
                assertThat(isSshBanner(banner(InetAddress.getLoopbackAddress(), forward.boundPort))).describedAs("'%s' through loopback", stored).isTrue()
            }
        }
    }

    @Test
    fun aForwardBoundToOneInterfaceAddressAnswersOnlyThere() = LiveServer.withConnection { server ->
        val lan = networkAddress()
        server.local(lan.hostAddress).use { forward ->
            assertThat(isSshBanner(banner(lan, forward.boundPort))).describedAs("through %s", lan).isTrue()
            assertThat(banner(InetAddress.getLoopbackAddress(), forward.boundPort)).describedAs("through loopback").isNull()
        }
    }

    @Test
    fun aSocksProxyIsOnLoopbackByDefaultAndOnTheNetworkOnlyWhenBoundThere() = LiveServer.withConnection { server ->
        val lan = networkAddress()
        server.dynamic("localhost").use { forward ->
            assertThat(socksReply(InetAddress.getLoopbackAddress(), forward.boundPort)).describedAs("loopback").isEqualTo(listOf(5, 0))
            assertThat(socksReply(lan, forward.boundPort)).describedAs("network address of a loopback proxy").isNull()
        }
        server.dynamic("").use { forward ->
            assertThat(socksReply(lan, forward.boundPort)).describedAs("network address of a proxy on every interface").isEqualTo(listOf(5, 0))
            assertThat(socksReply(InetAddress.getLoopbackAddress(), forward.boundPort)).describedAs("loopback").isEqualTo(listOf(5, 0))
        }
    }

    @Test
    fun stoppingAForwardClosesItsPort() = LiveServer.withConnection { server ->
        val forward = server.local("")
        val port = forward.boundPort
        assertThat(isSshBanner(banner(InetAddress.getLoopbackAddress(), port))).isTrue()
        forward.stop()
        assertThat(banner(InetAddress.getLoopbackAddress(), port)).isNull()
        assertThat(banner(networkAddress(), port)).isNull()
    }
}
