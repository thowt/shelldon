/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("NewApi") // JVM unit tests run on the desktop JDK, not on a device

package org.connectbot.shelldon.container

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.entity.HostCryptoConfig
import org.connectbot.shelldon.exec.ExecLimits
import org.connectbot.shelldon.exec.SshExec
import org.connectbot.shelldon.ssh.ShelldonSshIdentity
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshClientConfig
import org.junit.Assume.assumeTrue
import org.junit.rules.ExternalResource
import java.security.Provider
import java.security.Security

/**
 * A logged-in connection to a throw-away OpenSSH container, for the container tests of the command
 * runner and the key installer. Same switch as [OpenSshContainerTest]: off unless
 * `SHELLDON_CONTAINER_TESTS=1`, and with it set a missing Docker is a failure.
 */
internal object LiveServer {
    const val HOME = "/home/${DockerSshd.USER}"

    /** Call from a `@BeforeClass`. */
    fun requireDocker() {
        assumeTrue(
            "Set SHELLDON_CONTAINER_TESTS=1 to run the OpenSSH container tests",
            System.getenv("SHELLDON_CONTAINER_TESTS") == "1",
        )
        val reason = Docker.unavailableReason()
        check(reason == null) { "SHELLDON_CONTAINER_TESTS=1 but Docker is not usable: $reason" }
    }

    class Connection(val client: SshClient) {
        /** Runs [command] with the default limits and returns what it printed; fails the test if it did not exit with 0. */
        suspend fun sh(command: String): String {
            val result = SshExec.run(client, command, ExecLimits())
            assertThat(result.exitCode).describedAs("exit code of: %s%n%s", command, result.stdout + result.stderr).isEqualTo(0)
            return result.stdout
        }
    }

    fun withConnection(block: suspend (Connection) -> Unit) {
        DockerSshd.start().use { server ->
            runBlocking {
                withTimeout(180_000) {
                    val client = SshClient(
                        SshClientConfig {
                            host = server.host
                            port = server.port
                            handshakeTimeoutMs = 30_000
                            cryptoPolicy(HostCryptoConfig().resolve())
                            ShelldonSshIdentity.applyTo(this)
                            // As in the app: the library would otherwise hang up when the last channel closes,
                            // and a second command on the same connection would find it gone.
                            autoDisconnectOnLastChannelClose = false
                            hostKeyVerifier = object : HostKeyVerifier {
                                override suspend fun verify(key: PublicKey): Boolean = server.hostKeys.any { (type, blob) -> PublicKey(type, blob) == key }
                            }
                        },
                    )
                    try {
                        val connected = client.connect()
                        check(connected == ConnectResult.Success) { "connect() returned $connected\n--- sshd log ---\n${server.logs()}" }
                        assertThat(client.authenticatePassword(DockerSshd.USER, DockerSshd.PASSWORD)).isEqualTo(AuthResult.Success)
                        block(Connection(client))
                    } finally {
                        client.disconnect()
                    }
                }
            }
        }
    }
}

/**
 * Robolectric leaves Conscrypt first in the shared JVM, and these tests are about commands and files,
 * so they run on the JDK's own providers (see [OpenSshContainerTest]).
 */
class JdkProvidersRule : ExternalResource() {
    private var removed: List<Pair<Int, Provider>> = emptyList()

    override fun before() {
        removed = Security.getProviders().withIndex()
            .filter { it.value.name.contains("conscrypt", ignoreCase = true) }
            .map { it.index to it.value }
        removed.forEach { (_, provider) -> Security.removeProvider(provider.name) }
    }

    override fun after() {
        removed.forEach { (index, provider) -> Security.insertProviderAt(provider, index + 1) }
        removed = emptyList()
    }
}
