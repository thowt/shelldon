/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.hostlist

import org.connectbot.data.entity.Host
import org.connectbot.shelldon.data.groups.GroupSnapshot
import org.connectbot.shelldon.data.groups.GroupTree
import org.connectbot.shelldon.data.groups.HostGroup
import java.util.UUID

/** A folder row: the group, how many hosts are anywhere below it, and how many folders sit directly in it. */
internal data class GroupEntry(
    val group: HostGroup,
    val hostCount: Int,
    val subgroupCount: Int,
)

/**
 * What the Hosts list shows for one position in the group tree.
 *
 * Browsing shows the folders and hosts of one level, with a [breadcrumb] to climb back up. A search
 * or a tag filter looks across all groups instead ([isFlat]), so a match is never hidden in a folder.
 */
internal data class HostBrowse(
    /** From the top level down to the current group; empty at the top level. */
    val breadcrumb: List<HostGroup>,
    val groups: List<GroupEntry>,
    val hosts: List<Host>,
    val isFlat: Boolean,
)

internal object HostBrowser {
    /**
     * @param hosts hosts that already passed the protocol chips and the text search
     * @param searching true while a text search is active
     * @param currentGroupId the folder being viewed; a missing (deleted) folder counts as the top level
     * @param selectedTagIds a host must carry all of them
     */
    fun browse(
        hosts: List<Host>,
        snapshot: GroupSnapshot,
        currentGroupId: UUID?,
        selectedTagIds: Set<UUID>,
        searching: Boolean,
    ): HostBrowse {
        val groupsById = snapshot.groupsById
        val current = currentGroupId?.takeIf { it in groupsById }
        val tagged = if (selectedTagIds.isEmpty()) {
            hosts
        } else {
            hosts.filter { snapshot.tagIdsByHost[it.id].orEmpty().containsAll(selectedTagIds) }
        }

        if (searching || selectedTagIds.isNotEmpty()) {
            return HostBrowse(
                breadcrumb = GroupTree.chain(groupsById, current).asReversed(),
                groups = emptyList(),
                hosts = tagged,
                isFlat = true,
            )
        }

        // A host pointing at a group that no longer exists is shown at the top level.
        fun groupOf(host: Host): UUID? = host.groupId?.takeIf { it in groupsById }

        val children = snapshot.groups
            .filter { it.parentId == current || (current == null && it.parentId != null && it.parentId !in groupsById) }
            .sortedWith(compareBy<HostGroup>({ it.sortOrder }, { it.name.lowercase() }))
        val entries = children.map { child ->
            val below = GroupTree.subtree(snapshot.groups, child.id)
            GroupEntry(
                group = child,
                hostCount = hosts.count { groupOf(it) in below },
                subgroupCount = snapshot.groups.count { it.parentId == child.id },
            )
        }
        return HostBrowse(
            breadcrumb = GroupTree.chain(groupsById, current).asReversed(),
            groups = entries,
            hosts = hosts.filter { groupOf(it) == current },
            isFlat = false,
        )
    }
}

/**
 * The small grey line under a host's address: its tags, and where it lives when the list is flat
 * (search or tag filter) and the folder would otherwise be hidden. Null when there is nothing to say.
 */
internal fun hostCaption(host: Host, snapshot: GroupSnapshot, showPath: Boolean): String? {
    val parts = ArrayList<String>(2)
    if (showPath) {
        host.groupId?.takeIf { it in snapshot.groupsById }?.let { groupId ->
            parts += GroupTree.path(snapshot.groupsById, groupId).joinToString(" / ")
        }
    }
    val tagNames = snapshot.tagIdsByHost[host.id].orEmpty()
        .mapNotNull { snapshot.tagsById[it]?.name }
        .sortedBy { it.lowercase() }
    if (tagNames.isNotEmpty()) parts += tagNames.joinToString(" ") { "#$it" }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}
