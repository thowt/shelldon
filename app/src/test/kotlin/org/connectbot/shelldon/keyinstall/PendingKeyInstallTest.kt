/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.keyinstall

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.UUID

class PendingKeyInstallTest {
    private val host = UUID(0, 1)
    private val key = UUID(0, 2)

    @Test
    fun nothingIsPendingAtFirst() {
        assertThat(PendingKeyInstall().request.value).isNull()
    }

    @Test
    fun aRequestIsRememberedAndCleared() {
        val pending = PendingKeyInstall()

        pending.set(host, key, nowMillis = 1_000)
        assertThat(pending.request.value).isEqualTo(PendingKeyInstall.Request(host, key, 1_000))

        pending.clear()
        assertThat(pending.request.value).isNull()
    }

    @Test
    fun aNewRequestReplacesTheOldOne() {
        val pending = PendingKeyInstall()
        pending.set(host, key, nowMillis = 1)

        pending.set(UUID(0, 3), UUID(0, 4), nowMillis = 2)

        assertThat(pending.request.value).isEqualTo(PendingKeyInstall.Request(UUID(0, 3), UUID(0, 4), 2))
    }

    @Test
    fun aRequestOnlyCountsForItsOwnServer() {
        val request = PendingKeyInstall.Request(host, key, createdAtMillis = 1_000)

        assertThat(request.forHost(host, nowMillis = 2_000)).isEqualTo(request)
        assertThat(request.forHost(UUID(0, 9), nowMillis = 2_000)).isNull()
        assertThat(null.forHost(host, nowMillis = 2_000)).isNull()
    }

    @Test
    fun aRequestRunsOutAfterFifteenMinutes() {
        val request = PendingKeyInstall.Request(host, key, createdAtMillis = 1_000)
        val limit = 1_000 + PendingKeyInstall.MAX_AGE_MILLIS

        assertThat(request.forHost(host, nowMillis = limit)).isEqualTo(request)
        assertThat(request.forHost(host, nowMillis = limit + 1)).isNull()
    }

    @Test
    fun aClockThatWentBackwardsDoesNotKeepARequestAlive() {
        val request = PendingKeyInstall.Request(host, key, createdAtMillis = 5_000)

        assertThat(request.forHost(host, nowMillis = 4_000)).isNull()
    }
}
