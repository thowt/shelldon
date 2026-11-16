/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ssh

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshClientConfig
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ShelldonSshIdentityTest {
    @Test
    fun followsTheIdentificationSyntaxOfRfc4253() {
        // SSH-protoversion-softwareversion: the software version is printable US-ASCII without space or '-'.
        assertThat(ShelldonSshIdentity.CLIENT_VERSION).matches("SSH-2\\.0-[!-,.-~]+")
        // 255 bytes including the CR LF the library appends.
        assertThat(ShelldonSshIdentity.CLIENT_VERSION.length).isLessThanOrEqualTo(253)
    }

    @Test
    fun revealsNeitherTheForkOriginNorAVersion() {
        val lower = ShelldonSshIdentity.CLIENT_VERSION.lowercase()
        assertThat(lower).doesNotContain("connectbot").doesNotContain("cbssh")
        assertThat(ShelldonSshIdentity.CLIENT_VERSION.substringAfter("SSH-2.0-")).doesNotContainPattern("\\d")
    }

    @Test
    fun theLibrarySendsExactlyThatLineWhenConfiguredTheWayTheAppDoes() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val seen = CompletableFuture<String>()
            thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        // Send our own line first: the order of the two lines is up to the implementation.
                        socket.getOutputStream().apply {
                            write("SSH-2.0-FakeServer\r\n".toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                        val line = ByteArrayOutputStream()
                        val input = socket.getInputStream()
                        while (line.size() < 255) {
                            val b = input.read()
                            if (b < 0 || b == '\n'.code) break
                            line.write(b)
                        }
                        seen.complete(line.toString(Charsets.US_ASCII).trimEnd('\r'))
                    }
                } catch (e: Exception) {
                    seen.completeExceptionally(e)
                }
            }

            val client = SshClient(
                SshClientConfig {
                    host = "127.0.0.1"
                    port = server.localPort
                    handshakeTimeoutMs = 5_000
                    ShelldonSshIdentity.applyTo(this)
                    hostKeyVerifier = object : HostKeyVerifier {
                        override suspend fun verify(key: PublicKey): Boolean = false
                    }
                },
            )
            // The fake server hangs up after the identification line, so connecting fails. Only the line matters here.
            runBlocking {
                try {
                    withTimeout(15_000) { client.connect() }
                } catch (e: TimeoutCancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }

            assertThat(seen.get(10, TimeUnit.SECONDS)).isEqualTo(ShelldonSshIdentity.CLIENT_VERSION)
        }
    }
}
