/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.vault

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * The only thing stored for the vault itself: the random data key (DEK) that seals every secret,
 * wrapped by a key derived from the master password. Changing the password re-wraps the DEK and
 * leaves all sealed data untouched.
 *
 * Fixed 109-byte layout, big-endian:
 * ```
 *   0  magic "SHV1"
 *   4  KDF id (1 = Argon2id v1.3)
 *   5  memory KiB (4), iterations (4), parallelism (4)
 *  17  vault id (16)  stays the same when the password changes
 *  33  salt (16)
 *  49  nonce (12)
 *  61  wrapped DEK (32 + 16 tag)
 * ```
 * The first 61 bytes are the AES-GCM associated data of the wrap, so changing any KDF parameter,
 * the salt or the vault id makes unwrapping fail.
 */
internal class VaultHeader(
    val params: KdfParams,
    val vaultId: ByteArray,
    val salt: ByteArray,
    val nonce: ByteArray,
    val wrappedDek: ByteArray,
) {
    init {
        require(vaultId.size == ID_BYTES) { "bad vault id size" }
        require(salt.size == SALT_BYTES) { "bad salt size" }
        require(nonce.size == VaultCrypto.NONCE_BYTES) { "bad nonce size" }
        require(wrappedDek.size == WRAPPED_BYTES) { "bad wrapped key size" }
    }

    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(SIZE)
        buffer.put(MAGIC)
        buffer.put(KDF_ARGON2ID_V13)
        buffer.putInt(params.memoryKib)
        buffer.putInt(params.iterations)
        buffer.putInt(params.parallelism)
        buffer.put(vaultId)
        buffer.put(salt)
        buffer.put(nonce)
        buffer.put(wrappedDek)
        return buffer.array()
    }

    /** Opens the DEK. The caller wipes the returned array when done. */
    fun unwrapDek(password: String): ByteArray {
        val kek = VaultCrypto.deriveKey(password, salt, params)
        try {
            return VaultCrypto.decrypt(kek, nonce, aad(), wrappedDek)
        } catch (e: GeneralSecurityException) {
            throw VaultDataException("Wrong password or damaged vault", e)
        } finally {
            kek.fill(0)
        }
    }

    /** The same DEK and vault id under a new password, fresh salt and fresh nonce. */
    fun rewrap(dek: ByteArray, newPassword: String, newParams: KdfParams, random: SecureRandom): VaultHeader = create(newPassword, dek, newParams, random, vaultId)

    private fun aad(): ByteArray = toBytes().copyOf(AAD_BYTES)

    companion object {
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'H'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
        private const val KDF_ARGON2ID_V13: Byte = 1
        const val ID_BYTES = 16
        const val SALT_BYTES = 16
        const val WRAPPED_BYTES = VaultCrypto.KEY_BYTES + VaultCrypto.TAG_BYTES
        const val AAD_BYTES = 4 + 1 + 12 + ID_BYTES + SALT_BYTES + VaultCrypto.NONCE_BYTES
        const val SIZE = AAD_BYTES + WRAPPED_BYTES

        fun create(
            password: String,
            dek: ByteArray,
            params: KdfParams,
            random: SecureRandom,
            vaultId: ByteArray = ByteArray(ID_BYTES).also { random.nextBytes(it) },
        ): VaultHeader {
            val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
            val nonce = ByteArray(VaultCrypto.NONCE_BYTES).also { random.nextBytes(it) }
            // Build the header once with a placeholder to get the exact AAD bytes, then wrap for real.
            val placeholder = VaultHeader(params, vaultId, salt, nonce, ByteArray(WRAPPED_BYTES))
            val kek = VaultCrypto.deriveKey(password, salt, params)
            try {
                val wrapped = VaultCrypto.encrypt(kek, nonce, placeholder.aad(), dek)
                return VaultHeader(params, vaultId, salt, nonce, wrapped)
            } finally {
                kek.fill(0)
            }
        }

        /** Parses and range-checks a header read from disk. */
        fun parse(bytes: ByteArray): VaultHeader {
            if (bytes.size != SIZE) throw VaultDataException("Vault header has the wrong size")
            val buffer = ByteBuffer.wrap(bytes)
            val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
            if (!magic.contentEquals(MAGIC)) throw VaultDataException("Not a Shelldon vault")
            if (buffer.get() != KDF_ARGON2ID_V13) throw VaultDataException("Unsupported vault key derivation")
            val params = try {
                KdfParams(memoryKib = buffer.getInt(), iterations = buffer.getInt(), parallelism = buffer.getInt())
            } catch (e: IllegalArgumentException) {
                throw VaultDataException("Vault key derivation settings are out of range", e)
            }
            val id = ByteArray(ID_BYTES).also { buffer.get(it) }
            val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
            val nonce = ByteArray(VaultCrypto.NONCE_BYTES).also { buffer.get(it) }
            val wrapped = ByteArray(WRAPPED_BYTES).also { buffer.get(it) }
            return VaultHeader(params, id, salt, nonce, wrapped)
        }
    }
}
