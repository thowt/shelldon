/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.data.groups

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.connectbot.data.ConnectBotDatabase
import org.connectbot.data.EntityIds
import org.connectbot.data.entity.Host
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Why a change was refused. The UI maps each reason to a message. */
enum class Rejection {
    BLANK_NAME,
    NAME_TOO_LONG,
    NAME_TAKEN,
    BLANK_USERNAME,
    NOT_FOUND,
    WOULD_LOOP,
    TOO_DEEP,
    OTHER_REPOSITORY,
}

sealed interface Outcome<out T> {
    data class Ok<out T>(val value: T) : Outcome<T>

    data class Rejected(val reason: Rejection) : Outcome<Nothing>
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Ok)?.value

/** Everything the group screens need, kept consistent in one value. */
data class GroupSnapshot(
    val groups: List<HostGroup>,
    val tags: List<Tag>,
    val hostTags: List<HostTag>,
    val identities: List<Identity>,
) {
    val groupsById: Map<UUID, HostGroup> by lazy { groups.associateBy { it.id } }
    val identitiesById: Map<UUID, Identity> by lazy { identities.associateBy { it.id } }

    /** Tag ids per host. */
    val tagIdsByHost: Map<UUID, Set<UUID>> by lazy { hostTags.groupBy({ it.hostId }, { it.tagId }).mapValues { it.value.toSet() } }

    fun effectiveLogin(host: Host): EffectiveLogin = IdentityResolver.effectiveLogin(host, groupsById, identitiesById)
}

/**
 * Groups, tags and identities. Every change that could break a rule (loops, duplicate names,
 * dangling references) is checked and applied in one transaction, and refused with a [Rejection]
 * instead of throwing. Hosts are never deleted or hidden by anything done here.
 */
@Singleton
class ShelldonGroupRepository @Inject constructor(private val database: ConnectBotDatabase) {
    private val dao: ShelldonGroupDao get() = database.shelldonGroupDao()

    fun observeSnapshot(): Flow<GroupSnapshot> = combine(
        dao.observeGroups(),
        dao.observeTags(),
        dao.observeHostTags(),
        dao.observeIdentities(),
    ) { groups, tags, hostTags, identities -> GroupSnapshot(groups, tags, hostTags, identities) }

    suspend fun snapshot(): GroupSnapshot = database.withTransaction {
        GroupSnapshot(dao.getGroups(), dao.getTags(), dao.getAllHostTags(), dao.getIdentities())
    }

    // Groups

    suspend fun createGroup(
        name: String,
        parentId: UUID?,
        color: String? = null,
        identityId: UUID? = null,
    ): Outcome<HostGroup> = database.withTransaction<Outcome<HostGroup>> {
        val clean = checkName(name) ?: return@withTransaction nameProblem(name)
        val all = dao.getGroups()
        val byId = all.associateBy { it.id }
        val parent = parentId?.let { byId[it] ?: return@withTransaction Outcome.Rejected(Rejection.NOT_FOUND) }
        if (GroupTree.chain(byId, parentId).size + 1 > GroupTree.MAX_DEPTH) return@withTransaction Outcome.Rejected(Rejection.TOO_DEEP)
        if (siblingNameTaken(all, parentId, clean, exceptId = null)) return@withTransaction Outcome.Rejected(Rejection.NAME_TAKEN)
        if (!identityExists(identityId)) return@withTransaction Outcome.Rejected(Rejection.NOT_FOUND)
        val group = HostGroup(
            repositoryId = parent?.repositoryId ?: EntityIds.LOCAL,
            parentId = parentId,
            name = clean,
            color = color,
            sortOrder = nextSortOrder(all, parentId),
            identityId = identityId,
        )
        dao.insertGroup(group)
        Outcome.Ok(group)
    }

    /** Changes the editable settings of a group. Use [moveGroup] to change where it sits. */
    suspend fun editGroup(id: UUID, name: String, color: String?, identityId: UUID?): Outcome<HostGroup> = database.withTransaction<Outcome<HostGroup>> {
        val clean = checkName(name) ?: return@withTransaction nameProblem(name)
        val existing = dao.getGroup(id) ?: return@withTransaction Outcome.Rejected(Rejection.NOT_FOUND)
        if (siblingNameTaken(dao.getGroups(), existing.parentId, clean, exceptId = id)) return@withTransaction Outcome.Rejected(Rejection.NAME_TAKEN)
        if (!identityExists(identityId)) return@withTransaction Outcome.Rejected(Rejection.NOT_FOUND)
        val updated = existing.copy(name = clean, color = color, identityId = identityId)
        dao.updateGroup(updated)
        Outcome.Ok(updated)
    }

