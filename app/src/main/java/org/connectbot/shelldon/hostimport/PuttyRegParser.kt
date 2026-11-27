/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.hostimport

import java.io.ByteArrayOutputStream

/**
 * Reads a PuTTY (or KiTTY) session export, made with
 * `reg export HKCU\Software\SimonTatham\PuTTY\Sessions sessions.reg`.
 *
 * Each session is a registry key named after the session, with `HostName`, `Protocol`,
 * `PortNumber` and `UserName` values. Everything else (colours, fonts, key files, proxies) is
 * left behind. A `/` in a session name makes the part before it a folder, as KiTTY does.
 */
internal object PuttyRegParser {
    private const val SESSIONS = "\\PuTTY\\Sessions\\"

    fun looksLike(text: String): Boolean {
        val head = text.trimStart().take(40)
        return (head.startsWith("Windows Registry Editor", ignoreCase = true) || head.startsWith("REGEDIT4", ignoreCase = true)) &&
            text.contains(SESSIONS, ignoreCase = true)
    }

    fun parse(text: String): List<ImportedHost> {
        val hosts = ArrayList<ImportedHost>()
        var name: String? = null
        var values = HashMap<String, String>()

        fun flush() {
            val current = name ?: return
            if (!current.equals("Default Settings", ignoreCase = true)) hosts.add(toHost(current, values))
            name = null
            values = HashMap()
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                flush()
                val key = line.removePrefix("[").removeSuffix("]")
                val at = key.indexOf(SESSIONS, ignoreCase = true)
                if (at >= 0) {
                    val rest = key.substring(at + SESSIONS.length)
                    if (rest.isNotEmpty() && '\\' !in rest) name = unescapeName(rest)
                }
                continue
            }
            if (name == null || !line.startsWith("\"")) continue
            val close = closingQuote(line, 1)
            if (close < 0 || line.getOrNull(close + 1) != '=') continue
            val key = line.substring(1, close)
            val value = line.substring(close + 2)
            when {
                value.startsWith("\"") -> values[key] = unquote(value)
                value.startsWith("dword:", ignoreCase = true) -> value.substring(6).toLongOrNull(16)?.let { values[key] = it.toString() }
            }
        }
        flush()
        return hosts
    }

    private fun toHost(sessionName: String, values: Map<String, String>): ImportedHost {
        fun value(key: String): String? = values.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
        val protocol = when (value("Protocol")?.lowercase() ?: "ssh") {
            "ssh" -> ImportProtocol.SSH
            "telnet" -> ImportProtocol.TELNET
            else -> ImportProtocol.OTHER
        }
        var hostname = value("HostName").orEmpty().trim()
        var user = value("UserName").orEmpty().trim()
        if (user.isEmpty() && hostname.contains('@')) {
            user = hostname.substringBefore('@')
            hostname = hostname.substringAfter('@')
        }
        val parts = sessionName.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        return ImportedHost(
            name = parts.lastOrNull() ?: sessionName,
            hostname = hostname,
            port = value("PortNumber")?.toIntOrNull(),
            username = user,
            protocol = protocol,
            folder = parts.dropLast(1),
        )
    }

    /** Index of the quote that ends the string starting before [from], skipping `\"`, or -1. */
    private fun closingQuote(line: String, from: Int): Int {
        var i = from
        while (i < line.length) {
            when (line[i]) {
                '\\' -> i++
                '"' -> return i
            }
            i++
        }
        return -1
    }

    private fun unquote(value: String): String {
        val end = closingQuote(value, 1)
        val body = if (end < 0) value.substring(1) else value.substring(1, end)
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                out.append(body[i + 1])
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** PuTTY writes `%20` for a space and `%XX` for other awkward bytes in key names. */
    private fun unescapeName(name: String): String {
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < name.length) {
            val c = name[i]
            val code = if (c == '%' && i + 2 < name.length) name.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (code != null) {
                bytes.write(code)
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return TextDecoding.decode(bytes.toByteArray())
    }
}
