/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.connectbot.data.entity.KeyStorageType
import org.connectbot.data.entity.Pubkey
import org.connectbot.shelldon.vault.KdfParams
import org.connectbot.shelldon.vault.UnlockResult
import org.connectbot.shelldon.vault.Vault
import org.connectbot.shelldon.vault.VaultLockedException
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

/** Private keys must be sealed in the database and only readable while the vault is unlocked. */
@RunWith(RobolectricTestRunner::class)
class PubkeyRepositoryVaultTest {
    private lateinit var database: ConnectBotDatabase
    private lateinit var vault: Vault
    private lateinit var repository: PubkeyRepository

    private val password = "test password"
    private val privateBytes = "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n".toByteArray()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ConnectBotDatabase::class.java).allowMainThreadQueries().build()
        vault = Vault(File(context.cacheDir, "vault-${UUID.randomUUID()}.hdr"))
        vault.create(password, KdfParams(8192, 1, 1))
        repository = PubkeyRepository(database.pubkeyDao(), vault)
    }

    @After
    fun tearDown() = database.close()

    private fun key(nickname: String = "work", privateKey: ByteArray? = privateBytes, storage: KeyStorageType = KeyStorageType.EXPORTABLE) = Pubkey(
        nickname = nickname,
        type = "ssh-ed25519",
        privateKey = privateKey,
        publicKey = byteArrayOf(2),
        encrypted = false,
        startup = false,
        confirmation = false,
        createdDate = 0,
        storageType = storage,
    )

    @Test
    fun privateKeyIsSealedAtRestAndPlainWhenRead() = runBlocking {
        val key = key()
        repository.save(key)

        val raw = database.pubkeyDao().getById(key.id)!!
        assertThat(Vault.isSealed(raw.privateKey)).isTrue()
        assertThat(String(raw.privateKey!!, Charsets.ISO_8859_1)).doesNotContain("OPENSSH")

        assertThat(repository.getById(key.id)!!.privateKey).isEqualTo(privateBytes)
        assertThat(repository.getAll().single().privateKey).isEqualTo(privateBytes)
        assertThat(repository.observeAll().first().single().privateKey).isEqualTo(privateBytes)
        assertThat(repository.observePubkey(key.id).first()!!.privateKey).isEqualTo(privateBytes)
        assertThat(repository.getExportable().single().privateKey).isEqualTo(privateBytes)
    }

    @Test
    fun lockedVaultHidesPrivateKeysButKeepsPublicData() = runBlocking {
        val key = key()
        repository.save(key)
        vault.lock()

        val seen = repository.getById(key.id)!!
        assertThat(seen.privateKey).isNull()
        assertThat(seen.nickname).isEqualTo("work")
        assertThat(seen.publicKey).isEqualTo(byteArrayOf(2))
        assertThat(repository.observeAll().first().single().privateKey).isNull()

        assertThat(vault.unlock(password)).isEqualTo(UnlockResult.OK)
        assertThat(repository.getById(key.id)!!.privateKey).isEqualTo(privateBytes)
    }

    @Test
    fun savingWhileLockedNeverStoresPlaintextAndNeverWipesSealedData() = runBlocking {
        val key = key()
        repository.save(key)
        vault.lock()

        // New key with private material: refused, nothing written.
        assertThatThrownBy { runBlocking { repository.save(key(nickname = "other")) } }.isInstanceOf(VaultLockedException::class.java)
        assertThat(database.pubkeyDao().getAll()).hasSize(1)

        // Renaming a key that was read while locked must not erase its sealed private key.
        val readWhileLocked = repository.getById(key.id)!!
        repository.save(readWhileLocked.copy(nickname = "renamed", startup = true))
        vault.unlock(password)
        val after = repository.getById(key.id)!!
        assertThat(after.nickname).isEqualTo("renamed")
        assertThat(after.startup).isTrue()
        assertThat(after.privateKey).isEqualTo(privateBytes)
    }

    @Test
    fun aSealedBlobMovedToAnotherKeyDoesNotOpen() = runBlocking {
        val a = key("a")
        val b = key("b", privateKey = byteArrayOf(9, 9, 9))
        repository.save(a)
        repository.save(b)
        val sealedA = database.pubkeyDao().getById(a.id)!!.privateKey
        database.pubkeyDao().update(database.pubkeyDao().getById(b.id)!!.copy(privateKey = sealedA))

        assertThat(repository.getById(b.id)!!.privateKey).isNull()
        assertThat(repository.getById(a.id)!!.privateKey).isEqualTo(privateBytes)
    }

    @Test
    fun legacyPlaintextKeysAreSealedOnce() = runBlocking {
        val legacy = key("legacy")
        database.pubkeyDao().insert(legacy)
        // Read as-is before conversion.
        assertThat(repository.getById(legacy.id)!!.privateKey).isEqualTo(privateBytes)

        assertThat(repository.sealLegacyKeys()).isEqualTo(1)
        assertThat(Vault.isSealed(database.pubkeyDao().getById(legacy.id)!!.privateKey)).isTrue()
        assertThat(repository.getById(legacy.id)!!.privateKey).isEqualTo(privateBytes)
        assertThat(repository.sealLegacyKeys()).isEqualTo(0)
    }

    @Test
    fun keysWithoutPrivateMaterialAreLeftAlone() = runBlocking {
        val hardware = key("hardware", privateKey = null, storage = KeyStorageType.ANDROID_KEYSTORE)
        repository.save(hardware)
        assertThat(database.pubkeyDao().getById(hardware.id)!!.privateKey).isNull()
        vault.lock()
        repository.save(repository.getById(hardware.id)!!.copy(nickname = "renamed"))
        assertThat(repository.getById(hardware.id)!!.nickname).isEqualTo("renamed")
    }

    @Test
    fun resettingRemovesOnlyKeysThatWereSealed() = runBlocking {
        val sealed = key("sealed")
        val hardware = key("hardware", privateKey = null, storage = KeyStorageType.ANDROID_KEYSTORE)
        repository.save(sealed)
        repository.save(hardware)

        assertThat(repository.deleteSealedKeys()).isEqualTo(1)
        assertThat(repository.getAll().map { it.nickname }).containsExactly("hardware")
    }
}