    suspend fun moveGroup(id: UUID, newParentId: UUID?): Outcome<HostGroup> = database.withTransaction<Outcome<HostGroup>> {
        val all = dao.getGroups()
        val byId = all.associateBy { it.id }
        val group = byId[id] ?: return@withTransaction Outcome.Rejected(Rejection.NOT_FOUND)
        val parent = newParentId?.let { byId[it] ?: return@withTransaction Outcome.Rejected(Rejection.NOT_FOUND) }
        if (parent != null && parent.repositoryId != group.repositoryId) return@withTransaction Outcome.Rejected(Rejection.OTHER_REPOSITORY)
        if (GroupTree.wouldCreateCycle(byId, id, newParentId)) return@withTransaction Outcome.Rejected(Rejection.WOULD_LOOP)
        if (GroupTree.depthIfPlacedUnder(byId, id, newParentId) > GroupTree.MAX_DEPTH) return@withTransaction Outcome.Rejected(Rejection.TOO_DEEP)
        if (newParentId != group.parentId && siblingNameTaken(all, newParentId, group.name, exceptId = id)) {
            return@withTransaction Outcome.Rejected(Rejection.NAME_TAKEN)
        }
        val moved = if (newParentId == group.parentId) group else group.copy(parentId = newParentId, sortOrder = nextSortOrder(all, newParentId))
        if (moved != group) dao.updateGroup(moved)
        Outcome.Ok(moved)
    }

    /**
     * Deletes the group only. Sub-groups and hosts move up to its parent (renamed with a number if
     * a sibling there already has the same name), so nothing disappears.
     */
    suspend fun deleteGroup(id: UUID): Outcome<Unit> = database.withTransaction<Outcome<Unit>> {
        val all = dao.getGroups()
        val group = all.firstOrNull { it.id == id } ?: return@withTransaction Outcome.Rejected(Rejection.NOT_FOUND)
        val taken = all.filter { it.parentId == group.parentId && it.id != id }.mapTo(ArrayList()) { it.name }
        all.filter { it.parentId == id }.sortedBy { it.sortOrder }.forEach { child ->
            val name = GroupTree.uniqueName(child.name, taken)
            taken += name
            dao.updateGroup(child.copy(parentId = group.parentId, name = name))
        }
        dao.moveHostsBetweenGroups(id, group.parentId)
        dao.deleteGroup(group)
        Outcome.Ok(Unit)
    }

    suspend fun setHostGroup(hostId: UUID, groupId: UUID?): Outcome<Unit> = database.withTransaction<Outcome<Unit>> {
        val host = database.hostDao().getById(hostId) ?: return@withTransaction Outcome.Rejected(Rejection.NOT_FOUND)
        if (groupId != null) {
            val group = dao.getGroup(groupId) ?: return@withTransaction Outcome.Rejected(Rejection.NOT_FOUND)
            if (group.repositoryId != host.repositoryId) return@withTransaction Outcome.Rejected(Rejection.OTHER_REPOSITORY)
        }
        if (host.groupId != groupId) dao.setHostGroup(hostId, groupId)
        Outcome.Ok(Unit)
    }

    // Tags

    suspend fun createTag(name: String, color: String? = null): Outcome<Tag> = database.withTransaction<Outcome<Tag>> {
        val clean = checkName(name) ?: return@withTransaction nameProblem(name)
        if (dao.getTags().any { it.name.equals(clean, ignoreCase = true) }) return@withTransaction Outcome.Rejected(Rejection.NAME_TAKEN)
        val tag = Tag(name = clean, color = color)
        dao.insertTag(tag)
        Outcome.Ok(tag)
    }

    suspend fun editTag(id: UUID, name: String, color: String?): Outcome<Tag> = database.withTransaction<Outcome<Tag>> {
        val clean = checkName(name) ?: return@withTransaction nameProblem(name)
        val existing = dao.getTag(id) ?: return@withTransaction Outcome.Rejected(Rejection.NOT_FOUND)
        if (dao.getTags().any { it.id != id && it.name.equals(clean, ignoreCase = true) }) return@withTransaction Outcome.Rejected(Rejection.NAME_TAKEN)
        val updated = existing.copy(name = clean, color = color)
        dao.updateTag(updated)
        Outcome.Ok(updated)
    }

    /** Removes the tag from every host (the host rows themselves are untouched). */
    suspend fun deleteTag(id: UUID): Outcome<Unit> = database.withTransaction<Outcome<Unit>> {
        val tag = dao.getTag(id) ?: return@withTransaction Outcome.Rejected(Rejection.NOT_FOUND)
        dao.deleteTag(tag)
        Outcome.Ok(Unit)
    }

