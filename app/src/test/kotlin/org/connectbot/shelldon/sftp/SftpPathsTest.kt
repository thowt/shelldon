/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class SftpPathsTest {
    @Test
    fun normalizeCollapsesDotsAndSlashes() {
        assertThat(SftpPaths.normalize("/a//b/./c")).isEqualTo("/a/b/c")
        assertThat(SftpPaths.normalize("/a/b/../c")).isEqualTo("/a/c")
        assertThat(SftpPaths.normalize("/")).isEqualTo("/")
        assertThat(SftpPaths.normalize("")).isEqualTo("/")
        assertThat(SftpPaths.normalize("relative/dir")).isEqualTo("/relative/dir")
    }

    @Test
    fun dotDotNeverClimbsAboveTheRoot() {
        assertThat(SftpPaths.normalize("/../../etc")).isEqualTo("/etc")
        assertThat(SftpPaths.parent("/")).isEqualTo("/")
        assertThat(SftpPaths.parent("/a")).isEqualTo("/")
        assertThat(SftpPaths.parent("/a/b/c")).isEqualTo("/a/b")
    }

    @Test
    fun childJoinsAndRefusesNamesThatCouldEscape() {
        assertThat(SftpPaths.child("/", "etc")).isEqualTo("/etc")
        assertThat(SftpPaths.child("/home/me", "notes.txt")).isEqualTo("/home/me/notes.txt")
        for (bad in listOf("", ".", "..", "a/b", "../x", "x\u0000y")) {
            val thrown = try {
                SftpPaths.child("/home/me", bad)
                null
            } catch (e: IllegalArgumentException) {
                e
            }
            assertThat(thrown).describedAs("name %s", bad).isNotNull()
        }
    }

    @Test
    fun listableNamesExcludeWhatAHostileServerCouldSend() {
        assertThat(SftpPaths.isListable("report.pdf")).isTrue()
        assertThat(SftpPaths.isListable(".bashrc")).isTrue()
        assertThat(SftpPaths.isListable("with space")).isTrue()
        assertThat(SftpPaths.isListable("..")).isFalse()
        assertThat(SftpPaths.isListable(".")).isFalse()
        assertThat(SftpPaths.isListable("a/b")).isFalse()
        assertThat(SftpPaths.isListable("/etc/passwd")).isFalse()
        assertThat(SftpPaths.isListable("")).isFalse()
    }

    @Test
    fun newNamesAreStricterThanListedOnes() {
        assertThat(SftpPaths.isValidNewName("ok name.txt")).isTrue()
        assertThat(SftpPaths.isValidNewName("   ")).isFalse()
        assertThat(SftpPaths.isValidNewName("line\nbreak")).isFalse()
        assertThat(SftpPaths.isValidNewName("a".repeat(255))).isTrue()
        assertThat(SftpPaths.isValidNewName("a".repeat(256))).isFalse()
        assertThat(SftpPaths.isValidNewName("é".repeat(128))).isFalse()
    }

    @Test
    fun breadcrumbsWalkFromTheRoot() {
        assertThat(SftpPaths.breadcrumbs("/")).containsExactly(Crumb("/", "/"))
        assertThat(SftpPaths.breadcrumbs("/var/log")).containsExactly(Crumb("/", "/"), Crumb("var", "/var"), Crumb("log", "/var/log"))
    }

    @Test
    fun printableReplacesControlCharacters() {
        assertThat(SftpPaths.printable("plain")).isEqualTo("plain")
        assertThat(SftpPaths.printable("a\nb\u001bc")).isEqualTo("a�b�c")
    }

    @Test
    fun localFileNamesCannotMeanAFolder() {
        assertThat(SftpPaths.localFileName("a/b\\c")).isEqualTo("a_b_c")
        assertThat(SftpPaths.localFileName("..")).isEqualTo("download")
        assertThat(SftpPaths.localFileName("  ")).isEqualTo("download")
        assertThat(SftpPaths.localFileName(".bashrc")).isEqualTo(".bashrc")
        assertThat(SftpPaths.localFileName("tab\there")).isEqualTo("tab_here")
    }

    @Test
    fun remoteNamesFromLocalFilesAreAlwaysValid() {
        for (local in listOf("report.pdf", "a/b", "..", "", "   ", "tab\there", "x".repeat(400), "é".repeat(300))) {
            val name = SftpPaths.remoteFileName(local)
            assertThat(SftpPaths.isValidNewName(name)).describedAs("from %s gave %s", local, name).isTrue()
        }
        assertThat(SftpPaths.remoteFileName("report.pdf")).isEqualTo("report.pdf")
    }
}
