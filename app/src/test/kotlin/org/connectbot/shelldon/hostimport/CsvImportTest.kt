/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.hostimport

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class CsvImportTest {
    private fun open(text: String): CsvSource = checkNotNull(CsvImport.open(text))

    @Test
    fun readsATermiusStyleExportWithoutAskingForAMapping() {
        val source = open(
            "Groups,Label,Tags,Hostname/IP,Protocol,Port,Username,Password\n" +
                "Prod/EU,web1,\"web, edge\",10.0.0.1,ssh,2222,root,hunter2\n" +
                "Prod,db1,db,10.0.0.2,ssh,,admin,\n",
        )
        assertThat(source.settings.hasHeader).isTrue()
        val hosts = source.hosts
        assertThat(hosts).hasSize(2)
        assertThat(hosts[0].name).isEqualTo("web1")
        assertThat(hosts[0].hostname).isEqualTo("10.0.0.1")
        assertThat(hosts[0].port).isEqualTo(2222)
        assertThat(hosts[0].username).isEqualTo("root")
        assertThat(hosts[0].folder).containsExactly("Prod", "EU")
        assertThat(hosts[0].tags).containsExactly("web", "edge")
        assertThat(hosts[1].port).isNull()
    }

    @Test
    fun aPasswordColumnIsCountedAndNeverCopied() {
        val source = open("name,host,password\nweb1,10.0.0.1,hunter2\ndb1,10.0.0.2,\n")
        assertThat(source.hosts.map { it.hadPassword }).containsExactly(true, false)
        assertThat(source.hosts.toString()).doesNotContain("hunter2")
    }

    @Test
    fun semicolonAndTabSeparatedFilesWork() {
        assertThat(open("name;host;port\nweb;10.0.0.1;22\n").hosts.single().hostname).isEqualTo("10.0.0.1")
        assertThat(open("name\thost\tport\nweb\t10.0.0.1\t22\n").hosts.single().port).isEqualTo(22)
    }

    @Test
    fun quotedFieldsMayHoldDelimitersQuotesAndLineBreaks() {
        val source = open("name,host\n\"a, \"\"b\"\"\n c\",10.0.0.1\r\n")
        assertThat(source.hosts.single().name).isEqualTo("a, \"b\"\n c")
        assertThat(source.hosts.single().hostname).isEqualTo("10.0.0.1")
    }

    @Test
    fun aFileWithoutRecognisedHeadersNeedsAMapping() {
        val source = open("web1,10.0.0.1,22\ndb1,10.0.0.2,22\n")
        assertThat(source.settings.hasHeader).isFalse()
        assertThat(source.hosts).isEmpty()
        val mapped = source.withSettings(CsvSettings(hasHeader = false, columns = mapOf(CsvField.NAME to 0, CsvField.HOST to 1, CsvField.PORT to 2)))
        assertThat(mapped.hosts.map { it.name }).containsExactly("web1", "db1")
        assertThat(CsvImport.columnNames(mapped.table, hasHeader = false)).containsExactly("#1", "#2", "#3")
    }

    @Test
    fun switchingTheHeaderOnDropsTheFirstRow() {
        val source = open("web1,10.0.0.1\ndb1,10.0.0.2\n")
        val mapped = source.withSettings(CsvSettings(hasHeader = true, columns = mapOf(CsvField.NAME to 0, CsvField.HOST to 1)))
        assertThat(mapped.hosts.map { it.name }).containsExactly("db1")
    }

    @Test
    fun protocolWordsAreUnderstood() {
        assertThat(CsvImport.protocolOf("")).isEqualTo(ImportProtocol.SSH)
        assertThat(CsvImport.protocolOf("SSH2")).isEqualTo(ImportProtocol.SSH)
        assertThat(CsvImport.protocolOf("Telnet")).isEqualTo(ImportProtocol.TELNET)
        assertThat(CsvImport.protocolOf("rdp")).isEqualTo(ImportProtocol.OTHER)
        assertThat(CsvImport.protocolOf("ssh1")).isEqualTo(ImportProtocol.OTHER)
    }

    @Test
    fun foldersAndTagsAreSplitOnTheCommonSeparators() {
        assertThat(CsvImport.splitFolder("Prod > EU \\ Paris/ ")).containsExactly("Prod", "EU", "Paris")
        assertThat(CsvImport.splitTags("a; b,c | ,")).containsExactly("a", "b", "c")
    }

    @Test
    fun anEmptyOrBlankFileHasNoRows() {
        assertThat(CsvImport.open("")).isNull()
        assertThat(CsvImport.open("\n\n  \n")).isNull()
    }

    @Test
    fun aMissingHostColumnGivesNoHosts() {
        val source = open("name,port\nweb,22\n")
        assertThat(source.settings.columns.containsKey(CsvField.HOST)).isFalse()
        assertThat(source.hosts).isEmpty()
    }

    @Test
    fun theTwentyThousandRowLimitStopsAHugeFile() {
        val text = StringBuilder("name,host\n")
        repeat(25_000) { text.append("h$it,10.0.0.1\n") }
        assertThat(open(text.toString()).table.rows.size).isLessThanOrEqualTo(CsvReader.MAX_ROWS + 1)
    }
}
