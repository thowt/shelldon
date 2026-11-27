/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.hostimport

/**
 * Reads MobaXterm session exports (`.mxtsessions`) and the `[Bookmarks]` sections of `MobaXterm.ini`.
 *
 * A session line is `Name=#<icon>#<type>%host%port%user%...`. The type decides the protocol (0 is
 * SSH, 1 is Telnet, everything else is RDP, VNC, FTP and so on). `SubRep` in each `[Bookmarks_N]`
 * section is the folder, with backslashes between levels. The many display options are ignored,
 * and so is the password, which MobaXterm does not write to this file anyway.
 */
internal object MobaXtermParser {
    private val session = Regex("^#(\\d+)#(\\d+)%(.*)$")

    fun looksLike(text: String): Boolean = text.lineSequence().any { it.trim().startsWith("[Bookmarks", ignoreCase = true) } &&
        text.lineSequence().any { it.trimStart().startsWith("SubRep=", ignoreCase = true) }

    fun parse(text: String): List<ImportedHost> {
        val hosts = ArrayList<ImportedHost>()
        var inBookmarks = false
        var folder: List<String> = emptyList()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(";")) continue
            if (line.startsWith("[")) {
                inBookmarks = line.removePrefix("[").startsWith("Bookmarks", ignoreCase = true)
                folder = emptyList()
                continue
            }
            if (!inBookmarks) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            if (key.equals("SubRep", ignoreCase = true)) {
                folder = value.split('\\', '/').map { it.trim() }.filter { it.isNotEmpty() }
                continue
            }
            if (key.equals("ImgNum", ignoreCase = true)) continue
            val match = session.matchEntire(value) ?: continue
            val protocol = when (match.groupValues[2]) {
                "0" -> ImportProtocol.SSH
                "1" -> ImportProtocol.TELNET
                else -> ImportProtocol.OTHER
            }
            val parts = match.groupValues[3].split('%')
            val user = parts.getOrNull(2).orEmpty().trim().takeUnless { it == "<default>" || it == "-1" }.orEmpty()
            hosts.add(
                ImportedHost(
                    name = key.trim(),
                    hostname = parts.getOrNull(0).orEmpty().trim(),
                    port = parts.getOrNull(1)?.trim()?.toIntOrNull(),
                    username = if (protocol == ImportProtocol.SSH) user else "",
                    protocol = protocol,
                    folder = folder,
                ),
            )
        }
        return hosts
    }
}
