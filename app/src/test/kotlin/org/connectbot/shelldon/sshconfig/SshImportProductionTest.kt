/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sshconfig

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

/** The two places where the importer asks the SSH library, with the real library. */
class SshImportProductionTest {
    @Test
    fun theSshLibraryAcceptsTheEntriesALegacyConfigUses() {
        assertThat(policyAccepts("+diffie-hellman-group1-sha1")).isTrue()
        assertThat(policyAccepts("+diffie-hellman-group14-sha1")).isTrue()
        assertThat(policyAccepts("+aes128-cbc")).isTrue()
        assertThat(policyAccepts("+ssh-rsa")).isTrue()
    }

    @Test
    fun anUnencryptedKeyIsDecoded() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val pem = TestPem.pkcs8(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded)) + "\n"

        val decoded = SshKeyFileDecoder.decode(pem, null)

        assertThat(decoded).isInstanceOf(KeyDecodeResult.Decoded::class.java)
        decoded as KeyDecodeResult.Decoded
        assertThat(decoded.privateKey).isNotEmpty()
        assertThat(decoded.publicKey).isEqualTo(pair.public.encoded)
    }

    @Test
    fun aKeyThatCannotBeReadWithoutAPassphraseAsksForOneAndAWrongOneIsReported() {
        val text = TestPem.openSsh("bm90IGEga2V5") + "\n"
        assertThat(SshKeyFileDecoder.decode(text, null)).isEqualTo(KeyDecodeResult.NeedsPassphrase)
        assertThat(SshKeyFileDecoder.decode(text, "guess")).isEqualTo(KeyDecodeResult.WrongPassphrase)
        assertThat(SshKeyFileDecoder.decode("hello", null)).isEqualTo(KeyDecodeResult.Unreadable)
    }
}
