/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.data.groups

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.UUID

class GroupTreeTest {
    private fun g(n: Long, parent: Long? = null, name: String = "g$n") = HostGroup(id = UUID(0, n), parentId = parent?.let { UUID(0, it) }, name = name)

    private fun map(vararg groups: HostGroup) = groups.associateBy { it.id }

    private fun id(n: Long) = UUID(0, n)

    @Test
    fun chainIsNearestFirstAndPathIsRootFirst() {
        val groups = map(g(1, name = "Work"), g(2, 1, "Switches"), g(3, 2, "Floor 3"))
        assertThat(GroupTree.chain(groups, id(3)).map { it.name }).containsExactly("Floor 3", "Switches", "Work")
        assertThat(GroupTree.path(groups, id(3))).containsExactly("Work", "Switches", "Floor 3")
    }

    @Test
    fun chainOfNoGroupOrUnknownGroupIsEmpty() {
        val groups = map(g(1))
        assertThat(GroupTree.chain(groups, null)).isEmpty()
        assertThat(GroupTree.chain(groups, id(99))).isEmpty()
    }

    @Test
    fun chainStopsAtMissingParent() {
        val groups = map(g(2, 1))
        assertThat(GroupTree.chain(groups, id(2)).map { it.id }).containsExactly(id(2))
    }

    @Test
    fun chainSurvivesALoopInBadData() {
        val groups = map(g(1, 2), g(2, 1))
        assertThat(GroupTree.chain(groups, id(1)).map { it.id }).containsExactly(id(1), id(2))
    }

    @Test
    fun chainIsCutOffAtMaxDepth() {
        val deep = (1L..(GroupTree.MAX_DEPTH + 10L)).map { g(it, if (it == 1L) null else it - 1) }.toTypedArray()
        assertThat(GroupTree.chain(map(*deep), id(GroupTree.MAX_DEPTH + 10L))).hasSize(GroupTree.MAX_DEPTH)
    }

    @Test
    fun subtreeIncludesTheGroupAndAllBelowIt() {
        val groups = listOf(g(1), g(2, 1), g(3, 2), g(4, 1), g(5))
        assertThat(GroupTree.subtree(groups, id(1))).containsExactlyInAnyOrder(id(1), id(2), id(3), id(4))
    }

    @Test
    fun cycleDetection() {
        val groups = map(g(1), g(2, 1), g(3, 2), g(4))
        assertThat(GroupTree.wouldCreateCycle(groups, id(1), id(1))).isTrue()
        assertThat(GroupTree.wouldCreateCycle(groups, id(1), id(3))).isTrue()
        assertThat(GroupTree.wouldCreateCycle(groups, id(2), id(4))).isFalse()
        assertThat(GroupTree.wouldCreateCycle(groups, id(3), null)).isFalse()
        assertThat(GroupTree.wouldCreateCycle(groups, id(3), id(1))).isFalse()
    }

    @Test
    fun depthCountsLevelsAboveAndBelow() {
        val groups = map(g(1), g(2, 1), g(3, 2), g(4))
        // g4 alone placed at the top is 1 level, below g3 (which sits 3 deep) it is 4.
        assertThat(GroupTree.depthIfPlacedUnder(groups, id(4), null)).isEqualTo(1)
        assertThat(GroupTree.depthIfPlacedUnder(groups, id(4), id(3))).isEqualTo(4)
        // g2 carries g3 with it: two levels below, one above.
        assertThat(GroupTree.depthIfPlacedUnder(groups, id(2), id(4))).isEqualTo(3)
    }

    @Test
    fun uniqueNameIgnoresCaseAndNumbersUp() {
        assertThat(GroupTree.uniqueName("Lab", emptyList())).isEqualTo("Lab")
        assertThat(GroupTree.uniqueName("Lab", listOf("lab"))).isEqualTo("Lab (2)")
        assertThat(GroupTree.uniqueName("Lab", listOf("Lab", "Lab (2)", "LAB (3)"))).isEqualTo("Lab (4)")
    }
}
