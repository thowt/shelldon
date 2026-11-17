/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.keyinstall

/** What a run of the install command said. */
sealed interface KeyInstallOutcome {
    /** The key was appended to `authorized_keys`. */
    data object Added : KeyInstallOutcome

    /** The file already had this key; nothing was changed. */
    data object AlreadyPresent : KeyInstallOutcome

    /** The server did not confirm. [detail] is the first thing it printed, if anything. */
    data class NotConfirmed(val detail: String) : KeyInstallOutcome
}

/**
 * The command that puts a public key into `~/.ssh/authorized_keys` on a Unix-like server, the way
 * `ssh-copy-id` does it, and the reading of its answer.
 *
 * - It adds the key only if it is not in the file yet (matched on type and key data, so the same key under
 *   another comment, or with options in front, counts as present), and never removes or changes a line.
 * - A last line without a line break gets one first, so the new key is not glued onto it.
 * - New files and folders are private to the user (`umask 077`); existing ones only lose group and
 *   other write permission, which sshd insists on anyway.
 * - It is one line of plain `sh` inside `sh -c '...'`, with no single quote, backtick or `!` in it, so it
 *   reads the same under bash, zsh, fish and csh login shells. The key line is the only variable part
 *   and [AuthorizedKeyLine] has already limited it to characters that cannot end a double-quoted string.
 */
object KeyInstallScript {
    const val ADDED_MARKER = "SHELLDON_KEY_ADDED"
    const val PRESENT_MARKER = "SHELLDON_KEY_PRESENT"
    private const val MAX_DETAIL = 200

    fun command(key: AuthorizedKeyLine): String {
        val script = listOf(
            "exec 2>&1",
            "umask 077",
            "d=\"\$HOME/.ssh\"",
            "f=\"\$d/authorized_keys\"",
            "mkdir -p \"\$d\" || exit 1",
            "touch \"\$f\" || exit 1",
            "if grep -qF \"${key.keyText}\" \"\$f\"; then echo $PRESENT_MARKER; exit 0; fi",
            "[ -z \"\$(tail -c 1 \"\$f\")\" ] || echo >> \"\$f\"",
            "echo \"${key.text}\" >> \"\$f\" || exit 1",
            "chmod go-w \"\$d\" \"\$f\"",
            "echo $ADDED_MARKER",
        ).joinToString("; ")
        return "sh -c '$script'"
    }

    /**
     * Looks for a whole line that is a marker, so a server that echoes the command back is not taken for success.
     * Without one, the first thing the server printed (errors first) becomes the detail.
     */
    fun parse(stdout: String, stderr: String = "", exitCode: Int? = null): KeyInstallOutcome {
        val lines = stdout.lineSequence().map { it.trim() }.toList()
        if (PRESENT_MARKER in lines) return KeyInstallOutcome.AlreadyPresent
        if (ADDED_MARKER in lines) return KeyInstallOutcome.Added
        val said = (stderr.lineSequence() + lines.asSequence()).map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val detail = said.filter { !it.isISOControl() }.take(MAX_DETAIL)
        return KeyInstallOutcome.NotConfirmed(detail.ifEmpty { exitCode?.let { "exit code $it" }.orEmpty() })
    }
}
