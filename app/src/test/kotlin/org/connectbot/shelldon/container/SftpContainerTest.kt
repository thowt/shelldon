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
import org.connectbot.shelldon.sftp.SftpBrowser
import org.connectbot.shelldon.sftp.SftpFailure
import org.connectbot.shelldon.sftp.SftpKind
import org.connectbot.shelldon.sftp.SftpPaths
import org.connectbot.shelldon.sftp.SftpProblem
import org.connectbot.shelldon.sftp.SftpTransfers
import org.connectbot.shelldon.ssh.ShelldonSshIdentity
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshClientConfig
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.Provider
import java.security.Security
import java.util.Random

/**
 * The file browser's core against a real OpenSSH sftp-server, one throw-away container per test.
 * Same switch and same rules as [OpenSshContainerTest]: off unless `SHELLDON_CONTAINER_TESTS=1`, and
 * with it set a missing Docker is a failure.
 *
 * What a fake cannot prove: that the pipelined reads and writes, the folder delete that does not
 * follow links, and the error mapping behave against the server real users have. Content is checked
 * on the server side with `sha256sum`, not only by reading it back through the same code.
 */
class SftpContainerTest {
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

        private const val HOME = "/home/${DockerSshd.USER}"
    }

    // See OpenSshContainerTest: Robolectric leaves Conscrypt first in the shared JVM, and these tests are
    // about files, so they run on the JDK's own providers.
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

    private class Session(val client: SshClient, val sftp: SftpClient) {
        val browser = SftpBrowser(sftp)

        /** Runs a shell command on the server and returns what it printed. */
        suspend fun exec(command: String): String {
            val session = checkNotNull(client.openSession()) { "No session channel" }
            val output = ByteArrayOutputStream()
            session.use {
                assertThat(it.requestExec(command)).isTrue()
                for (chunk in it.stdout) output.write(chunk)
            }
            return output.toString(Charsets.UTF_8)
        }
    }

    private fun withSftp(block: suspend (Session) -> Unit) {
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
                            hostKeyVerifier = object : HostKeyVerifier {
                                override suspend fun verify(key: PublicKey): Boolean = server.hostKeys.any { (type, blob) -> PublicKey(type, blob) == key }
                            }
                        },
                    )
                    try {
                        val connected = client.connect()
                        check(connected == ConnectResult.Success) { "connect() returned $connected\n--- sshd log ---\n${server.logs()}" }
                        assertThat(client.authenticatePassword(DockerSshd.USER, DockerSshd.PASSWORD)).isEqualTo(AuthResult.Success)
                        val opened = client.openSftp()
                        check(opened is SftpResult.Success) { "openSftp() returned $opened\n--- sshd log ---\n${server.logs()}" }
                        opened.value.use { sftp -> block(Session(client, sftp)) }
                    } finally {
                        client.disconnect()
                    }
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun failureOf(block: suspend () -> Unit): SftpFailure? = try {
        block()
        null
    } catch (e: SftpFailure) {
        e
    }

    @Test
    fun theHomeFolderIsTheUsersHome() = withSftp { s ->
        assertThat(s.browser.home()).isEqualTo(HOME)
    }

    @Test
    fun aBigFileSurvivesUploadAndDownloadBitForBit() = withSftp { s ->
        // Not a multiple of the chunk size, and several windows long.
        val data = ByteArray(5 * 1024 * 1024 + 4321).also { Random(7).nextBytes(it) }
        val path = "$HOME/big.bin"

        val sent = SftpTransfers.upload(s.sftp, ByteArrayInputStream(data), path, overwrite = false)
        assertThat(sent).isEqualTo(data.size.toLong())
        // Checked by the server itself, not through the code under test.
        assertThat(s.exec("sha256sum $path").substringBefore(' ')).isEqualTo(sha256(data))
        assertThat(s.browser.size(path)).isEqualTo(data.size.toLong())

        val out = ByteArrayOutputStream()
        val received = SftpTransfers.download(s.sftp, path, out, data.size.toLong())
        assertThat(received).isEqualTo(data.size.toLong())
        assertThat(sha256(out.toByteArray())).isEqualTo(sha256(data))
    }

    @Test
    fun aFileOfUnknownSizeAndAnEmptyFileDownloadToo() = withSftp { s ->
        s.exec("head -c 70000 /dev/urandom > $HOME/random.bin; : > $HOME/empty.bin")
        val expected = s.exec("sha256sum $HOME/random.bin").substringBefore(' ')

        val unknown = ByteArrayOutputStream()
        SftpTransfers.download(s.sftp, "$HOME/random.bin", unknown, expectedSize = null)
        assertThat(unknown.size()).isEqualTo(70000)
        assertThat(sha256(unknown.toByteArray())).isEqualTo(expected)

        val empty = ByteArrayOutputStream()
        assertThat(SftpTransfers.download(s.sftp, "$HOME/empty.bin", empty, expectedSize = 0)).isEqualTo(0L)
        assertThat(empty.size()).isEqualTo(0)
    }

    @Test
    fun anExistingFileIsNotOverwrittenUnlessAskedTo() = withSftp { s ->
        val path = "$HOME/keep.txt"
        SftpTransfers.upload(s.sftp, ByteArrayInputStream("original".toByteArray()), path, overwrite = false)

        val failure = failureOf { SftpTransfers.upload(s.sftp, ByteArrayInputStream("intruder".toByteArray()), path, overwrite = false) }
        assertThat(failure?.problem).isEqualTo(SftpProblem.ALREADY_EXISTS)
        assertThat(s.exec("cat $path")).isEqualTo("original")

        SftpTransfers.upload(s.sftp, ByteArrayInputStream("replaced".toByteArray()), path, overwrite = true)
        assertThat(s.exec("cat $path")).isEqualTo("replaced")
    }

    @Test
    fun listingMarksFoldersFilesAndLinks() = withSftp { s ->
        s.exec("mkdir $HOME/docs && echo hi > $HOME/note.txt && ln -s /etc $HOME/etc-link && ln -s $HOME/note.txt $HOME/note-link && ln -s /nowhere $HOME/dead-link")
        val items = s.browser.list(HOME).associateBy { it.name }

        assertThat(items.getValue("docs").kind).isEqualTo(SftpKind.FOLDER)
        assertThat(items.getValue("note.txt").kind).isEqualTo(SftpKind.FILE)
        assertThat(items.getValue("note.txt").size).isEqualTo(3L)
        assertThat(items.getValue("etc-link").kind).isEqualTo(SftpKind.LINK)
        assertThat(items.getValue("etc-link").opensAsFolder).isTrue()
        assertThat(items.getValue("note-link").opensAsFolder).isFalse()
        assertThat(items.getValue("dead-link").opensAsFolder).isFalse()
        // Folders first.
        val names = s.browser.list(HOME).map { it.name }
        assertThat(names.indexOf("docs") < names.indexOf("note.txt")).isTrue()
        assertThat(names.indexOf("etc-link") < names.indexOf("note.txt")).isTrue()
    }

    @Test
    fun deletingAFolderTreeNeverFollowsALinkOutOfIt() = withSftp { s ->
        s.exec("mkdir -p $HOME/tree/a/b /tmp/outside && touch $HOME/tree/a/b/f /tmp/outside/precious && ln -s /tmp/outside $HOME/tree/a/link")
        val tree = s.browser.list(HOME).first { it.name == "tree" }

        val removed = s.browser.delete(tree)

        assertThat(removed).isEqualTo(5)
        assertThat(s.exec("test ! -e $HOME/tree && echo gone")).isEqualTo("gone\n")
        assertThat(s.exec("test -e /tmp/outside/precious && echo safe")).isEqualTo("safe\n")
    }

    @Test
    fun createRenameAndNamesThatCouldEscape() = withSftp { s ->
        val folder = s.browser.createFolder(HOME, "made")
        assertThat(folder).isEqualTo("$HOME/made")
        assertThat(failureOf { s.browser.createFolder(HOME, "made") }?.problem).isEqualTo(SftpProblem.ALREADY_EXISTS)
        assertThat(failureOf { s.browser.createFolder(HOME, "../escape") }?.problem).isEqualTo(SftpProblem.INVALID_NAME)

        val item = s.browser.list(HOME).first { it.name == "made" }
        assertThat(s.browser.rename(item, "renamed")).isEqualTo("$HOME/renamed")
        assertThat(s.exec("test -d $HOME/renamed && test ! -e $HOME/made && echo ok")).isEqualTo("ok\n")
    }

    @Test
    fun aFolderTheUserMayNotReadIsPermissionDeniedAndAMissingOneIsNotFound() = withSftp { s ->
        assertThat(failureOf { s.browser.list("/root") }?.problem).isEqualTo(SftpProblem.PERMISSION_DENIED)
        assertThat(failureOf { s.browser.list("/no/such/folder") }?.problem).isEqualTo(SftpProblem.NOT_FOUND)
        assertThat(failureOf { SftpTransfers.download(s.sftp, "/no/such/file", ByteArrayOutputStream(), 10) }?.problem).isEqualTo(SftpProblem.NOT_FOUND)
    }

    @Test
    fun namesWithSpacesAndUnicodeWork() = withSftp { s ->
        val name = "räksmörgås med mellanrum.txt"
        SftpTransfers.upload(s.sftp, ByteArrayInputStream("ok".toByteArray()), SftpPaths.child(HOME, name), overwrite = false)
        assertThat(s.browser.list(HOME).any { it.name == name }).isTrue()
        val out = ByteArrayOutputStream()
        SftpTransfers.download(s.sftp, SftpPaths.child(HOME, name), out, 2)
        assertThat(out.toString(Charsets.UTF_8)).isEqualTo("ok")
    }
}
