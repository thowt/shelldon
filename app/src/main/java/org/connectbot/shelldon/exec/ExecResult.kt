/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.exec

/** How long a remote command may run and how much of its output is kept. */
data class ExecLimits(
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /** Per stream (standard output and standard error each); the rest is read and thrown away. */
    val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
) {
    init {
        require(timeoutMillis > 0) { "The time limit must be positive" }
        require(maxOutputBytes > 0) { "The output limit must be positive" }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024
    }
}

/** What a remote command did, as far as the server told us. */
data class ExecResult(
    val stdout: String,
    val stderr: String,
    /** The exit code, or null if the command ended on a signal or the server reported nothing. */
    val exitCode: Int?,
    /** The signal that ended the command, such as `KILL`, if it ended on one. */
    val signal: String?,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val durationMillis: Long,
) {
    val succeeded: Boolean get() = exitCode == 0
}

/** How running one command on one server ended, for the cases where it may not have run at all. */
sealed interface ExecOutcome {
    /** The command ran; [result] says how it ended (a non-zero exit code is still [Finished]). */
    data class Finished(val result: ExecResult) : ExecOutcome

    /** There is no live connection that can run commands (not connected, or Telnet, Mosh or a local shell). */
    data object Unavailable : ExecOutcome

    /** The command could not be run: refused, timed out, or the connection broke. [detail] is for the user. */
    data class Failed(val detail: String) : ExecOutcome
}
