/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.data.groups

import java.util.UUID

/**
 * Pure helpers over a set of groups. Synced or imported data can contain loops or missing parents,
 * so every walk is bounded and stops at the first repeat instead of trusting the data.
 */
object GroupTree {
    /** Deeper nesting than this is cut off, and refused when creating or moving a group. */
    const val MAX_DEPTH = 16

    /** [startId] and its ancestors, nearest first. Stops at a missing parent, a repeat or [MAX_DEPTH]. */
    fun chain(groups: Map<UUID, HostGroup>, startId: UUID?): List<HostGroup> {
        val result = ArrayList<HostGroup>()
        val seen = HashSet<UUID>()
        var current = startId
        while (current != null && result.size < MAX_DEPTH && seen.add(current)) {
            val group = groups[current] ?: break
            result += group
            current = group.parentId
        }
        return result
    }

    /** [wanted], or "wanted (2)", "wanted (3)" ... the first one not in [taken], ignoring case. */
    fun uniqueName(wanted: String, taken: Collection<String>): String {
        val used = taken.mapTo(HashSet()) { it.lowercase() }
        if (wanted.lowercase() !in used) return wanted
        var n = 2
        while ("$wanted ($n)".lowercase() in used) n++
        return "$wanted ($n)"
    }

    /** Names from the top level down to [groupId], for display ("Work / Switches"). */
    fun path(groups: Map<UUID, HostGroup>, groupId: UUID?): List<String> = chain(groups, groupId).map { it.name }.asReversed()

    /** [groupId] and everything below it. */
    fun subtree(groups: Collection<HostGroup>, groupId: UUID): Set<UUID> {
        val children = groups.groupBy { it.parentId }
        val result = LinkedHashSet<UUID>()
        val queue = ArrayDeque<UUID>().apply { add(groupId) }
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (result.add(next)) children[next].orEmpty().forEach { queue.add(it.id) }
        }
        return result
    }

    /** True if making [newParentId] the parent of [groupId] would put a group below itself. */
    fun wouldCreateCycle(groups: Map<UUID, HostGroup>, groupId: UUID, newParentId: UUID?): Boolean {
        if (newParentId == null) return false
        if (newParentId == groupId) return true
        return chain(groups, newParentId).any { it.id == groupId }
    }

    /** Levels a group would span if [groupId] were placed below [newParentId] (1 = top level). */
    fun depthIfPlacedUnder(groups: Map<UUID, HostGroup>, groupId: UUID, newParentId: UUID?): Int {
        val above = chain(groups, newParentId).size
        val below = subtreeHeight(groups.values, groupId)
        return above + below
    }

    private fun subtreeHeight(groups: Collection<HostGroup>, groupId: UUID): Int {
        val children = groups.groupBy { it.parentId }
        var height = 0
        var level = listOf(groupId)
        val seen = HashSet<UUID>()
        while (level.isNotEmpty() && height < MAX_DEPTH * 2) {
            height++
            level = level.filter { seen.add(it) }.flatMap { id -> children[id].orEmpty().map { it.id } }
        }
        return height
    }
}
