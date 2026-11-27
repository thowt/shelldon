/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.keys

/**
 * What to tell the user when a stored private key could not be loaded for login.
 *
 * "Bad password" is only true for a key that is protected with a password. For any other key the
 * same failure means the stored data cannot be read, and saying "bad password" sends the user
 * hunting for a password that does not exist. The text never contains key material.
 */
object KeyLoadFailure {
    fun message(nickname: String, passwordProtected: Boolean): String = if (passwordProtected) {
        "Bad password for key '$nickname'. Authentication failed."
    } else {
        "Could not read key '$nickname'. It is not password protected, so the stored key data is " +
            "damaged or unsupported. Try importing it again. Authentication failed."
    }
}
