/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sshconfig

import org.connectbot.sshlib.AlgorithmCategory

/** One concrete `Host` alias from an OpenSSH config, with the settings that apply to it after inheritance. */
data class SshConfigHost(
    val alias: String,
    val hostName: String,
    val user: String?,
    val port: Int,
    /** The `ProxyJump` value as written, or null. Only a single hop can be imported. */
    val proxyJump: String?,
    /** Lower-case option names that apply to this host and are not imported (for the summary). */
    val unsupported: Set<String>,
    /** Algorithm lists as written in the file (`+aes128-cbc`, `a,b,c`), by category. */
    val algorithms: Map<AlgorithmCategory, String> = emptyMap(),
    /** Categories in [algorithms] whose value came from a wildcard block such as `Host *`, not from a block naming this host. */
    val algorithmsFromWildcard: Set<AlgorithmCategory> = emptySet(),
    /** Every `IdentityFile` that applies, in file order, as written. */
    val identityFiles: List<String> = emptyList(),
    /** False when `PubkeyAuthentication no` applies; null when the file says nothing. */
    val pubkeyAuthentication: Boolean? = null,
    /**
     * Options this app refuses on purpose, as `option to value`: host key checking turned off, or a
     * `known_hosts` file that is `/dev/null`. They are never imported.
     */
    val refused: Map<String, String> = emptyMap(),
)

data class SshConfigParseResult(
    val hosts: List<SshConfigHost>,
    /** Wildcard-only `Host` patterns (they supply defaults but are not hosts). */
    val wildcardPatterns: List<String>,
    /** `Match` blocks cannot be evaluated here and are skipped. */
    val matchBlocks: Int,
    /** `Include` lines are not followed; the included files must be imported on their own. */
    val includes: List<String>,
)

/**
 * Reads the subset of `ssh_config(5)` that describes hosts: `Host`, `HostName`, `User`, `Port`,
 * `ProxyJump`, the algorithm lists, `IdentityFile` and `PubkeyAuthentication`. Wildcard blocks such as
 * `Host *` act as defaults the way OpenSSH applies them: the first value found, in file order, wins
 * (`IdentityFile` is the exception: every value applies). Everything else is reported rather than guessed at.
 */
object SshConfigParser {
    private val ALGORITHM_KEYWORDS = mapOf(
        "kexalgorithms" to AlgorithmCategory.KEX,
        "ciphers" to AlgorithmCategory.CIPHER,
        "macs" to AlgorithmCategory.MAC,
        "hostkeyalgorithms" to AlgorithmCategory.HOST_KEY,
        "pubkeyacceptedalgorithms" to AlgorithmCategory.PUBKEY,
        // The name OpenSSH used before 8.5.
        "pubkeyacceptedkeytypes" to AlgorithmCategory.PUBKEY,
    )
    private val IMPORTED = setOf("hostname", "user", "port", "proxyjump", "identityfile", "pubkeyauthentication") + ALGORITHM_KEYWORDS.keys
    private val HOST_KEY_CHECKING_OFF = setOf("no", "off", "false", "accept-new")

    private class Block(val patterns: List<String>) {
        val options = mutableListOf<Pair<String, String>>()
    }

    fun parse(text: String): SshConfigParseResult {
        val blocks = mutableListOf(Block(listOf("*")))
        val wildcardPatterns = mutableListOf<String>()
        val includes = mutableListOf<String>()
        var matchBlocks = 0

        for (rawLine in text.lineSequence()) {
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) continue
            val keywordEnd = line.indexOfFirst { it.isWhitespace() || it == '=' }
            val keyword = (if (keywordEnd < 0) line else line.substring(0, keywordEnd)).lowercase()
            val value = if (keywordEnd < 0) "" else line.substring(keywordEnd).trimStart { it.isWhitespace() || it == '=' }.trim()
            when (keyword) {
                "host" -> {
                    val patterns = tokens(value)
                    blocks.add(Block(patterns))
                    patterns.filter { it.isWildcard() && !it.startsWith("!") }.forEach { if (it !in wildcardPatterns) wildcardPatterns.add(it) }
                }

                "match" -> {
                    matchBlocks++
                    blocks.add(Block(emptyList()))
                }

                "include" -> includes.addAll(tokens(value))

                else -> blocks.last().options.add(keyword to unquote(value))
            }
        }

        val aliases = blocks.flatMap { it.patterns }
            .filter { !it.startsWith("!") && !it.isWildcard() }
            .distinct()

