/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sshconfig

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.sshlib.AlgorithmCategory
import org.junit.Test

class SshConfigParserTest {
    private fun hosts(config: String) = SshConfigParser.parse(config).hosts.associateBy { it.alias }

    @Test
    fun readsTheBasicFields() {
        val host = hosts(
            """
            Host router
                HostName 192.168.1.1
                User admin
                Port 2222
            """.trimIndent(),
        ).getValue("router")
        assertThat(host.hostName).isEqualTo("192.168.1.1")
        assertThat(host.user).isEqualTo("admin")
        assertThat(host.port).isEqualTo(2222)
        assertThat(host.proxyJump).isNull()
    }

    @Test
    fun aliasIsTheHostNameWhenNoneIsGiven() {
        val host = hosts("Host nas.lan\n  User root").getValue("nas.lan")
        assertThat(host.hostName).isEqualTo("nas.lan")
        assertThat(host.port).isEqualTo(22)
    }

    @Test
    fun keywordsAreCaseInsensitiveAndAcceptEqualsSigns() {
        val host = hosts("host box\n  hostname=example.org\n  USER = bob\n  Port=2200").getValue("box")
        assertThat(host.hostName).isEqualTo("example.org")
        assertThat(host.user).isEqualTo("bob")
        assertThat(host.port).isEqualTo(2200)
    }

    @Test
    fun theFirstValueInFileOrderWinsAndWildcardBlocksGiveDefaults() {
        val all = hosts(
            """
            Host special
                User root
            Host *.lan special
                User deploy
                Port 2022
            Host *
                User fallback
                Port 22
                ServerAliveInterval 30
            Host plain.lan
                HostName 10.0.0.9
            """.trimIndent(),
        )
        assertThat(all.keys).containsExactlyInAnyOrder("special", "plain.lan")
        assertThat(all.getValue("special").user).isEqualTo("root")
        assertThat(all.getValue("special").port).isEqualTo(2022)
        assertThat(all.getValue("plain.lan").user).isEqualTo("deploy")
        assertThat(all.getValue("plain.lan").hostName).isEqualTo("10.0.0.9")
        assertThat(all.getValue("plain.lan").unsupported).containsExactly("serveraliveinterval")
    }

    @Test
    fun globalOptionsBeforeTheFirstHostApplyToEveryone() {
        // Options before the first Host line are read first, so they win over later blocks, as in OpenSSH.
        val all = hosts("User everyone\n\nHost a\n  HostName a.example\nHost b\n  User bee\n  Port 2200")
        assertThat(all.getValue("a").user).isEqualTo("everyone")
        assertThat(all.getValue("b").user).isEqualTo("everyone")
        assertThat(all.getValue("b").port).isEqualTo(2200)
    }

    @Test
    fun negatedPatternsExcludeAHost() {
        val all = hosts("Host * !secret\n  User ops\nHost secret\n  HostName s.example\nHost open\n  HostName o.example")
        assertThat(all.getValue("open").user).isEqualTo("ops")
        assertThat(all.getValue("secret").user).isNull()
    }

    @Test
    fun percentHInHostNameIsTheAlias() {
        assertThat(hosts("Host web\n  HostName %h.example.com").getValue("web").hostName).isEqualTo("web.example.com")
    }

    @Test
    fun proxyJumpIsReadAndNoneIsNot() {
        val all = hosts("Host inner\n  ProxyJump bastion\nHost bastion\n  ProxyJump none\nHost other\n  ProxyJump a,b")
        assertThat(all.getValue("inner").proxyJump).isEqualTo("bastion")
        assertThat(all.getValue("bastion").proxyJump).isNull()
        assertThat(all.getValue("other").proxyJump).isEqualTo("a,b")
    }

    @Test
    fun commentsQuotesAndBlankLinesAreHandled() {
        val all = hosts(
            """
            # my lab
            Host "lab box" # trailing comment
                HostName "10.1.1.1"
                User   me   # inline

            Host two
            """.trimIndent(),
        )
        assertThat(all.keys).containsExactlyInAnyOrder("lab box", "two")
        assertThat(all.getValue("lab box").hostName).isEqualTo("10.1.1.1")
        assertThat(all.getValue("lab box").user).isEqualTo("me")
    }

    @Test
    fun badPortsFallBackToTheDefault() {
        assertThat(hosts("Host a\n  Port 0").getValue("a").port).isEqualTo(22)
        assertThat(hosts("Host a\n  Port 70000").getValue("a").port).isEqualTo(22)
        assertThat(hosts("Host a\n  Port ssh").getValue("a").port).isEqualTo(22)
    }

    @Test
    fun matchBlocksIncludesAndWildcardsAreReportedNotGuessed() {
        val result = SshConfigParser.parse(
            """
            Include ~/.ssh/extra.conf
            Match host foo exec "true"
                User hidden
            Host *.prod
                User prod
            Host real
                HostName r.example
            """.trimIndent(),
        )
        assertThat(result.matchBlocks).isEqualTo(1)
        assertThat(result.includes).containsExactly("~/.ssh/extra.conf")
        assertThat(result.wildcardPatterns).containsExactly("*.prod")
        assertThat(result.hosts.map { it.alias }).containsExactly("real")
        // The Match block's options were not applied to anyone.
        assertThat(result.hosts.single().user).isNull()
    }

    @Test
    fun unsupportedOptionsAreListedPerHost() {
        val host = hosts("Host old\n  HostName 1.2.3.4\n  KexAlgorithms +diffie-hellman-group1-sha1\n  IdentityFile ~/.ssh/id\n  ProxyCommand nc %h %p").getValue("old")
        assertThat(host.unsupported).containsExactly("proxycommand")
    }

