/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.keyinstall

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.shelldon.exec.ExecOutcome
import org.connectbot.shelldon.exec.ExecResult
import org.junit.Test
import java.io.ByteArrayOutputStream

class KeyInstallScriptTest {
    private fun key(nickname: String = "pixel"): AuthorizedKeyLine {
        val out = ByteArrayOutputStream()
        for (field in listOf("ssh-ed25519".toByteArray(), ByteArray(32) { it.toByte() })) {
            out.write(byteArrayOf(0, 0, 0, field.size.toByte()))
            out.write(field)
        }
        return AuthorizedKeyLine.of("ssh-ed25519", out.toByteArray(), nickname)
    }

    private fun result(stdout: String = "", stderr: String = "", exitCode: Int? = 0) = ExecResult(
        stdout = stdout,
        stderr = stderr,
        exitCode = exitCode,
        signal = null,
        stdoutTruncated = false,
        stderrTruncated = false,
        durationMillis = 1,
    )

    @Test
    fun theCommandIsOneLineOfShInsideSingleQuotes() {
        val command = KeyInstallScript.command(key())
        assertThat(command).startsWith("sh -c '").endsWith("'")
        assertThat(command).doesNotContain("\n")
        val inner = command.removePrefix("sh -c '").removeSuffix("'")
        // Nothing that a bash, zsh, fish or csh login shell would read differently inside single quotes.
        assertThat(inner).doesNotContain("'").doesNotContain("`").doesNotContain("!").doesNotContain("\\")
    }

    @Test
    fun theCommandCarriesTheKeyAndBothMarkers() {
        val line = key()
        val command = KeyInstallScript.command(line)
        assertThat(command).contains(line.text).contains(line.keyText)
        assertThat(command).contains(KeyInstallScript.ADDED_MARKER).contains(KeyInstallScript.PRESENT_MARKER)
    }

    @Test
    fun aHostileNicknameChangesNothingButTheComment() {
        val plain = KeyInstallScript.command(key("evil"))
        val hostile = KeyInstallScript.command(key("evil\"; rm -rf ~; echo \"\n'`\$(id)"))
        val inner = hostile.removePrefix("sh -c '").removeSuffix("'")
        assertThat(inner).doesNotContain("'").doesNotContain("`").doesNotContain("\n")
        // Only double quotes of the script itself: same number as with a harmless nickname.
        assertThat(hostile.count { it == '"' }).isEqualTo(plain.count { it == '"' })
        assertThat(hostile.count { it == ';' }).isEqualTo(plain.count { it == ';' })
    }

    @Test
    fun theMarkersAreRecognisedOnTheirOwnLine() {
        assertThat(KeyInstallScript.parse("SHELLDON_KEY_ADDED\n")).isEqualTo(KeyInstallOutcome.Added)
        assertThat(KeyInstallScript.parse("SHELLDON_KEY_PRESENT\n")).isEqualTo(KeyInstallOutcome.AlreadyPresent)
        assertThat(KeyInstallScript.parse("Last login: today\r\nSHELLDON_KEY_ADDED\r\n")).isEqualTo(KeyInstallOutcome.Added)
    }

    @Test
    fun aServerThatEchoesTheCommandBackIsNotTakenForSuccess() {
        val echoed = KeyInstallScript.command(key()) + "\n"
        assertThat(KeyInstallScript.parse(echoed)).isInstanceOf(KeyInstallOutcome.NotConfirmed::class.java)
    }

    @Test
    fun withoutAMarkerTheDetailIsWhatTheServerSaid() {
        val outcome = KeyInstallScript.parse(stdout = "", stderr = "sh: 1: mkdir: Permission denied\n") as KeyInstallOutcome.NotConfirmed
        assertThat(outcome.detail).isEqualTo("sh: 1: mkdir: Permission denied")
        val byCode = KeyInstallScript.parse(stdout = "", stderr = "", exitCode = 127) as KeyInstallOutcome.NotConfirmed
        assertThat(byCode.detail).isEqualTo("exit code 127")
    }

    @Test
    fun theDetailHasNoControlCharactersAndIsShort() {
        val outcome = KeyInstallScript.parse("\u001b[31mred\u0007 " + "x".repeat(1000)) as KeyInstallOutcome.NotConfirmed
        assertThat(outcome.detail).doesNotContain("\u001b").doesNotContain("\u0007")
        assertThat(outcome.detail.length).isLessThanOrEqualTo(200)
    }

    @Test
    fun theInstallerMapsEveryWayItCanEnd() = runTest {
        val line = key()
        assertThat(KeyInstaller.install(line) { ExecOutcome.Finished(result("SHELLDON_KEY_ADDED\n")) }).isEqualTo(KeyInstallResult.Added)
        assertThat(KeyInstaller.install(line) { ExecOutcome.Finished(result("SHELLDON_KEY_PRESENT\n")) }).isEqualTo(KeyInstallResult.AlreadyPresent)
        assertThat(KeyInstaller.install(line) { ExecOutcome.Unavailable }).isEqualTo(KeyInstallResult.NoCommandChannel)
        assertThat(KeyInstaller.install(line) { ExecOutcome.Failed("boom") }).isEqualTo(KeyInstallResult.Failed("boom"))
        assertThat(KeyInstaller.install(line) { throw IllegalStateException("odd") }).isEqualTo(KeyInstallResult.Failed("odd"))
        assertThat(KeyInstaller.install(line) { ExecOutcome.Finished(result("% Invalid input\n", exitCode = 1)) })
            .isEqualTo(KeyInstallResult.NotConfirmed("% Invalid input"))
    }

    @Test
    fun theInstallerSendsTheCommandForThisKey() = runTest {
        val line = key()
        var sent: String? = null
        KeyInstaller.install(line) {
            sent = it
            ExecOutcome.Finished(result("SHELLDON_KEY_ADDED"))
        }
        assertThat(sent).isEqualTo(KeyInstallScript.command(line))
    }
}