        val hosts = aliases.map { alias ->
            val effective = LinkedHashMap<String, String>()
            val fromWildcard = mutableSetOf<String>()
            val identityFiles = mutableListOf<String>()
            for (block in blocks) {
                if (!applies(block, alias)) continue
                val names = block.patterns.any { !it.startsWith("!") && !it.isWildcard() && it == alias }
                for ((key, value) in block.options) {
                    if (key == "identityfile") {
                        if (value.isNotBlank() && !value.equals("none", ignoreCase = true)) identityFiles.add(value)
                    } else if (effective.putIfAbsent(key, value) == null && !names) {
                        fromWildcard.add(key)
                    }
                }
            }
            val algorithms = LinkedHashMap<AlgorithmCategory, String>()
            val algorithmsFromWildcard = mutableSetOf<AlgorithmCategory>()
            for ((keyword, category) in ALGORITHM_KEYWORDS) {
                val value = effective[keyword]?.takeIf { it.isNotBlank() } ?: continue
                if (category in algorithms) continue
                algorithms[category] = value
                if (keyword in fromWildcard) algorithmsFromWildcard.add(category)
            }
            val refused = LinkedHashMap<String, String>()
            effective["stricthostkeychecking"]?.takeIf { it.lowercase() in HOST_KEY_CHECKING_OFF }?.let { refused["StrictHostKeyChecking"] = it }
            effective["userknownhostsfile"]?.takeIf { it.trim() == "/dev/null" }?.let { refused["UserKnownHostsFile"] = it }
            SshConfigHost(
                alias = alias,
                hostName = (effective["hostname"] ?: alias).replace("%h", alias),
                user = effective["user"]?.takeIf { it.isNotBlank() },
                port = effective["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22,
                proxyJump = effective["proxyjump"]?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) },
                unsupported = effective.keys.filterNot { it in IMPORTED }.toSet(),
                algorithms = algorithms,
                algorithmsFromWildcard = algorithmsFromWildcard,
                identityFiles = identityFiles,
                pubkeyAuthentication = if (effective["pubkeyauthentication"]?.lowercase() in setOf("no", "false")) false else null,
                refused = refused,
            )
        }
        return SshConfigParseResult(hosts, wildcardPatterns, matchBlocks, includes)
    }

    /**
     * OpenSSH writes `+a,b` for "append a and b" and `a,b` for "exactly these". The app's policy syntax
     * carries the operation on every entry (`+a,+b`), which is also what its editor produces.
     * Returns null for an empty list.
     */
    fun toPolicyExpression(openSshValue: String): String? {
        val value = openSshValue.trim()
        val operation = value.firstOrNull()?.takeIf { it == '+' || it == '-' || it == '^' }
        val names = (if (operation == null) value else value.substring(1)).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return null
        return names.joinToString(",") { if (operation == null) it else "$operation$it" }
    }

    private fun applies(block: Block, alias: String): Boolean {
        val positive = block.patterns.filter { !it.startsWith("!") }
        val negative = block.patterns.filter { it.startsWith("!") }.map { it.substring(1) }
        return positive.any { glob(it, alias) } && negative.none { glob(it, alias) }
    }

    private fun String.isWildcard() = any { it == '*' || it == '?' } || startsWith("!")

    /** OpenSSH patterns: `*` and `?` only, case-sensitive. */
    internal fun glob(pattern: String, text: String): Boolean {
        val regex = buildString {
            pattern.forEach { c ->
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
        }
        return Regex(regex).matches(text)
    }

    /** Whitespace-separated words, with double quotes grouping. */
    private fun tokens(value: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var hasToken = false
        for (c in value) {
            when {
                c == '"' -> {
                    inQuotes = !inQuotes
                    hasToken = true
                }

                c.isWhitespace() && !inQuotes -> {
                    if (hasToken) result.add(current.toString())
                    current.clear()
                    hasToken = false
                }

                else -> {
                    current.append(c)
                    hasToken = true
                }
            }
        }
        if (hasToken) result.add(current.toString())
        return result
    }

    private fun unquote(value: String): String = if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) value.substring(1, value.length - 1) else value

    /** A `#` starts a comment at the start of a line or after whitespace, outside quotes. */
    private fun stripComment(line: String): String {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            if (c == '#' && !inQuotes && (i == 0 || line[i - 1].isWhitespace())) return line.substring(0, i)
        }
        return line
    }
}
