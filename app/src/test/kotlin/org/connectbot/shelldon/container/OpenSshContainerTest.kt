/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.container

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.entity.HostCryptoConfig
import org.connectbot.shelldon.ssh.ShelldonSshIdentity
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.ConnectionInfo
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshClientConfig
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The app's crypto settings against a real OpenSSH server, one throw-away container per test.
 *
 * Off by default, because it needs Docker and a few minutes. Run it with
 * `SHELLDON_CONTAINER_TESTS=1 ./gradlew :app:testOssDebugUnitTest --tests 'org.connectbot.shelldon.container.*'`.
 * With the variable set, a missing Docker is a failure and not a skip, so CI cannot go green without
 * having tested anything.
 *
 * The library (sshlib) runs its own container tests against OpenSSH 3.7 to 7.4 and current releases.
 * These cover what is ours: the per-host policy presets as stored by the app and the identification
 * line, driven through the same classes the SSH transport uses ([HostCryptoConfig.resolve],
 * [ShelldonSshIdentity]) and the same client calls. Host keys are checked against what the server
 * really has; there is no accept-all verifier, not even here.
 */
class OpenSshContainerTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun requireDocker() {
            assumeTrue(
                "Set SHELLDON_CONTAINER_TESTS=1 to run the OpenSSH container tests",
                System.getenv("SHELLDON_CONTAINER_TESTS") == "1",
            )
            val reason = Docker.unavailableReason()
            check(reason == null) { "SHELLDON_CONTAINER_TESTS=1 but Docker is not usable: $reason" }
        }

        private val POST_QUANTUM = Regex("(mlkem|sntrup).*")
    }

    private class Outcome(val result: ConnectResult, val info: ConnectionInfo?, val execOutput: String?)

    /** Connects the way the SSH transport does, logs in with the container's password and runs one command. */
    private fun connect(server: DockerSshd, crypto: HostCryptoConfig): Outcome = runBlocking {
        withTimeout(90_000) {
            val policy = crypto.resolve()
            val client = SshClient(
                SshClientConfig {
                    host = server.host
                    port = server.port
                    handshakeTimeoutMs = 30_000
                    cryptoPolicy(policy)
                    ShelldonSshIdentity.applyTo(this)
                    hostKeyVerifier = object : HostKeyVerifier {
                        override suspend fun verify(key: PublicKey): Boolean = server.hostKeys.any { (type, blob) -> PublicKey(type, blob) == key }
                    }
                },
            )
            try {
                val result = client.connect()
                if (result != ConnectResult.Success) return@withTimeout Outcome(result, null, null)
                val info = client.connectionInfo
                assertThat(client.authenticatePassword(DockerSshd.USER, DockerSshd.PASSWORD)).isEqualTo(AuthResult.Success)
                val session = checkNotNull(client.openSession()) { "No session channel" }
                val output = ByteArrayOutputStream()
                session.use {
                    assertThat(it.requestExec("printf shelldon-ok")).isTrue()
                    for (chunk in it.stdout) output.write(chunk)
                }
                Outcome(result, info, output.toString(Charsets.US_ASCII))
            } finally {
                client.disconnect()
            }
        }
    }

    @Test
    fun aNewHostNegotiatesPostQuantumKeyExchangeWithAStockServer() {
        DockerSshd.start().use { server ->
            val outcome = connect(server, HostCryptoConfig())
            assertThat(outcome.result).describedAs("%s", server.logs()).isEqualTo(ConnectResult.Success)
            assertThat(outcome.info?.kexAlgorithm).matches(POST_QUANTUM.pattern)
            assertThat(outcome.execOutput).isEqualTo("shelldon-ok")
        }
    }

    @Test
    fun aServerThatOnlyHasMlKemIsReachableWithTheDefaults() {
        DockerSshd.start("KexAlgorithms=mlkem768x25519-sha256").use { server ->
            val outcome = connect(server, HostCryptoConfig())
            assertThat(outcome.result).describedAs("%s", server.logs()).isEqualTo(ConnectResult.Success)
            assertThat(outcome.info?.kexAlgorithm).isEqualTo("mlkem768x25519-sha256")
            assertThat(outcome.execOutput).isEqualTo("shelldon-ok")
        }
    }

    @Test
    fun aServerThatOnlyHasSntrupIsReachableWithTheDefaults() {
        DockerSshd.start("KexAlgorithms=sntrup761x25519-sha512,sntrup761x25519-sha512@openssh.com").use { server ->
            val outcome = connect(server, HostCryptoConfig())
            assertThat(outcome.result).describedAs("%s", server.logs()).isEqualTo(ConnectResult.Success)
            assertThat(outcome.info?.kexAlgorithm).startsWith("sntrup761x25519-sha512")
            assertThat(outcome.execOutput).isEqualTo("shelldon-ok")
        }
    }

    @Test
    fun aClassicalOnlyServerIsReachableByDefaultAndRefusedByTheFuturePreset() {
        DockerSshd.start("KexAlgorithms=curve25519-sha256").use { server ->
            val byDefault = connect(server, HostCryptoConfig())
            assertThat(byDefault.result).describedAs("%s", server.logs()).isEqualTo(ConnectResult.Success)
            assertThat(byDefault.info?.kexAlgorithm).isEqualTo("curve25519-sha256")

            val future = connect(server, HostCryptoConfig(preset = "FUTURE"))
            assertThat(future.result).describedAs("FUTURE against a classical-only server").isNotEqualTo(ConnectResult.Success)
            assertThat(future.info).isNull()
        }
    }

    @Test
    fun aLegacyOnlyServerNeedsTheLegacyPresetOnThatHost() {
        DockerSshd.start("KexAlgorithms=diffie-hellman-group14-sha1").use { server ->
            val byDefault = connect(server, HostCryptoConfig())
            assertThat(byDefault.result).describedAs("DEFAULT against a SHA-1-only server").isNotEqualTo(ConnectResult.Success)
            assertThat(byDefault.info).isNull()

            val legacy = connect(server, HostCryptoConfig(preset = "LEGACY"))
            assertThat(legacy.result).describedAs("%s", server.logs()).isEqualTo(ConnectResult.Success)
            assertThat(legacy.info?.kexAlgorithm).isEqualTo("diffie-hellman-group14-sha1")
            assertThat(legacy.execOutput).isEqualTo("shelldon-ok")
        }
    }

    @Test
    fun theServerSeesTheShelldonIdentificationLine() {
        DockerSshd.start("LogLevel=DEBUG1").use { server ->
            val outcome = connect(server, HostCryptoConfig())
            assertThat(outcome.result).describedAs("%s", server.logs()).isEqualTo(ConnectResult.Success)
            // sshd logs the line at debug level; the log is written asynchronously, so give it a moment.
            val deadline = System.nanoTime() + 10_000_000_000L
            var logs = server.logs()
            while ("remote software version ShelldonSSH" !in logs && System.nanoTime() < deadline) {
                Thread.sleep(250)
                logs = server.logs()
            }
            assertThat(logs).contains("remote software version ShelldonSSH")
            assertThat(logs.lowercase()).doesNotContain("cbssh")
        }
    }
}
