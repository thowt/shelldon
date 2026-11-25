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
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Where biometric unlock stands on disk and in the Keystore. */
enum class BiometricStatus {
    /** Never set up, or switched off. */
    NOT_SET_UP,

    /** The wrap file and the Keystore key are both there. */
    READY,

    /** The wrap file is there but the Keystore key is gone, for instance deleted by the system. */
    KEY_LOST,
}

/** How an attempt to start a biometric unlock went. */
sealed interface BiometricStart {
    /** Show the system prompt with [cipher]. */
    class Ready(val cipher: Cipher) : BiometricStart

    /** Biometric unlock is not set up. */
    data object NotSetUp : BiometricStart

    /** The enrolled biometrics changed and the system invalidated the key. Biometric unlock was switched off. */
    data object Invalidated : BiometricStart

    /** It was set up but cannot be used any more (key gone, wrap damaged). Biometric unlock was switched off. */
    data object Lost : BiometricStart

    /** The system refused the key just now. Nothing was switched off; the master password works. */
    data object Unavailable : BiometricStart
}

/**
 * Decides what a failed attempt to start an unlock means. Only a key that the system itself
 * invalidated is a reason to throw the biometric setup away. Every other error (the Keystore is
 * busy, the device counts as locked, a vendor glitch) may be gone on the next try, so the setup is
 * kept and the master password is used this time.
 */
internal fun startFailure(error: Exception): BiometricStart = when (error) {
    is KeyPermanentlyInvalidatedException -> BiometricStart.Invalidated
    else -> BiometricStart.Unavailable
}

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

    /** What is on disk and in the Keystore. If the Keystore cannot be asked, the wrap file decides. */
    fun status(): BiometricStatus {
        val hasWrap = wrapFile.exists()
        val hasKey = try {
            keyStore.containsAlias(KEY_ALIAS)
        } catch (e: Exception) {
            Timber.w(e, "Could not ask the Keystore about the biometric key")
            return if (hasWrap) BiometricStatus.READY else BiometricStatus.NOT_SET_UP
        }
        return when {
            hasWrap && hasKey -> BiometricStatus.READY
            hasWrap -> BiometricStatus.KEY_LOST
            else -> BiometricStatus.NOT_SET_UP
        }
    }

    /** True if biometric unlock has been set up for the current vault and can be tried. */
    fun isEnabled(): Boolean = status() == BiometricStatus.READY

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
     * Step 1 of unlocking: a decrypt cipher to authenticate with, or the reason there is none.
     * Biometric unlock is switched off only when it cannot work any more: the system invalidated
     * the key because the biometrics changed, the key is gone, or the wrap is damaged. A temporary
     * Keystore error leaves everything as it is.
     */
    fun beginUnlock(): BiometricStart {
        when (status()) {
            BiometricStatus.NOT_SET_UP -> return BiometricStart.NotSetUp

            BiometricStatus.KEY_LOST -> {
                Timber.w("The biometric key is gone; biometric unlock switched off")
                disable()
                return BiometricStart.Lost
            }

            BiometricStatus.READY -> Unit
        }
        return try {
            val stored = wrapFile.readBytes()
            val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (key == null || stored.size != VaultCrypto.NONCE_BYTES + VaultCrypto.KEY_BYTES + VaultCrypto.TAG_BYTES) {
                Timber.w("The biometric key or wrap is unusable; biometric unlock switched off")
                disable()
                BiometricStart.Lost
            } else {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(VaultCrypto.TAG_BITS, stored.copyOf(VaultCrypto.NONCE_BYTES)))
                BiometricStart.Ready(cipher)
            }
        } catch (e: Exception) {
            val outcome = startFailure(e)
            if (outcome == BiometricStart.Invalidated) {
                Timber.i("Biometrics changed; biometric unlock switched off")
                disable()
            } else {
                Timber.w(e, "Biometric unlock is not usable right now; kept for the next try")
            }
            outcome
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
