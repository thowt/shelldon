/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.hostlist

private const val DEFAULT_SSH_PORT = 22
private const val MAX_PORT = 65535
private const val MAX_HOSTNAME_LENGTH = 253

private val USERNAME = Regex("[A-Za-z0-9._+-]+")
private val HOST_LABEL = Regex("[A-Za-z0-9_]([A-Za-z0-9_-]*[A-Za-z0-9_])?")

/**
 * A one-off SSH destination typed into the search field, as `[user@]host[:port]`.
 */
data class QuickConnectTarget(
    val username: String,
    val hostname: String,
    val port: Int,
) {
    /** Name shown for the temporary connection. */
    val nickname: String
        get() = if (username.isEmpty()) hostname else "$username@$hostname"
}

/**
 * Parses `[user@]host[:port]`. Returns null for anything else, including empty input, spaces,
 * an empty user or host, a port outside 1..65535, and IPv6 literals (use the host editor for those).
 */
fun parseQuickConnect(input: String): QuickConnectTarget? {
    val text = input.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null

    val at = text.lastIndexOf('@')
    val username = if (at >= 0) text.substring(0, at) else ""
    val hostAndPort = if (at >= 0) text.substring(at + 1) else text
    if (at >= 0 && !USERNAME.matches(username)) return null

    val colons = hostAndPort.count { it == ':' }
    if (colons > 1) return null
    val hostname = if (colons == 1) hostAndPort.substringBefore(':') else hostAndPort
    val port = if (colons == 1) {
        val portText = hostAndPort.substringAfter(':')
        if (portText.isEmpty() || !portText.all { it in '0'..'9' }) return null
        portText.toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: return null
    } else {
        DEFAULT_SSH_PORT
    }

    if (!isValidHostname(hostname)) return null
    return QuickConnectTarget(username = username, hostname = hostname, port = port)
}

/**
 * True when [input] looks deliberately like an address (has a user, a port or a dot), so the
 * list can offer to connect without hijacking plain searches such as "nas".
 */
fun looksLikeAddress(input: String): Boolean {
    val text = input.trim()
    return text.contains('@') || text.contains('.') || text.contains(':')
}

private fun isValidHostname(hostname: String): Boolean {
    if (hostname.isEmpty() || hostname.length > MAX_HOSTNAME_LENGTH) return false
    return hostname.split('.').all { HOST_LABEL.matches(it) }
}