    @Test
    fun algorithmListsAreReadPerCategoryAsWritten() {
        val host = hosts(
            """
            Host old
              KexAlgorithms +diffie-hellman-group1-sha1
              Ciphers aes128-cbc,aes256-cbc
              MACs hmac-sha1
              HostKeyAlgorithms +ssh-rsa
              PubkeyAcceptedAlgorithms +ssh-rsa
            """.trimIndent(),
        ).getValue("old")
        assertThat(host.algorithms).containsEntry(AlgorithmCategory.KEX, "+diffie-hellman-group1-sha1")
        assertThat(host.algorithms).containsEntry(AlgorithmCategory.CIPHER, "aes128-cbc,aes256-cbc")
        assertThat(host.algorithms).containsEntry(AlgorithmCategory.MAC, "hmac-sha1")
        assertThat(host.algorithms).containsEntry(AlgorithmCategory.HOST_KEY, "+ssh-rsa")
        assertThat(host.algorithms).containsEntry(AlgorithmCategory.PUBKEY, "+ssh-rsa")
        assertThat(host.algorithmsFromWildcard).isEmpty()
        assertThat(host.unsupported).isEmpty()
    }

    @Test
    fun theOlderNameForPubkeyAlgorithmsIsRead() {
        val host = hosts("Host old\n  PubkeyAcceptedKeyTypes +ssh-rsa").getValue("old")
        assertThat(host.algorithms).containsEntry(AlgorithmCategory.PUBKEY, "+ssh-rsa")
    }

    @Test
    fun algorithmListsThatOnlyComeFromWildcardBlocksAreMarked() {
        val parsed = hosts(
            """
            Host named
              Ciphers aes128-cbc
            Host *
              KexAlgorithms +diffie-hellman-group1-sha1
              Ciphers aes256-cbc
            """.trimIndent(),
        )
        val named = parsed.getValue("named")
        // Ciphers was set for this host by name; the first value wins, so the wildcard one is not used.
        assertThat(named.algorithms).containsEntry(AlgorithmCategory.CIPHER, "aes128-cbc")
        assertThat(named.algorithmsFromWildcard).containsExactly(AlgorithmCategory.KEX)
    }

    @Test
    fun aWildcardBlockBeforeTheHostBlockWinsLikeInOpenSsh() {
        val host = hosts("Host *\n  Ciphers aes256-cbc\nHost a\n  Ciphers aes128-cbc").getValue("a")
        assertThat(host.algorithms).containsEntry(AlgorithmCategory.CIPHER, "aes256-cbc")
        assertThat(host.algorithmsFromWildcard).containsExactly(AlgorithmCategory.CIPHER)
    }

    @Test
    fun everyIdentityFileAppliesInOrderAndNoneIsIgnored() {
        val host = hosts(
            """
            Host a
              IdentityFile ~/.ssh/id_a
              IdentityFile none
            Host *
              IdentityFile ~/.ssh/id_default
            """.trimIndent(),
        ).getValue("a")
        assertThat(host.identityFiles).containsExactly("~/.ssh/id_a", "~/.ssh/id_default")
    }

    @Test
    fun turningHostKeyCheckingOffIsRefusedAndRecordedNotImported() {
        val parsed = hosts(
            """
            Host lab
              StrictHostKeyChecking no
            Host eager
              StrictHostKeyChecking accept-new
            Host fine
              StrictHostKeyChecking yes
            Host asking
              StrictHostKeyChecking ask
            Host volatile
              UserKnownHostsFile /dev/null
            Host elsewhere
              UserKnownHostsFile ~/.ssh/other_hosts
            """.trimIndent(),
        )
        assertThat(parsed.getValue("lab").refused).containsEntry("StrictHostKeyChecking", "no")
        assertThat(parsed.getValue("eager").refused).containsKey("StrictHostKeyChecking")
        assertThat(parsed.getValue("fine").refused).isEmpty()
        assertThat(parsed.getValue("asking").refused).isEmpty()
        assertThat(parsed.getValue("volatile").refused).containsEntry("UserKnownHostsFile", "/dev/null")
        assertThat(parsed.getValue("elsewhere").refused).isEmpty()
    }

    @Test
    fun pubkeyAuthenticationNoIsRead() {
        assertThat(hosts("Host a\n  PubkeyAuthentication no").getValue("a").pubkeyAuthentication).isFalse()
        assertThat(hosts("Host a\n  PubkeyAuthentication yes").getValue("a").pubkeyAuthentication).isNull()
        assertThat(hosts("Host a").getValue("a").pubkeyAuthentication).isNull()
    }

    @Test
    fun opensshAlgorithmListsBecomePolicyExpressions() {
        assertThat(SshConfigParser.toPolicyExpression("+a,b")).isEqualTo("+a,+b")
        assertThat(SshConfigParser.toPolicyExpression("-a")).isEqualTo("-a")
        assertThat(SshConfigParser.toPolicyExpression("^a, b")).isEqualTo("^a,^b")
        assertThat(SshConfigParser.toPolicyExpression("a,b")).isEqualTo("a,b")
        assertThat(SshConfigParser.toPolicyExpression("  +a  ")).isEqualTo("+a")
        assertThat(SshConfigParser.toPolicyExpression("")).isNull()
        assertThat(SshConfigParser.toPolicyExpression("+")).isNull()
        assertThat(SshConfigParser.toPolicyExpression("+,")).isNull()
    }

    @Test
    fun globMatchingSupportsStarAndQuestionMarkOnly() {
        assertThat(SshConfigParser.glob("web?.lan", "web1.lan")).isTrue()
        assertThat(SshConfigParser.glob("web?.lan", "web12.lan")).isFalse()
        assertThat(SshConfigParser.glob("*.lan", "a.b.lan")).isTrue()
        assertThat(SshConfigParser.glob("a.b", "aXb")).isFalse()
    }
}
