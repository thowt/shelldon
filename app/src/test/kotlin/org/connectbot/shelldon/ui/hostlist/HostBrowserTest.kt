/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.hostlist

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.entity.Host
import org.connectbot.shelldon.data.groups.GroupSnapshot
import org.connectbot.shelldon.data.groups.HostGroup
import org.connectbot.shelldon.data.groups.HostTag
import org.connectbot.shelldon.data.groups.Tag
import org.junit.Test
import java.util.UUID

class HostBrowserTest {
    private fun id(n: Long) = UUID(0, n)

    private fun group(n: Long, parent: Long? = null, sort: Int = 0) = HostGroup(id = id(n), parentId = parent?.let { id(it) }, name = "g$n", sortOrder = sort)

    private fun host(n: Long, group: Long? = null) = Host(id = id(1000 + n), nickname = "h$n", hostname = "h$n.lan", groupId = group?.let { id(it) })

    private fun snapshot(groups: List<HostGroup>, tags: List<Tag> = emptyList(), links: List<HostTag> = emptyList()) = GroupSnapshot(groups, tags, links, emptyList())

    private val work = group(1)
    private val switches = group(2, parent = 1)
    private val lab = group(3, sort = 1)

    private val hosts = listOf(host(1), host(2, group = 1), host(3, group = 2), host(4, group = 3))
    private val tree = snapshot(listOf(work, switches, lab))

    private fun nicknames(list: List<Host>) = list.map { it.nickname }

    @Test
    fun topLevelShowsTopFoldersAndLooseHosts() {
        val view = HostBrowser.browse(hosts, tree, null, emptySet(), searching = false)

        assertThat(view.isFlat).isFalse()
        assertThat(view.breadcrumb).isEmpty()
        assertThat(view.groups.map { it.group.name }).containsExactly("g1", "g3")
        assertThat(nicknames(view.hosts)).containsExactly("h1")
    }

    @Test
    fun folderCountsHostsAnywhereBelowIt() {
        val view = HostBrowser.browse(hosts, tree, null, emptySet(), searching = false)

        val workEntry = view.groups.first { it.group.id == work.id }
        assertThat(workEntry.hostCount).isEqualTo(2)
        assertThat(workEntry.subgroupCount).isEqualTo(1)
        assertThat(view.groups.first { it.group.id == lab.id }.hostCount).isEqualTo(1)
    }

    @Test
    fun enteringAFolderShowsItsLevelAndABreadcrumb() {
        val view = HostBrowser.browse(hosts, tree, switches.id, emptySet(), searching = false)

        assertThat(view.breadcrumb.map { it.name }).containsExactly("g1", "g2")
        assertThat(view.groups).isEmpty()
        assertThat(nicknames(view.hosts)).containsExactly("h3")
    }

    @Test
    fun foldersFollowTheirManualOrderThenTheirName() {
        val first = group(10, sort = 2)
        val second = group(11, sort = 1)
        val third = group(12, sort = 1)
        val view = HostBrowser.browse(emptyList(), snapshot(listOf(first, second, third)), null, emptySet(), searching = false)

        assertThat(view.groups.map { it.group.id }).containsExactly(second.id, third.id, first.id)
    }

    @Test
    fun searchLooksAcrossAllFoldersAndHidesThem() {
        val view = HostBrowser.browse(hosts, tree, work.id, emptySet(), searching = true)

        assertThat(view.isFlat).isTrue()
        assertThat(view.groups).isEmpty()
        assertThat(nicknames(view.hosts)).containsExactlyInAnyOrder("h1", "h2", "h3", "h4")
    }

    @Test
    fun tagFilterNeedsEveryChosenTagAndWorksAcrossFolders() {
        val prod = Tag(id = id(50), name = "prod")
        val eu = Tag(id = id(51), name = "eu")
        val links = listOf(
            HostTag.of(hosts[0].id, prod.id, hosts[0].repositoryId),
            HostTag.of(hosts[2].id, prod.id, hosts[2].repositoryId),
            HostTag.of(hosts[2].id, eu.id, hosts[2].repositoryId),
        )
        val withTags = snapshot(listOf(work, switches, lab), listOf(prod, eu), links)

        val one = HostBrowser.browse(hosts, withTags, null, setOf(prod.id), searching = false)
        assertThat(nicknames(one.hosts)).containsExactlyInAnyOrder("h1", "h3")
        assertThat(one.isFlat).isTrue()

        val both = HostBrowser.browse(hosts, withTags, null, setOf(prod.id, eu.id), searching = false)
        assertThat(nicknames(both.hosts)).containsExactly("h3")
    }

    @Test
    fun aDeletedCurrentFolderFallsBackToTheTopLevel() {
        val view = HostBrowser.browse(hosts, tree, id(999), emptySet(), searching = false)

        assertThat(view.breadcrumb).isEmpty()
        assertThat(view.groups.map { it.group.name }).containsExactly("g1", "g3")
    }

    @Test
    fun hostsAndFoldersWithAMissingParentAreShownAtTheTopLevel() {
        val orphanGroup = group(20, parent = 999)
        val orphanHost = host(9, group = 998)
        val view = HostBrowser.browse(hosts + orphanHost, snapshot(listOf(work, switches, lab, orphanGroup)), null, emptySet(), searching = false)

        assertThat(view.groups.map { it.group.id }).contains(orphanGroup.id)
        assertThat(nicknames(view.hosts)).containsExactlyInAnyOrder("h1", "h9")
    }

    @Test
    fun captionListsSortedTagsAndTheFolderPathOnlyWhenAsked() {
        val prod = Tag(id = id(50), name = "prod")
        val eu = Tag(id = id(51), name = "EU")
        val links = listOf(HostTag.of(hosts[2].id, prod.id), HostTag.of(hosts[2].id, eu.id))
        val withTags = snapshot(listOf(work, switches, lab), listOf(prod, eu), links)

        assertThat(hostCaption(hosts[2], withTags, showPath = true)).isEqualTo("g1 / g2 · #EU #prod")
        assertThat(hostCaption(hosts[2], withTags, showPath = false)).isEqualTo("#EU #prod")
        assertThat(hostCaption(hosts[0], withTags, showPath = true)).isNull()
        assertThat(hostCaption(hosts[1], withTags, showPath = true)).isEqualTo("g1")
    }
}
