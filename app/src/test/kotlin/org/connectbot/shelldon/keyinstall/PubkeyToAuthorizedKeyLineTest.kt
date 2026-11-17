/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.keyinstall

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.entity.KeyStorageType
import org.connectbot.data.entity.Pubkey
import org.connectbot.fido2.Fido2TestData
import org.connectbot.sshlib.SshSigning
import org.connectbot.sshlib.sk.SkAlgorithm
import org.connectbot.sshlib.sk.SkPublicKeyEncoder
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class PubkeyToAuthorizedKeyLineTest {
    private fun stored(nickname: String, type: String, publicKey: ByteArray, storage: KeyStorageType = KeyStorageType.EXPORTABLE) = Pubkey(
        nickname = nickname,
        type = type,
        privateKey = null,
        publicKey = publicKey,
        encrypted = false,
        startup = false,
        confirmation = false,
        createdDate = 0,
        storageType = storage,
    )

    @Test
    fun anEd25519KeyBecomesAnSshEd25519Line() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val line = stored("phone key", "Ed25519", pair.public.encoded).toAuthorizedKeyLine()!!
        assertThat(line.type).isEqualTo("ssh-ed25519")
        assertThat(line.comment).isEqualTo("phone-key")
        // The same blob the library itself produces for this key.
        assertThat(line.blob).isEqualTo(SshSigning.encodePublicKey(KeyPair(pair.public, null)).publicKeyBlob)
    }

    @Test
    fun anEcdsaKeyBecomesAnEcdsaLine() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val line = stored("ec", "EC", pair.public.encoded).toAuthorizedKeyLine()!!
        assertThat(line.type).isEqualTo("ecdsa-sha2-nistp256")
    }

    @Test
    fun anRsaKeyBecomesAnSshRsaLine() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val line = stored("rsa", "RSA", pair.public.encoded).toAuthorizedKeyLine()!!
        assertThat(line.type).isEqualTo("ssh-rsa")
    }

    @Test
    fun aSecurityKeyKeepsItsWireBlobUnderTheOpenSshTypeName() {
        val wire = SkPublicKeyEncoder.encode(SkAlgorithm.ED25519, Fido2TestData.ED25519_PUBLIC_KEY, "ssh:")
        val line = stored("yubikey", "SK-Ed25519", wire, KeyStorageType.FIDO2_RESIDENT_KEY).toAuthorizedKeyLine()!!
        assertThat(line.type).isEqualTo("sk-ssh-ed25519@openssh.com")
        assertThat(line.blob).isEqualTo(wire)
    }

    @Test
    fun anImportedKeyOfUnknownTypeHasNoLine() {
        assertThat(stored("old", "IMPORTED", byteArrayOf(1, 2, 3)).toAuthorizedKeyLine()).isNull()
    }

    @Test
    fun garbageKeyDataHasNoLineInsteadOfCrashing() {
        assertThat(stored("bad", "Ed25519", byteArrayOf(1, 2, 3)).toAuthorizedKeyLine()).isNull()
        assertThat(stored("bad", "SK-Ed25519", byteArrayOf(1, 2, 3), KeyStorageType.FIDO2_RESIDENT_KEY).toAuthorizedKeyLine()).isNull()
    }
}
