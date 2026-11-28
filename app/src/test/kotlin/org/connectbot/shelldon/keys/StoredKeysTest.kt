/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.keys

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.connectbot.data.entity.Pubkey
import org.connectbot.util.PubkeyUtils
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.util.UUID

/**
 * Some Android providers name an Ed25519 key only by its OID. Importing such a key stored that raw
 * name, and login then failed with NoSuchAlgorithmException. These tests model that provider.
 */
class StoredKeysTest {
    private val ed25519 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    /** The same key, but reporting the algorithm name some providers give it. */
    private fun oidNamed(pair: KeyPair, oid: String) = KeyPair(
        object : PublicKey {
            override fun getAlgorithm() = oid

            override fun getFormat() = pair.public.format

            override fun getEncoded(): ByteArray = pair.public.encoded
        },
        object : PrivateKey {
            override fun getAlgorithm() = oid

            override fun getFormat() = pair.private.format

            override fun getEncoded(): ByteArray = pair.private.encoded
        },
    )

    private fun stored(type: String, pair: KeyPair) = Pubkey(
        id = UUID.randomUUID(),
        nickname = "k",
        type = type,
        encrypted = false,
        startup = false,
        confirmation = false,
        createdDate = 0L,
        privateKey = pair.private.encoded,
        publicKey = pair.public.encoded,
    )

    @Test
    fun typeOf_knownProviderNames() {
        assertThat(StoredKeys.typeOf(ed25519)).isEqualTo("Ed25519")
        assertThat(StoredKeys.typeOf(rsa)).isEqualTo("RSA")
        assertThat(StoredKeys.typeOf(ec)).isEqualTo("EC")
    }

    @Test
    fun typeOf_ed25519NamedByOid_isStoredAsEd25519() {
        assertThat(StoredKeys.typeOf(oidNamed(ed25519, "1.3.101.112"))).isEqualTo("Ed25519")
    }

    @Test
    fun decodePrivate_unknownStoredType_isReadFromTheKeyItself() {
        for (type in listOf("1.3.101.112", "something-new", "")) {
            val key = PubkeyUtils.decodePrivate(ed25519.private.encoded, type)
            assertThat(key!!.encoded).describedAs(type).isEqualTo(ed25519.private.encoded)
        }
        assertThat(PubkeyUtils.decodePrivate(rsa.private.encoded, "mystery")!!.algorithm).isEqualTo("RSA")
        assertThat(PubkeyUtils.decodePrivate(ec.private.encoded, "mystery")!!.algorithm).isEqualTo("EC")
    }

    @Test
    fun decodePublic_unknownStoredType_isReadFromTheKeyItself() {
        assertThat(PubkeyUtils.decodePublic(ed25519.public.encoded, "mystery").encoded).isEqualTo(ed25519.public.encoded)
        assertThat(PubkeyUtils.decodePublic(rsa.public.encoded, "mystery").algorithm).isEqualTo("RSA")
        assertThat(PubkeyUtils.decodePublic(ec.public.encoded, "mystery").algorithm).isEqualTo("EC")
    }

    @Test
    fun convertToKeyPair_keyStoredUnderOidName_loads() {
        // What the old import left in the database for an Ed25519 key.
        val pair = PubkeyUtils.convertToKeyPair(stored("1.3.101.112", ed25519), null)

        assertThat(pair!!.private.encoded).isEqualTo(ed25519.private.encoded)
        assertThat(pair.public.encoded).isEqualTo(ed25519.public.encoded)
    }

    @Test
    fun canonicalType_namesOnlyOidTypesAndLeavesTheRestAsStored() {
        assertThat(StoredKeys.canonicalType("1.3.101.112")).isEqualTo("Ed25519")
        assertThat(StoredKeys.canonicalType("1.2.3.4")).isEqualTo("1.2.3.4")
        assertThat(StoredKeys.canonicalType("Ed25519")).isEqualTo("Ed25519")
        assertThat(StoredKeys.canonicalType("EdDSA")).isEqualTo("EdDSA")
        assertThat(StoredKeys.canonicalType("ssh-ed25519")).isEqualTo("ssh-ed25519")
        assertThat(StoredKeys.canonicalType("IMPORTED")).isEqualTo("IMPORTED")
        assertThat(StoredKeys.canonicalType("sk-ssh-ed25519@openssh.com")).isEqualTo("sk-ssh-ed25519@openssh.com")
        assertThat(StoredKeys.canonicalType("mystery")).isEqualTo("mystery")
    }

    @Test
    fun decodeUnknownType_garbage_isRefused() {
        assertThatThrownBy { PubkeyUtils.decodePrivate(ByteArray(10), "mystery") }.isInstanceOf(Exception::class.java)
        assertThatThrownBy { PubkeyUtils.decodePublic(ByteArray(10), "mystery") }.isInstanceOf(Exception::class.java)
    }
}
