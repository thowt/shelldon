/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sshconfig

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.sshlib.AlgorithmCategory
import org.junit.Test
import java.util.UUID
import kotlin.io.encoding.Base64

class SshImportPlannerTest {
    private fun file(name: String, text: String) = PickedFile(name, text.toByteArray())

    /** A key file whose "content" is just a marker the fake decoder understands. */
    private fun keyFile(name: String, marker: String) = file(name, TestPem.openSsh(marker) + "\n")

    private val decoder = KeyFileDecoder { text, passphrase ->
        when {
            "ENCRYPTED" in text -> when (passphrase) {
                null -> KeyDecodeResult.NeedsPassphrase
                "right" -> KeyDecodeResult.Decoded("ssh-ed25519", byteArrayOf(9), byteArrayOf(9))
                else -> KeyDecodeResult.WrongPassphrase
            }

            "BROKEN" in text -> KeyDecodeResult.Unreadable

            else -> {
                val pub = text.lines()[1].removePrefix("PUB").toByte()
                KeyDecodeResult.Decoded("ssh-ed25519", byteArrayOf(pub), byteArrayOf(pub))
            }
        }
    }

    private fun input(
        files: List<PickedFile>,
        hosts: List<ExistingHost> = emptyList(),
        keys: List<ExistingKey> = emptyList(),
        stored: Map<UUID, List<StoredHostKey>> = emptyMap(),
        passphrases: Map<String, String> = emptyMap(),
    ) = SshImportInput(files, hosts, keys, stored, passphrases)

    private fun plan(input: SshImportInput, accepts: (String) -> Boolean = { true }) = SshImportPlanner.plan(input, decoder, accepts)

    private fun host(nickname: String, hostname: String, port: Int = 22, local: Boolean = true) = ExistingHost(UUID.randomUUID(), nickname, hostname, port, local)

    private fun blob(base64: String) = Base64.decode(base64)

    // File kinds

    @Test
    fun filesAreSortedByContentNotByName() {
        assertThat(PickedFiles.kindOf(file("config", "Host a\n  HostName a.example"))).isEqualTo(PickedFileKind.CONFIG)
        assertThat(PickedFiles.kindOf(file("renamed.txt", "Host a\n  HostName a.example"))).isEqualTo(PickedFileKind.CONFIG)
        assertThat(PickedFiles.kindOf(file("known_hosts", "a.example ssh-ed25519 $ED1"))).isEqualTo(PickedFileKind.KNOWN_HOSTS)
        assertThat(PickedFiles.kindOf(file("x", "host ssh-ed25519 $ED1"))).isEqualTo(PickedFileKind.KNOWN_HOSTS)
        assertThat(PickedFiles.kindOf(file("id_ed25519", TestPem.openSsh("abc")))).isEqualTo(PickedFileKind.PRIVATE_KEY)
        assertThat(PickedFiles.kindOf(file("id_ed25519.pub", "ssh-ed25519 $ED1 me@pc"))).isEqualTo(PickedFileKind.PUBLIC_KEY)
        assertThat(PickedFiles.kindOf(file("x", "ssh-ed25519 $ED1"))).isEqualTo(PickedFileKind.PUBLIC_KEY)
    }

    @Test
    fun aHostNamedLikeAKeyTypeIsStillAConfig() {
        assertThat(PickedFiles.kindOf(file("config", "Host ssh-gateway\n  HostName gw.example"))).isEqualTo(PickedFileKind.CONFIG)
    }

    @Test
    fun otherFilesAreNotUsed() {
        assertThat(PickedFiles.kindOf(PickedFile("a.bin", byteArrayOf(1, 0, 2)))).isEqualTo(PickedFileKind.UNKNOWN)
        assertThat(PickedFiles.kindOf(file("empty", ""))).isEqualTo(PickedFileKind.UNKNOWN)
        assertThat(PickedFiles.kindOf(file("notes.txt", "remember the milk"))).isEqualTo(PickedFileKind.UNKNOWN)
        assertThat(PickedFiles.kindOf(file("authorized_keys", "# only comments\n"))).isEqualTo(PickedFileKind.UNKNOWN)
        val huge = TestPem.openSsh("A".repeat(PickedFiles.MAX_KEY_BYTES))
        assertThat(PickedFiles.kindOf(file("big", huge))).isEqualTo(PickedFileKind.UNKNOWN)
    }

