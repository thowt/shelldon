/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.data.groups

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Queries for groups, tags and identities. Kept apart from the upstream DAOs on purpose. */
@Dao
interface ShelldonGroupDao {
    // Groups

    @Query("SELECT * FROM host_groups ORDER BY sort_order, name COLLATE NOCASE")
    fun observeGroups(): Flow<List<HostGroup>>

    @Query("SELECT * FROM host_groups")
    suspend fun getGroups(): List<HostGroup>

    @Query("SELECT * FROM host_groups WHERE id = :id")
    suspend fun getGroup(id: UUID): HostGroup?

    @Insert
    suspend fun insertGroup(group: HostGroup)

    @Update
    suspend fun updateGroup(group: HostGroup)

    @Delete
    suspend fun deleteGroup(group: HostGroup)

    @Query("UPDATE host_groups SET parent_id = :newParentId WHERE parent_id = :oldParentId")
    suspend fun reparentChildren(oldParentId: UUID, newParentId: UUID?)

    @Query("UPDATE hosts SET group_id = :newGroupId WHERE group_id = :oldGroupId")
    suspend fun moveHostsBetweenGroups(oldGroupId: UUID, newGroupId: UUID?)

    @Query("UPDATE hosts SET group_id = :groupId WHERE id = :hostId")
    suspend fun setHostGroup(hostId: UUID, groupId: UUID?)

    // Tags

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    fun observeTags(): Flow<List<Tag>>

    @Query("SELECT * FROM tags")
    suspend fun getTags(): List<Tag>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getTag(id: UUID): Tag?

    @Insert
    suspend fun insertTag(tag: Tag)

    @Update
    suspend fun updateTag(tag: Tag)

    @Delete
    suspend fun deleteTag(tag: Tag)

    @Query("SELECT * FROM host_tags")
    fun observeHostTags(): Flow<List<HostTag>>

    @Query("SELECT * FROM host_tags")
    suspend fun getAllHostTags(): List<HostTag>

    @Query("SELECT * FROM host_tags WHERE host_id = :hostId")
    suspend fun getHostTags(hostId: UUID): List<HostTag>

    @Insert
    suspend fun insertHostTags(rows: List<HostTag>)

    @Delete
    suspend fun deleteHostTags(rows: List<HostTag>)

    // Identities

    @Query("SELECT * FROM identities ORDER BY name COLLATE NOCASE")
    fun observeIdentities(): Flow<List<Identity>>

    @Query("SELECT * FROM identities")
    suspend fun getIdentities(): List<Identity>

    @Query("SELECT * FROM identities WHERE id = :id")
    suspend fun getIdentity(id: UUID): Identity?

    @Insert
    suspend fun insertIdentity(identity: Identity)

    @Update
    suspend fun updateIdentity(identity: Identity)

    @Delete
    suspend fun deleteIdentity(identity: Identity)

    @Query("UPDATE hosts SET identity_id = NULL WHERE identity_id = :identityId")
    suspend fun clearHostIdentity(identityId: UUID)

    @Query("UPDATE host_groups SET identity_id = NULL WHERE identity_id = :identityId")
    suspend fun clearGroupIdentity(identityId: UUID)

    @Query("UPDATE hosts SET identity_id = :identityId WHERE id = :hostId")
    suspend fun setHostIdentity(hostId: UUID, identityId: UUID?)
}
