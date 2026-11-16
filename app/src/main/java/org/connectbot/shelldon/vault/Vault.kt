/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.encoding.Base64

enum class VaultState {
    /** No master password has been set yet. */
    NO_VAULT,

    /** A vault exists but the data key is not in memory. */
    LOCKED,

    /** The data key is in memory and secrets can be sealed and opened. */
    UNLOCKED,
}

enum class UnlockResult { OK, WRONG_PASSWORD, NO_VAULT, DAMAGED }

/**
 * Master-password vault for everything secret the app stores: private keys, saved passwords and
 * (later) sync credentials.
 *
 * A random 256-bit data key (DEK) seals each secret with AES-256-GCM. The DEK lives on disk only in
 * wrapped form (see [VaultHeader]) and in memory only while the vault is unlocked. Locking zeroes it.
 * Sessions that are already open keep working when the vault locks, because they hold their own
 * copy of what they need.
 *
 * Every sealed blob is bound to a *context* string (for example `pubkey/<id>`), which goes into the
 * GCM associated data. A blob copied from one record to another therefore refuses to open.
 */
class Vault(
    private val headerFile: File,
    private val random: SecureRandom = SecureRandom(),
) {
    private val operations = Any()
    private val keyLock = ReentrantReadWriteLock()

    // Guarded by keyLock.
    private var dek: ByteArray? = null

    @Volatile
    private var header: VaultHeader? = null

    private val mutableState = MutableStateFlow(if (headerFile.exists()) VaultState.LOCKED else VaultState.NO_VAULT)
    val state: StateFlow<VaultState> = mutableState.asStateFlow()

    val isUnlocked: Boolean get() = state.value == VaultState.UNLOCKED

    /**
     * Stable identifier of this vault (survives password changes). Used to bind the biometric
     * wrap to this vault; null while no vault exists.
     */
    val vaultId: ByteArray?
        get() = try {
            cachedHeader()?.vaultId?.copyOf()
        } catch (_: VaultDataException) {
            null
        }

    /** Creates the vault and leaves it unlocked. */
    fun create(password: String, params: KdfParams = KdfParams.DEFAULT) {
        require(password.length >= MIN_PASSWORD_LENGTH) { "Password must have at least $MIN_PASSWORD_LENGTH characters" }
        synchronized(operations) {
            check(!headerFile.exists()) { "A vault already exists" }
            val newDek = ByteArray(VaultCrypto.KEY_BYTES).also { random.nextBytes(it) }
            val newHeader = VaultHeader.create(password, newDek, params, random)
            writeAtomically(newHeader.toBytes())
            header = newHeader
            install(newDek)
        }
    }

    fun unlock(password: String): UnlockResult {
        synchronized(operations) {
            val current = try {
                cachedHeader()
            } catch (_: VaultDataException) {
                return UnlockResult.DAMAGED
            }
            if (current == null) return UnlockResult.NO_VAULT
            val candidate = try {
                current.unwrapDek(password)
            } catch (_: VaultDataException) {
                return UnlockResult.WRONG_PASSWORD
            }
            install(candidate)
            return UnlockResult.OK
        }
    }

    /**
     * Unlocks with a data key obtained elsewhere (the biometric path). The key is authenticated by
     * the caller's own wrap, which is bound to [vaultId]. The array is copied.
     */
    internal fun unlockWithKey(key: ByteArray): Boolean {
        synchronized(operations) {
            if (!headerFile.exists() || key.size != VaultCrypto.KEY_BYTES) return false
            install(key.copyOf())
            return true
        }
    }

    /** Wipes the data key from memory. Cheap and safe to call at any time. */
    fun lock() {
        keyLock.write {
            dek?.fill(0)
            dek = null
        }
        mutableState.value = if (headerFile.exists()) VaultState.LOCKED else VaultState.NO_VAULT
    }

    /**
     * Re-wraps the data key under a new password after checking the old one. Sealed data is not
     * touched. Returns false when [oldPassword] is wrong.
     */
    fun changePassword(oldPassword: String, newPassword: String, params: KdfParams = KdfParams.DEFAULT): Boolean {
        require(newPassword.length >= MIN_PASSWORD_LENGTH) { "Password must have at least $MIN_PASSWORD_LENGTH characters" }
        synchronized(operations) {
            val current = cachedHeader() ?: return false
            val currentKey = try {
                current.unwrapDek(oldPassword)
            } catch (_: VaultDataException) {
                return false
            }
            try {
                val rewrapped = current.rewrap(currentKey, newPassword, params, random)
                writeAtomically(rewrapped.toBytes())
                header = rewrapped
            } finally {
                currentKey.fill(0)
            }
            return true
        }
    }

    /**
     * Deletes the vault. Everything sealed with it becomes unreadable for good; callers are
     * responsible for removing those records.
     */
    fun reset() {
        synchronized(operations) {
            lock()
            headerFile.delete()
            header = null
            mutableState.value = VaultState.NO_VAULT
        }
    }

    /** Seals [plaintext] under [context]. Throws [VaultLockedException] while locked. */
    fun seal(plaintext: ByteArray, context: String): ByteArray {
        val nonce = ByteArray(VaultCrypto.NONCE_BYTES).also { random.nextBytes(it) }
        val ciphertext = useKey { VaultCrypto.encrypt(it, nonce, aad(context), plaintext) }
        return SEALED_MAGIC + nonce + ciphertext
    }

    /**
     * Opens a blob made by [seal] for the same [context].
     *
     * @throws VaultLockedException while locked
     * @throws VaultDataException if the data is not sealed data, was altered, or belongs to another context
     */
    fun open(blob: ByteArray, context: String): ByteArray {
        if (!isSealed(blob)) throw VaultDataException("Not sealed data")
        val nonce = blob.copyOfRange(SEALED_MAGIC.size, SEALED_HEADER_BYTES)
        val ciphertext = blob.copyOfRange(SEALED_HEADER_BYTES, blob.size)
        return try {
            useKey { VaultCrypto.decrypt(it, nonce, aad(context), ciphertext) }
        } catch (e: GeneralSecurityException) {
            throw VaultDataException("Sealed data failed to authenticate", e)
        }
    }

    fun sealText(plaintext: String, context: String): String = TEXT_PREFIX + Base64.encode(seal(plaintext.toByteArray(Charsets.UTF_8), context))

    fun openText(sealed: String, context: String): String {
        if (!isSealedText(sealed)) throw VaultDataException("Not sealed data")
        val blob = try {
            Base64.decode(sealed.substring(TEXT_PREFIX.length))
        } catch (e: IllegalArgumentException) {
            throw VaultDataException("Sealed data is damaged", e)
        }
        val plaintext = open(blob, context)
        try {
            return String(plaintext, Charsets.UTF_8)
        } finally {
            plaintext.fill(0)
        }
    }

    /** Runs [block] with the data key; the array must not be kept or modified. */
    internal fun <T> useKey(block: (ByteArray) -> T): T = keyLock.read {
        val key = dek ?: throw VaultLockedException()
        block(key)
    }

    private fun install(newKey: ByteArray) {
        keyLock.write {
            dek?.fill(0)
            dek = newKey
        }
        mutableState.value = VaultState.UNLOCKED
    }

    private fun cachedHeader(): VaultHeader? {
        header?.let { return it }
        if (!headerFile.exists()) return null
        // Reject anything that is not exactly header-sized before reading it.
        if (headerFile.length() != VaultHeader.SIZE.toLong()) throw VaultDataException("Vault file has the wrong size")
        return VaultHeader.parse(headerFile.readBytes()).also { header = it }
    }

    private fun writeAtomically(bytes: ByteArray) {
        val temp = File(headerFile.parentFile, headerFile.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!temp.renameTo(headerFile)) {
            temp.delete()
            throw IOException("Could not store the vault file")
        }
    }

    private fun aad(context: String): ByteArray = (AAD_PREFIX + context).toByteArray(Charsets.UTF_8)

    companion object {
        const val MIN_PASSWORD_LENGTH = 8

        private const val AAD_PREFIX = "shelldon/v1/"
        private const val TEXT_PREFIX = "sh1:"

        /** A zero byte first, so a sealed blob can never be mistaken for DER (0x30) or PEM/OpenSSH text. */
        private val SEALED_MAGIC = byteArrayOf(0, 'S'.code.toByte(), 'H'.code.toByte(), '1'.code.toByte())
        private val SEALED_HEADER_BYTES = SEALED_MAGIC.size + VaultCrypto.NONCE_BYTES
        private val SEALED_MIN_BYTES = SEALED_HEADER_BYTES + VaultCrypto.TAG_BYTES

        /** True if [bytes] looks like something [seal] produced (says nothing about validity). */
        fun isSealed(bytes: ByteArray?): Boolean {
            if (bytes == null || bytes.size < SEALED_MIN_BYTES) return false
            for (i in SEALED_MAGIC.indices) {
                if (bytes[i] != SEALED_MAGIC[i]) return false
            }
            return true
        }

        fun isSealedText(text: String?): Boolean = text != null && text.startsWith(TEXT_PREFIX)
    }
}
