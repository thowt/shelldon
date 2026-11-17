/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("NewApi") // JVM unit tests run on the desktop JDK, not on a device

package org.connectbot.shelldon.keyinstall

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

class AuthorizedKeyLineTest {
    /** SSH wire format: each field is a 4-byte length and the bytes. */
    private fun wire(vararg fields: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (field in fields) {
            out.write(byteArrayOf((field.size ushr 24).toByte(), (field.size ushr 16).toByte(), (field.size ushr 8).toByte(), field.size.toByte()))
            out.write(field)
        }
        return out.toByteArray()
    }

    private fun ed25519Blob(): ByteArray = wire("ssh-ed25519".toByteArray(), ByteArray(32) { it.toByte() })

    @Test
    fun theLineIsTypeKeyAndComment() {
        val blob = ed25519Blob()
        val line = AuthorizedKeyLine.of("ssh-ed25519", blob, "pixel")
        assertThat(line.text).isEqualTo("ssh-ed25519 ${Base64.getEncoder().encodeToString(blob)} pixel")
        assertThat(line.keyText).isEqualTo("ssh-ed25519 ${Base64.getEncoder().encodeToString(blob)}")
    }

    @Test
    fun theFingerprintIsTheUnpaddedBase64OfTheSha256OfTheKey() {
        val blob = ed25519Blob()
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        val expected = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        assertThat(AuthorizedKeyLine.of("ssh-ed25519", blob, "k").fingerprint).isEqualTo(expected)
        assertThat(expected).doesNotContain("=")
    }

    @Test
    fun securityKeyTypesWithAtSignsAndDotsAreAccepted() {
        val blob = wire("sk-ssh-ed25519@openssh.com".toByteArray(), ByteArray(32), "ssh:".toByteArray())
        assertThat(AuthorizedKeyLine.of("sk-ssh-ed25519@openssh.com", blob, "yubikey").text).startsWith("sk-ssh-ed25519@openssh.com ")
    }

    @Test
    fun aTypeThatIsNotAPlainNameIsRefused() {
        val blob = ed25519Blob()
        for (bad in listOf("", "ssh ed25519", "SSH-ED25519", "ssh-ed25519\"", "ssh-ed25519;rm", "ssh-ed25519\nssh-rsa")) {
            assertThatThrownBy { AuthorizedKeyLine.of(bad, blob, "k") }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun keyDataThatNamesAnotherTypeIsRefused() {
        assertThatThrownBy { AuthorizedKeyLine.of("ssh-rsa", ed25519Blob(), "k") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun emptyOrTruncatedKeyDataIsRefused() {
        assertThatThrownBy { AuthorizedKeyLine.of("ssh-ed25519", ByteArray(0), "k") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AuthorizedKeyLine.of("ssh-ed25519", byteArrayOf(0, 0, 0), "k") }.isInstanceOf(IllegalArgumentException::class.java)
        // Claims a name longer than the data.
        assertThatThrownBy { AuthorizedKeyLine.of("ssh-ed25519", byteArrayOf(0, 0, 0, 99, 1, 2), "k") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun theCommentKeepsOnlyHarmlessCharacters() {
        assertThat(AuthorizedKeyLine.safeComment("pixel-10_pro@home.lan+x")).isEqualTo("pixel-10_pro@home.lan+x")
        assertThat(AuthorizedKeyLine.safeComment("my work key")).isEqualTo("my-work-key")
        assertThat(AuthorizedKeyLine.safeComment("  --padded--  ")).isEqualTo("padded")
    }

    @Test
    fun aNicknameCannotAddALineOrBreakOutOfTheQuotes() {
        val hostile = "x\nssh-ed25519 AAAAEVIL attacker\" ; rm -rf ~ ; echo \"`id` \$(id) 'q' \\ !"
        val comment = AuthorizedKeyLine.safeComment(hostile)
        assertThat(comment).matches("[A-Za-z0-9._@+-]+")
        val line = AuthorizedKeyLine.of("ssh-ed25519", ed25519Blob(), hostile)
        assertThat(line.text).doesNotContain("\n").doesNotContain("\"").doesNotContain("'").doesNotContain("`").doesNotContain("$").doesNotContain("\\").doesNotContain("!")
        assertThat(line.text.split(' ')).hasSize(3)
    }

    @Test
    fun aNicknameWithNoUsableCharactersGetsTheAppName() {
        assertThat(AuthorizedKeyLine.safeComment("")).isEqualTo("shelldon")
        assertThat(AuthorizedKeyLine.safeComment("ключ 🔑")).isEqualTo("shelldon")
    }

    @Test
    fun aLongNicknameIsCutShort() {
        val comment = AuthorizedKeyLine.safeComment("a".repeat(500))
        assertThat(comment).hasSize(64)
    }
}
