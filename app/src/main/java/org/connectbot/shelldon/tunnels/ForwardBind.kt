/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.tunnels

import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Where a local (`-L`) or dynamic (`-D`) forward listens on this phone.
 *
 * The stored value is the `sourceAddr` of the forward, in OpenSSH's words: `localhost` (the default
 * and what every forward made before this existed holds), `127.0.0.1`, `::1`, an empty string or `*`
 * for every interface, `0.0.0.0`, `::`, or the literal address of one interface. A host name other
 * than `localhost` is refused on purpose: resolving it would be a network lookup at the moment a
 * tunnel starts, and the answer could change what gets exposed.
 *
 * Anything that is not understood gives null, and the caller must not start the forward. It never
 * falls back to another address, in either direction.
 */
object ForwardBind {
    private val LOOPBACK_V4 = byteArrayOf(127, 0, 0, 1)

    fun socketAddress(sourceAddr: String, port: Int): InetSocketAddress? {
        if (port !in 0..65535) return null
        val text = sourceAddr.trim().removePrefix("[").removeSuffix("]")
        if (text.isEmpty() || text == "*") return InetSocketAddress(port)
        if (text.equals("localhost", ignoreCase = true)) return InetSocketAddress(InetAddress.getByAddress(LOOPBACK_V4), port)
        val bytes = parseIpv4(text) ?: parseIpv6(text) ?: return null
        return InetSocketAddress(InetAddress.getByAddress(bytes), port)
    }

    /** True when anything other than this phone can reach the listening port, or the address is not understood. */
    fun isExposed(sourceAddr: String): Boolean {
        val address = socketAddress(sourceAddr, 0) ?: return true
        return !address.address.isLoopbackAddress
    }

    private fun parseIpv4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val value = part.toInt()
            if (value > 255) return null
            out[i] = value.toByte()
        }
        return out
    }

    /** Eight groups with at most one `::`, and an optional IPv4 tail. No zone ids, no host names, no lookup. */
    private fun parseIpv6(input: String): ByteArray? {
        if (!input.contains(':')) return null
        if (!input.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return null
        var text = input
        if (text.contains('.')) {
            val cut = text.lastIndexOf(':')
            val tail = parseIpv4(text.substring(cut + 1)) ?: return null
            val high = ((tail[0].toInt() and 0xFF) shl 8) or (tail[1].toInt() and 0xFF)
            val low = ((tail[2].toInt() and 0xFF) shl 8) or (tail[3].toInt() and 0xFF)
            text = text.substring(0, cut + 1) + Integer.toHexString(high) + ":" + Integer.toHexString(low)
        }
        val halves = text.split("::")
        if (halves.size > 2) return null
        fun groups(part: String): List<String>? {
            if (part.isEmpty()) return emptyList()
            val list = part.split(':')
            return if (list.all { it.length in 1..4 }) list else null
        }
        val head = groups(halves[0]) ?: return null
        val rest = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
        val all: List<String> = when {
            halves.size == 1 -> if (head.size == 8) head else return null
            head.size + rest.size <= 7 -> head + List(8 - head.size - rest.size) { "0" } + rest
            else -> return null
        }
        val out = ByteArray(16)
        for ((i, group) in all.withIndex()) {
            val value = group.toInt(16)
            out[i * 2] = (value shr 8).toByte()
            out[i * 2 + 1] = value.toByte()
        }
        return out
    }
}
