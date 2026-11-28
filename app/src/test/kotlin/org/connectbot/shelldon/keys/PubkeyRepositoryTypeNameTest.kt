/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.keys

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.ConnectBotDatabase
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.Pubkey
import org.connectbot.shelldon.vault.KdfParams
import org.connectbot.shelldon.vault.Vault
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

/** An older import stored some providers' OID name as the key type; the Keys list showed it raw. */
@RunWith(RobolectricTestRunner::class)
class PubkeyRepositoryTypeNameTest {
    private lateinit var database: ConnectBotDatabase
    private lateinit var repository: PubkeyRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ConnectBotDatabase::class.java).allowMainThreadQueries().build()
        val vault = Vault(File(context.cacheDir, "vault-${UUID.randomUUID()}.hdr"))
        vault.create("test password", KdfParams(8192, 1, 1))
        repository = PubkeyRepository(database.pubkeyDao(), vault)
    }

    @After
    fun tearDown() = database.close()

    private fun storedWithType(type: String) = Pubkey(
        nickname = "work",
        type = type,
        privateKey = null,
        publicKey = byteArrayOf(2),
        encrypted = false,
        startup = false,
        confirmation = false,
        createdDate = 0,
    ).also { runBlocking { database.pubkeyDao().insertRow(it) } }

    @Test
    fun oidTypeIsShownAsEd25519_everywhereKeysAreRead() = runBlocking<Unit> {
        val key = storedWithType("1.3.101.112")

        assertThat(repository.getById(key.id)!!.type).isEqualTo("Ed25519")
        assertThat(repository.getAll().single().type).isEqualTo("Ed25519")
        assertThat(repository.observeAll().first().single().type).isEqualTo("Ed25519")
    }

    @Test
    fun savingAKeyReadThisWayStoresTheCanonicalName() = runBlocking<Unit> {
        val key = storedWithType("1.3.101.112")

        repository.save(repository.getById(key.id)!!.copy(nickname = "renamed"))

        assertThat(database.pubkeyDao().getById(key.id)!!.type).isEqualTo("Ed25519")
    }

    @Test
    fun otherTypeNamesAreLeftAlone() = runBlocking<Unit> {
        val key = storedWithType("sk-ssh-ed25519@openssh.com")

        assertThat(repository.getById(key.id)!!.type).isEqualTo("sk-ssh-ed25519@openssh.com")
    }
}
