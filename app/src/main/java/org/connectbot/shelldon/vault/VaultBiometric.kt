/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lets a Class 3 (strong) biometric stand in for the master password.
 *
 * The vault's data key is wrapped a second time, under an Android Keystore AES key that can only be
 * used right after a successful biometric prompt (the prompt is bound to the cipher, so the key
 * cannot be used without it), and that is destroyed by the system when the set of enrolled
 * biometrics changes. The wrap is stored in `shelldon-vault.bio` and is bound to the vault id, so
 * it cannot be swapped between vaults. The master password keeps working and remains the root of
 * trust; biometrics are only a shortcut.
 *
 * Both directions are two steps because the cipher has to be handed to the system prompt:
 * `begin…` returns an initialised cipher, the UI shows the prompt with it, and `finish…` completes
 * the operation with the cipher the prompt authenticated.
 */
@Singleton
class VaultBiometric @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vault: Vault,
) {
    private val wrapFile = File(context.filesDir, "shelldon-vault.bio")

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    /** True if the device has an enrolled strong biometric. */
    fun isAvailable(): Boolean = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** True if biometric unlock has been set up for the current vault. */
    fun isEnabled(): Boolean = wrapFile.exists() && keyStore.containsAlias(KEY_ALIAS)

    /** Step 1 of enabling: a fresh Keystore key and an encrypt cipher to authenticate with. Needs the vault unlocked. */
    fun beginEnable(): Cipher {
        check(vault.isUnlocked) { "The vault must be unlocked to enable biometric unlock" }
        disable()
        createKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher
    }

    /** Step 2 of enabling: [cipher] has been authenticated by the prompt. Wraps the data key and stores it. */
    fun finishEnable(cipher: Cipher): Boolean = try {
        val id = vault.vaultId ?: throw IllegalStateException("no vault")
        cipher.updateAAD(aad(id))
        val wrapped = vault.useKey { cipher.doFinal(it) }
        wrapFile.writeBytes(cipher.iv + wrapped)
        true
    } catch (e: Exception) {
        Timber.e(e, "Could not enable biometric unlock")
        disable()
        false
    }

    /**
     * Step 1 of unlocking: a decrypt cipher to authenticate with, or null if biometric unlock is
     * not usable (not set up, or the system invalidated the key because biometrics changed, in
     * which case it is switched off and the password is needed again).
     */
    fun beginUnlock(): Cipher? {
        if (!isEnabled()) return null
        return try {
            val stored = wrapFile.readBytes()
            if (stored.size != VaultCrypto.NONCE_BYTES + VaultCrypto.KEY_BYTES + VaultCrypto.TAG_BYTES) {
                disable()
                return null
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(VaultCrypto.TAG_BITS, stored.copyOf(VaultCrypto.NONCE_BYTES)))
            cipher
        } catch (_: KeyPermanentlyInvalidatedException) {
            Timber.i("Biometrics changed; biometric unlock switched off")
            disable()
            null
        } catch (e: GeneralSecurityException) {
            Timber.e(e, "Biometric unlock is not usable")
            disable()
            null
        } catch (e: IOException) {
            Timber.e(e, "Biometric unlock is not usable")
            null
        }
    }

    /** Step 2 of unlocking: [cipher] has been authenticated by the prompt. */
    fun finishUnlock(cipher: Cipher): Boolean {
        var dek: ByteArray? = null
        return try {
            val id = vault.vaultId ?: return false
            val stored = wrapFile.readBytes()
            cipher.updateAAD(aad(id))
            dek = cipher.doFinal(stored, VaultCrypto.NONCE_BYTES, stored.size - VaultCrypto.NONCE_BYTES)
            vault.unlockWithKey(dek)
        } catch (e: Exception) {
            Timber.e(e, "Biometric unlock failed")
            false
        } finally {
            dek?.fill(0)
        }
    }

    /** Removes the wrap and the Keystore key. The master password is unaffected. */
    fun disable() {
        wrapFile.delete()
        try {
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            Timber.w(e, "Could not delete the biometric key")
        }
    }

    private fun key(): SecretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey

    private fun aad(vaultId: ByteArray): ByteArray = ("shelldon/v1/bio/" + vaultId.joinToString("") { "%02x".format(it) }).toByteArray(Charsets.UTF_8)

    private fun createKey() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generateKey(strongBox = true)
                return
            } catch (_: StrongBoxUnavailableException) {
                Timber.d("No StrongBox; using the default Keystore")
            }
        }
        generateKey(strongBox = false)
    }

    private fun generateKey(strongBox: Boolean) {
        val builder = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Timeout 0: every use needs its own strong-biometric authentication.
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            applyP(builder, strongBox)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        generator.generateKey()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun applyP(builder: KeyGenParameterSpec.Builder, strongBox: Boolean) {
        builder.setUnlockedDeviceRequired(true)
        if (strongBox) builder.setIsStrongBoxBacked(true)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "shelldon_vault_biometric"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
