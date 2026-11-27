/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.hostimport

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class FileFormatsTest {
    private fun found(bytes: ByteArray): HostSource = (HostSources.read(bytes) as SourceResult.Found).source

    private fun found(text: String): HostSource = found(text.toByteArray(Charsets.UTF_8))

    private fun problem(text: String): SourceProblem = (HostSources.read(text.toByteArray(Charsets.UTF_8)) as SourceResult.Failed).problem

    // MobaXterm

    private val moba = "[Bookmarks]\r\nSubRep=\r\nImgNum=42\r\n" +
        "[Bookmarks_1]\r\nSubRep=Servers\\Prod\r\nImgNum=41\r\n" +
        "web=#109#0%10.0.0.1%2222%deploy%%-1%-1%%%%%0%0%0%%%-1%0%0%0%%1080%%0%0%1%#MobaFont%10%0%0%-1%15%236,236,236%30,30,30%180,180,192%0%-1%0%%xterm%-1%-1%_Std_Colors_0_%80%24%0%1%-1%<none>%%0%1%-1%-1%#0# #-1\r\n" +
        "router=#98#1%192.168.1.1%23%%-1%-1%#0# #-1\r\n" +
        "desktop=#91#4%10.0.0.9%3389%admin%#0# #-1\r\n" +
        "[Bookmarks_2]\r\nSubRep=\r\nImgNum=41\r\n" +
        "local=#109#0%localhost%22%<default>%%-1%-1%#0# #-1\r\n" +
        "[Colors]\r\nfoo=#109#0%ignored%22%x%\r\n"

    @Test
    fun mobaXtermSessionsKeepTheirFoldersAndProtocols() {
        val source = found(moba)
        assertThat(source.format).isEqualTo(SourceFormat.MOBAXTERM)
        val byName = source.hosts.associateBy { it.name }
        assertThat(byName.keys).containsExactlyInAnyOrder("web", "router", "desktop", "local")
        assertThat(byName.getValue("web").hostname).isEqualTo("10.0.0.1")
        assertThat(byName.getValue("web").port).isEqualTo(2222)
        assertThat(byName.getValue("web").username).isEqualTo("deploy")
        assertThat(byName.getValue("web").folder).containsExactly("Servers", "Prod")
        assertThat(byName.getValue("router").protocol).isEqualTo(ImportProtocol.TELNET)
        assertThat(byName.getValue("desktop").protocol).isEqualTo(ImportProtocol.OTHER)
        assertThat(byName.getValue("local").username).isEmpty()
        assertThat(byName.getValue("local").folder).isEmpty()
    }

    @Test
    fun mobaXtermFilesInTheWindowsCodePageAreDecoded() {
        val text = "[Bookmarks]\r\nSubRep=\r\n[Bookmarks_1]\r\nSubRep=Caf\u00E9\r\nbox=#109#0%10.0.0.1%22%root%\r\n"
        val source = found(text.toByteArray(charset("windows-1252")))
        assertThat(source.hosts.single().folder).containsExactly("Caf\u00E9")
    }

    // PuTTY

    private val putty = "Windows Registry Editor Version 5.00\r\n\r\n" +
        "[HKEY_CURRENT_USER\\Software\\SimonTatham\\PuTTY\\Sessions\\Default%20Settings]\r\n\"HostName\"=\"\"\r\n\r\n" +
        "[HKEY_CURRENT_USER\\Software\\SimonTatham\\PuTTY\\Sessions\\My%20Server]\r\n" +
        "\"HostName\"=\"example.org\"\r\n\"Protocol\"=\"ssh\"\r\n\"PortNumber\"=dword:000008ae\r\n\"UserName\"=\"root\"\r\n" +
        "\"PublicKeyFile\"=\"C:\\\\keys\\\\id.ppk\"\r\n\r\n" +
        "[HKEY_CURRENT_USER\\Software\\SimonTatham\\PuTTY\\Sessions\\Lab%2Frouter]\r\n" +
        "\"HostName\"=\"admin@192.168.1.1\"\r\n\"Protocol\"=\"telnet\"\r\n\"PortNumber\"=dword:00000017\r\n\r\n" +
        "[HKEY_CURRENT_USER\\Software\\SimonTatham\\PuTTY\\Sessions\\Console]\r\n\"HostName\"=\"COM1\"\r\n\"Protocol\"=\"serial\"\r\n"

    @Test
    fun puttySessionsAreReadFromARegistryExport() {
        val source = found(putty)
        assertThat(source.format).isEqualTo(SourceFormat.PUTTY)
        val byName = source.hosts.associateBy { it.name }
        assertThat(byName.keys).containsExactlyInAnyOrder("My Server", "router", "Console")
        assertThat(byName.getValue("My Server").port).isEqualTo(2222)
        assertThat(byName.getValue("My Server").username).isEqualTo("root")
        assertThat(byName.getValue("router").username).isEqualTo("admin")
        assertThat(byName.getValue("router").hostname).isEqualTo("192.168.1.1")
        assertThat(byName.getValue("router").protocol).isEqualTo(ImportProtocol.TELNET)
        assertThat(byName.getValue("router").folder).containsExactly("Lab")
        assertThat(byName.getValue("Console").protocol).isEqualTo(ImportProtocol.OTHER)
    }

    @Test
    fun puttyExportsInUtf16AreRead() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + putty.toByteArray(Charsets.UTF_16LE)
        assertThat(found(bytes).hosts.map { it.name }).contains("My Server")
    }

    // mRemoteNG

    private val mremote = """
        <?xml version="1.0" encoding="utf-8"?>
        <mrng:Connections xmlns:mrng="http://mremoteng.org" Name="Connections" Export="false" EncryptionEngine="AES" BlockCipherMode="GCM" KdfIterations="1000" FullFileEncryption="false" ConfVersion="2.6">
          <Node Name="Servers" Type="Container" Id="1">
            <Node Name="Prod" Type="Container" Id="2">
              <Node Name="web &amp; api" Type="Connection" Id="3" Username="root" Password="c2VjcmV0" Hostname="10.0.0.1" Protocol="SSH2" Port="22" />
              <Node Name="old" Type="Connection" Id="4" Hostname="10.0.0.2" Protocol="SSH1" Port="22" />
            </Node>
            <Node Name="router" Type="Connection" Id="5" Hostname="192.168.1.1" Protocol="Telnet" Port="23" />
          </Node>
          <Node Name="desktop" Type="Connection" Id="6" Hostname="10.0.0.9" Protocol="RDP" Port="3389" />
        </mrng:Connections>
    """.trimIndent()

    @Test
    fun mRemoteNgContainersBecomeFolders() {
        val source = found(mremote)
        assertThat(source.format).isEqualTo(SourceFormat.MREMOTENG)
        val byName = source.hosts.associateBy { it.name }
        assertThat(byName.keys).containsExactlyInAnyOrder("web & api", "old", "router", "desktop")
        assertThat(byName.getValue("web & api").folder).containsExactly("Servers", "Prod")
        assertThat(byName.getValue("web & api").hadPassword).isTrue()
        assertThat(byName.getValue("web & api").protocol).isEqualTo(ImportProtocol.SSH)
        assertThat(byName.getValue("old").protocol).isEqualTo(ImportProtocol.OTHER)
        assertThat(byName.getValue("router").folder).containsExactly("Servers")
        assertThat(byName.getValue("desktop").folder).isEmpty()
        assertThat(source.hosts.toString()).doesNotContain("c2VjcmV0")
    }

    @Test
    fun anXmlFileWithADocumentTypeIsRefused() {
        val bomb = "<?xml version=\"1.0\"?>\n<!DOCTYPE lolz [<!ENTITY lol \"lol\">]>\n<mrng:Connections xmlns:mrng=\"http://mremoteng.org\"><Node Name=\"&lol;\" Type=\"Connection\" Hostname=\"x\" /></mrng:Connections>"
        assertThat(problem(bomb)).isEqualTo(SourceProblem.UNSAFE_XML)
    }

    @Test
    fun aFullyEncryptedMRemoteNgFileIsRefusedPlainly() {
        val text = "<?xml version=\"1.0\"?><mrng:Connections xmlns:mrng=\"http://mremoteng.org\" FullFileEncryption=\"true\" ConfVersion=\"2.6\">AAAA</mrng:Connections>"
        assertThat(problem(text)).isEqualTo(SourceProblem.ENCRYPTED)
    }

    @Test
    fun brokenXmlIsReportedNotCrashed() {
        assertThat(problem("<?xml version=\"1.0\"?><mrng:Connections xmlns:mrng=\"http://mremoteng.org\" ConfVersion=\"2.6\"><Node Name=\"a")).isEqualTo(SourceProblem.MALFORMED)
    }

    @Test
    fun xmlThatIsNotAnMRemoteNgFileIsNotRecognised() {
        assertThat(problem("<?xml version=\"1.0\"?><note><to>x</to></note>")).isEqualTo(SourceProblem.UNRECOGNIZED)
    }

    @Test
    fun theXmlScannerDecodesNumericReferencesAndLeavesUnknownEntitiesAlone() {
        assertThat(MiniXml.decodeEntities("a&#65;&#x42;&lt;&unknown;&amp;")).isEqualTo("aAB<&unknown;&")
        assertThat(MiniXml.decodeEntities("&#0;&#xD800;")).isEqualTo("&#0;&#xD800;")
    }

    @Test
    fun deeplyNestedXmlIsStopped() {
        val text = "<?xml version=\"1.0\"?><mrng:Connections xmlns:mrng=\"http://mremoteng.org\" ConfVersion=\"2.6\">" + "<Node Type=\"Container\" Name=\"x\">".repeat(500)
        assertThat(problem(text)).isEqualTo(SourceProblem.MALFORMED)
    }

    // Detection

    @Test
    fun csvIsTheFallbackAndEmptyFilesAreNotRecognised() {
        assertThat(found("name,host\nweb,10.0.0.1\n").format).isEqualTo(SourceFormat.CSV)
        assertThat(HostSources.read(ByteArray(0))).isEqualTo(SourceResult.Failed(SourceProblem.UNRECOGNIZED))
        assertThat(HostSources.read(ByteArray(HostSources.MAX_BYTES + 1))).isEqualTo(SourceResult.Failed(SourceProblem.UNRECOGNIZED))
    }

    @Test
    fun aByteOrderMarkDoesNotSpoilTheFirstHeader() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "name,host\nweb,10.0.0.1\n".toByteArray()
        assertThat(found(bytes).hosts.single().name).isEqualTo("web")
    }
}
