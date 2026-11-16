/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.ConnectBotDatabase
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.Pubkey
import org.connectbot.data.sync.SyncDatabase
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.shelldon.data.groups.Outcome
import org.connectbot.shelldon.data.groups.ShelldonGroupRepository
import org.connectbot.shelldon.vault.KdfParams
import org.connectbot.shelldon.vault.Vault
import org.connectbot.util.HostConstants
import org.connectbot.util.SecurePasswordStorage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

/** Backs up one phone and restores onto another, each with its own database and vault. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShelldonBackupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fast = KdfParams(memoryKib = 8 * 1024, iterations = 1, parallelism = 1)
    private val dispatcher = UnconfinedTestDispatcher()
    private val privateBytes = "-----BEGIN OPENSSH PRIVATE KEY-----\nfake key\n".toByteArray()
    private val backupPassword = "backup passphrase"
    private val devices = mutableListOf<Device>()

    private inner class Device(name: String) {
        val database: ConnectBotDatabase = Room.inMemoryDatabaseBuilder(context, ConnectBotDatabase::class.java).allowMainThreadQueries().build()
        val vault = Vault(File(context.cacheDir, "$name-${UUID.randomUUID()}.hdr")).also { it.create("master password", fast) }
        val passwords = SecurePasswordStorage(context, vault)
        val pubkeys = PubkeyRepository(database.pubkeyDao(), vault)
        val groups = ShelldonGroupRepository(database)
        val backup = ShelldonBackup(context, database, passwords, pubkeys, CoroutineDispatchers(dispatcher, dispatcher, dispatcher)).also { it.kdfParams = fast }

        init {
            SyncDatabase.install(database.openHelper.writableDatabase)
            devices.add(this)
        }

        suspend fun host(nickname: String, hostname: String = "$nickname.lan", configure: (Host) -> Host = { it }): Host = configure(Host(nickname = nickname, hostname = hostname, username = "")).also { database.hostDao().insert(it) }

        suspend fun hostByName(nickname: String): Host = database.hostDao().getAll().single { it.nickname == nickname }
    }

    @Before
    fun clearPasswords() {
        context.getSharedPreferences("secure_host_passwords", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun closeDatabases() {
        devices.forEach { it.database.close() }
    }

    private class Source(val router: Host, val key: Pubkey)

    /** A small estate: nested folders, a tag, an identity with a key, one host using all of it, one plain host. */
    private suspend fun Device.populate(): Source {
        val key = Pubkey(nickname = "work", type = "ssh-ed25519", privateKey = privateBytes, publicKey = byteArrayOf(2), encrypted = false, startup = false, confirmation = false, createdDate = 7)
        pubkeys.save(key)
        val identity = (groups.createIdentity("admin", "root", key.id) as Outcome.Ok).value
        val lab = (groups.createGroup("Lab", null) as Outcome.Ok).value
        val switches = (groups.createGroup("Switches", lab.id) as Outcome.Ok).value
        val tag = (groups.createTag("prod") as Outcome.Ok).value
        val router = host("router") { it.copy(pubkeyId = key.id, groupId = switches.id, identityId = identity.id) }
        groups.setHostTags(router.id, setOf(tag.id))
        host("plain")
        passwords.savePassword(router.id, "hunter2")
        return Source(router, key)
    }

    @Test
    fun restoresTheWholeEstateOntoAnotherPhoneWithNewIds() = runTest {
        val source = Device("source")
        val original = source.populate()
        val file = source.backup.export(backupPassword, BackupOptions(includeKeys = true, includePasswords = true))

        val target = Device("target")
        val result = target.backup.import(file, backupPassword)

        assertThat(result).isEqualTo(BackupImportResult(hostsImported = 2, hostsSkipped = 0, keysImported = 1, keysSkipped = 0, foldersImported = 2, tagsImported = 1, identitiesImported = 1, passwordsRestored = 1))

        val router = target.hostByName("router")
        assertThat(router.id).isNotEqualTo(original.router.id)
        val snapshot = target.groups.snapshot()
        val switches = snapshot.groups.single { it.name == "Switches" }
        assertThat(router.groupId).isEqualTo(switches.id)
        assertThat(snapshot.groupsById.getValue(switches.parentId!!).name).isEqualTo("Lab")
        assertThat(snapshot.groupsById.getValue(switches.parentId!!).parentId).isNull()

        val identity = snapshot.identities.single()
        assertThat(router.identityId).isEqualTo(identity.id)
        assertThat(identity.username).isEqualTo("root")

        val key = target.pubkeys.getAll().single()
        assertThat(key.id).isNotEqualTo(original.key.id)
        assertThat(key.privateKey).isEqualTo(privateBytes)
        assertThat(router.pubkeyId).isEqualTo(key.id)
        assertThat(identity.keyId).isEqualTo(key.id)

        assertThat(snapshot.tagIdsByHost[router.id]).containsExactly(snapshot.tags.single().id)
        assertThat(target.passwords.getPassword(router.id)).isEqualTo("hunter2")
        assertThat(target.hostByName("plain").groupId).isNull()
    }

    @Test
    fun restoringTwiceAddsNothing() = runTest {
        val source = Device("source")
        source.populate()
        val file = source.backup.export(backupPassword, BackupOptions(includeKeys = true, includePasswords = true))
        val target = Device("target")
        target.backup.import(file, backupPassword)

        val again = target.backup.import(file, backupPassword)

        assertThat(again).isEqualTo(BackupImportResult(hostsImported = 0, hostsSkipped = 2, keysImported = 0, keysSkipped = 1, foldersImported = 0, tagsImported = 0, identitiesImported = 0, passwordsRestored = 0))
        assertThat(target.database.hostDao().getAll()).hasSize(2)
        assertThat(target.groups.snapshot().groups).hasSize(2)
        assertThat(target.pubkeys.getAll()).hasSize(1)
    }

    @Test
    fun aHostYouAlreadyHaveIsNeverChanged() = runTest {
        val source = Device("source")
        source.populate()
        val file = source.backup.export(backupPassword, BackupOptions(includeKeys = true, includePasswords = true))

        val target = Device("target")
        val mine = target.host("router", hostname = "mine.example")
        target.passwords.savePassword(mine.id, "my own password")
        val result = target.backup.import(file, backupPassword)

        assertThat(result.hostsSkipped).isEqualTo(1)
        assertThat(result.passwordsRestored).isEqualTo(0)
        val router = target.hostByName("router")
        assertThat(router.id).isEqualTo(mine.id)
        assertThat(router.hostname).isEqualTo("mine.example")
        assertThat(router.groupId).isNull()
        assertThat(router.identityId).isNull()
        assertThat(router.pubkeyId).isEqualTo(HostConstants.PUBKEYID_ANY)
        assertThat(target.groups.snapshot().tagIdsByHost[router.id]).isNull()
        assertThat(target.passwords.getPassword(router.id)).isEqualTo("my own password")
    }

    @Test
    fun keysAndPasswordsAreLeftOutUnlessAskedFor() = runTest {
        val source = Device("source")
        source.populate()
        val file = source.backup.export(backupPassword, BackupOptions(includeKeys = false, includePasswords = false))

        val target = Device("target")
        val result = target.backup.import(file, backupPassword)

        assertThat(result.keysImported).isEqualTo(0)
        assertThat(result.passwordsRestored).isEqualTo(0)
        assertThat(target.pubkeys.getAll()).isEmpty()
        val router = target.hostByName("router")
        assertThat(router.pubkeyId).isEqualTo(HostConstants.PUBKEYID_ANY)
        assertThat(target.passwords.hasPassword(router.id)).isFalse()
        assertThat(target.groups.snapshot().identities.single().keyId).isNull()
        // Folders, tags and the host's place in them still come across.
        assertThat(router.groupId).isNotNull()
    }

    @Test
    fun theFileIsEncryptedAndNeedsTheRightPassword() = runTest {
        val source = Device("source")
        source.populate()
        val file = source.backup.export(backupPassword, BackupOptions(includeKeys = true, includePasswords = true))
        val text = String(file, Charsets.ISO_8859_1)
        assertThat(text).doesNotContain("router").doesNotContain("hunter2").doesNotContain("Switches")

        val target = Device("target")
        val failure = runCatching { target.backup.import(file, "not the passphrase") }.exceptionOrNull()
        assertThat(failure).isInstanceOf(BackupException::class.java)
        assertThat((failure as BackupException).problem).isEqualTo(BackupProblem.WRONG_PASSWORD_OR_DAMAGED)
        assertThat(target.database.hostDao().getAll()).isEmpty()
    }

    @Test
    fun aLockedVaultRefusesToExportKeysOrRestoreThem() = runTest {
        val source = Device("source")
        source.populate()
        val file = source.backup.export(backupPassword, BackupOptions(includeKeys = true, includePasswords = true))

        val target = Device("target")
        target.vault.lock()
        val failure = runCatching { target.backup.import(file, backupPassword) }.exceptionOrNull()

        assertThat(failure).isNotNull()
        // The restore is one transaction: nothing was left behind.
        assertThat(target.database.hostDao().getAll()).isEmpty()
        assertThat(target.groups.snapshot().groups).isEmpty()
    }
}
