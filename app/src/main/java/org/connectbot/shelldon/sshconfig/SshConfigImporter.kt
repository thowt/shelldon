/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sshconfig

import androidx.room.withTransaction
import org.connectbot.data.ConnectBotDatabase
import org.connectbot.data.entity.Host
import javax.inject.Inject
import javax.inject.Singleton

data class SshConfigImportResult(
    val added: Int,
    /** Hosts whose nickname already exists; they are left exactly as they are. */
    val skipped: Int,
    val jumpHostsLinked: Int,
    /** Aliases whose `ProxyJump` could not be turned into a jump host (several hops, or an unknown host). */
    val jumpHostsUnresolved: List<String>,
    /** Option names that applied to added hosts but are not imported. */
    val unsupportedOptions: Set<String>,
    val matchBlocks: Int,
    val includes: List<String>,
)

/**
 * Adds hosts from an OpenSSH config file. It only adds: a host whose nickname already exists is
 * skipped untouched. No credentials are read; `IdentityFile` is reported, not followed.
 */
@Singleton
class SshConfigImporter @Inject constructor(private val database: ConnectBotDatabase) {
    suspend fun import(text: String): SshConfigImportResult {
        val parsed = SshConfigParser.parse(text)
        return database.withTransaction {
            val dao = database.hostDao()
            val byNickname = dao.getAll().associateBy { it.nickname }.toMutableMap()
            val created = mutableListOf<Pair<SshConfigHost, Host>>()
            var skipped = 0

            for (entry in parsed.hosts) {
                if (byNickname.containsKey(entry.alias)) {
                    skipped++
                    continue
                }
                val host = Host(nickname = entry.alias, hostname = entry.hostName, username = entry.user.orEmpty(), port = entry.port)
                dao.insert(host)
                byNickname[entry.alias] = host
                created.add(entry to host)
            }

            var linked = 0
            val unresolved = mutableListOf<String>()
            for ((entry, host) in created) {
                val proxy = entry.proxyJump ?: continue
                val jump = resolveJump(proxy, byNickname.values)
                if (jump == null || jump.id == host.id) {
                    unresolved.add(entry.alias)
                } else {
                    dao.update(host.copy(jumpHostId = jump.id))
                    linked++
                }
            }

            SshConfigImportResult(
                added = created.size,
                skipped = skipped,
                jumpHostsLinked = linked,
                jumpHostsUnresolved = unresolved,
                unsupportedOptions = created.flatMap { it.first.unsupported }.toSortedSet(),
                matchBlocks = parsed.matchBlocks,
                includes = parsed.includes,
            )
        }
    }

    /**
     * One hop only: an alias that is a host here, or `[user@]host[:port]` matching a host's address.
     */
    private fun resolveJump(proxyJump: String, hosts: Collection<Host>): Host? {
        if (proxyJump.contains(',')) return null
        hosts.firstOrNull { it.nickname == proxyJump }?.let { return it }
        val address = proxyJump.substringAfter('@').substringBeforeLast(':', proxyJump.substringAfter('@'))
        val port = proxyJump.substringAfter('@').substringAfterLast(':', "").toIntOrNull()
        return hosts.firstOrNull { it.hostname == address && (port == null || it.port == port) }
    }
}
