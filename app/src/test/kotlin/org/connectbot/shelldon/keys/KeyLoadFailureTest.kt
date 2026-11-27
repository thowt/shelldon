/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.keys

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class KeyLoadFailureTest {
    @Test
    fun passwordProtectedKey_saysBadPassword() {
        assertThat(KeyLoadFailure.message("work", passwordProtected = true)).contains("Bad password for key 'work'")
    }

    @Test
    fun unprotectedKey_doesNotBlameAPassword() {
        val message = KeyLoadFailure.message("work", passwordProtected = false)

        assertThat(message).contains("Could not read key 'work'")
        assertThat(message).doesNotContainIgnoringCase("bad password")
    }
}
