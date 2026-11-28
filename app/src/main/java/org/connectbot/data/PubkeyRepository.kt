/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025-2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.runBlocking
import org.connectbot.data.dao.PubkeyDao
import org.connectbot.data.entity.KeyStorageType
import org.connectbot.data.entity.Pubkey
import org.connectbot.shelldon.keys.StoredKeys
import org.connectbot.shelldon.vault.Vault
import org.connectbot.shelldon.vault.VaultDataException
import org.connectbot.shelldon.vault.VaultLockedException
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing SSH public/private key pairs.
 * Handles pubkey CRUD operations.
 *
 * Private key material is sealed with the Shelldon [Vault] before it reaches the database and is
 * opened on the way out, so everything above this class sees plain keys. While the vault is locked,
 * keys are returned with a null private key, and saving a key that was read that way never
 * overwrites the sealed data.
 *
 * @param pubkeyDao The DAO for accessing pubkey data
 * @param vault The vault that seals private keys
 */
@Singleton
class PubkeyRepository @Inject constructor(
    private val pubkeyDao: PubkeyDao,
    private val vault: Vault,
) {

    // ============================================================================
    // Sealing
    // ============================================================================

    private fun privateKeyContext(id: UUID): String = "pubkey/$id/private-key"

    /**
     * The key as callers should see it: private key opened, or null while locked or damaged, and
     * the type under its canonical name (an older import stored some providers' OID names).
     */
    private fun Pubkey.opened(): Pubkey = withOpenedPrivateKey().let { it.copy(type = StoredKeys.canonicalType(it.type)) }

    private fun Pubkey.withOpenedPrivateKey(): Pubkey {
        val stored = privateKey
        if (stored == null || !Vault.isSealed(stored)) return this
        return try {
            copy(privateKey = vault.open(stored, privateKeyContext(id)))
        } catch (_: VaultLockedException) {
            copy(privateKey = null)
        } catch (e: VaultDataException) {
            Timber.e(e, "Stored private key %s failed to authenticate", id)
            copy(privateKey = null)
        }
    }

    /** The row to write: private key sealed, never replacing sealed data with nothing. */
    private fun Pubkey.sealedForStorage(existing: Pubkey?): Pubkey {
        val plain = privateKey
        if (plain == null) {
            val old = existing?.privateKey
            return if (old != null && Vault.isSealed(old)) copy(privateKey = old) else this
        }
        if (Vault.isSealed(plain)) return this
        return copy(privateKey = vault.seal(plain, privateKeyContext(id)))
    }

    // ============================================================================
    // Pubkey Operations
    // ============================================================================

    /**
     * Observe all pubkeys reactively.
     *
     * @return Flow of pubkey list that updates automatically
     */
    fun observeAll(): Flow<List<Pubkey>> = combine(pubkeyDao.observeAll(), vault.state) { keys, _ -> keys.map { it.opened() } }

    /**
     * Observe pubkeys by storage type reactively.
     *
     * @param type The storage type (EXPORTABLE or ANDROID_KEYSTORE)
     * @return Flow of pubkey list filtered by type
     */
    fun observeByStorageType(type: KeyStorageType): Flow<List<Pubkey>> = combine(pubkeyDao.observeByStorageType(type), vault.state) { keys, _ -> keys.map { it.opened() } }

    /**
     * Observe a specific pubkey reactively.
     *
     * @param pubkeyId The pubkey ID
     * @return Flow of pubkey that updates automatically
     */
    fun observePubkey(pubkeyId: UUID): Flow<Pubkey?> = combine(pubkeyDao.observeById(pubkeyId), vault.state) { key, _ -> key?.opened() }

    /**
     * Get all pubkeys.
     *
     * @return List of all pubkeys
     */
    suspend fun getAll(): List<Pubkey> = pubkeyDao.getAll().map { it.opened() }

    /**
     * Find a pubkey by its unique ID.
     *
     * @param pubkeyId The pubkey ID
     * @return The pubkey, or null if not found
     */
    suspend fun getById(pubkeyId: UUID): Pubkey? = pubkeyDao.getById(pubkeyId)?.opened()

    /**
     * Find a pubkey by its unique ID (blocking).
     * For Java interop from service layer.
     *
     * @param pubkeyId The pubkey ID
     * @return The pubkey, or null if not found
     */
    fun getByIdBlocking(pubkeyId: UUID): Pubkey? = runBlocking {
        pubkeyDao.getById(pubkeyId)?.opened()
    }

    /**
     * Find a pubkey by nickname.
     *
     * @param nickname The pubkey nickname
     * @return The pubkey, or null if not found
     */
    suspend fun getByNickname(nickname: String): Pubkey? = pubkeyDao.getByNickname(nickname)?.opened()

    /**
     * Get all pubkeys that allow backup.
     *
     * @return List of backupable pubkeys
     */
    suspend fun getBackupable(): List<Pubkey> = pubkeyDao.getBackupable().map { it.opened() }

    /**
     * Get all exportable pubkeys (not stored in Android Keystore).
     *
     * @return List of exportable pubkeys
     */
    suspend fun getExportable(): List<Pubkey> = pubkeyDao.getExportable().map { it.opened() }

    /**
     * Get all pubkeys marked for automatic unlocking at startup.
     *
     * @return List of startup pubkeys
     */
    suspend fun getStartupKeys(): List<Pubkey> = pubkeyDao.getStartupKeys().map { it.opened() }

    /**
     * Get all pubkeys marked for automatic unlocking at startup (blocking).
     * For Java interop from service layer.
     *
     * @return List of startup pubkeys
     */
    fun getStartupKeysBlocking(): List<Pubkey> = runBlocking {
        pubkeyDao.getStartupKeys().map { it.opened() }
    }

    /**
     * Save a pubkey (insert or update).
     *
     * @param pubkey The pubkey to save
     * @return The saved pubkey with updated ID
     * @throws VaultLockedException if the key has private material and the vault is locked
     */
    suspend fun save(pubkey: Pubkey): Pubkey {
        val existing = pubkeyDao.getById(pubkey.id)
        val row = pubkey.sealedForStorage(existing)
        return if (existing == null) {
            // New pubkey - insert
            pubkey.copy(id = pubkeyDao.insert(row))
        } else {
            // Existing pubkey - update
            pubkeyDao.update(row)
            pubkey
        }
    }

    /**
     * Seals private keys that were stored in the clear by earlier versions. Needs the vault
     * unlocked; safe to call repeatedly.
     *
     * @return how many keys were converted
     */
    suspend fun sealLegacyKeys(): Int {
        var converted = 0
        for (row in pubkeyDao.getAll()) {
            val plain = row.privateKey ?: continue
            if (Vault.isSealed(plain)) continue
            pubkeyDao.update(row.copy(privateKey = vault.seal(plain, privateKeyContext(row.id))))
            converted++
        }
        return converted
    }

    /** How many keys have their private part sealed in the vault. Opens none of them. */
    suspend fun countSealedKeys(): Int = pubkeyDao.getAll().count { Vault.isSealed(it.privateKey) }

    /**
     * Deletes every key whose private part is sealed in the vault. Only for resetting a vault whose
     * password is lost: the sealed data can never be opened again.
     *
     * @return how many keys were removed
     */
    suspend fun deleteSealedKeys(): Int {
        var removed = 0
        for (row in pubkeyDao.getAll()) {
            if (Vault.isSealed(row.privateKey)) {
                pubkeyDao.delete(row)
                removed++
            }
        }
        return removed
    }

    /**
     * Delete a pubkey.
     *
     * @param pubkey The pubkey to delete
     */
    suspend fun delete(pubkey: Pubkey) {
        pubkeyDao.delete(pubkey)
    }

    /**
     * Update backup permission for a pubkey.
     *
     * @param pubkeyId The pubkey ID
     * @param allowBackup Whether to allow backup
     */
    suspend fun updateBackupPermission(pubkeyId: UUID, allowBackup: Boolean) {
        pubkeyDao.updateBackupPermission(pubkeyId, allowBackup)
    }
}
