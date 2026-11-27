/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("NewApi") // JVM unit tests run on the desktop JDK, not on a device

package org.connectbot.shelldon.container

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.shelldon.sftp.ChannelSource
import org.connectbot.shelldon.sftp.FilePartial
import org.connectbot.shelldon.sftp.RemoteVersion
import org.connectbot.shelldon.sftp.SftpFailure
import org.connectbot.shelldon.sftp.SftpItems
import org.connectbot.shelldon.sftp.SftpProblem
import org.connectbot.shelldon.sftp.SftpResume
import org.connectbot.shelldon.sftp.SftpTransfers
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpResult
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Random

/**
 * Continuing a transfer that stopped, against a real OpenSSH sftp-server: that the server accepts a
 * write or read from the middle of a file, that the finished file is bit for bit the original (checked
 * with `sha256sum` on the server, not through the code under test), and that a part that does not
 * belong is thrown away instead of being built upon. Same switch as the other container tests.
 */
class SftpResumeContainerTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun requireDocker() = LiveServer.requireDocker()

        private const val HOME = LiveServer.HOME
        private const val SIZE = 6_000_000
    }

    @get:Rule
    val jdkProviders = JdkProvidersRule()

    @get:Rule
    val temp = TemporaryFolder()

    private fun withSftp(block: suspend (LiveServer.Connection, SftpClient) -> Unit) = LiveServer.withConnection { server ->
        val opened = server.client.openSftp()
        check(opened is SftpResult.Success) { "openSftp() returned $opened" }
        opened.value.use { sftp -> block(server, sftp) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun bytes(count: Int, seed: Long) = ByteArray(count).also { Random(seed).nextBytes(it) }

    private suspend fun LiveServer.Connection.serverHash(path: String): String = sh("sha256sum $path").substringBefore(' ')

    private suspend fun versionOf(sftp: SftpClient, path: String): RemoteVersion {
        val attrs = (sftp.stat(path) as SftpResult.Success).value
        return RemoteVersion(checkNotNull(attrs.size), SftpItems.seconds(attrs.mtime))
    }

    @Test
    fun aDownloadContinuesFromWhatIsThereAndEndsBitForBit() = withSftp { server, sftp ->
        val path = "$HOME/big.bin"
        server.sh("head -c $SIZE /dev/urandom > $path")
        val whole = ByteArrayOutputStream().also { SftpTransfers.download(sftp, path, it, SIZE.toLong()) }.toByteArray()
        val partial = FilePartial(temp.newFile("big.part")).also { it.file.writeBytes(whole.copyOf(2_500_000)) }

        val kept = SftpResume.download(sftp, path, versionOf(sftp, path), partial)

        assertThat(kept).isEqualTo(2_500_000L)
        assertThat(sha256(partial.file.readBytes())).isEqualTo(server.serverHash(path))
    }

    @Test
    fun aPartThatDoesNotBelongToTheFileIsNotBuiltUpon() = withSftp { server, sftp ->
        val path = "$HOME/big.bin"
        server.sh("head -c $SIZE /dev/urandom > $path")
        val whole = ByteArrayOutputStream().also { SftpTransfers.download(sftp, path, it, SIZE.toLong()) }.toByteArray()
        // Right length, but a byte near the start is wrong: the checks at spread places catch it.
        val wrong = whole.copyOf(2_500_000).also { it[10] = (it[10] + 1).toByte() }
        val partial = FilePartial(temp.newFile("wrong.part")).also { it.file.writeBytes(wrong) }

        val kept = SftpResume.download(sftp, path, versionOf(sftp, path), partial)

        assertThat(kept).isEqualTo(0L)
        assertThat(sha256(partial.file.readBytes())).isEqualTo(server.serverHash(path))
    }

    @Test
    fun aFileThatChangedSinceTheListIsReportedNotMixed() = withSftp { server, sftp ->
        val path = "$HOME/changing.bin"
        server.sh("head -c 1000000 /dev/urandom > $path")
        val listed = versionOf(sftp, path)
        server.sh("head -c 1000001 /dev/urandom > $path")

        val failure = try {
            SftpResume.download(sftp, path, listed, FilePartial(temp.newFile("changing.part")))
            null
        } catch (e: SftpFailure) {
            e
        }

        assertThat(failure?.problem).isEqualTo(SftpProblem.CHANGED)
    }

    @Test
    fun anUploadContinuesAnUnfinishedOneAndOnlyThenGetsItsName() = withSftp { server, sftp ->
        val data = bytes(SIZE, seed = 1)
        val local = temp.newFile("up.bin").also { it.writeBytes(data) }
        val part = "$HOME/.up.bin.part"
        SftpTransfers.upload(sftp, ByteArrayInputStream(data.copyOf(2_000_000)), part, overwrite = true)

        val source = checkNotNull(ChannelSource.openOrNull { FileInputStream(local) })
        val kept = source.use { SftpResume.upload(sftp, it, "$HOME/up.bin", part) }

        assertThat(kept).isEqualTo(2_000_000L)
        assertThat(server.serverHash("$HOME/up.bin")).isEqualTo(sha256(data))
        assertThat(server.sh("ls -A $HOME | grep -c 'part' || true").trim()).isEqualTo("0")
    }

    @Test
    fun anUnfinishedUploadOfSomethingElseIsStartedAgain() = withSftp { server, sftp ->
        val data = bytes(SIZE, seed = 2)
        val local = temp.newFile("up2.bin").also { it.writeBytes(data) }
        val part = "$HOME/.up2.bin.part"
        SftpTransfers.upload(sftp, ByteArrayInputStream(bytes(1_000_000, seed = 3)), part, overwrite = true)

        val source = checkNotNull(ChannelSource.openOrNull { FileInputStream(local) })
        val kept = source.use { SftpResume.upload(sftp, it, "$HOME/up2.bin", part) }

        assertThat(kept).isEqualTo(0L)
        assertThat(server.serverHash("$HOME/up2.bin")).isEqualTo(sha256(data))
    }

    @Test
    fun anExistingFileIsNeverReplacedByAnUpload() = withSftp { server, sftp ->
        server.sh("echo original > $HOME/taken.bin")
        val local = temp.newFile("taken.bin").also { it.writeBytes(bytes(100_000, seed = 4)) }

        val failure = try {
            val source = checkNotNull(ChannelSource.openOrNull { FileInputStream(local) })
            source.use { SftpResume.upload(sftp, it, "$HOME/taken.bin", "$HOME/.taken.bin.part") }
            null
        } catch (e: SftpFailure) {
            e
        }

        assertThat(failure?.problem).isEqualTo(SftpProblem.ALREADY_EXISTS)
        assertThat(server.sh("cat $HOME/taken.bin")).isEqualTo("original\n")
    }

    @Test
    fun aHiddenNameThatIsAsLongAsAFileNameMayBeWorks() = withSftp { server, sftp ->
        val name = "é".repeat(120) + ".bin"
        val data = bytes(50_000, seed = 5)
        val local = File(temp.root, "long.bin").also { it.writeBytes(data) }
        val source = checkNotNull(ChannelSource.openOrNull { FileInputStream(local) })

        source.use {
            SftpResume.upload(sftp, it, "$HOME/$name", "$HOME/" + SftpResume.uploadPartName(name, it.length, 1_700_000_000L))
        }

        assertThat(server.serverHash("$HOME/$name")).isEqualTo(sha256(data))
    }
}
