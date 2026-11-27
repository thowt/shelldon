/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("NewApi") // JVM unit tests run on the desktop JDK, not on a device

package org.connectbot.shelldon.container

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.shelldon.container.LiveServer.HOME
import org.connectbot.shelldon.sftp.FileTree
import org.connectbot.shelldon.sftp.FolderDownload
import org.connectbot.shelldon.sftp.FolderResult
import org.connectbot.shelldon.sftp.RemoteTree
import org.connectbot.shelldon.sftp.SftpProblem
import org.connectbot.shelldon.sftp.SftpTreeScan
import org.connectbot.shelldon.sftp.newTempDir
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpResult
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Downloading a whole folder from a real OpenSSH sftp-server, one throw-away container per test. The
 * server builds the folder with its own tools, and what arrives is compared with `sha256sum` on the
 * server, not only read back through the code under test.
 *
 * What a fake cannot prove: how links, pipes and unreadable entries really show up in a listing, and
 * that file content survives the pipelined reads of a real server.
 */
class SftpFolderContainerTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun requireDocker() = LiveServer.requireDocker()

        private const val ROOT = "$HOME/proj"
    }

    @get:Rule
    val jdkProviders = JdkProvidersRule()

    private val destination: File = newTempDir("shelldon-folder-container")
    private val temp: File = newTempDir("shelldon-folder-container-temp")

    private suspend fun LiveServer.Connection.withSftp(block: suspend (SftpClient) -> Unit) {
        val opened = client.openSftp()
        check(opened is SftpResult.Success) { "openSftp() returned $opened" }
        opened.value.use { block(it) }
    }

    /** A folder with the things real folders have: nesting, empty files, odd names, a big file, links, a pipe and a folder that cannot be read. */
    private suspend fun LiveServer.Connection.buildFolder() {
        sh(
            """
            mkdir -p $ROOT/src/util $ROOT/empty $ROOT/locked &&
            echo hello > $ROOT/readme.txt &&
            : > $ROOT/zero.bin &&
            head -c 3000000 /dev/urandom > $ROOT/big.bin &&
            head -c 4096 /dev/urandom > $ROOT/src/main.dat &&
            head -c 70000 /dev/urandom > "$ROOT/src/util/with space and ünïcode.dat" &&
            echo secret > $ROOT/locked/inside.txt &&
            ln -s /etc $ROOT/link-to-etc &&
            ln -s . $ROOT/loop &&
            mkfifo $ROOT/pipe &&
            chmod 000 $ROOT/locked
            """.trimIndent().replace("\n", " "),
        )
    }

    private suspend fun scan(sftp: SftpClient): RemoteTree = SftpTreeScan.scan(sftp, ROOT, "proj")

    private suspend fun download(sftp: SftpClient, tree: RemoteTree): FolderResult = FolderDownload(sftp, FileTree(), temp).run(ROOT, tree, destination)

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun theScanSeesFilesAndFoldersAndCountsWhatItLeavesOut() = LiveServer.withConnection { server ->
        server.buildFolder()

        server.withSftp { sftp ->
            val tree = scan(sftp)

            assertThat(tree.files.map { it.parts.joinToString("/") }).containsExactly(
                "big.bin",
                "readme.txt",
                "src/main.dat",
                "src/util/with space and ünïcode.dat",
                "zero.bin",
            )
            assertThat(tree.folders).contains(listOf("src"), listOf("src", "util"), listOf("empty"))
            assertThat(tree.linksSkipped).isEqualTo(2)
            assertThat(tree.othersSkipped).isEqualTo(1)
            assertThat(tree.unreadable).isEqualTo(1)
            assertThat(tree.truncated).isFalse()
            assertThat(tree.totalBytes).isEqualTo(3_000_000L + 6 + 4096 + 70_000)
        }
    }

    @Test
    fun everyFileArrivesWithTheSameHashAsOnTheServer() = LiveServer.withConnection { server ->
        server.buildFolder()

        server.withSftp { sftp ->
            val result = download(sftp, scan(sftp))

            assertThat(result.failures).isEmpty()
            assertThat(result.downloaded).isEqualTo(5)
            val local = File(destination, "proj")
            val hashes = server.sh("cd $ROOT && sha256sum big.bin readme.txt src/main.dat zero.bin 'src/util/with space and ünïcode.dat'")
            for (line in hashes.trim().lines()) {
                val (hash, path) = line.split("  ", limit = 2)
                assertThat(sha256(File(local, path))).describedAs(path).isEqualTo(hash)
            }
            assertThat(File(local, "empty").isDirectory).isTrue()
        }
    }

    @Test
    fun linksPipesAndLockedFoldersAreNotCopiedAndNothingOutsideIsReached() = LiveServer.withConnection { server ->
        server.buildFolder()

        server.withSftp { sftp ->
            download(sftp, scan(sftp))

            val local = File(destination, "proj")
            assertThat(File(local, "link-to-etc").exists()).isFalse()
            assertThat(File(local, "loop").exists()).isFalse()
            assertThat(File(local, "pipe").exists()).isFalse()
            assertThat(File(local, "locked").exists()).isFalse()
            assertThat(destination.walkTopDown().none { it.name == "passwd" }).isTrue()
        }
    }

    @Test
    fun aSecondRunFindsEverythingThereAndChangesNothing() = LiveServer.withConnection { server ->
        server.buildFolder()

        server.withSftp { sftp ->
            download(sftp, scan(sftp))
            val before = File(destination, "proj").walkTopDown().filter { it.isFile }.associate { it.path to it.lastModified() }

            val again = download(sftp, scan(sftp))

            assertThat(again.downloaded).isEqualTo(0)
            assertThat(again.alreadyThere).isEqualTo(5)
            assertThat(File(destination, "proj").walkTopDown().filter { it.isFile }.associate { it.path to it.lastModified() }).isEqualTo(before)
        }
    }

    @Test
    fun anUnreadableFileIsReportedAndTheRestStillArrives() = LiveServer.withConnection { server ->
        server.buildFolder()
        server.sh("chmod 000 $ROOT/readme.txt")

        server.withSftp { sftp ->
            val result = download(sftp, scan(sftp))

            assertThat(result.downloaded).isEqualTo(4)
            assertThat(result.failures.map { it.path to it.problem }).containsExactly("readme.txt" to SftpProblem.PERMISSION_DENIED)
            assertThat(File(destination, "proj/readme.txt").exists()).isFalse()
            assertThat(File(destination, "proj/big.bin").length()).isEqualTo(3_000_000L)
            assertThat(temp.listFiles()?.toList().orEmpty()).isEmpty()
        }
    }

    @Test
    fun aFileThatGrewSinceTheScanIsStillDownloadedWholeOrReportedNeverHalf() = LiveServer.withConnection { server ->
        server.buildFolder()

        server.withSftp { sftp ->
            val tree = scan(sftp)
            server.sh("echo more >> $ROOT/readme.txt")

            val result = download(sftp, tree)

            val copy = File(destination, "proj/readme.txt")
            assertThat(result.failures.size + result.downloaded).isEqualTo(5)
            // Either whole as it is now, or reported; never a cut-off copy among the finished files.
            if (copy.exists()) assertThat(sha256(copy)).isEqualTo(server.sh("sha256sum $ROOT/readme.txt").substringBefore(' '))
        }
    }

    @Test
    fun aMissingTopFolderIsAFailureNotAnEmptyResult() = LiveServer.withConnection { server ->
        server.withSftp { sftp ->
            val failure = runCatching { SftpTreeScan.scan(sftp, "$HOME/nope", "nope") }.exceptionOrNull()

            assertThat(failure).isInstanceOf(org.connectbot.shelldon.sftp.SftpFailure::class.java)
            assertThat((failure as org.connectbot.shelldon.sftp.SftpFailure).problem).isEqualTo(SftpProblem.NOT_FOUND)
        }
    }
}
