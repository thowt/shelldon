/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class SftpItemsTest {
    private fun item(name: String, kind: SftpKind, perms: Int? = null, linkToFolder: Boolean = false) = SftpItem(name, "/$name", kind, 1, 0, perms, linkToFolder)

    @Test
    fun kindComesFromTheModeBits() {
        assertThat(SftpItems.kindOf(0x41ED)).isEqualTo(SftpKind.FOLDER)
        assertThat(SftpItems.kindOf(0x81A4)).isEqualTo(SftpKind.FILE)
        assertThat(SftpItems.kindOf(0xA1FF)).isEqualTo(SftpKind.LINK)
        assertThat(SftpItems.kindOf(0x2190)).isEqualTo(SftpKind.OTHER)
        assertThat(SftpItems.kindOf(null)).isNull()
        assertThat(SftpItems.kindOf(0x1A4)).isNull()
    }

    @Test
    fun permissionStringLooksLikeLs() {
        assertThat(SftpItems.permissionString(item("d", SftpKind.FOLDER, 0x41ED))).isEqualTo("drwxr-xr-x")
        assertThat(SftpItems.permissionString(item("f", SftpKind.FILE, 0x81A4))).isEqualTo("-rw-r--r--")
        assertThat(SftpItems.permissionString(item("l", SftpKind.LINK, 0xA1FF))).isEqualTo("lrwxrwxrwx")
        assertThat(SftpItems.permissionString(item("x", SftpKind.FILE, null))).isNull()
    }

    @Test
    fun foldersComeFirstThenNamesWithoutCase() {
        val sorted = SftpItems.sorted(
            listOf(
                item("beta.txt", SftpKind.FILE),
                item("Alpha", SftpKind.FOLDER),
                item("alpha.txt", SftpKind.FILE),
                item("link-to-dir", SftpKind.LINK, linkToFolder = true),
                item("zeta", SftpKind.FOLDER),
                item("broken-link", SftpKind.LINK),
            ),
        )
        assertThat(sorted.map { it.name }).containsExactly("Alpha", "link-to-dir", "zeta", "alpha.txt", "beta.txt", "broken-link")
    }

    @Test
    fun timesAreUnsigned() {
        assertThat(SftpItems.seconds(1_700_000_000)).isEqualTo(1_700_000_000L)
        assertThat(SftpItems.seconds(-1)).isEqualTo(4_294_967_295L)
        assertThat(SftpItems.seconds(null)).isNull()
    }
}
