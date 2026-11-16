/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.data.groups

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.connectbot.data.EntityIds
import org.connectbot.data.entity.Host
import java.util.UUID

/**
 * A folder for hosts. Groups nest through [parentId] (null means top level) and carry defaults that
 * their hosts and sub-groups inherit, nearest group first. No hard foreign key on [parentId] or
 * [identityId]: like `jump_host_id` and `profile_id` upstream, dangling references are tolerated
 * and cleaned by [ShelldonGroupRepository].
 */
@Entity(
    tableName = "host_groups",
    indices = [Index(value = ["parent_id"])],
)
data class HostGroup(
    @PrimaryKey
    val id: UUID = UUID.randomUUID(),

    @ColumnInfo(name = "repository_id", defaultValue = "'" + EntityIds.LOCAL_TEXT + "'")
    val repositoryId: UUID = EntityIds.LOCAL,

    @ColumnInfo(name = "parent_id")
    val parentId: UUID? = null,

    val name: String,

    /** Optional accent colour, same text format as [Host.color]. */
    val color: String? = null,

    /** Manual order among siblings; ties fall back to the name. */
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,

    /**
     * Default identity for every host below this group. Null inherits from the parent group,
     * [EntityIds.NONE] stops inheriting here (hosts below prompt for credentials).
     */
    @ColumnInfo(name = "identity_id")
    val identityId: UUID? = null,
)

/** A label that can be put on any host, across groups. Names are unique per repository, ignoring case. */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["repository_id", "name"], unique = true)],
)
data class Tag(
    @PrimaryKey
    val id: UUID = UUID.randomUUID(),

    @ColumnInfo(name = "repository_id", defaultValue = "'" + EntityIds.LOCAL_TEXT + "'")
    val repositoryId: UUID = EntityIds.LOCAL,

    val name: String,

    val color: String? = null,
)

/** Join row between a host and a tag. The id is derived from the pair so sync sees one stable object. */
@Entity(
    tableName = "host_tags",
    foreignKeys = [
        ForeignKey(entity = Host::class, parentColumns = ["id"], childColumns = ["host_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Tag::class, parentColumns = ["id"], childColumns = ["tag_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["host_id", "tag_id"], unique = true),
        Index(value = ["tag_id"]),
    ],
)
data class HostTag(
    @PrimaryKey
    val id: UUID,

    @ColumnInfo(name = "repository_id", defaultValue = "'" + EntityIds.LOCAL_TEXT + "'")
    val repositoryId: UUID = EntityIds.LOCAL,

    @ColumnInfo(name = "host_id")
    val hostId: UUID,

    @ColumnInfo(name = "tag_id")
    val tagId: UUID,
) {
    companion object {
        fun of(hostId: UUID, tagId: UUID, repositoryId: UUID = EntityIds.LOCAL): HostTag = HostTag(
            id = UUID.nameUUIDFromBytes("shelldon.host_tag:$hostId:$tagId".toByteArray(Charsets.UTF_8)),
            repositoryId = repositoryId,
            hostId = hostId,
            tagId = tagId,
        )
    }
}

/**
 * A reusable login: a user name and, optionally, a stored key. It holds **no password**. Secrets
 * wait for the vault (milestone M4) so nothing sensitive is stored unprotected.
 */
@Entity(
    tableName = "identities",
    indices = [Index(value = ["repository_id", "name"], unique = true)],
)
data class Identity(
    @PrimaryKey
    val id: UUID = UUID.randomUUID(),

    @ColumnInfo(name = "repository_id", defaultValue = "'" + EntityIds.LOCAL_TEXT + "'")
    val repositoryId: UUID = EntityIds.LOCAL,

    val name: String,

    val username: String = "",

    /** Id of a stored key, or null for "no stored key" (prompt, or whatever the host itself says). */
    @ColumnInfo(name = "key_id")
    val keyId: UUID? = null,
)
