/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sshconfig

import org.connectbot.ui.screens.knownhostlist.parseOpenSshHostKey
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/** `|1|salt|hash` from a `HashKnownHosts yes` file: HMAC-SHA1 of the name, keyed with the salt. */
class HashedHostName(val salt: ByteArray, val hash: ByteArray)

/**
 * One usable line of a `known_hosts` file. Either [names] is set (the comma-separated patterns as
 * written) or [hashed] is, never both.
 */
class KnownHostsEntry(
    val names: List<String>?,
    val hashed: HashedHostName?,
    val algorithm: String,
    val keyBlob: ByteArray,
)

class KnownHostsParseResult(
    val entries: List<KnownHostsEntry>,
    /** `@cert-authority` lines: host certificates are not checked here, so they are not imported. */
    val certAuthorityLines: Int,
    /** `@revoked` lines: never imported. */
    val revokedLines: Int,
    /** Lines that are neither a comment nor a key this app can read. */
    val unreadableLines: Int,
)

/**
 * Reads OpenSSH `known_hosts` files and answers whether an entry is for a given host and port.
 * Wildcards (`*`, `?`), negation (`!`), `[host]:port` and hashed names are understood. Which keys
 * are trusted is decided by the person importing, so nothing here widens trust: an entry only ever
 * matches the exact host and port it names.
 */
object KnownHostsFile {
    fun parse(text: String): KnownHostsParseResult {
        val entries = mutableListOf<KnownHostsEntry>()
        var certAuthority = 0
        var revoked = 0
        var unreadable = 0
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts[0].startsWith("@")) {
                when (parts[0]) {
                    "@cert-authority" -> certAuthority++
                    "@revoked" -> revoked++
                    else -> unreadable++
                }
                continue
            }
            if (parts.size < 3) {
                unreadable++
                continue
            }
            val key = parseOpenSshHostKey(parts.drop(1).joinToString(" "))
            if (key == null) {
                unreadable++
                continue
            }
            val hashed = parts[0].takeIf { it.startsWith("|1|") }?.let(::parseHashed)
            if (parts[0].startsWith("|") && hashed == null) {
                unreadable++
                continue
            }
            entries.add(
                KnownHostsEntry(
                    names = if (hashed == null) parts[0].split(',').filter { it.isNotEmpty() } else null,
                    hashed = hashed,
                    algorithm = key.algorithm,
                    keyBlob = key.keyBlob,
                ),
            )
        }
        return KnownHostsParseResult(entries, certAuthority, revoked, unreadable)
    }

    /** True when [entry] names [hostname] on [port]. A bare name means port 22; other ports are written `[name]:port`. */
    fun matches(entry: KnownHostsEntry, hostname: String, port: Int): Boolean {
        val host = hostname.trim().lowercase()
        entry.hashed?.let { hashed ->
            val candidate = if (port == DEFAULT_PORT) host else "[$host]:$port"
            return hmacSha1(hashed.salt, candidate.toByteArray(Charsets.UTF_8)).contentEquals(hashed.hash)
        }
        var matched = false
        for (token in entry.names.orEmpty()) {
            val negated = token.startsWith("!")
            if (!patternMatches(token.removePrefix("!").lowercase(), host, port)) continue
            if (negated) return false
            matched = true
        }
        return matched
    }

    private fun patternMatches(pattern: String, host: String, port: Int): Boolean {
        val hostPattern: String
        val patternPort: Int
        if (pattern.startsWith("[")) {
            val end = pattern.indexOf("]:")
            if (end < 0) return false
            hostPattern = pattern.substring(1, end)
            patternPort = pattern.substring(end + 2).toIntOrNull() ?: return false
        } else {
            hostPattern = pattern
            patternPort = DEFAULT_PORT
        }
        if (patternPort != port) return false
        return if (hostPattern.any { it == '*' || it == '?' }) SshConfigParser.glob(hostPattern, host) else hostPattern == host
    }

    private fun parseHashed(token: String): HashedHostName? {
        val fields = token.split('|')
        if (fields.size != 4) return null
        return try {
            HashedHostName(Base64.decode(fields[2]), Base64.decode(fields[3]))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(data)
    }

    private const val DEFAULT_PORT = 22
}
