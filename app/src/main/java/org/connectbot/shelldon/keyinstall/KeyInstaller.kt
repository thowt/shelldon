/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.keyinstall

import org.connectbot.data.entity.Pubkey
import org.connectbot.shelldon.exec.ExecOutcome
import org.connectbot.sshlib.SshSigning
import org.connectbot.util.PubkeyUtils
import java.security.GeneralSecurityException
import java.security.KeyPair
import kotlin.coroutines.cancellation.CancellationException

/** How a try to install a key ended. */
sealed interface KeyInstallResult {
    data object Added : KeyInstallResult

    data object AlreadyPresent : KeyInstallResult

    /** The key cannot be turned into an `authorized_keys` line (an imported key of unknown type, say). */
    data object KeyUnusable : KeyInstallResult

    /** The connection is gone, or is not one that can run commands (Telnet, Mosh, a local shell). */
    data object NoCommandChannel : KeyInstallResult

    /** The command could not be run: refused, timed out, or the connection broke. */
    data class Failed(val detail: String) : KeyInstallResult

    /** The command ran but the server did not say it installed the key, for instance because its shell is not a Unix shell. */
    data class NotConfirmed(val detail: String) : KeyInstallResult
}

/** The public half of a stored key as an `authorized_keys` line, or null if it has none that can be used. */
fun Pubkey.toAuthorizedKeyLine(): AuthorizedKeyLine? = try {
    if (isFido2) {
        // The stored bytes are already in SSH wire format; only the type name needs the OpenSSH spelling.
        val sshType = when (type) {
            "SK-Ed25519" -> "sk-ssh-ed25519@openssh.com"
            "SK-ECDSA" -> "sk-ecdsa-sha2-nistp256@openssh.com"
            else -> type
        }
        AuthorizedKeyLine.of(sshType, publicKey, nickname)
    } else if (type == "IMPORTED") {
        null
    } else {
        val encoded = SshSigning.encodePublicKey(KeyPair(PubkeyUtils.decodePublic(publicKey, type), null))
        AuthorizedKeyLine.of(encoded.algorithmName, encoded.publicKeyBlob, nickname)
    }
} catch (e: IllegalArgumentException) {
    null
} catch (e: GeneralSecurityException) {
    null
}

/** Puts a public key into `authorized_keys` through whatever can run a command on the server. */
object KeyInstaller {
    /**
     * @param run runs one command on the server, see [org.connectbot.shelldon.exec.SshExec]
     * @return how it ended; never throws except for cancellation
     */
    suspend fun install(key: AuthorizedKeyLine, run: suspend (String) -> ExecOutcome): KeyInstallResult {
        val outcome = try {
            run(KeyInstallScript.command(key))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return KeyInstallResult.Failed(e.message ?: e.javaClass.simpleName)
        }
        return when (outcome) {
            ExecOutcome.Unavailable -> KeyInstallResult.NoCommandChannel

            is ExecOutcome.Failed -> KeyInstallResult.Failed(outcome.detail)

            is ExecOutcome.Finished -> when (val read = KeyInstallScript.parse(outcome.result.stdout, outcome.result.stderr, outcome.result.exitCode)) {
                KeyInstallOutcome.Added -> KeyInstallResult.Added
                KeyInstallOutcome.AlreadyPresent -> KeyInstallResult.AlreadyPresent
                is KeyInstallOutcome.NotConfirmed -> KeyInstallResult.NotConfirmed(read.detail)
            }
        }
    }
}
