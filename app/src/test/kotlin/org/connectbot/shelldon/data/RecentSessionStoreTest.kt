/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.shelldon.ui.hostlist.QuickConnectTarget
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentSessionStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("shelldon_recent_sessions", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun target(n: Int) = QuickConnectTarget("user", "host$n.lan", 22)

    @Test
    fun record_keepsNewestFirstAndSurvivesARestart() {
        RecentSessionStore(context).apply {
            record(target(1), nowMs = 10)
            record(target(2), nowMs = 20)
        }

        val reloaded = RecentSessionStore(context)

        assertThat(reloaded.entries.value.map { it.target }).containsExactly(target(2), target(1))
    }

    @Test
    fun record_sameDestinationTwiceKeepsOneEntryWithTheNewTime() {
        val store = RecentSessionStore(context)
        store.record(target(1), nowMs = 10)
        store.record(target(1), nowMs = 50)

        assertThat(store.entries.value).containsExactly(RecentTarget(target(1), 50))
    }

    @Test
    fun record_keepsAtMostTenEntries() {
        val store = RecentSessionStore(context)
        (1..14).forEach { store.record(target(it), nowMs = it.toLong()) }

        assertThat(store.entries.value).hasSize(10)
        assertThat(store.entries.value.first().target).isEqualTo(target(14))
    }

    @Test
    fun remove_forgetsTheDestinationOnDiskToo() {
        val store = RecentSessionStore(context)
        store.record(target(1), nowMs = 1)
        store.record(target(2), nowMs = 2)
        store.remove(target(2))

        assertThat(RecentSessionStore(context).entries.value.map { it.target }).containsExactly(target(1))
    }
}
