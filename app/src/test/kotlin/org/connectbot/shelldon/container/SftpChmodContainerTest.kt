/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("NewApi") // JVM unit tests run on the desktop JDK, not on a device

package org.connectbot.shelldon.container

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.shelldon.sftp.SftpBrowser
import org.connectbot.shelldon.sftp.SftpChmod
import org.connectbot.shelldon.sftp.SftpFailure
import org.connectbot.shelldon.sftp.SftpItem
import org.connectbot.shelldon.sftp.SftpKind
import org.connectbot.shelldon.sftp.SftpMode
import org.connectbot.shelldon.sftp.SftpProblem
import org.connectbot.sshlib.SftpResult
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * Changing permissions through a real OpenSSH sftp-server. A fake cannot show that the server really
 * applies the bits we send (setuid and sticky included), that only the permissions change, or what it
 * answers to a file that is not ours. Every result is read back with `stat` on the server, not through
 * the code under test. Same switch as the other container tests: off unless `SHELLDON_CONTAINER_TESTS=1`.
 */
class SftpChmodContainerTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun requireDocker() = LiveServer.requireDocker()

        private const val HOME = LiveServer.HOME
    }

    @get:Rule
    val jdkProviders = JdkProvidersRule()

    private fun withSftp(block: suspend (LiveServer.Connection, SftpBrowser, SftpChmod) -> Unit) = LiveServer.withConnection { server ->
        val opened = server.client.openSftp()
        check(opened is SftpResult.Success) { "openSftp() returned $opened" }
        opened.value.use { sftp -> block(server, SftpBrowser(sftp), SftpChmod(sftp)) }
    }

    private suspend fun LiveServer.Connection.modeOf(path: String): String = sh("stat -c %a $path").trim()

    private suspend fun SftpBrowser.item(folder: String, name: String): SftpItem = list(folder).first { it.name == name }

    @Test
    fun aFileGetsTheModeAndNothingElseChanges() = withSftp { server, browser, chmod ->
        server.sh("echo hello > $HOME/a.txt; chmod 644 $HOME/a.txt")
        val before = server.sh("stat -c '%U %G %s %Y' $HOME/a.txt")

        val result = chmod.apply(browser.item(HOME, "a.txt"), SftpMode(0b110_000_000), recursive = false)

        assertThat(result.changed).isEqualTo(1)
        assertThat(server.modeOf("$HOME/a.txt")).isEqualTo("600")
        assertThat(server.sh("stat -c '%U %G %s %Y' $HOME/a.txt")).isEqualTo(before)
        assertThat(server.sh("cat $HOME/a.txt")).isEqualTo("hello\n")
    }

    @Test
    fun theSpecialBitsReachTheServer() = withSftp { server, browser, chmod ->
        server.sh("touch $HOME/tool; mkdir $HOME/shared")

        chmod.apply(browser.item(HOME, "tool"), SftpMode.parseOctal("4755")!!, recursive = false)
        chmod.apply(browser.item(HOME, "shared"), SftpMode.parseOctal("1777")!!, recursive = false)

        assertThat(server.modeOf("$HOME/tool")).isEqualTo("4755")
        assertThat(server.modeOf("$HOME/shared")).isEqualTo("1777")
        // And back: the bits are cleared when the mode no longer has them.
        chmod.apply(browser.item(HOME, "tool"), SftpMode.parseOctal("755")!!, recursive = false)
        assertThat(server.modeOf("$HOME/tool")).isEqualTo("755")
    }

    @Test
    fun aRecursiveChangeKeepsFoldersOpenableAndNeverFollowsALink() = withSftp { server, browser, chmod ->
        server.sh("mkdir -p $HOME/tree/sub && touch $HOME/tree/a.txt $HOME/tree/sub/b.txt && chmod 755 $HOME/tree $HOME/tree/sub && chmod 644 $HOME/tree/a.txt $HOME/tree/sub/b.txt")
        // A link out of the tree to a file and to a folder that are not ours; they must stay as they are.
        server.sh("touch /tmp/outside.txt && chmod 644 /tmp/outside.txt && ln -s /tmp/outside.txt $HOME/tree/file-link && ln -s /tmp $HOME/tree/folder-link")
        val tmpBefore = server.modeOf("/tmp")

        val result = chmod.apply(browser.item(HOME, "tree"), SftpMode.parseOctal("640")!!, recursive = true)

        assertThat(result.changed).isEqualTo(4)
        assertThat(result.failed).isEqualTo(0)
        assertThat(server.modeOf("$HOME/tree/a.txt")).isEqualTo("640")
        assertThat(server.modeOf("$HOME/tree/sub/b.txt")).isEqualTo("640")
        assertThat(server.modeOf("$HOME/tree")).isEqualTo("750")
        assertThat(server.modeOf("$HOME/tree/sub")).isEqualTo("750")
        assertThat(server.modeOf("/tmp/outside.txt")).isEqualTo("644")
        assertThat(server.modeOf("/tmp")).isEqualTo(tmpBefore)
        // The tree can still be listed afterwards, which it could not if the folders had lost execute.
        assertThat(browser.list("$HOME/tree/sub").map { it.name }).containsExactly("b.txt")
    }

    @Test
    fun aFolderThatCannotBeListedIsCountedAndTheRestIsStillChanged() = withSftp { server, browser, chmod ->
        server.sh("mkdir -p $HOME/tree2/locked && touch $HOME/tree2/ok.txt $HOME/tree2/locked/hidden.txt && chmod 644 $HOME/tree2/ok.txt && chmod 000 $HOME/tree2/locked")

        val result = chmod.apply(browser.item(HOME, "tree2"), SftpMode.parseOctal("640")!!, recursive = true)

        assertThat(result.failed).isEqualTo(1)
        assertThat(result.firstFailure?.problem).isEqualTo(SftpProblem.PERMISSION_DENIED)
        assertThat(result.changed).isEqualTo(3)
        assertThat(server.modeOf("$HOME/tree2/ok.txt")).isEqualTo("640")
        assertThat(server.modeOf("$HOME/tree2/locked")).isEqualTo("750")
        assertThat(server.modeOf("$HOME/tree2")).isEqualTo("750")
    }

    @Test
    fun aFileThatIsNotOursAndAFileThatIsGoneAreReportedAsWhatTheyAre() = withSftp { _, _, chmod ->
        fun file(path: String) = SftpItem(path.substringAfterLast('/'), path, SftpKind.FILE, size = 1, modified = null, permissions = 0x81A4)

        val notOurs = try {
            chmod.apply(file("/etc/passwd"), SftpMode(0b111_111_111), recursive = false)
            null
        } catch (e: SftpFailure) {
            e
        }
        val gone = try {
            chmod.apply(file("$HOME/does-not-exist"), SftpMode(0b110_100_100), recursive = false)
            null
        } catch (e: SftpFailure) {
            e
        }

        assertThat(notOurs?.problem).isEqualTo(SftpProblem.PERMISSION_DENIED)
        assertThat(gone?.problem).isEqualTo(SftpProblem.NOT_FOUND)
    }
}
