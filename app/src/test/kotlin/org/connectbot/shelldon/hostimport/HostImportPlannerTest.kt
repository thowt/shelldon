/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.hostimport

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.UUID

class HostImportPlannerTest {
    private fun host(
        name: String,
        hostname: String,
        port: Int? = null,
        username: String = "",
        protocol: ImportProtocol = ImportProtocol.SSH,
        folder: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        password: Boolean = false,
    ) = ImportedHost(name, hostname, port, username, protocol, folder, tags, password)

    private fun plan(
        imported: List<ImportedHost>,
        saved: List<SavedHost> = emptyList(),
        options: HostImportOptions = HostImportOptions(),
    ) = HostImportPlanner.plan(imported, saved, options, maxFolderDepth = 16, newId = UUID::randomUUID)

    @Test
    fun usesTheDefaultPortOfTheProtocol() {
        val result = plan(
            listOf(host("a", "10.0.0.1"), host("b", "10.0.0.2", protocol = ImportProtocol.TELNET)),
            options = HostImportOptions(includeTelnet = true),
        )
        assertThat(result.hosts.map { it.port }).containsExactly(22, 23)
        assertThat(result.hosts.map { it.protocol }).containsExactly("ssh", "telnet")
    }

    @Test
    fun telnetStaysOutUnlessAskedFor() {
        val entries = listOf(host("a", "10.0.0.1"), host("b", "10.0.0.2", protocol = ImportProtocol.TELNET))
        val off = plan(entries)
        assertThat(off.hosts).hasSize(1)
        assertThat(off.telnetLeftOut).isEqualTo(1)
        val on = plan(entries, options = HostImportOptions(includeTelnet = true))
        assertThat(on.hosts).hasSize(2)
        assertThat(on.telnetLeftOut).isEqualTo(0)
    }

    @Test
    fun otherProtocolsAndBadAddressesAreCountedNotImported() {
        val result = plan(
            listOf(
                host("rdp", "10.0.0.9", protocol = ImportProtocol.OTHER),
                host("blank", ""),
                host("spaces", "a b"),
                host("url", "ssh://10.0.0.1/x"),
                host("port0", "10.0.0.1", port = 0),
                host("port70000", "10.0.0.1", port = 70000),
                host("ok", "10.0.0.5"),
            ),
        )
        assertThat(result.hosts.map { it.nickname }).containsExactly("ok")
        assertThat(result.otherProtocols).isEqualTo(1)
        assertThat(result.invalid).isEqualTo(5)
    }

    @Test
    fun aBlankNameFallsBackToTheAddress() {
        assertThat(plan(listOf(host("", "nas.lan"))).hosts.single().nickname).isEqualTo("nas.lan")
    }

    @Test
    fun userAndPortCanSitInTheAddress() {
        val a = plan(listOf(host("a", "root@10.0.0.1:2200"))).hosts.single()
        assertThat(a.hostname).isEqualTo("10.0.0.1")
        assertThat(a.username).isEqualTo("root")
        assertThat(a.port).isEqualTo(2200)
        val v6 = plan(listOf(host("v6", "[fe80::1]"))).hosts.single()
        assertThat(v6.hostname).isEqualTo("fe80::1")
        val bare6 = plan(listOf(host("bare", "fe80::1"))).hosts.single()
        assertThat(bare6.hostname).isEqualTo("fe80::1")
        assertThat(bare6.port).isEqualTo(22)
    }

    @Test
    fun anExistingHostIsLeftAloneAndImportingTwiceAddsNothing() {
        val saved = listOf(SavedHost("web", "10.0.0.1", 22, "ssh"))
        val result = plan(listOf(host("web", "10.0.0.1"), host("db", "10.0.0.2")), saved)
        assertThat(result.hosts.map { it.status }).containsExactly(PlannedStatus.EXISTS, PlannedStatus.NEW)
        assertThat(result.existing).isEqualTo(1)
        assertThat(result.newHosts.single().nickname).isEqualTo("db")
    }

    @Test
    fun aClashingNameWithAnotherAddressGetsANumber() {
        val saved = listOf(SavedHost("db", "10.0.0.1", 22, "ssh"))
        val result = plan(listOf(host("db", "10.0.0.2"), host("DB", "10.0.0.3")), saved)
        assertThat(result.hosts.map { it.nickname }).containsExactly("db (2)", "DB (3)")
        assertThat(result.hosts.first().renamedFrom).isEqualTo("db")
    }

    @Test
    fun importingTheSameFileAgainAfterARenameStillAddsNothing() {
        val saved = listOf(SavedHost("db", "10.0.0.1", 22, "ssh"), SavedHost("db (2)", "10.0.0.2", 22, "ssh"))
        val result = plan(listOf(host("db", "10.0.0.1"), host("db", "10.0.0.2")), saved)
        assertThat(result.hosts.map { it.status }).containsExactly(PlannedStatus.EXISTS, PlannedStatus.EXISTS)
    }

    @Test
    fun duplicateRowsInOneFileAreImportedOnce() {
        val result = plan(listOf(host("web", "10.0.0.1"), host("web", "10.0.0.1")))
        assertThat(result.newHosts).hasSize(1)
        assertThat(result.existing).isEqualTo(1)
    }

    @Test
    fun foldersAndTagsAreCleanedAndCounted() {
        val result = plan(
            listOf(
                host("a", "10.0.0.1", folder = listOf("Prod", " EU "), tags = listOf("Web", "web", "edge")),
                host("b", "10.0.0.2", folder = listOf("prod", "US"), tags = listOf("WEB")),
            ),
        )
        assertThat(result.hosts[0].folder).containsExactly("Prod", "EU")
        assertThat(result.hosts[0].tags).containsExactly("Web", "edge")
        assertThat(result.folderPaths).hasSize(3)
        assertThat(result.tagNames).containsExactlyInAnyOrder("web", "edge")
    }

    @Test
    fun foldersAreCutAtTheMaximumDepthAndNamesAtTheirLength() {
        val deep = (1..20).map { "f$it" }
        val result = HostImportPlanner.plan(
            listOf(host("x".repeat(300), "10.0.0.1", folder = deep)),
            emptyList(),
            HostImportOptions(),
            maxFolderDepth = 16,
        )
        assertThat(result.hosts.single().folder).hasSize(16)
        assertThat(result.hosts.single().nickname.length).isEqualTo(HostImportPlanner.MAX_NAME)
    }

    @Test
    fun controlCharactersAreRemovedFromNames() {
        assertThat(plan(listOf(host("we\u0007b\n1", "10.0.0.1"))).hosts.single().nickname).isEqualTo("web1")
    }

    @Test
    fun passwordsAreCountedNeverCarried() {
        val result = plan(listOf(host("a", "10.0.0.1", password = true), host("b", "10.0.0.2")))
        assertThat(result.passwordsLeftBehind).isEqualTo(1)
        assertThat(result.hosts.toString()).doesNotContain("assword=true")
    }

    @Test
    fun aHugeFileIsCappedAtTheHostLimit() {
        val many = (1..HostImportPlanner.MAX_HOSTS + 10).map { host("h$it", "10.0.${it / 250}.${it % 250}") }
        val result = plan(many)
        assertThat(result.hosts).hasSize(HostImportPlanner.MAX_HOSTS)
        assertThat(result.invalid).isEqualTo(10)
    }
}
