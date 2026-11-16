/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.vault

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The two primitives the vault is built from: Argon2id (BouncyCastle) for turning a password into
 * a key, and AES-256-GCM (the platform provider) for everything that gets encrypted.
 */
internal object VaultCrypto {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    const val TAG_BYTES = TAG_BITS / 8

    /**
     * Argon2id v1.3 over the NFC-normalised UTF-8 password, so the same phrase typed on another
     * keyboard or input method still opens the vault. Returns [KEY_BYTES] bytes the caller must
     * wipe when done.
     */
    fun deriveKey(password: String, salt: ByteArray, params: KdfParams): ByteArray {
        val passwordBytes = Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(StandardCharsets.UTF_8)
        try {
            val generator = Argon2BytesGenerator()
            generator.init(
                Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(params.memoryKib)
                    .withIterations(params.iterations)
                    .withParallelism(params.parallelism)
                    .withSalt(salt)
                    .build(),
            )
            val key = ByteArray(KEY_BYTES)
            generator.generateBytes(passwordBytes, key, 0, key.size)
            return key
        } finally {
            passwordBytes.fill(0)
        }
    }

    /** AES-256-GCM; the result is ciphertext followed by the 16-byte tag. */
    fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** Throws [java.security.GeneralSecurityException] (an AEAD tag failure) if anything was changed. */
    fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }
}
