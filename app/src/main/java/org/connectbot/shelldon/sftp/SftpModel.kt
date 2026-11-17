/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

enum class SftpKind { FOLDER, FILE, LINK, OTHER }

/** One row of the file list. */
data class SftpItem(
    val name: String,
    val path: String,
    val kind: SftpKind,
    val size: Long?,
    /** Seconds since 1970, if the server said. */
    val modified: Long?,
    val permissions: Int?,
    /** A link whose target is a folder opens like one. */
    val linkToFolder: Boolean = false,
) {
    val opensAsFolder: Boolean get() = kind == SftpKind.FOLDER || (kind == SftpKind.LINK && linkToFolder)
    val isHidden: Boolean get() = name.startsWith('.')
}

object SftpItems {
    private const val TYPE_MASK = 0xF000
    private const val TYPE_FOLDER = 0x4000
    private const val TYPE_FILE = 0x8000
    private const val TYPE_LINK = 0xA000

    /** From the mode bits of an SFTP attribute block; null if the server sent none. */
    fun kindOf(permissions: Int?): SftpKind? = when (permissions?.and(TYPE_MASK)) {
        null -> null
        TYPE_FOLDER -> SftpKind.FOLDER
        TYPE_FILE -> SftpKind.FILE
        TYPE_LINK -> SftpKind.LINK
        0 -> null
        else -> SftpKind.OTHER
    }

    /** Folders (and links to folders) first, then by name without regard to case. */
    fun sorted(items: List<SftpItem>): List<SftpItem> = items.sortedWith(
        compareBy<SftpItem> { !it.opensAsFolder }.thenBy { it.name.lowercase() }.thenBy { it.name },
    )

    /** `drwxr-xr-x` style, or null without permission bits. */
    fun permissionString(item: SftpItem): String? {
        val bits = item.permissions ?: return null
        val type = when (item.kind) {
            SftpKind.FOLDER -> 'd'
            SftpKind.LINK -> 'l'
            SftpKind.FILE -> '-'
            SftpKind.OTHER -> '?'
        }
        val rwx = StringBuilder().append(type)
        "rwxrwxrwx".forEachIndexed { index, c -> rwx.append(if (bits and (1 shl (8 - index)) != 0) c else '-') }
        return rwx.toString()
    }

    /** The server's 32-bit time, which is unsigned on the wire. */
    fun seconds(time: Int?): Long? = time?.toLong()?.and(0xFFFFFFFFL)
}
