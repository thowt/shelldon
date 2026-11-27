/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("NewApi") // JVM unit tests run on the desktop JDK, not on a device

package org.connectbot.shelldon.container

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.shelldon.container.LiveServer.HOME
import org.connectbot.shelldon.sftp.EditOpen
import org.connectbot.shelldon.sftp.EditRefusal
import org.connectbot.shelldon.sftp.EditSave
import org.connectbot.shelldon.sftp.SftpEdit
import org.connectbot.shelldon.sftp.SftpEditText
import org.connectbot.shelldon.sftp.SftpFailure
import org.connectbot.shelldon.sftp.SftpProblem
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpResult
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test

/**
 * Editing a text file on a real OpenSSH sftp-server, one throw-away container per test. What a fake cannot
 * show: that the overwrite keeps the file's inode, owner and mode, that a read-only file or folder is refused
 * with the right reason, and that no hidden copy is left behind. The server's own `stat`, `cat` and
 * `sha256sum` do the checking.
 */
class SftpEditContainerTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun requireDocker() = LiveServer.requireDocker()

        private const val FILE = "$HOME/edit.conf"
    }

    @get:Rule
    val jdkProviders = JdkProvidersRule()

    private suspend fun LiveServer.Connection.withSftp(block: suspend (SftpClient) -> Unit) {
        val opened = client.openSftp()
        check(opened is SftpResult.Success) { "openSftp() returned $opened" }
        opened.value.use { block(it) }
    }

    private suspend fun SftpClient.opened(path: String = FILE): EditOpen.Opened = SftpEdit.open(this, path) as EditOpen.Opened

    private suspend fun LiveServer.Connection.leftovers(): String = sh("ls -A $HOME | grep shelldon-edit || true").trim()

    @Test
    fun aSaveReplacesTheContentAndKeepsTheFileItself() = LiveServer.withConnection { server ->
        server.sh("printf 'PermitRootLogin no\\nPort 22\\n' > $FILE && chmod 640 $FILE")
        val before = server.sh("stat -c '%i %a %U' $FILE").trim()

        server.withSftp { sftp ->
            val opened = sftp.opened()
            val result = SftpEdit.save(sftp, opened.snapshot, "PermitRootLogin no\nPort 2222\n".toByteArray(), force = false)

            assertThat(result).isInstanceOf(EditSave.Saved::class.java)
        }

        assertThat(server.sh("cat $FILE")).isEqualTo("PermitRootLogin no\nPort 2222\n")
        assertThat(server.sh("stat -c '%i %a %U' $FILE").trim()).describedAs("inode, mode and owner").isEqualTo(before)
        assertThat(server.leftovers()).isEmpty()
    }

    @Test
    fun aShorterTextDoesNotLeaveTheTailOfTheOldOne() = LiveServer.withConnection { server ->
        server.sh("head -c 20000 /dev/zero | tr '\\0' 'a' > $FILE")

        server.withSftp { sftp ->
            SftpEdit.save(sftp, sftp.opened().snapshot, "short\n".toByteArray(), force = false)
        }

        assertThat(server.sh("cat $FILE")).isEqualTo("short\n")
    }

    @Test
    fun crlfAndAByteOrderMarkComeBackExactlyAsTheyWere() = LiveServer.withConnection { server ->
        server.sh("printf '\\357\\273\\277one\\r\\ntwo\\r\\n' > $FILE")

        server.withSftp { sftp ->
            val opened = sftp.opened()
            assertThat(opened.file.text).isEqualTo("one\ntwo\n")
            val edited = SftpEditText.encode("one\ntwo\nthree\n", opened.file)
            SftpEdit.save(sftp, opened.snapshot, edited, force = false)
        }

        assertThat(server.sh("od -An -c $FILE").replace(Regex("\\s+"), " ").trim()).isEqualTo("357 273 277 o n e \\r \\n t w o \\r \\n t h r e e \\r \\n")
    }

    @Test
    fun aFileThatChangedOnTheServerIsNotOverwrittenUnlessForced() = LiveServer.withConnection { server ->
        server.sh("echo original > $FILE")

        server.withSftp { sftp ->
            val opened = sftp.opened()
            Thread.sleep(1100) // modification times have a resolution of one second
            server.sh("echo 'someone else was here' > $FILE")

            val refused = SftpEdit.save(sftp, opened.snapshot, "mine\n".toByteArray(), force = false)
            assertThat(refused).isEqualTo(EditSave.ChangedOnServer)
            assertThat(server.sh("cat $FILE")).isEqualTo("someone else was here\n")
            assertThat(server.leftovers()).isEmpty()

            val forced = SftpEdit.save(sftp, opened.snapshot, "mine\n".toByteArray(), force = true)
            assertThat(forced).isInstanceOf(EditSave.Saved::class.java)
            assertThat(server.sh("cat $FILE")).isEqualTo("mine\n")
        }
    }

    @Test
    fun aCopyIsSavedNextToTheFileWhichStaysAsItWas() = LiveServer.withConnection { server ->
        server.sh("echo original > $FILE")
        val hash = server.sh("sha256sum $FILE")

        server.withSftp { sftp ->
            val copy = SftpEdit.saveCopy(sftp, sftp.opened().snapshot, "mine\n".toByteArray())

            assertThat(copy).isEqualTo("$HOME/edit (edited).conf")
        }

        assertThat(server.sh("sha256sum $FILE")).isEqualTo(hash)
        assertThat(server.sh("cat '$HOME/edit (edited).conf'")).isEqualTo("mine\n")
    }

    @Test
    fun aReadOnlyFileIsRefusedWithTheRightReasonAndNothingIsLeftBehind() = LiveServer.withConnection { server ->
        server.sh("echo original > $FILE && chmod 444 $FILE")

        server.withSftp { sftp ->
            val opened = sftp.opened()

            val failure = runCatching { SftpEdit.save(sftp, opened.snapshot, "mine\n".toByteArray(), force = false) }.exceptionOrNull()

            assertThat(failure).isInstanceOf(SftpFailure::class.java)
            assertThat((failure as SftpFailure).problem).isEqualTo(SftpProblem.PERMISSION_DENIED)
        }

        assertThat(server.sh("cat $FILE")).isEqualTo("original\n")
        assertThat(server.leftovers()).isEmpty()
    }

    @Test
    fun aFolderWeCannotWriteInIsRefusedBeforeAnythingChanges() = LiveServer.withConnection { server ->
        server.sh("mkdir $HOME/ro && echo original > $HOME/ro/edit.conf && chmod 555 $HOME/ro")

        server.withSftp { sftp ->
            val opened = sftp.opened("$HOME/ro/edit.conf")

            val failure = runCatching { SftpEdit.save(sftp, opened.snapshot, "mine\n".toByteArray(), force = false) }.exceptionOrNull()

            assertThat((failure as SftpFailure).problem).isEqualTo(SftpProblem.PERMISSION_DENIED)
        }

        assertThat(server.sh("cat $HOME/ro/edit.conf")).isEqualTo("original\n")
    }

    @Test
    fun filesThatCannotBeEditedAreRefusedWhenTheyAreOpened() = LiveServer.withConnection { server ->
        server.sh("head -c 300000 /dev/zero | tr '\\0' 'a' > $HOME/big.txt && printf '\\177ELF\\000\\001\\002' > $HOME/program.bin && printf 'caf\\351\\n' > $HOME/latin1.txt")

        server.withSftp { sftp ->
            assertThat(SftpEdit.open(sftp, "$HOME/big.txt")).isEqualTo(EditOpen.Refused(EditRefusal.TOO_LARGE))
            assertThat(SftpEdit.open(sftp, "$HOME/program.bin")).isEqualTo(EditOpen.Refused(EditRefusal.BINARY))
            assertThat(SftpEdit.open(sftp, "$HOME/latin1.txt")).isEqualTo(EditOpen.Refused(EditRefusal.NOT_UTF8))
            assertThat(SftpEditText.MAX_BYTES).isLessThan(300_000)
        }
    }

    @Test
    fun aLinkIsNotOpenedForEditing() = LiveServer.withConnection { server ->
        server.sh("echo original > $FILE && ln -s $FILE $HOME/link.conf")

        server.withSftp { sftp ->
            val failure = runCatching { SftpEdit.open(sftp, "$HOME/link.conf") }.exceptionOrNull()

            assertThat(failure).isInstanceOf(SftpFailure::class.java)
        }
    }
}
