/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("NewApi") // JVM unit tests run on the desktop JDK, not on a device

package org.connectbot.shelldon.container

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.shelldon.exec.ExecLimits
import org.connectbot.shelldon.exec.SshExec
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * The command runner against a real OpenSSH server: what a fake cannot show is that output, errors and the
 * exit code arrive the way a real sshd sends them, that both streams can be busy at once, and that the
 * limits really stop a command.
 */
class ExecContainerTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun requireDocker() = LiveServer.requireDocker()
    }

    @get:Rule
    val jdkProviders = JdkProvidersRule()

    @Test
    fun outputAndAnExitCodeOfZeroComeBack() = LiveServer.withConnection { server ->
        val result = SshExec.run(server.client, "echo hello; uname -s")
        assertThat(result.stdout).isEqualTo("hello\nLinux\n")
        assertThat(result.stderr).isEmpty()
        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.succeeded).isTrue()
        assertThat(result.signal).isNull()
    }

    @Test
    fun errorsAndANonZeroExitCodeAreKeptApart() = LiveServer.withConnection { server ->
        val result = SshExec.run(server.client, "echo out; echo oops 1>&2; exit 3")
        assertThat(result.stdout).isEqualTo("out\n")
        assertThat(result.stderr).isEqualTo("oops\n")
        assertThat(result.exitCode).isEqualTo(3)
        assertThat(result.succeeded).isFalse()
    }

    @Test
    fun aCommandThatDoesNotExistIsAnExitCodeNotAnException() = LiveServer.withConnection { server ->
        val result = SshExec.run(server.client, "no-such-command-here")
        assertThat(result.exitCode).isEqualTo(127)
        assertThat(result.stderr).contains("not found")
    }

    @Test
    fun aCommandKilledBySignalReportsTheSignal() = LiveServer.withConnection { server ->
        val result = SshExec.run(server.client, "kill -TERM \$\$")
        assertThat(result.exitCode).isNull()
        assertThat(result.signal).isNotNull().contains("TERM")
    }

    @Test
    fun textComesBackAsUtf8() = LiveServer.withConnection { server ->
        val result = SshExec.run(server.client, "printf '\\303\\251\\342\\202\\254'")
        assertThat(result.stdout).isEqualTo("é€")
    }

    @Test
    fun outputPastTheLimitIsCutButTheCommandStillFinishes() = LiveServer.withConnection { server ->
        val result = SshExec.run(server.client, "head -c 300000 /dev/zero | tr '\\000' x; echo done 1>&2", ExecLimits(maxOutputBytes = 1000))
        assertThat(result.stdout).hasSize(1000)
        assertThat(result.stdoutTruncated).isTrue()
        assertThat(result.stderr).isEqualTo("done\n")
        assertThat(result.stderrTruncated).isFalse()
        assertThat(result.exitCode).isEqualTo(0)
    }

    @Test
    fun bothStreamsBusyAtOnceDoNotStall() = LiveServer.withConnection { server ->
        val result = SshExec.run(
            server.client,
            "head -c 600000 /dev/zero | tr '\\000' e 1>&2 & head -c 600000 /dev/zero | tr '\\000' o; wait",
            ExecLimits(maxOutputBytes = 700_000),
        )
        assertThat(result.stdout).hasSize(600_000)
        assertThat(result.stderr).hasSize(600_000)
        assertThat(result.exitCode).isEqualTo(0)
    }

    @Test
    fun aCommandThatRunsTooLongIsStoppedWithAClearError() = LiveServer.withConnection { server ->
        val error = try {
            SshExec.run(server.client, "sleep 30", ExecLimits(timeoutMillis = 1500))
            null
        } catch (e: IOException) {
            e
        }
        assertThat(error).isNotNull().hasMessageContaining("did not finish")
        // The connection is still good afterwards.
        assertThat(SshExec.run(server.client, "echo again").stdout).isEqualTo("again\n")
    }

    @Test
    fun severalCommandsRunAtOnceOnOneConnection() = LiveServer.withConnection { server ->
        val outputs = coroutineScope {
            (1..6).map { n -> async { SshExec.run(server.client, "sleep 1; echo $n").stdout } }.awaitAll()
        }
        assertThat(outputs).containsExactly("1\n", "2\n", "3\n", "4\n", "5\n", "6\n")
    }
}
