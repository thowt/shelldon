/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sshconfig

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

internal const val ED1 = "AAAAC3NzaC1lZDI1NTE5AAAAIAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB"
internal const val ED2 = "AAAAC3NzaC1lZDI1NTE5AAAAIAICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgIC"
internal const val ED3 = "AAAAC3NzaC1lZDI1NTE5AAAAIAMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMD"
internal const val RSA1 = "AAAAB3NzaC1yc2EAAAADAQABAAAAgAABAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB"

// `example.com` and `[example.com]:2222` hashed with the salt 00 01 02 ... 13, as `ssh-keygen -H` would.
internal const val HASHED_PLAIN = "|1|AAECAwQFBgcICQoLDA0ODxAREhM=|nnUK16ANsXd3hL31YfAkGOluSjU="
internal const val HASHED_PORT = "|1|AAECAwQFBgcICQoLDA0ODxAREhM=|Wgcx+Fm+LmaWwC7rQ80eIf2uHe0="

class KnownHostsFileTest {
    private fun entries(text: String) = KnownHostsFile.parse(text).entries

    private fun matches(line: String, host: String, port: Int = 22): Boolean = KnownHostsFile.matches(entries(line).single(), host, port)

    @Test
    fun readsKeysAndSkipsCommentsAndBlankLines() {
        val result = KnownHostsFile.parse(
            """
            # a comment

            example.com ssh-ed25519 $ED1
            [10.0.0.1]:2222,10.0.0.2 ssh-rsa $RSA1 trailing comment
            """.trimIndent(),
        )
        assertThat(result.entries.map { it.algorithm }).containsExactly("ssh-ed25519", "ssh-rsa")
        assertThat(result.entries[1].names).containsExactly("[10.0.0.1]:2222", "10.0.0.2")
        assertThat(result.unreadableLines).isEqualTo(0)
    }

    @Test
    fun certificateAuthorityRevokedAndBrokenLinesAreCountedNotImported() {
        val result = KnownHostsFile.parse(
            """
            @cert-authority *.example.com ssh-ed25519 $ED1
            @revoked example.com ssh-ed25519 $ED2
            @unknown example.com ssh-ed25519 $ED3
            example.com ssh-ed25519 not-base64-or-a-key
            justonefield
            ok.example.com ssh-ed25519 $ED3
            """.trimIndent(),
        )
        assertThat(result.certAuthorityLines).isEqualTo(1)
        assertThat(result.revokedLines).isEqualTo(1)
        assertThat(result.unreadableLines).isEqualTo(3)
        assertThat(result.entries.map { it.names!!.single() }).containsExactly("ok.example.com")
    }

    @Test
    fun aBareNameMeansPort22AndOtherPortsAreWrittenInBrackets() {
        assertThat(matches("example.com ssh-ed25519 $ED1", "example.com")).isTrue()
        assertThat(matches("example.com ssh-ed25519 $ED1", "example.com", 2222)).isFalse()
        assertThat(matches("[example.com]:2222 ssh-ed25519 $ED1", "example.com", 2222)).isTrue()
        assertThat(matches("[example.com]:2222 ssh-ed25519 $ED1", "example.com")).isFalse()
        assertThat(matches("[example.com]:2222 ssh-ed25519 $ED1", "example.com", 2223)).isFalse()
    }

    @Test
    fun namesAreComparedIgnoringCase() {
        assertThat(matches("Example.COM ssh-ed25519 $ED1", "example.com")).isTrue()
        assertThat(matches("example.com ssh-ed25519 $ED1", "EXAMPLE.com")).isTrue()
    }

    @Test
    fun eachNameInACommaListIsAnAlternative() {
        val line = "host.example.com,192.0.2.7 ssh-ed25519 $ED1"
        assertThat(matches(line, "host.example.com")).isTrue()
        assertThat(matches(line, "192.0.2.7")).isTrue()
        assertThat(matches(line, "192.0.2.8")).isFalse()
    }

    @Test
    fun wildcardsAndNegationFollowOpenSsh() {
        val line = "*.example.com,!secret.example.com ssh-ed25519 $ED1"
        assertThat(matches(line, "web.example.com")).isTrue()
        assertThat(matches(line, "secret.example.com")).isFalse()
        assertThat(matches(line, "example.org")).isFalse()
        assertThat(matches("web?.lan ssh-ed25519 $ED1", "web1.lan")).isTrue()
        assertThat(matches("web?.lan ssh-ed25519 $ED1", "web12.lan")).isFalse()
    }

    @Test
    fun aPatternNeverMatchesAnotherHostOnlyBecauseTheNameIsASubstring() {
        assertThat(matches("example.com ssh-ed25519 $ED1", "notexample.com")).isFalse()
        assertThat(matches("example.com ssh-ed25519 $ED1", "example.com.evil.test")).isFalse()
    }

    @Test
    fun hashedNamesAreMatchedAgainstTheHostWeAskAbout() {
        assertThat(matches("$HASHED_PLAIN ssh-ed25519 $ED1", "example.com")).isTrue()
        assertThat(matches("$HASHED_PLAIN ssh-ed25519 $ED1", "Example.com")).isTrue()
        assertThat(matches("$HASHED_PLAIN ssh-ed25519 $ED1", "other.com")).isFalse()
        assertThat(matches("$HASHED_PLAIN ssh-ed25519 $ED1", "example.com", 2222)).isFalse()
        assertThat(matches("$HASHED_PORT ssh-ed25519 $ED1", "example.com", 2222)).isTrue()
        assertThat(matches("$HASHED_PORT ssh-ed25519 $ED1", "example.com")).isFalse()
    }

    @Test
    fun aDamagedHashedNameIsUnreadable() {
        val result = KnownHostsFile.parse("|1|!!!|??? ssh-ed25519 $ED1\n|2|x|y ssh-ed25519 $ED1")
        assertThat(result.entries).isEmpty()
        assertThat(result.unreadableLines).isEqualTo(2)
    }
}
