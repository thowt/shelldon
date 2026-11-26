/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.hostimport

import java.util.UUID

/** What the person left switched on in the preview. */
data class HostImportOptions(
    /** Telnet sends everything in clear text, so those entries stay out unless asked for. */
    val includeTelnet: Boolean = false,
)

/** A host that is already saved, as far as the planner needs to know. */
data class SavedHost(val nickname: String, val hostname: String, val port: Int, val protocol: String)

enum class PlannedStatus { NEW, EXISTS }

/** One host as it will be saved, or why it will not be. */
data class HostToImport(
    val id: UUID,
    val nickname: String,
    /** The name as the file had it, when [nickname] had to be changed to avoid a clash. */
    val renamedFrom: String?,
    val hostname: String,
    val port: Int,
    val username: String,
    /** `ssh` or `telnet`, the values of the host's protocol column. */
    val protocol: String,
    val folder: List<String>,
    val tags: List<String>,
    val status: PlannedStatus,
)

/** Everything the preview shows, and everything the import writes. */
data class HostImportPlan(
    val hosts: List<HostToImport>,
    /** Telnet entries left out because the option is off. */
    val telnetLeftOut: Int,
    /** RDP, VNC, FTP, serial, SSH1 and the like. */
    val otherProtocols: Int,
    /** No usable address or port, or characters that cannot be part of one. */
    val invalid: Int,
    /** Entries that had a password in the file. Passwords are never imported. */
    val passwordsLeftBehind: Int,
) {
    val newHosts: List<HostToImport> get() = hosts.filter { it.status == PlannedStatus.NEW }

    val existing: Int get() = hosts.count { it.status == PlannedStatus.EXISTS }

    /** The folders and tags that new hosts use, each counted once. */
    val folderPaths: Set<List<String>>
        get() = newHosts.flatMap { host -> host.folder.indices.map { host.folder.take(it + 1).map(String::lowercase) } }.toSet()

    val tagNames: Set<String> get() = newHosts.flatMap { host -> host.tags.map(String::lowercase) }.toSet()
}

/**
 * Turns what a file held into hosts that can be saved. Pure: it only reads its arguments.
 *
 * A host that is already saved (same address, port and protocol, and the same name or that name with
 * a number) is left alone and listed as such, so importing the same file twice adds nothing. A
 * different host with a name that is taken gets a number added to its name and never replaces
 * the existing one.
 */
object HostImportPlanner {
    const val MAX_NAME = 100
    const val MAX_TAGS_PER_HOST = 20
    const val MAX_HOSTS = 5_000

    fun plan(
        imported: List<ImportedHost>,
        saved: List<SavedHost>,
        options: HostImportOptions,
        maxFolderDepth: Int,
        newId: () -> UUID = UUID::randomUUID,
    ): HostImportPlan {
        val takenNames = HashSet<String>()
        val namesAtAddress = HashMap<String, MutableList<String>>()
        for (host in saved) {
            takenNames.add(host.nickname.lowercase())
            namesAtAddress.getOrPut(addressKey(host.hostname, host.port, host.protocol)) { ArrayList() }.add(host.nickname.lowercase())
        }

        val planned = ArrayList<HostToImport>()
        var telnetLeftOut = 0
        var other = 0
        var invalid = 0
        var passwords = 0

        for (entry in imported) {
            if (planned.size >= MAX_HOSTS) {
                invalid++
                continue
            }
            if (entry.protocol == ImportProtocol.OTHER) {
                other++
                continue
            }
            if (entry.protocol == ImportProtocol.TELNET && !options.includeTelnet) {
                telnetLeftOut++
                continue
            }
            val address = address(entry)
            if (address == null) {
                invalid++
                continue
            }
            if (entry.hadPassword) passwords++

            val protocol = if (entry.protocol == ImportProtocol.TELNET) "telnet" else "ssh"
            val wanted = clean(entry.name).ifEmpty { address.hostname }.take(MAX_NAME)
            val addressKey = addressKey(address.hostname, address.port, protocol)
            val folder = entry.folder.map { clean(it).take(MAX_NAME) }.filter { it.isNotEmpty() }.take(maxFolderDepth)
            val tags = entry.tags.map { clean(it).take(MAX_NAME) }.filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }.take(MAX_TAGS_PER_HOST)

            if (namesAtAddress[addressKey].orEmpty().any { sameNameOrNumbered(it, wanted.lowercase()) }) {
                planned.add(
                    HostToImport(newId(), wanted, null, address.hostname, address.port, address.username, protocol, folder, tags, PlannedStatus.EXISTS),
                )
                continue
            }
            val nickname = freeName(wanted, takenNames)
            takenNames.add(nickname.lowercase())
            namesAtAddress.getOrPut(addressKey) { ArrayList() }.add(nickname.lowercase())
            planned.add(
                HostToImport(
                    id = newId(),
                    nickname = nickname,
                    renamedFrom = wanted.takeIf { it != nickname },
                    hostname = address.hostname,
                    port = address.port,
                    username = address.username,
                    protocol = protocol,
                    folder = folder,
                    tags = tags,
                    status = PlannedStatus.NEW,
                ),
            )
        }
        return HostImportPlan(planned, telnetLeftOut, other, invalid, passwords)
    }

    private class Address(val hostname: String, val port: Int, val username: String)

    /** The checked address of [entry], or null when it cannot be used. */
    private fun address(entry: ImportedHost): Address? {
        var hostname = clean(entry.hostname)
        var username = clean(entry.username)
        var port = entry.port
        if (hostname.contains('@')) {
            if (username.isEmpty()) username = hostname.substringBefore('@')
            hostname = hostname.substringAfter('@')
        }
        // host:port, but not an IPv6 address, which has several colons
        if (hostname.count { it == ':' } == 1) {
            val tail = hostname.substringAfter(':').toIntOrNull()
            if (tail != null) {
                if (port == null) port = tail
                hostname = hostname.substringBefore(':')
            }
        }
        hostname = hostname.removeSurrounding("[", "]")
        if (hostname.isEmpty() || hostname.length > 255) return null
        if (hostname.any { it.isWhitespace() || it == '/' || it == '\\' || it == '@' || it == '"' || it == '\'' }) return null
        val finalPort = port ?: if (entry.protocol == ImportProtocol.TELNET) 23 else 22
        if (finalPort !in 1..65535) return null
        return Address(hostname, finalPort, username.take(MAX_NAME))
    }

    /** Trims and drops control characters, which have no place in a name or an address. */
    private fun clean(value: String): String = value.filterNot { it.isISOControl() }.trim()

    private fun addressKey(hostname: String, port: Int, protocol: String): String = "${hostname.lowercase()}\u0000$port\u0000$protocol"

    /** True for `web` and for `web (2)`, the name this planner gives a second host called `web`. */
    private fun sameNameOrNumbered(saved: String, wanted: String): Boolean {
        if (saved == wanted) return true
        if (!saved.startsWith("$wanted (") || !saved.endsWith(")")) return false
        val number = saved.substring(wanted.length + 2, saved.length - 1)
        return number.isNotEmpty() && number.all { it in '0'..'9' }
    }

    private fun freeName(wanted: String, taken: Set<String>): String {
        if (wanted.lowercase() !in taken) return wanted
        var n = 2
        while (true) {
            val suffix = " ($n)"
            val candidate = wanted.take(MAX_NAME - suffix.length) + suffix
            if (candidate.lowercase() !in taken) return candidate
            n++
        }
    }
}
