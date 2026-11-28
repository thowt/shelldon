/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.keys

import org.connectbot.sshlib.SshKeys
import org.connectbot.sshlib.SshSigning
import org.connectbot.util.SshKeyType
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.X509EncodedKeySpec
import kotlin.io.encoding.Base64

/**
 * Names stored keys and reads them back without trusting the provider's algorithm name.
 *
 * Android providers do not agree on what `Key.getAlgorithm()` returns for an Ed25519 key: some say
 * "EdDSA", some "Ed25519", some only the numeric OID "1.3.101.112". An import that stored that raw
 * name left a key the app could no longer recognise, and login failed with
 * `NoSuchAlgorithmException`. The SSH library already copes with all of these names, so the type
 * is taken from the key's SSH wire name, and a key stored under a name nobody knows is read from
 * its own bytes.
 */
internal object StoredKeys {
    /** The type name to store for [pair], one of [SshKeyType.storedName] whenever it is a known type. */
    fun typeOf(pair: KeyPair): String {
        val fromWireName = try {
            SshKeyType.fromOpenSshType(SshSigning.encodePublicKey(pair).algorithmName)
        } catch (_: Exception) {
            null
        }
        return fromWireName?.storedName
            ?: SshKeyType.fromJavaAlgorithm(pair.private.algorithm)?.storedName
            ?: pair.private.algorithm
    }

    private val OID_NAME = Regex("[0-9]+(\\.[0-9]+)+")

    /**
     * The canonical name for a stored type that is only an OID: "Ed25519" for "1.3.101.112". An
     * older import wrote the provider's raw name. Every other name is returned unchanged, so what
     * the app stored (legacy "ssh-ed25519", "IMPORTED", hardware key types) reads back as saved.
     */
    fun canonicalType(type: String): String {
        if (!OID_NAME.matches(type)) return type
        return SshKeyType.fromStoredType(type)?.storedName ?: type
    }

    /** Reads an unencrypted PKCS#8 private key of any type the SSH library supports. */
    @Throws(InvalidKeySpecException::class)
    fun pairFromPkcs8(pkcs8: ByteArray): KeyPair = try {
        val body = Base64.Default.encode(pkcs8)
        SshKeys.decodePemPrivateKey("-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n")
    } catch (e: Exception) {
        throw InvalidKeySpecException("Stored private key cannot be read", e)
    }

    @Throws(InvalidKeySpecException::class)
    fun decodePrivate(pkcs8: ByteArray): PrivateKey = pairFromPkcs8(pkcs8).private

    /** Reads an X.509 public key of unknown type: Ed25519 by its fixed layout, then RSA and EC. */
    @Throws(InvalidKeySpecException::class)
    fun decodePublic(spki: ByteArray): PublicKey {
        try {
            return Ed25519Keys.decodePublic(spki)
        } catch (_: InvalidKeySpecException) {
            // not Ed25519, try the others
        }
        for (algorithm in listOf("RSA", "EC")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePublic(X509EncodedKeySpec(spki))
            } catch (_: GeneralSecurityException) {
                // wrong type, try the next
            }
        }
        throw InvalidKeySpecException("Stored public key type is not recognised")
    }
}
