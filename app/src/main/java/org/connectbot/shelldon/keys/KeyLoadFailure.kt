/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.keys

import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

/**
 * What to tell the user when a stored private key could not be loaded for login.
 *
 * "Bad password" is only true when the decryption step itself failed, which is what a wrong
 * password does. Any other failure (the key bytes cannot be decoded, no provider handles the key)
 * is reported as an unreadable key, with the kind of failure named so a bug report can say what
 * happened. The text never contains key material or the exception's own message.
 */
object KeyLoadFailure {
    fun message(nickname: String, passwordProtected: Boolean, cause: Throwable): String = if (passwordProtected && isWrongPassword(cause)) {
        "Bad password for key '$nickname'. Authentication failed."
    } else {
        "Could not read key '$nickname' (${cause.javaClass.simpleName}). The stored key data is " +
            "damaged or unsupported. Try importing it again. Authentication failed."
    }

    private fun isWrongPassword(cause: Throwable): Boolean = generateSequence(cause) { it.cause }.take(3).any {
        it is BadPaddingException || it is IllegalBlockSizeException
    }
}
