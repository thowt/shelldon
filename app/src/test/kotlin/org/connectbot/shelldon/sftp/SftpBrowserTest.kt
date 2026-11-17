/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class SftpBrowserTest {
    private val fake = FakeSftpClient().apply {
        folders += listOf("/home", "/home/test", "/home/test/docs", "/home/test/docs/deep")
        files["/home/test/a.txt"] = "hello".toByteArray()
        files["/home/test/.hidden"] = ByteArray(3)
        files["/home/test/docs/b.txt"] = ByteArray(10)
        files["/home/test/docs/deep/c.txt"] = ByteArray(1)
        links["/home/test/docs-link"] = "/home/test/docs"
        links["/home/test/a-link"] = "/home/test/a.txt"
        links["/home/test/dead-link"] = "/nowhere"
    }
    private val browser = SftpBrowser(fake)

    private fun failureOf(block: suspend () -> Unit): SftpFailure? = try {
        runBlocking { block() }
        null
    } catch (e: SftpFailure) {
        e
    }

    @Test
    fun homeIsTheServersStartingFolder() = runBlocking<Unit> {
        assertThat(browser.home()).isEqualTo("/home/test")
    }

    @Test
    fun listingSortsFoldersFirstAndSkipsDotEntries() = runBlocking<Unit> {
        val items = browser.list("/home/test")
        assertThat(items.map { it.name }).containsExactly("docs", "docs-link", ".hidden", "a-link", "a.txt", "dead-link")
        assertThat(items.first { it.name == "docs" }.kind).isEqualTo(SftpKind.FOLDER)
        assertThat(items.first { it.name == "a.txt" }.size).isEqualTo(5L)
        assertThat(items.first { it.name == "a.txt" }.path).isEqualTo("/home/test/a.txt")
    }

    @Test
    fun linksToFoldersOpenAsFoldersAndOthersDoNot() = runBlocking<Unit> {
        val items = browser.list("/home/test").associateBy { it.name }
        assertThat(items.getValue("docs-link").opensAsFolder).isTrue()
        assertThat(items.getValue("a-link").opensAsFolder).isFalse()
        assertThat(items.getValue("dead-link").opensAsFolder).isFalse()
        assertThat(items.getValue("a-link").kind).isEqualTo(SftpKind.LINK)
    }

    @Test
    fun aListingWithoutModeBitsIsCompletedWithLstat() = runBlocking<Unit> {
        fake.listingWithoutModes = true
        val items = browser.list("/home/test").associateBy { it.name }
        assertThat(items.getValue("docs").kind).isEqualTo(SftpKind.FOLDER)
        assertThat(items.getValue("a.txt").kind).isEqualTo(SftpKind.FILE)
        assertThat(items.getValue("docs-link").opensAsFolder).isTrue()
    }

    @Test
    fun entriesWithPathTricksFromTheServerAreDropped() = runBlocking<Unit> {
        fake.extraListedNames = listOf("../../etc/passwd", "x/y", "a\u0000b", "")
        val names = browser.list("/home/test").map { it.name }
        assertThat(names).containsExactly("docs", "docs-link", ".hidden", "a-link", "a.txt", "dead-link")
    }

    @Test
    fun listingAMissingFolderIsNotFound() {
        val failure = failureOf { browser.list("/nope") }
        assertThat(failure).isNotNull()
        assertThat(failure!!.problem).isEqualTo(SftpProblem.NOT_FOUND)
    }

    @Test
    fun createFolderMakesItAndRefusesDuplicatesAndBadNames() = runBlocking<Unit> {
        assertThat(browser.createFolder("/home/test", "new")).isEqualTo("/home/test/new")
        assertThat("/home/test/new" in fake.folders).isTrue()
        assertThat(failureOf { browser.createFolder("/home/test", "new") }!!.problem).isEqualTo(SftpProblem.ALREADY_EXISTS)
        assertThat(failureOf { browser.createFolder("/home/test", "a/b") }!!.problem).isEqualTo(SftpProblem.INVALID_NAME)
        assertThat(failureOf { browser.createFolder("/home/test", "..") }!!.problem).isEqualTo(SftpProblem.INVALID_NAME)
    }

    @Test
    fun renameStaysInTheFolderAndNeverReplaces() = runBlocking<Unit> {
        val item = browser.list("/home/test").first { it.name == "a.txt" }
        assertThat(browser.rename(item, "renamed.txt")).isEqualTo("/home/test/renamed.txt")
        assertThat("/home/test/renamed.txt" in fake.files).isTrue()
        val other = browser.list("/home/test").first { it.name == ".hidden" }
        assertThat(failureOf { browser.rename(other, "renamed.txt") }!!.problem).isEqualTo(SftpProblem.ALREADY_EXISTS)
        assertThat(failureOf { browser.rename(other, "../escape") }!!.problem).isEqualTo(SftpProblem.INVALID_NAME)
        assertThat("/home/test/.hidden" in fake.files).isTrue()
    }

    @Test
    fun deletingAFolderRemovesEverythingInsideItAndCountsIt() = runBlocking<Unit> {
        val docs = browser.list("/home/test").first { it.name == "docs" }
        val seen = mutableListOf<Int>()
        val removed = browser.delete(docs) { seen += it }
        assertThat(removed).isEqualTo(4)
        assertThat(seen.last()).isEqualTo(4)
        assertThat(fake.folders.any { it.startsWith("/home/test/docs/") || it == "/home/test/docs" }).isFalse()
        assertThat(fake.files.keys.any { it.startsWith("/home/test/docs/") }).isFalse()
        assertThat("/home/test/a.txt" in fake.files).isTrue()
    }

    @Test
    fun deletingALinkToAFolderRemovesOnlyTheLink() = runBlocking<Unit> {
        val link = browser.list("/home/test").first { it.name == "docs-link" }
        assertThat(browser.delete(link)).isEqualTo(1)
        assertThat("/home/test/docs-link" in fake.links).isFalse()
        assertThat("/home/test/docs/b.txt" in fake.files).isTrue()
    }

    @Test
    fun aFolderTreeThatIsTooDeepIsRefused() = runBlocking<Unit> {
        var path = "/home/test/deepest"
        fake.folders += path
        repeat(SftpBrowser.MAX_DEPTH + 2) {
            path += "/d"
            fake.folders += path
        }
        val item = SftpItem("deepest", "/home/test/deepest", SftpKind.FOLDER, null, null, 0x41ED)
        assertThat(failureOf { browser.delete(item) }!!.problem).isEqualTo(SftpProblem.TOO_DEEP)
    }

    @Test
    fun existsDistinguishesMissingFromError() = runBlocking<Unit> {
        assertThat(browser.exists("/home/test/a.txt")).isTrue()
        assertThat(browser.exists("/home/test/missing")).isFalse()
    }
}
