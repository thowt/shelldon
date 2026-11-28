/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.keys

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.security.spec.InvalidKeySpecException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

class KeyLoadFailureTest {
    @Test
    fun decryptionFailure_onProtectedKey_saysBadPassword() {
        val message = KeyLoadFailure.message("work", passwordProtected = true, cause = BadPaddingException("x"))

        assertThat(message).contains("Bad password for key 'work'")
    }

    @Test
    fun wrongBlockSize_onProtectedKey_saysBadPassword() {
        val message = KeyLoadFailure.message("work", passwordProtected = true, cause = IllegalBlockSizeException("x"))

        assertThat(message).contains("Bad password for key 'work'")
    }

    @Test
    fun decodeFailure_onProtectedKey_doesNotBlameThePassword() {
        val message = KeyLoadFailure.message("work", passwordProtected = true, cause = InvalidKeySpecException("secret detail"))

        assertThat(message).contains("Could not read key 'work' (InvalidKeySpecException)")
        assertThat(message).doesNotContainIgnoringCase("bad password")
        assertThat(message).doesNotContain("secret detail")
    }

    @Test
    fun anyFailure_onUnprotectedKey_doesNotBlameAPassword() {
        val message = KeyLoadFailure.message("work", passwordProtected = false, cause = BadPaddingException("x"))

        assertThat(message).contains("Could not read key 'work'")
        assertThat(message).doesNotContainIgnoringCase("bad password")
    }

    @Test
    fun wrappedDecryptionFailure_isStillBadPassword() {
        val message = KeyLoadFailure.message("work", passwordProtected = true, cause = RuntimeException(BadPaddingException("x")))

        assertThat(message).contains("Bad password for key 'work'")
    }
}
