/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.keys

import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.InvalidKeySpecException

/**
 * Reads stored Ed25519 keys back into JCA keys without asking Android for an Ed25519 key factory.
 *
 * The SSH library stopped registering its own Ed25519 provider and now picks a working one per
 * call. An unpinned `KeyFactory.getInstance("Ed25519")` in the app can therefore resolve to a
 * factory that only accepts keystore-backed keys (some Android builds list one) and throw on a
 * perfectly good stored key. The login code then reported that as a wrong password.
 *
 * The private key goes through the library's own PKCS#8 reader, which tries every installed
 * provider and has a built-in fallback. The public key is checked byte for byte against the fixed
 * RFC 8410 layout and wrapped, since signing and the wire encoding only ever use its encoded form.
 */
internal object Ed25519Keys {
    // SubjectPublicKeyInfo for id-Ed25519 (1.3.101.112) followed by the 32 raw key bytes.
    private val SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    private const val RAW_KEY_BYTES = 32
    private val ALGORITHM_NAMES = setOf("ed25519", "eddsa", "1.3.101.112")

    @Throws(InvalidKeySpecException::class)
    fun decodePrivate(pkcs8: ByteArray): PrivateKey {
        // The PEM reader accepts any PKCS#8 key and picks the type itself, so check what came back.
        val key = StoredKeys.decodePrivate(pkcs8)
        if (key.algorithm.lowercase() !in ALGORITHM_NAMES) {
            throw InvalidKeySpecException("Stored key is not an Ed25519 key")
        }
        return key
    }

    @Throws(InvalidKeySpecException::class)
    fun decodePublic(spki: ByteArray): PublicKey {
        if (spki.size != SPKI_PREFIX.size + RAW_KEY_BYTES ||
            !spki.copyOfRange(0, SPKI_PREFIX.size).contentEquals(SPKI_PREFIX)
        ) {
            throw InvalidKeySpecException("Stored key is not an Ed25519 public key")
        }
        return Ed25519PublicKey(spki.copyOf())
    }

    /** Public half of an Ed25519 key; only the encoded form matters to the SSH library. */
    private class Ed25519PublicKey(private val spki: ByteArray) : PublicKey {
        override fun getAlgorithm(): String = "EdDSA"

        override fun getFormat(): String = "X.509"

        override fun getEncoded(): ByteArray = spki.copyOf()

        override fun equals(other: Any?): Boolean = other is PublicKey && other.format == format && other.encoded.contentEquals(spki)

        override fun hashCode(): Int = spki.contentHashCode()
    }
}
