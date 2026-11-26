/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.hostimport

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.connectbot.data.ConnectBotDatabase
import org.connectbot.data.EntityIds
import org.connectbot.data.entity.Host
import org.connectbot.shelldon.data.groups.GroupTree
import org.connectbot.shelldon.data.groups.HostGroup
import org.connectbot.shelldon.data.groups.HostTag
import org.connectbot.shelldon.data.groups.Outcome
import org.connectbot.shelldon.data.groups.ShelldonGroupRepository
import org.connectbot.shelldon.data.groups.Tag
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

class HostImportResult(
    val hostsAdded: Int,
    /** Hosts that were already saved, or whose name was taken in the meantime. Left as they are. */
    val hostsSkipped: Int,
    val foldersCreated: Int,
    val tagsCreated: Int,
)

/**
 * Saves a [HostImportPlan]: hosts, the folders they sit in and their tags, in one transaction, so a
 * failure leaves nothing behind. It only adds. Folders and tags that already exist (compared
 * ignoring case) are reused, and no existing host is changed. No password is ever written.
 */
@Singleton
class HostImporter @Inject constructor(
    private val database: ConnectBotDatabase,
    private val groups: ShelldonGroupRepository,
) {
    /** What is saved right now, for the planner. */
    suspend fun saved(): List<SavedHost> = database.hostDao().getAll().map { SavedHost(it.nickname, it.hostname, it.port, it.protocol) }

    suspend fun plan(source: HostSource, saved: List<SavedHost>, options: HostImportOptions): HostImportPlan = withContext(Dispatchers.Default) {
        HostImportPlanner.plan(source.hosts, saved, options, GroupTree.MAX_DEPTH)
    }

    suspend fun import(plan: HostImportPlan): HostImportResult = database.withTransaction {
        val hostDao = database.hostDao()
        val groupDao = database.shelldonGroupDao()
        val folders = groupDao.getGroups().filter { it.repositoryId == EntityIds.LOCAL }.toMutableList()
        val tags = groupDao.getTags().filter { it.repositoryId == EntityIds.LOCAL }.associateByTo(HashMap()) { it.name.lowercase() }
        val takenNames = hostDao.getAll().mapTo(HashSet()) { it.nickname.lowercase() }
        val folderIds = HashMap<List<String>, UUID?>()
        val tagRows = ArrayList<HostTag>()
        var foldersCreated = 0
        var tagsCreated = 0
        var added = 0

        suspend fun folderId(path: List<String>): UUID? {
            if (path.isEmpty()) return null
            val key = path.map { it.lowercase() }
            if (key in folderIds) return folderIds.getValue(key)
            val parent = folderId(path.dropLast(1))
            val existing = folders.firstOrNull { it.parentId == parent && it.name.equals(path.last(), ignoreCase = true) }
            val id = when {
                existing != null -> existing.id

                else -> when (val created = groups.createGroup(path.last(), parent)) {
                    is Outcome.Ok<HostGroup> -> {
                        folders.add(created.value)
                        foldersCreated++
                        created.value.id
                    }

                    // Too deep or refused: the host goes into the nearest folder that exists.
                    is Outcome.Rejected -> parent
                }
            }
            folderIds[key] = id
            return id
        }

        suspend fun tagId(name: String): UUID? {
            val key = name.lowercase()
            tags[key]?.let { return it.id }
            return when (val created = groups.createTag(name)) {
                is Outcome.Ok<Tag> -> {
                    tags[key] = created.value
                    tagsCreated++
                    created.value.id
                }

                is Outcome.Rejected -> null
            }
        }

        for (planned in plan.newHosts) {
            if (!takenNames.add(planned.nickname.lowercase())) continue
            val host = Host(
                id = planned.id,
                nickname = planned.nickname,
                protocol = planned.protocol,
                // Telnet has no user name; the login prompt asks for it.
                username = if (planned.protocol == "ssh") planned.username else "",
                hostname = planned.hostname,
                port = planned.port,
                groupId = folderId(planned.folder),
            )
            hostDao.insert(host)
            for (name in planned.tags) tagId(name)?.let { tagRows.add(HostTag.of(host.id, it)) }
            added++
        }
        if (tagRows.isNotEmpty()) groupDao.insertHostTags(tagRows.distinctBy { it.id })

        HostImportResult(
            hostsAdded = added,
            hostsSkipped = plan.hosts.size - added,
            foldersCreated = foldersCreated,
            tagsCreated = tagsCreated,
        )
    }
}
