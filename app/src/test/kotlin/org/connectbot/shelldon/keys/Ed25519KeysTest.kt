/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.keys

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.connectbot.data.entity.Pubkey
import org.connectbot.sshlib.SshKeys
import org.connectbot.util.PubkeyUtils
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.security.Key
import java.security.KeyFactorySpi
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.Security
import java.security.spec.InvalidKeySpecException
import java.security.spec.KeySpec
import java.util.UUID

/**
 * Stored Ed25519 keys must load even when the first Ed25519 key factory Android offers cannot read
 * raw key bytes. Some Android builds list such a factory (the keystore one), so an unpinned
 * `KeyFactory.getInstance("Ed25519")` picks it and fails, and the login code then reported that as
 * "Bad password for key". The stand-in provider below behaves the same way.
 */
class Ed25519KeysTest {
    private val ed25519 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private var rejecting: Provider? = null

    @Before
    fun installRejectingFactory() {
        val provider = RejectingEd25519Provider()
        Security.insertProviderAt(provider, 1)
        rejecting = provider
    }

    @After
    fun removeRejectingFactory() {
        rejecting?.let { Security.removeProvider(it.name) }
    }

    @Test
    fun decodePrivate_firstFactoryRejectsRawKeys_stillDecodes() {
        val key = PubkeyUtils.decodePrivate(ed25519.private.encoded, "Ed25519")

        assertThat(key).isNotNull
        assertThat(key!!.encoded).isEqualTo(ed25519.private.encoded)
    }

    @Test
    fun decodePublic_firstFactoryRejectsRawKeys_stillDecodes() {
        val key = PubkeyUtils.decodePublic(ed25519.public.encoded, "Ed25519")

        assertThat(key.encoded).isEqualTo(ed25519.public.encoded)
    }

    @Test
    fun convertToKeyPair_importedOpenSshKey_roundTripsThroughStorage() {
        // What the import screen stores: the key decoded from the file text, then re-encoded.
        val fileText = SshKeys.encodeOpenSshPrivateKey(ed25519)
        val imported = SshKeys.decodePemPrivateKey(fileText)
        val stored = Pubkey(
            id = UUID.randomUUID(),
            nickname = "imported",
            type = "Ed25519",
            encrypted = false,
            startup = false,
            confirmation = false,
            createdDate = 0L,
            privateKey = imported.private.encoded,
            publicKey = imported.public.encoded,
        )

        val pair = PubkeyUtils.convertToKeyPair(stored, null)

        assertThat(pair).isNotNull
        assertThat(pair!!.public.encoded).isEqualTo(ed25519.public.encoded)
        assertThat(pair.private.encoded).isEqualTo(ed25519.private.encoded)
    }

    @Test
    fun convertToKeyPair_importedKeyWithPassphrase_roundTripsThroughStorage() {
        val fileText = SshKeys.encodeOpenSshPrivateKey(ed25519, "correct horse")
        val imported = SshKeys.decodePemPrivateKey(fileText, "correct horse")
        val stored = Pubkey(
            id = UUID.randomUUID(),
            nickname = "imported",
            type = "Ed25519",
            encrypted = true,
            startup = false,
            confirmation = false,
            createdDate = 0L,
            privateKey = PubkeyUtils.getEncodedPrivate(imported.private, "store secret"),
            publicKey = imported.public.encoded,
        )

        val pair = PubkeyUtils.convertToKeyPair(stored, "store secret")

        assertThat(pair!!.private.encoded).isEqualTo(ed25519.private.encoded)
    }

    @Test
    fun decodePublic_damagedBytes_isRefused() {
        val damaged = ed25519.public.encoded.copyOf().also { it[0] = 0x31 }

        assertThatThrownBy { PubkeyUtils.decodePublic(damaged, "Ed25519") }.isInstanceOf(Exception::class.java)
        assertThatThrownBy { PubkeyUtils.decodePublic(ByteArray(10), "Ed25519") }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun decodePrivate_otherKeyTypeStoredAsEd25519_isRefused() {
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

        assertThatThrownBy { PubkeyUtils.decodePrivate(ec.private.encoded, "Ed25519") }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun decodePrivate_damagedBytes_isRefused() {
        assertThatThrownBy { PubkeyUtils.decodePrivate(ByteArray(10), "Ed25519") }.isInstanceOf(Exception::class.java)
    }
}

/** Advertises an Ed25519 key factory that, like the keystore one, refuses every raw key. */
private class RejectingEd25519Provider : Provider("RejectingEd25519", 1.0, "test stand-in") {
    init {
        put("KeyFactory.Ed25519", RejectingFactory::class.java.name)
    }
}

class RejectingFactory : KeyFactorySpi() {
    override fun engineGeneratePublic(keySpec: KeySpec?): PublicKey = throw InvalidKeySpecException("keystore keys only")

    override fun engineGeneratePrivate(keySpec: KeySpec?): PrivateKey = throw InvalidKeySpecException("keystore keys only")

    override fun <T : KeySpec?> engineGetKeySpec(key: Key?, keySpec: Class<T>?): T = throw InvalidKeySpecException("keystore keys only")

    override fun engineTranslateKey(key: Key?): Key = throw java.security.InvalidKeyException("keystore keys only")
}
