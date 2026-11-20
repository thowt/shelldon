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
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.KnownHost
import org.connectbot.data.entity.Pubkey
import org.connectbot.data.sync.SyncDatabase
import org.connectbot.shelldon.vault.VaultLockedException
import org.connectbot.util.HostConstants
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import kotlin.io.encoding.Base64

@RunWith(RobolectricTestRunner::class)
class SshImporterTest {
    private lateinit var database: ConnectBotDatabase
    private lateinit var pubkeys: PubkeyRepository
    private lateinit var importer: SshImporter
    private val savedKeys = mutableListOf<Pubkey>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ConnectBotDatabase::class.java).allowMainThreadQueries().build()
        SyncDatabase.install(database.openHelper.writableDatabase)
        pubkeys = mock {
            onBlocking { save(any()) } doAnswer {
                val key = it.getArgument<Pubkey>(0)
                savedKeys.add(key)
                key
            }
        }
        importer = SshImporter(database, pubkeys)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun file(name: String, text: String) = PickedFile(name, text.toByteArray())

    private fun keyFile(name: String, marker: String) = file(name, TestPem.openSsh(marker) + "\n")

    private val decoder = KeyFileDecoder { text, _ ->
        val pub = text.lines()[1].removePrefix("PUB").toByte()
        KeyDecodeResult.Decoded("ssh-ed25519", byteArrayOf(pub), byteArrayOf(pub))
    }

    /** Plans against what is in the database right now, the way [SshImporter.plan] does, with the SSH library's checks replaced. */
    private suspend fun planOf(vararg files: PickedFile): SshImportPlan {
        val hosts = database.hostDao().getAll().map { ExistingHost(it.id, it.nickname, it.hostname, it.port, true) }
        val keys = database.pubkeyDao().getAll().map { ExistingKey(it.id, it.nickname, it.publicKey) }
        val stored = database.knownHostDao().getAll()
            .mapNotNull { known -> known.hostId?.let { it to StoredHostKey(known.hostKeyAlgo, known.hostKey) } }
            .groupBy({ it.first }, { it.second })
        return SshImportPlanner.plan(SshImportInput(files.toList(), hosts, keys, stored), decoder) { true }
    }

    private suspend fun byName() = database.hostDao().getAll().associateBy { it.nickname }

    private val config = """
        Host bastion
            HostName bastion.example.com
            User jump
            Port 2222
            IdentityFile ~/.ssh/id_work

        Host inner
            HostName 10.0.0.5
            User admin
            ProxyJump bastion
            KexAlgorithms +diffie-hellman-group1-sha1
            Ciphers aes128-cbc

        Host chain
            HostName 10.0.0.6
            ProxyJump a,b

        Host *
            ServerAliveInterval 30
    """.trimIndent()

    private val knownHosts = "[bastion.example.com]:2222 ssh-ed25519 $ED1\n10.0.0.5 ssh-ed25519 $ED2\nunrelated.example.com ssh-ed25519 $ED3"

    @Test
    fun addsHostsAlgorithmsKeysAndHostKeysAndLinksTheJumpHost() = runTest {
        val plan = planOf(file("config", config), file("known_hosts", knownHosts), keyFile("id_work", "PUB7"))

        val result = importer.import(plan, SshImportOptions())

        assertThat(result.hostsAdded).isEqualTo(3)
        assertThat(result.hostsSkipped).isEqualTo(0)
        val hosts = byName()
        val bastion = hosts.getValue("bastion")
        assertThat(bastion.hostname).isEqualTo("bastion.example.com")
        assertThat(bastion.username).isEqualTo("jump")
        assertThat(bastion.port).isEqualTo(2222)
        assertThat(bastion.protocol).isEqualTo("ssh")
        assertThat(bastion.crypto.preset).isEqualTo("DEFAULT")
        assertThat(hosts.getValue("inner").jumpHostId).isEqualTo(bastion.id)
        assertThat(result.jumpHostsLinked).isEqualTo(1)
        assertThat(result.jumpHostsUnresolved).containsExactly("chain")
        assertThat(hosts.getValue("chain").jumpHostId).isNull()

        // The algorithm lists became a custom policy on the host that named them, with the DH minimum
        // the 1024-bit group needs; hosts without any keep the default policy.
        val inner = hosts.getValue("inner").crypto
        assertThat(inner.preset).isEqualTo("CUSTOM")
        assertThat(inner.kex).isEqualTo("+diffie-hellman-group1-sha1")
        assertThat(inner.cipher).isEqualTo("aes128-cbc")
        assertThat(inner.minimumDhBits).isEqualTo(1024)
        assertThat(result.hostsWithAlgorithms).isEqualTo(1)

        // The key was saved through the repository (which seals it) and the host that names it uses it.
        assertThat(savedKeys.map { it.nickname }).containsExactly("id_work")
        assertThat(bastion.pubkeyId).isEqualTo(savedKeys.single().id)
        assertThat(hosts.getValue("inner").pubkeyId).isEqualTo(HostConstants.PUBKEYID_ANY)
        assertThat(result.keysImported).isEqualTo(1)
        assertThat(result.hostsLinkedToKey).isEqualTo(1)

        // Host keys went to the hosts they belong to; the unrelated line was not stored anywhere.
        val bastionKeys = database.knownHostDao().getByHostId(bastion.id)
        assertThat(bastionKeys.map { it.hostKeyAlgo }).containsExactly("ssh-ed25519")
        assertThat(bastionKeys.single().hostKey).isEqualTo(Base64.decode(ED1))
        assertThat(bastionKeys.single().port).isEqualTo(2222)
        assertThat(database.knownHostDao().getByHostId(hosts.getValue("inner").id)).hasSize(1)
        assertThat(database.knownHostDao().getAll()).hasSize(2)
        assertThat(result.knownHostsAdded).isEqualTo(2)
    }

    @Test
    fun algorithmsThatOnlyComeFromAWildcardBlockAreLeftOutUnlessAskedFor() = runTest {
        val text = "Host a\n  HostName a.example\nHost *\n  KexAlgorithms +diffie-hellman-group1-sha1"
        importer.import(planOf(file("config", text)), SshImportOptions())
        assertThat(byName().getValue("a").crypto.preset).isEqualTo("DEFAULT")
    }

    @Test
    fun algorithmsFromAWildcardBlockAreAppliedWhenAskedFor() = runTest {
        val text = "Host a\n  HostName a.example\nHost *\n  KexAlgorithms +diffie-hellman-group1-sha1"
        importer.import(planOf(file("config", text)), SshImportOptions(wildcardAlgorithms = true))
        assertThat(byName().getValue("a").crypto.preset).isEqualTo("CUSTOM")
        assertThat(byName().getValue("a").crypto.kex).isEqualTo("+diffie-hellman-group1-sha1")
        assertThat(byName().getValue("a").crypto.minimumDhBits).isEqualTo(1024)
    }

    @Test
    fun algorithmsCanBeSwitchedOff() = runTest {
        importer.import(planOf(file("config", "Host a\n  Ciphers aes128-cbc")), SshImportOptions(algorithms = false))
        assertThat(byName().getValue("a").crypto.preset).isEqualTo("DEFAULT")
        assertThat(byName().getValue("a").crypto.cipher).isNull()
    }

    @Test
    fun keysAndHostKeysCanBeSwitchedOffAndTheHostIsThenNotLinked() = runTest {
        val plan = planOf(file("config", config), file("known_hosts", knownHosts), keyFile("id_work", "PUB7"))

        val result = importer.import(plan, SshImportOptions(keys = false, knownHosts = false))

        assertThat(savedKeys).isEmpty()
        assertThat(database.knownHostDao().getAll()).isEmpty()
        assertThat(byName().getValue("bastion").pubkeyId).isEqualTo(HostConstants.PUBKEYID_ANY)
        assertThat(result.keysImported).isEqualTo(0)
        assertThat(result.knownHostsAdded).isEqualTo(0)
        assertThat(result.hostsLinkedToKey).isEqualTo(0)
    }

    @Test
    fun pubkeyAuthenticationNoTurnsKeysOffForThatHostOnly() = runTest {
        importer.import(planOf(file("config", "Host a\n  PubkeyAuthentication no\nHost b")), SshImportOptions())
        assertThat(byName().getValue("a").useKeys).isFalse()
        assertThat(byName().getValue("b").useKeys).isTrue()
    }

    @Test
    fun anExistingHostIsLeftAloneButStillGetsItsHostKeysAndCanBeAJumpHost() = runTest {
        val mine = Host(nickname = "bastion", hostname = "bastion.example.com", port = 2222, username = "me")
        database.hostDao().insert(mine)

        val result = importer.import(planOf(file("config", config), file("known_hosts", knownHosts)), SshImportOptions())

        assertThat(result.hostsAdded).isEqualTo(2)
        assertThat(result.hostsSkipped).isEqualTo(1)
        val hosts = byName()
        assertThat(hosts.getValue("bastion").id).isEqualTo(mine.id)
        assertThat(hosts.getValue("bastion").username).isEqualTo("me")
        assertThat(hosts.getValue("bastion").pubkeyId).isEqualTo(HostConstants.PUBKEYID_ANY)
        assertThat(hosts.getValue("inner").jumpHostId).isEqualTo(mine.id)
        assertThat(database.knownHostDao().getByHostId(mine.id)).hasSize(1)
    }

    @Test
    fun importingTheSameFilesTwiceAddsNothing() = runTest {
        importer.import(planOf(file("config", config), file("known_hosts", knownHosts)), SshImportOptions())

        val again = importer.import(planOf(file("config", config), file("known_hosts", knownHosts)), SshImportOptions())

        assertThat(again.hostsAdded).isEqualTo(0)
        assertThat(again.hostsSkipped).isEqualTo(3)
        assertThat(again.knownHostsAdded).isEqualTo(0)
        assertThat(database.hostDao().getAll()).hasSize(3)
        assertThat(database.knownHostDao().getAll()).hasSize(2)
    }

    @Test
    fun aHostKeyThatDiffersFromTheStoredOneIsNotReplaced() = runTest {
        val mine = Host(nickname = "web", hostname = "web.example.com")
        database.hostDao().insert(mine)
        database.knownHostDao().insert(
            KnownHost(hostId = mine.id, hostname = "web.example.com", port = 22, hostKeyAlgo = "ssh-ed25519", hostKey = Base64.decode(ED2)),
        )

        val result = importer.import(planOf(file("known_hosts", "web.example.com ssh-ed25519 $ED1")), SshImportOptions())

        assertThat(result.knownHostsAdded).isEqualTo(0)
        assertThat(database.knownHostDao().getByHostId(mine.id).map { it.hostKey.toList() }).containsExactly(Base64.decode(ED2).toList())
    }

    @Test
    fun aJumpHostCanBeNamedByAddress() = runTest {
        val result = importer.import(planOf(file("config", "Host a\n  HostName 1.1.1.1\nHost b\n  HostName 2.2.2.2\n  ProxyJump root@1.1.1.1")), SshImportOptions())
        assertThat(result.jumpHostsLinked).isEqualTo(1)
        val hosts = byName()
        assertThat(hosts.getValue("b").jumpHostId).isEqualTo(hosts.getValue("a").id)
    }

    @Test
    fun aLockedVaultStopsTheImportAndLeavesNothingBehind() = runTest {
        pubkeys = mock { onBlocking { save(any()) } doThrow VaultLockedException() }
        importer = SshImporter(database, pubkeys)

        val failure = runCatching { importer.import(planOf(file("config", "Host a"), keyFile("id_work", "PUB7")), SshImportOptions()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(VaultLockedException::class.java)

        assertThat(database.hostDao().getAll()).isEmpty()
    }
}
