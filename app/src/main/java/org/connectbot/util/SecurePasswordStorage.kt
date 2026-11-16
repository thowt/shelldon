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

package org.connectbot.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import org.connectbot.shelldon.vault.Vault
import org.connectbot.shelldon.vault.VaultDataException
import org.connectbot.shelldon.vault.VaultLockedException
import timber.log.Timber
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for host login passwords.
 *
 * Passwords are sealed with the Shelldon vault (AES-256-GCM under the master-password key, bound
 * to the host id) and kept in a private preferences file, keyed by host ID. They are separate from
 * the database and are never part of JSON exports.
 *
 * Passwords saved by earlier versions were encrypted with a plain Android Keystore key that needs
 * no authentication; [sealLegacyEntries] moves them under the vault the first time it is unlocked,
 * and until then they stay unreadable.
 */
@Singleton
class SecurePasswordStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vault: Vault,
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }
    }

    /** The pre-vault Keystore key, if this install still has one. Never created any more. */
    private fun legacyKey(): SecretKey? = (keyStore.getEntry(LEGACY_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun decryptLegacy(encryptedData: String): String? = try {
        val secretKey = legacyKey() ?: throw IllegalStateException("legacy key is gone")
        val combined = Base64.decode(encryptedData, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val encryptedBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
    } catch (e: Exception) {
        Timber.e(e, "Failed to read a password saved by an earlier version")
        null
    }

    /**
     * Save a password for a host.
     *
     * @param hostId The unique host ID
     * @param password The password to save (null or empty to clear)
     * @throws VaultLockedException if the vault is locked; nothing is written then
     */
    fun savePassword(hostId: UUID, password: String?) {
        val key = getPasswordKey(hostId)
        if (password.isNullOrEmpty()) {
            prefs.edit().remove(key).apply()
        } else {
            val sealed = vault.sealText(password, sealContext(hostId))
            prefs.edit().putString(key, sealed).apply()
        }
    }

    /**
     * Get the saved password for a host.
     *
     * @param hostId The unique host ID
     * @return The saved password, or null if none is saved or the vault is locked
     */
    fun getPassword(hostId: UUID): String? {
        val stored = prefs.getString(getPasswordKey(hostId), null) ?: return null
        return when {
            Vault.isSealedText(stored) -> try {
                vault.openText(stored, sealContext(hostId))
            } catch (_: VaultLockedException) {
                null
            } catch (e: VaultDataException) {
                Timber.e(e, "Saved password for host %s failed to authenticate", hostId)
                null
            }

            // Saved before the vault existed; sealLegacyEntries() converts it.
            vault.isUnlocked -> decryptLegacy(stored)

            else -> null
        }
    }

    /**
     * Check if a password is saved for a host. Works while the vault is locked.
     *
     * @param hostId The unique host ID
     * @return true if a password is saved
     */
    fun hasPassword(hostId: UUID): Boolean = prefs.contains(getPasswordKey(hostId))

    /**
     * Delete the saved password for a host.
     * Should be called when a host is deleted.
     *
     * @param hostId The unique host ID
     */
    fun deletePassword(hostId: UUID) {
        prefs.edit().remove(getPasswordKey(hostId)).apply()
    }

    /** Removes every saved password. Used when the vault is reset and they could not be read again. */
    fun deleteAll() {
        prefs.edit().clear().apply()
    }

    /**
     * Moves passwords saved by earlier versions under the vault and then deletes the old Keystore
     * key. Needs the vault unlocked.
     *
     * Nothing is thrown away on a doubt: an entry is dropped only when the old key is gone for good
     * (then nothing can open it). If the key exists but an entry fails to decrypt, that entry and the
     * key stay, and the next unlock tries again.
     *
     * @return how many entries were converted
     */
    fun sealLegacyEntries(): Int {
        if (!vault.isUnlocked) return 0
        val keyGone = try {
            legacyKey() == null
        } catch (e: Exception) {
            // Cannot tell right now; leave everything as it is.
            Timber.w(e, "Could not look up the old password key")
            return 0
        }
        var converted = 0
        var unreadable = false
        val editor = prefs.edit()
        for ((key, value) in prefs.all) {
            if (!key.startsWith(KEY_PREFIX) || value !is String || Vault.isSealedText(value)) continue
            val hostId = try {
                UUID.fromString(key.removePrefix(KEY_PREFIX))
            } catch (_: IllegalArgumentException) {
                editor.remove(key)
                continue
            }
            if (keyGone) {
                editor.remove(key)
                continue
            }
            val plain = decryptLegacy(value)
            if (plain == null) {
                unreadable = true
            } else {
                editor.putString(key, vault.sealText(plain, sealContext(hostId)))
                converted++
            }
        }
        editor.commit()
        if (!unreadable) {
            try {
                keyStore.deleteEntry(LEGACY_KEY_ALIAS)
            } catch (e: Exception) {
                Timber.w(e, "Could not delete the old password key")
            }
        }
        return converted
    }

    private fun getPasswordKey(hostId: UUID): String = "$KEY_PREFIX$hostId"

    private fun sealContext(hostId: UUID): String = "password/$hostId"

    companion object {
        private const val PREFS_FILE_NAME = "secure_host_passwords"
        private const val KEY_PREFIX = "password_"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val LEGACY_KEY_ALIAS = "connectbot_password_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }
}