    // Hosts

    @Test
    fun newAndExistingHostsAreToldApart() {
        val mine = host("web", "mine.example")
        val plan = plan(input(listOf(file("config", "Host web\n  HostName web.example\nHost db\n  HostName db.example")), hosts = listOf(mine)))
        assertThat(plan.hosts.map { it.entry.alias to it.status }).containsExactly("web" to HostImportStatus.EXISTS, "db" to HostImportStatus.NEW)
        assertThat(plan.hosts.first().id).isEqualTo(mine.id)
        assertThat(plan.newHosts.map { it.entry.alias }).containsExactly("db")
    }

    @Test
    fun aSecondConfigFileIsIgnoredAndReported() {
        val plan = plan(input(listOf(file("config", "Host a"), file("config.bak", "Host b"), file("notes", "hello"))))
        assertThat(plan.hosts.map { it.entry.alias }).containsExactly("a")
        assertThat(plan.ignoredFiles).containsExactlyInAnyOrder("config.bak", "notes")
    }

    // Algorithms

    @Test
    fun algorithmListsBecomePolicyExpressionsPerCategory() {
        val plan = plan(
            input(
                listOf(
                    file(
                        "config",
                        """
                        Host old
                          KexAlgorithms +diffie-hellman-group1-sha1,diffie-hellman-group14-sha1
                          Ciphers aes256-ctr,aes128-cbc
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val algorithms = plan.hosts.single().algorithms.associateBy { it.category }
        assertThat(algorithms.getValue(AlgorithmCategory.KEX).expression).isEqualTo("+diffie-hellman-group1-sha1,+diffie-hellman-group14-sha1")
        assertThat(algorithms.getValue(AlgorithmCategory.KEX).older).isTrue()
        assertThat(algorithms.getValue(AlgorithmCategory.CIPHER).expression).isEqualTo("aes256-ctr,aes128-cbc")
        assertThat(algorithms.getValue(AlgorithmCategory.CIPHER).older).isTrue()
        assertThat(plan.hosts.single().lowersDhMinimum).isTrue()
    }

    @Test
    fun modernAlgorithmsAreNotCalledOlderAndDoNotLowerTheMinimum() {
        val plan = plan(input(listOf(file("config", "Host new\n  KexAlgorithms curve25519-sha256\n  Ciphers chacha20-poly1305@openssh.com"))))
        assertThat(plan.hosts.single().algorithms.none { it.older }).isTrue()
        assertThat(plan.hosts.single().lowersDhMinimum).isFalse()
    }

    @Test
    fun removingAWeakAlgorithmIsNotCalledOlderAndDoesNotLowerTheMinimum() {
        val plan = plan(input(listOf(file("config", "Host a\n  KexAlgorithms -diffie-hellman-group1-sha1\n  Ciphers -aes128-cbc"))))
        assertThat(plan.hosts.single().algorithms.none { it.older }).isTrue()
        assertThat(plan.hosts.single().lowersDhMinimum).isFalse()
    }

    @Test
    fun entriesTheSshLibraryRejectsAreLeftOutAndNamed() {
        val accepts = { token: String -> token.trimStart('+', '-', '^') != "arcfour" }
        val plan = plan(input(listOf(file("config", "Host old\n  Ciphers +arcfour,aes128-cbc\nHost bad\n  Ciphers arcfour"))), accepts)
        val old = plan.hosts.first { it.entry.alias == "old" }
        assertThat(old.algorithms.single().expression).isEqualTo("+aes128-cbc")
        assertThat(old.droppedAlgorithms).containsExactly("CIPHER: arcfour")
        val bad = plan.hosts.first { it.entry.alias == "bad" }
        assertThat(bad.algorithms).isEmpty()
        assertThat(bad.droppedAlgorithms).containsExactly("CIPHER: arcfour")
    }

    @Test
    fun wildcardOnlyAlgorithmsAreMarkedSoTheyCanBeLeftOut() {
        val plan = plan(input(listOf(file("config", "Host a\n  Ciphers aes128-cbc\nHost b\nHost *\n  KexAlgorithms +diffie-hellman-group1-sha1"))))
        val a = plan.hosts.first { it.entry.alias == "a" }
        assertThat(a.algorithms.associate { it.category to it.fromWildcard }).containsEntry(AlgorithmCategory.CIPHER, false).containsEntry(AlgorithmCategory.KEX, true)
        val b = plan.hosts.first { it.entry.alias == "b" }
        assertThat(b.algorithms.single().fromWildcard).isTrue()
    }

    // Refused and unsupported options

    @Test
    fun refusedOptionsAreListedWithTheirHostsAndNotAmongTheUnsupportedOnes() {
        val plan = plan(
            input(
                listOf(
                    file(
                        "config",
                        """
                        Host a
                          StrictHostKeyChecking no
                          ProxyCommand nc %h %p
                        Host b
                          StrictHostKeyChecking accept-new
                        Host c
                          UserKnownHostsFile /dev/null
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        assertThat(plan.refused).containsEntry("StrictHostKeyChecking", listOf("a", "b")).containsEntry("UserKnownHostsFile", listOf("c"))
        assertThat(plan.unsupportedOptions).containsExactly("proxycommand")
    }

    // Keys

    @Test
    fun keysAreImportedLinkedToTheHostsThatNameThem() {
        val plan = plan(
            input(
                listOf(
                    file("config", "Host a\n  IdentityFile ~/.ssh/id_work"),
                    keyFile("id_work", "PUB1"),
                ),
            ),
        )
        val key = plan.keys.single()
        assertThat(key.state).isEqualTo(KeyFileState.IMPORT)
        val a = plan.hosts.single()
        assertThat(a.identityState).isEqualTo(IdentityLinkState.IMPORTED_KEY)
        assertThat(a.identityKeyId).isEqualTo(key.id)
    }

    @Test
    fun aKeyAlreadyInTheKeychainIsNotImportedAgainEvenUnderAnotherName() {
        val existing = ExistingKey(UUID.randomUUID(), "my-old-name", byteArrayOf(1))
        val plan = plan(input(listOf(file("config", "Host a\n  IdentityFile ~/.ssh/id_work"), keyFile("id_work", "PUB1")), keys = listOf(existing)))
        assertThat(plan.keys.single().state).isEqualTo(KeyFileState.ALREADY_IN_KEYCHAIN)
        assertThat(plan.keys.single().id).isEqualTo(existing.id)
        assertThat(plan.hosts.single().identityState).isEqualTo(IdentityLinkState.EXISTING_KEY)
        assertThat(plan.hosts.single().identityKeyId).isEqualTo(existing.id)
    }

    @Test
    fun aDifferentKeyWithTheSameNameIsNotOverwritten() {
        val existing = ExistingKey(UUID.randomUUID(), "id_work", byteArrayOf(99))
        val plan = plan(input(listOf(keyFile("id_work", "PUB1")), keys = listOf(existing)))
        assertThat(plan.keys.single().state).isEqualTo(KeyFileState.NAME_TAKEN)
    }

    @Test
    fun aKeychainKeyWithTheNameIsUsedWhenTheFileIsNotPicked() {
        val existing = ExistingKey(UUID.randomUUID(), "id_work", null)
        val plan = plan(input(listOf(file("config", "Host a\n  IdentityFile ~/.ssh/id_work")), keys = listOf(existing)))
        assertThat(plan.hosts.single().identityState).isEqualTo(IdentityLinkState.EXISTING_KEY)
        assertThat(plan.hosts.single().identityKeyId).isEqualTo(existing.id)
    }

    @Test
    fun anIdentityFileWithNoKeyAnywhereIsReportedAsMissing() {
        val plan = plan(input(listOf(file("config", "Host a\n  IdentityFile C:\\Users\\me\\.ssh\\id_gone"))))
        assertThat(plan.hosts.single().identityState).isEqualTo(IdentityLinkState.MISSING)
        assertThat(plan.hosts.single().identityName).isEqualTo("id_gone")
        assertThat(plan.hosts.single().identityKeyId).isNull()
    }

    @Test
    fun anEncryptedKeyWaitsForItsPassphraseAndAWrongOneIsReported() {
        val files = listOf(keyFile("id_enc", "ENCRYPTED"), keyFile("id_bad", "BROKEN"))
        assertThat(plan(input(files)).keys.associate { it.fileName to it.state })
            .containsEntry("id_enc", KeyFileState.NEEDS_PASSPHRASE)
            .containsEntry("id_bad", KeyFileState.UNREADABLE)
        assertThat(plan(input(files, passphrases = mapOf("id_enc" to "nope"))).keys.first { it.fileName == "id_enc" }.state).isEqualTo(KeyFileState.WRONG_PASSPHRASE)
        assertThat(plan(input(files, passphrases = mapOf("id_enc" to "right"))).keys.first { it.fileName == "id_enc" }.state).isEqualTo(KeyFileState.IMPORT)
    }

    @Test
    fun publicKeyFilesAreNotUsed() {
        val plan = plan(input(listOf(file("id_ed25519.pub", "ssh-ed25519 $ED1 me@pc"))))
        assertThat(plan.keys).isEmpty()
        assertThat(plan.ignoredFiles).containsExactly("id_ed25519.pub")
    }

    // known_hosts

    private val twoHosts = "Host web\n  HostName web.example.com\nHost db\n  HostName db.example.com\n  Port 2222"

    @Test
    fun hostKeysAreMatchedToHostsByNameAndPort() {
        val known = file("known_hosts", "web.example.com ssh-ed25519 $ED1\n[db.example.com]:2222 ssh-ed25519 $ED2\nother.example.com ssh-ed25519 $ED3\ndb.example.com ssh-ed25519 $ED3")
        val plan = plan(input(listOf(file("config", twoHosts), known)))
        val knownPlan = plan.knownHosts!!
        assertThat(knownPlan.add.map { it.hostNickname to it.algorithm }).containsExactlyInAnyOrder("web" to "ssh-ed25519", "db" to "ssh-ed25519")
        assertThat(knownPlan.add.first { it.hostNickname == "db" }.keyBlob).isEqualTo(blob(ED2))
        // `other.example.com` is not saved here; `db.example.com` on port 22 is not the host on port 2222.
        assertThat(knownPlan.unmatchedLines).isEqualTo(2)
        assertThat(knownPlan.totalLines).isEqualTo(4)
    }

    @Test
    fun existingHostsGetTheirHostKeysToo() {
        val mine = host("web", "web.example.com")
        val plan = plan(input(listOf(file("known_hosts", "web.example.com ssh-ed25519 $ED1")), hosts = listOf(mine)))
        assertThat(plan.knownHosts!!.add.single().hostId).isEqualTo(mine.id)
    }

    @Test
    fun hostsInAnotherRepositoryAreNotTouched() {
        val synced = host("web", "web.example.com", local = false)
        val plan = plan(input(listOf(file("known_hosts", "web.example.com ssh-ed25519 $ED1")), hosts = listOf(synced)))
        assertThat(plan.knownHosts!!.add).isEmpty()
        assertThat(plan.knownHosts!!.unmatchedLines).isEqualTo(1)
    }

    @Test
    fun aKeyThatIsAlreadyStoredIsCountedNotAddedAgain() {
        val mine = host("web", "web.example.com")
        val stored = mapOf(mine.id to listOf(StoredHostKey("ssh-ed25519", blob(ED1))))
        val plan = plan(input(listOf(file("known_hosts", "web.example.com ssh-ed25519 $ED1")), hosts = listOf(mine), stored = stored))
        assertThat(plan.knownHosts!!.add).isEmpty()
        assertThat(plan.knownHosts!!.alreadyKnown).isEqualTo(1)
    }

    @Test
    fun aKeyThatDiffersFromTheStoredOneIsNeverReplaced() {
        val mine = host("web", "web.example.com")
        val stored = mapOf(mine.id to listOf(StoredHostKey("ssh-ed25519", blob(ED2))))
        val plan = plan(input(listOf(file("known_hosts", "web.example.com ssh-ed25519 $ED1")), hosts = listOf(mine), stored = stored))
        assertThat(plan.knownHosts!!.add).isEmpty()
        assertThat(plan.knownHosts!!.conflicts).containsExactly("web (ssh-ed25519)")
    }

    @Test
    fun aDifferentKeyTypeForAKnownHostIsAdded() {
        val mine = host("web", "web.example.com")
        val stored = mapOf(mine.id to listOf(StoredHostKey("ssh-rsa", blob(RSA1))))
        val plan = plan(input(listOf(file("known_hosts", "web.example.com ssh-ed25519 $ED1")), hosts = listOf(mine), stored = stored))
        assertThat(plan.knownHosts!!.add.single().algorithm).isEqualTo("ssh-ed25519")
    }

    @Test
    fun severalDifferentKeysOfOneTypeForOneHostAreNotGuessedAt() {
        val plan = plan(input(listOf(file("config", "Host web\n  HostName web.example.com"), file("known_hosts", "web.example.com ssh-ed25519 $ED1\n10.0.0.1,web.example.com ssh-ed25519 $ED2\nweb.example.com ssh-rsa $RSA1"))))
        val known = plan.knownHosts!!
        assertThat(known.ambiguous).containsExactly("web (ssh-ed25519)")
        assertThat(known.add.map { it.algorithm }).containsExactly("ssh-rsa")
    }

    @Test
    fun theSameKeyListedUnderTwoNamesIsOneEntry() {
        val plan = plan(input(listOf(file("config", "Host web\n  HostName web.example.com"), file("known_hosts", "web.example.com ssh-ed25519 $ED1\nweb.example.com,10.0.0.1 ssh-ed25519 $ED1"))))
        assertThat(plan.knownHosts!!.add).hasSize(1)
        assertThat(plan.knownHosts!!.ambiguous).isEmpty()
    }

    @Test
    fun aKnownHostsFileMostlyOfCertificateAuthorityLinesIsStillKnownHosts() {
        val text = "@cert-authority *.example.com ssh-ed25519 $ED1\n@cert-authority *.example.org ssh-ed25519 $ED2\n@revoked bad.example.com ssh-ed25519 $ED3"

        assertThat(PickedFiles.kindOf(file("known_hosts", text))).isEqualTo(PickedFileKind.KNOWN_HOSTS)
    }

    @Test
    fun hashedEntriesAreMatchedAndCertificateAuthoritiesAreOnlyCounted() {
        val text = "$HASHED_PLAIN ssh-ed25519 $ED1\n@cert-authority *.example.com ssh-ed25519 $ED2\n@revoked bad.example.com ssh-ed25519 $ED3"
        val plan = plan(input(listOf(file("config", "Host ex\n  HostName example.com"), file("known_hosts", text))))
        val known = plan.knownHosts!!
        assertThat(known.add.single().hostNickname).isEqualTo("ex")
        assertThat(known.certAuthorityLines).isEqualTo(1)
        assertThat(known.revokedLines).isEqualTo(1)
        assertThat(known.totalLines).isEqualTo(3)
    }

    @Test
    fun noKnownHostsFileMeansNoKnownHostsPlan() {
        assertThat(plan(input(listOf(file("config", "Host a")))).knownHosts).isNull()
    }
}