    /** Makes the host carry exactly [tagIds]. Unknown tag ids are ignored. */
    suspend fun setHostTags(hostId: UUID, tagIds: Set<UUID>): Outcome<Unit> = database.withTransaction<Outcome<Unit>> {
        val host = database.hostDao().getById(hostId) ?: return@withTransaction Outcome.Rejected(Rejection.NOT_FOUND)
        val known = dao.getTags().filter { it.repositoryId == host.repositoryId }.mapTo(HashSet()) { it.id }
        val wanted = tagIds.filter { it in known }.toSet()
        val current = dao.getHostTags(hostId)
        dao.deleteHostTags(current.filter { it.tagId !in wanted })
        val have = current.mapTo(HashSet()) { it.tagId }
        dao.insertHostTags(wanted.filter { it !in have }.map { HostTag.of(hostId, it, host.repositoryId) })
        Outcome.Ok(Unit)
    }

    // Identities

    suspend fun createIdentity(name: String, username: String, keyId: UUID?): Outcome<Identity> = database.withTransaction<Outcome<Identity>> {
        val clean = checkName(name) ?: return@withTransaction nameProblem(name)
        val user = username.trim()
        if (user.isEmpty()) return@withTransaction Outcome.Rejected(Rejection.BLANK_USERNAME)
        if (dao.getIdentities().any { it.name.equals(clean, ignoreCase = true) }) return@withTransaction Outcome.Rejected(Rejection.NAME_TAKEN)
        val identity = Identity(name = clean, username = user, keyId = keyId)
        dao.insertIdentity(identity)
        Outcome.Ok(identity)
    }

    suspend fun editIdentity(id: UUID, name: String, username: String, keyId: UUID?): Outcome<Identity> = database.withTransaction<Outcome<Identity>> {
        val clean = checkName(name) ?: return@withTransaction nameProblem(name)
        val user = username.trim()
        if (user.isEmpty()) return@withTransaction Outcome.Rejected(Rejection.BLANK_USERNAME)
        val existing = dao.getIdentity(id) ?: return@withTransaction Outcome.Rejected(Rejection.NOT_FOUND)
        if (dao.getIdentities().any { it.id != id && it.name.equals(clean, ignoreCase = true) }) return@withTransaction Outcome.Rejected(Rejection.NAME_TAKEN)
        val updated = existing.copy(name = clean, username = user, keyId = keyId)
        dao.updateIdentity(updated)
        Outcome.Ok(updated)
    }

    /** Hosts and groups that used the identity go back to inheriting. */
    suspend fun deleteIdentity(id: UUID): Outcome<Unit> = database.withTransaction<Outcome<Unit>> {
        val identity = dao.getIdentity(id) ?: return@withTransaction Outcome.Rejected(Rejection.NOT_FOUND)
        dao.clearHostIdentity(id)
        dao.clearGroupIdentity(id)
        dao.deleteIdentity(identity)
        Outcome.Ok(Unit)
    }

    /** [identityId] null inherits, [EntityIds.NONE] opts the host out of inheriting. */
    suspend fun setHostIdentity(hostId: UUID, identityId: UUID?): Outcome<Unit> = database.withTransaction<Outcome<Unit>> {
        database.hostDao().getById(hostId) ?: return@withTransaction Outcome.Rejected(Rejection.NOT_FOUND)
        if (!identityExists(identityId)) return@withTransaction Outcome.Rejected(Rejection.NOT_FOUND)
        dao.setHostIdentity(hostId, identityId)
        Outcome.Ok(Unit)
    }

    // Helpers

    private fun checkName(name: String): String? = name.trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }

    private fun nameProblem(name: String): Outcome<Nothing> = Outcome.Rejected(if (name.isBlank()) Rejection.BLANK_NAME else Rejection.NAME_TOO_LONG)

    private suspend fun identityExists(id: UUID?): Boolean = id == null || id == EntityIds.NONE || dao.getIdentity(id) != null

    private fun siblingNameTaken(all: List<HostGroup>, parentId: UUID?, name: String, exceptId: UUID?): Boolean = all.any { it.parentId == parentId && it.id != exceptId && it.name.equals(name, ignoreCase = true) }

    private fun nextSortOrder(all: List<HostGroup>, parentId: UUID?): Int = (all.filter { it.parentId == parentId }.maxOfOrNull { it.sortOrder } ?: -1) + 1

    companion object {
        const val MAX_NAME_LENGTH = 100
    }
}
