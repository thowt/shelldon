/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.exec

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.sshlib.SessionExit
import org.connectbot.sshlib.SshClient
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Runs one command on a connection that is already logged in, on a new channel (not a new login), and
 * collects what it printed. Nothing here touches Android, so it is tested against a real OpenSSH.
 *
 * The command runs in the account's login shell, exactly as if typed after `ssh host '...'`. Output is
 * not logged anywhere by this code; it may contain secrets and belongs to the caller.
 */
object SshExec {
    private const val EXIT_STATUS_WAIT_MILLIS = 2_000L

    /**
     * @throws IOException if no channel could be opened, the server refused the command, or it did not
     *   finish within [ExecLimits.timeoutMillis]. A command that ran and failed is a normal [ExecResult].
     */
    suspend fun run(client: SshClient, command: String, limits: ExecLimits = ExecLimits()): ExecResult {
        val started = System.nanoTime()
        val session = client.openSession() ?: throw IOException("The server did not open a channel")
        try {
            val finished = withTimeoutOrNull(limits.timeoutMillis) {
                if (!session.requestExec(command)) throw IOException("The server refused to run the command")
                coroutineScope {
                    // Both streams are read at once; a server that fills one while nobody reads it would stall.
                    val errors = async { drain(session.stderr, limits.maxOutputBytes) }
                    val output = drain(session.stdout, limits.maxOutputBytes)
                    output to errors.await()
                }
            } ?: throw IOException("The command did not finish within ${limits.timeoutMillis / 1000} seconds")
            val exit = withTimeoutOrNull(EXIT_STATUS_WAIT_MILLIS) { session.exitInfo.await() }
            val (output, errors) = finished
            return ExecResult(
                stdout = output.text,
                stderr = errors.text,
                // The wire value is an unsigned 32-bit number; anything that is not a plain exit code is reported as none.
                exitCode = (exit as? SessionExit.Status)?.code?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt(),
                signal = (exit as? SessionExit.Signal)?.signalName,
                stdoutTruncated = output.truncated,
                stderrTruncated = errors.truncated,
                durationMillis = (System.nanoTime() - started) / 1_000_000,
            )
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    private class Captured(val text: String, val truncated: Boolean)

    /** Keeps the first [max] bytes and keeps reading after that, so the server is never held up. */
    private suspend fun drain(channel: ReceiveChannel<ByteArray>, max: Int): Captured {
        val buffer = ByteArrayOutputStream()
        var truncated = false
        for (chunk in channel) {
            val room = max - buffer.size()
            if (room > 0) buffer.write(chunk, 0, minOf(room, chunk.size))
            if (chunk.size > room) truncated = true
        }
        return Captured(String(buffer.toByteArray(), Charsets.UTF_8), truncated)
    }
}
