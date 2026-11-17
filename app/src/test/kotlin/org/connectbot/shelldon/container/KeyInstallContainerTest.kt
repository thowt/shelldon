/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("NewApi") // JVM unit tests run on the desktop JDK, not on a device

package org.connectbot.shelldon.container

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.shelldon.container.LiveServer.HOME
import org.connectbot.shelldon.exec.ExecOutcome
import org.connectbot.shelldon.exec.SshExec
import org.connectbot.shelldon.keyinstall.AuthorizedKeyLine
import org.connectbot.shelldon.keyinstall.KeyInstallResult
import org.connectbot.shelldon.keyinstall.KeyInstaller
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator

/**
 * Installing a key into `authorized_keys` on a real OpenSSH server, one throw-away container per test.
 * The server side is checked with the server's own tools (`stat`, `cat`, and OpenSSH's `ssh-keygen`
 * reading the file), not only with the code under test.
 */
class KeyInstallContainerTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun requireDocker() = LiveServer.requireDocker()

        private const val DIR = "$HOME/.ssh"
        private const val FILE = "$DIR/authorized_keys"
    }

    @get:Rule
    val jdkProviders = JdkProvidersRule()

    /** A real Ed25519 public key in SSH wire format. */
    private fun realKey(nickname: String): AuthorizedKeyLine {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        // The JDK key is X.509 (a 12 byte header, then the 32 byte key); the SSH form is two length-prefixed fields.
        val raw = pair.public.encoded.takeLast(32).toByteArray()
        val out = ByteArrayOutputStream()
        for (field in listOf("ssh-ed25519".toByteArray(), raw)) {
            out.write(byteArrayOf(0, 0, 0, field.size.toByte()))
            out.write(field)
        }
        return AuthorizedKeyLine.of("ssh-ed25519", out.toByteArray(), nickname)
    }

    private suspend fun LiveServer.Connection.install(line: AuthorizedKeyLine): KeyInstallResult = KeyInstaller.install(line) { command ->
        ExecOutcome.Finished(SshExec.run(client, command))
    }

    @Test
    fun anAccountWithoutAnSshFolderGetsTheFolderTheFileAndTheKey() = LiveServer.withConnection { server ->
        val key = realKey("phone")
        assertThat(server.sh("test -e $DIR && echo yes || echo no")).isEqualTo("no\n")

        assertThat(server.install(key)).isEqualTo(KeyInstallResult.Added)

        assertThat(server.sh("cat $FILE")).isEqualTo(key.text + "\n")
        assertThat(server.sh("stat -c %a $DIR").trim()).isEqualTo("700")
        assertThat(server.sh("stat -c %a $FILE").trim()).isEqualTo("600")
        // OpenSSH itself reads the line as a valid key with the fingerprint we show the user.
        assertThat(server.sh("ssh-keygen -lf $FILE")).contains(key.fingerprint)
    }

    @Test
    fun theSecondRunSaysAlreadyPresentAndChangesNothing() = LiveServer.withConnection { server ->
        val key = realKey("phone")
        assertThat(server.install(key)).isEqualTo(KeyInstallResult.Added)
        val before = server.sh("cat $FILE")

        assertThat(server.install(key)).isEqualTo(KeyInstallResult.AlreadyPresent)

        assertThat(server.sh("cat $FILE")).isEqualTo(before)
        assertThat(server.sh("wc -l < $FILE").trim()).isEqualTo("1")
    }

    @Test
    fun theSameKeyUnderAnotherCommentCountsAsPresent() = LiveServer.withConnection { server ->
        val key = realKey("phone")
        server.sh("mkdir -p $DIR && printf '%s\\n' '${key.keyText} somebody@else' > $FILE")

        assertThat(server.install(key)).isEqualTo(KeyInstallResult.AlreadyPresent)

        assertThat(server.sh("cat $FILE")).isEqualTo("${key.keyText} somebody@else\n")
    }

    @Test
    fun existingKeysStayAndAFileWithoutATrailingNewlineIsNotGluedTogether() = LiveServer.withConnection { server ->
        val key = realKey("phone")
        server.sh("mkdir -p $DIR && printf 'ssh-rsa AAAAEXISTING old-key\\nssh-ed25519 AAAAOTHER no-newline-at-end' > $FILE")

        assertThat(server.install(key)).isEqualTo(KeyInstallResult.Added)

        assertThat(server.sh("cat $FILE")).isEqualTo("ssh-rsa AAAAEXISTING old-key\nssh-ed25519 AAAAOTHER no-newline-at-end\n${key.text}\n")
    }

    @Test
    fun aHostileNicknameAddsOneHarmlessLineAndRunsNothing() = LiveServer.withConnection { server ->
        val key = realKey("x\"; touch $HOME/pwned; echo \"\nssh-ed25519 AAAAEVIL attacker\n'`touch $HOME/pwned2`\$(touch $HOME/pwned3)")

        assertThat(server.install(key)).isEqualTo(KeyInstallResult.Added)

        assertThat(server.sh("wc -l < $FILE").trim()).isEqualTo("1")
        assertThat(server.sh("cat $FILE")).isEqualTo(key.text + "\n")
        assertThat(server.sh("ls -A $HOME | grep -c pwned || true").trim()).isEqualTo("0")
    }

    @Test
    fun aFolderOthersCouldWriteToLosesThatPermissionButKeepsItsContents() = LiveServer.withConnection { server ->
        val key = realKey("phone")
        server.sh("mkdir -m 775 $DIR && : > $FILE && chmod 666 $FILE")

        assertThat(server.install(key)).isEqualTo(KeyInstallResult.Added)

        assertThat(server.sh("stat -c %a $DIR").trim()).isEqualTo("755")
        assertThat(server.sh("stat -c %a $FILE").trim()).isEqualTo("644")
    }

    @Test
    fun aFolderThatCannotBeCreatedIsReportedNotHidden() = LiveServer.withConnection { server ->
        val key = realKey("phone")
        // A plain file where the folder should be.
        server.sh("echo not-a-folder > $DIR")

        val result = server.install(key)

        assertThat(result).isInstanceOf(KeyInstallResult.NotConfirmed::class.java)
        assertThat((result as KeyInstallResult.NotConfirmed).detail).isNotEmpty()
        assertThat(server.sh("cat $DIR")).isEqualTo("not-a-folder\n")
    }

    @Test
    fun theInstalledKeyIsAcceptedWhenSshdChecksItsFileModes() = LiveServer.withConnection { server ->
        val key = realKey("phone")
        assertThat(server.install(key)).isEqualTo(KeyInstallResult.Added)
        // sshd's StrictModes refuses a file or folder others can write to; ours must pass the same test.
        // (The leading test makes this fail, not pass, when the file is not there.)
        assertThat(server.sh("test -f $FILE && find $DIR -perm /022 | wc -l").trim()).isEqualTo("0")
    }
}
