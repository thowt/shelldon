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
import org.conscrypt.OpenSSLProvider
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.Provider
import java.security.Security

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

    // All test classes share one JVM, and Robolectric puts Conscrypt first in the JCA provider list for good.
    // These tests are about the app's policy, so they run on the JDK's own providers, the way sshlib's
    // own container tests do. The provider order the app really installs has its own tests below.
    private var removedProviders: List<Pair<Int, Provider>> = emptyList()

    @Before
    fun useTheJdkProviders() {
        removedProviders = Security.getProviders().withIndex()
            .filter { it.value.name.contains("conscrypt", ignoreCase = true) }
            .map { it.index to it.value }
        removedProviders.forEach { (_, provider) -> Security.removeProvider(provider.name) }
    }

    @After
    fun restoreTheProviders() {
        removedProviders.forEach { (index, provider) -> Security.insertProviderAt(provider, index + 1) }
        removedProviders = emptyList()
    }

    /** Does what `ProviderLoader.load` does when the oss flavour starts: Conscrypt goes in first. */
    private fun <T> withTheAppsProviderOrder(block: () -> T): T {
        val conscrypt = OpenSSLProvider()
        Security.insertProviderAt(conscrypt, 1)
        try {
            return block()
        } finally {
            Security.removeProvider(conscrypt.name)
        }
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

    /**
     * Fails with what is needed to understand a refused connection from the CI log alone: the result
     * the client returned, its cause, and the server's own log.
     */
    private fun requireConnected(label: String, outcome: Outcome, server: DockerSshd) {
        val result = outcome.result
        if (result == ConnectResult.Success) return
        val cause = when (result) {
            is ConnectResult.TransportError -> result.cause
            is ConnectResult.ProtocolError -> result.cause
            else -> null
        }
        throw AssertionError("$label: connect() returned $result\nproviders: ${Security.getProviders().map { it.name }}\n--- sshd log ---\n${server.logs()}", cause)
    }

    /**
     * A refusal has to be the client's key-exchange policy speaking. "Anything but success" would also
     * pass for a server that never came up, which proves nothing about the policy.
     */
    private fun requireKexRefused(label: String, outcome: Outcome) {
        assertThat(outcome.result).describedAs(label).isInstanceOf(ConnectResult.AlgorithmMismatch::class.java)
        assertThat((outcome.result as ConnectResult.AlgorithmMismatch).message).describedAs(label).contains("KEX")
    }

    @Test
    fun aNewHostNegotiatesPostQuantumKeyExchangeWithAStockServer() {
        DockerSshd.start().use { server ->
            val outcome = connect(server, HostCryptoConfig())
            requireConnected("default policy", outcome, server)
            assertThat(outcome.info?.kexAlgorithm).matches(POST_QUANTUM.pattern)
            assertThat(outcome.execOutput).isEqualTo("shelldon-ok")
        }
    }

    // Fails on the JVM: with Conscrypt first and the JDK providers behind it, sshlib 0.6.9 completes the key
    // exchange and then cannot decrypt ChaCha20-Poly1305 packets (both sides report corruption). Android has
    // no JDK providers behind Conscrypt, so this may not apply to the phone, and a JVM cannot tell. Remove
    // @Ignore once that is settled on a device. See docs/PLAN.md, "Known limits and risks".
    @Ignore("Open question for Android: sshlib's ChaCha20 with Conscrypt first, see docs/PLAN.md")
    @Test
    fun chaCha20Poly1305WorksWithTheProviderOrderTheAppInstalls() {
        DockerSshd.start("Ciphers=chacha20-poly1305@openssh.com").use { server ->
            val outcome = withTheAppsProviderOrder { connect(server, HostCryptoConfig()) }
            requireConnected("Conscrypt first, chacha20-poly1305", outcome, server)
            assertThat(outcome.execOutput).isEqualTo("shelldon-ok")
        }
    }

    @Test
    fun aesGcmWorksWithTheProviderOrderTheAppInstalls() {
        DockerSshd.start("Ciphers=aes256-gcm@openssh.com").use { server ->
            val outcome = withTheAppsProviderOrder { connect(server, HostCryptoConfig()) }
            requireConnected("Conscrypt first, aes256-gcm", outcome, server)
            assertThat(outcome.execOutput).isEqualTo("shelldon-ok")
        }
    }

    @Test
    fun aServerThatOnlyHasMlKemIsReachableWithTheDefaults() {
        DockerSshd.start("KexAlgorithms=mlkem768x25519-sha256").use { server ->
            val outcome = connect(server, HostCryptoConfig())
            requireConnected("default policy", outcome, server)
            assertThat(outcome.info?.kexAlgorithm).isEqualTo("mlkem768x25519-sha256")
            assertThat(outcome.execOutput).isEqualTo("shelldon-ok")
        }
    }

    @Test
    fun aServerThatOnlyHasSntrupIsReachableWithTheDefaults() {
        DockerSshd.start("KexAlgorithms=sntrup761x25519-sha512,sntrup761x25519-sha512@openssh.com").use { server ->
            val outcome = connect(server, HostCryptoConfig())
            requireConnected("default policy", outcome, server)
            assertThat(outcome.info?.kexAlgorithm).startsWith("sntrup761x25519-sha512")
            assertThat(outcome.execOutput).isEqualTo("shelldon-ok")
        }
    }

    @Test
    fun aClassicalOnlyServerIsReachableByDefaultAndRefusedByTheFuturePreset() {
        DockerSshd.start("KexAlgorithms=curve25519-sha256").use { server ->
            val byDefault = connect(server, HostCryptoConfig())
            requireConnected("default policy", byDefault, server)
            assertThat(byDefault.info?.kexAlgorithm).isEqualTo("curve25519-sha256")

            val future = connect(server, HostCryptoConfig(preset = "FUTURE"))
            requireKexRefused("FUTURE against a classical-only server", future)
            assertThat(future.info).isNull()
        }
    }

    @Test
    fun aLegacyOnlyServerNeedsTheLegacyPresetOnThatHost() {
        DockerSshd.start("KexAlgorithms=diffie-hellman-group14-sha1").use { server ->
            val byDefault = connect(server, HostCryptoConfig())
            requireKexRefused("DEFAULT against a SHA-1-only server", byDefault)
            assertThat(byDefault.info).isNull()

            val legacy = connect(server, HostCryptoConfig(preset = "LEGACY"))
            requireConnected("LEGACY preset", legacy, server)
            assertThat(legacy.info?.kexAlgorithm).isEqualTo("diffie-hellman-group14-sha1")
            assertThat(legacy.execOutput).isEqualTo("shelldon-ok")
        }
    }

    @Test
    fun theServerSeesTheShelldonIdentificationLine() {
        DockerSshd.start("LogLevel=DEBUG1").use { server ->
            val outcome = connect(server, HostCryptoConfig())
            requireConnected("default policy", outcome, server)
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
