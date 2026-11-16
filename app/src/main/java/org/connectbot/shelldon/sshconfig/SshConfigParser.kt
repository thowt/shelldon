/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sshconfig

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
 * Reads the subset of `ssh_config(5)` that describes hosts: `Host`, `HostName`, `User`, `Port` and
 * `ProxyJump`. Wildcard blocks such as `Host *` act as defaults the way OpenSSH applies them: the
 * first value found, in file order, wins. Everything else is reported rather than guessed at.
 */
object SshConfigParser {
    private val IMPORTED = setOf("hostname", "user", "port", "proxyjump")

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
            for (block in blocks) {
                if (!applies(block, alias)) continue
                for ((key, value) in block.options) effective.putIfAbsent(key, value)
            }
            SshConfigHost(
                alias = alias,
                hostName = (effective["hostname"] ?: alias).replace("%h", alias),
                user = effective["user"]?.takeIf { it.isNotBlank() },
                port = effective["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22,
                proxyJump = effective["proxyjump"]?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) },
                unsupported = effective.keys.filterNot { it in IMPORTED }.toSet(),
            )
        }
        return SshConfigParseResult(hosts, wildcardPatterns, matchBlocks, includes)
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
