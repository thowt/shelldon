/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sshconfig

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.ConnectBotDatabase
import org.connectbot.data.entity.Host
import org.connectbot.data.sync.SyncDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SshConfigImporterTest {
    private lateinit var database: ConnectBotDatabase
    private lateinit var importer: SshConfigImporter

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ConnectBotDatabase::class.java).allowMainThreadQueries().build()
        SyncDatabase.install(database.openHelper.writableDatabase)
        importer = SshConfigImporter(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun byName() = database.hostDao().getAll().associateBy { it.nickname }

    private val config = """
        Host bastion
            HostName bastion.example.com
            User jump
            Port 2222

        Host inner
            HostName 10.0.0.5
            User admin
            ProxyJump bastion
            KexAlgorithms +diffie-hellman-group1-sha1

        Host chain
            HostName 10.0.0.6
            ProxyJump a,b

        Host *
            ServerAliveInterval 30
    """.trimIndent()

    @Test
    fun addsHostsWithTheirSettingsAndLinksTheJumpHost() = runTest {
        val result = importer.import(config)

        assertThat(result.added).isEqualTo(3)
        assertThat(result.skipped).isEqualTo(0)
        val hosts = byName()
        val bastion = hosts.getValue("bastion")
        assertThat(bastion.hostname).isEqualTo("bastion.example.com")
        assertThat(bastion.username).isEqualTo("jump")
        assertThat(bastion.port).isEqualTo(2222)
        assertThat(bastion.protocol).isEqualTo("ssh")
        assertThat(hosts.getValue("inner").jumpHostId).isEqualTo(bastion.id)
        assertThat(result.jumpHostsLinked).isEqualTo(1)
        assertThat(result.jumpHostsUnresolved).containsExactly("chain")
        assertThat(hosts.getValue("chain").jumpHostId).isNull()
        assertThat(result.unsupportedOptions).contains("kexalgorithms", "serveraliveinterval")
    }

    @Test
    fun anExistingHostIsLeftAloneAndCanStillBeAJumpHost() = runTest {
        val mine = Host(nickname = "bastion", hostname = "my.bastion", username = "me")
        database.hostDao().insert(mine)

        val result = importer.import(config)

        assertThat(result.added).isEqualTo(2)
        assertThat(result.skipped).isEqualTo(1)
        val hosts = byName()
        assertThat(hosts.getValue("bastion").id).isEqualTo(mine.id)
        assertThat(hosts.getValue("bastion").hostname).isEqualTo("my.bastion")
        assertThat(hosts.getValue("bastion").username).isEqualTo("me")
        assertThat(hosts.getValue("bastion").port).isEqualTo(22)
        assertThat(hosts.getValue("inner").jumpHostId).isEqualTo(mine.id)
    }

    @Test
    fun importingTheSameFileTwiceAddsNothing() = runTest {
        importer.import(config)
        val again = importer.import(config)
        assertThat(again.added).isEqualTo(0)
        assertThat(again.skipped).isEqualTo(3)
        assertThat(database.hostDao().getAll()).hasSize(3)
    }

    @Test
    fun aJumpHostCanBeNamedByAddress() = runTest {
        val result = importer.import("Host a\n  HostName 1.1.1.1\nHost b\n  HostName 2.2.2.2\n  ProxyJump root@1.1.1.1")
        assertThat(result.jumpHostsLinked).isEqualTo(1)
        val hosts = byName()
        assertThat(hosts.getValue("b").jumpHostId).isEqualTo(hosts.getValue("a").id)
    }
}
