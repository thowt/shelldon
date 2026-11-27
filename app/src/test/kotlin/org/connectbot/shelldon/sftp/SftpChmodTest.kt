/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.sshlib.SftpAttributes
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SftpStatusCode
import org.junit.Test

class SftpChmodTest {
    /** The fake server, plus a record of every permission change asked of it and a way to make some fail. */
    private class Recording(inner: FakeSftpClient) : SftpClient by inner {
        val calls = mutableListOf<Pair<String, SftpAttributes>>()
        val failures = HashMap<String, SftpStatusCode>()

        override suspend fun setstat(path: String, attrs: SftpAttributes): SftpResult<Unit> {
            calls += path to attrs
            val code = failures[path] ?: return SftpResult.Success(Unit)
            return SftpResult.ServerError(code, code.name)
        }

        val paths: List<String> get() = calls.map { it.first }
        fun modeOf(path: String): Int? = calls.lastOrNull { it.first == path }?.second?.permissions
    }

    private val fake = FakeSftpClient().apply {
        folders += listOf("/home", "/home/test", "/home/test/docs", "/home/test/docs/deep")
        files["/home/test/a.txt"] = "hello".toByteArray()
        files["/home/test/docs/b.txt"] = ByteArray(10)
        files["/home/test/docs/deep/c.txt"] = ByteArray(1)
        links["/home/test/docs/outside"] = "/etc"
        links["/home/test/a-link"] = "/home/test/a.txt"
    }
    private val server = Recording(fake)
    private val chmod = SftpChmod(server)

    private fun item(path: String, kind: SftpKind) = SftpItem(path.substringAfterLast('/'), path, kind, size = 1, modified = null, permissions = null)

    private fun failureOf(block: suspend () -> Unit): SftpFailure? = try {
        runBlocking { block() }
        null
    } catch (e: SftpFailure) {
        e
    }

    @Test
    fun oneFileGetsExactlyTheModeAndNothingElse() = runBlocking<Unit> {
        val result = chmod.apply(item("/home/test/a.txt", SftpKind.FILE), SftpMode(0b110_000_000), recursive = false)

        assertThat(result).isEqualTo(ChmodResult(changed = 1, failed = 0))
        assertThat(server.calls).hasSize(1)
        // Owner, size and times are not sent, so the server leaves them as they are.
        assertThat(server.calls.single().second).isEqualTo(SftpAttributes(permissions = 384))
    }

    @Test
    fun aFolderWithoutRecursionIsChangedAloneAndExactlyAsTyped() = runBlocking<Unit> {
        chmod.apply(item("/home/test/docs", SftpKind.FOLDER), SftpMode(0b110_100_100), recursive = false)

        assertThat(server.paths).containsExactly("/home/test/docs")
        assertThat(server.modeOf("/home/test/docs")).isEqualTo(0b110_100_100)
    }

    @Test
    fun recursionChangesFilesAndFoldersAndKeepsFoldersOpenable() = runBlocking<Unit> {
        val result = chmod.apply(item("/home/test/docs", SftpKind.FOLDER), SftpMode(0b110_100_100), recursive = true)

        assertThat(result).isEqualTo(ChmodResult(changed = 4, failed = 0))
        assertThat(server.modeOf("/home/test/docs/b.txt")).isEqualTo(0b110_100_100)
        assertThat(server.modeOf("/home/test/docs/deep/c.txt")).isEqualTo(0b110_100_100)
        assertThat(server.modeOf("/home/test/docs/deep")).isEqualTo(0b111_101_101)
        assertThat(server.modeOf("/home/test/docs")).isEqualTo(0b111_101_101)
    }

    @Test
    fun aFolderIsChangedAfterItsContentsSoItCanStillBeListed() = runBlocking<Unit> {
        chmod.apply(item("/home/test/docs", SftpKind.FOLDER), SftpMode(0b110_000_000), recursive = true)

        assertThat(server.paths.last()).isEqualTo("/home/test/docs")
        assertThat(server.paths.indexOf("/home/test/docs/deep/c.txt")).isLessThan(server.paths.indexOf("/home/test/docs/deep").toLong())
        assertThat(server.paths.indexOf("/home/test/docs/deep")).isLessThan(server.paths.indexOf("/home/test/docs").toLong())
    }

    @Test
    fun linksAreNeverFollowedOrChanged() = runBlocking<Unit> {
        chmod.apply(item("/home/test/docs", SftpKind.FOLDER), SftpMode(0b110_100_100), recursive = true)

        assertThat(server.paths).doesNotContain("/home/test/docs/outside")
        assertThat(server.paths.none { it.startsWith("/etc") }).isTrue()
    }

    @Test
    fun aLinkItselfCannotBeChangedAndNothingIsSent() {
        val failure = failureOf { chmod.apply(item("/home/test/a-link", SftpKind.LINK), SftpMode(420), recursive = false) }

        assertThat(failure?.problem).isEqualTo(SftpProblem.UNSUPPORTED)
        assertThat(server.calls).isEmpty()
    }

    @Test
    fun specialBitsAreRefusedForARecursiveChange() {
        val refused = try {
            runBlocking { chmod.apply(item("/home/test/docs", SftpKind.FOLDER), SftpMode(SftpMode.SETUID or 493), recursive = true) }
            false
        } catch (e: IllegalArgumentException) {
            true
        }

        assertThat(refused).isTrue()
        assertThat(server.calls).isEmpty()
    }

    @Test
    fun specialBitsAreFineForOneFile() = runBlocking<Unit> {
        chmod.apply(item("/home/test/a.txt", SftpKind.FILE), SftpMode(SftpMode.SETUID or 493), recursive = false)

        assertThat(server.modeOf("/home/test/a.txt")).isEqualTo(SftpMode.SETUID or 493)
    }

    @Test
    fun recursionOnAFileJustChangesTheFile() = runBlocking<Unit> {
        val result = chmod.apply(item("/home/test/a.txt", SftpKind.FILE), SftpMode(420), recursive = true)

        assertThat(result.changed).isEqualTo(1)
        assertThat(server.paths).containsExactly("/home/test/a.txt")
    }

    @Test
    fun anItemThatCannotBeChangedIsCountedAndTheRestGoOn() = runBlocking<Unit> {
        server.failures["/home/test/docs/b.txt"] = SftpStatusCode.PERMISSION_DENIED

        val result = chmod.apply(item("/home/test/docs", SftpKind.FOLDER), SftpMode(0b110_100_100), recursive = true)

        assertThat(result.changed).isEqualTo(3)
        assertThat(result.failed).isEqualTo(1)
        assertThat(result.firstFailure?.problem).isEqualTo(SftpProblem.PERMISSION_DENIED)
        assertThat(server.modeOf("/home/test/docs/deep/c.txt")).isEqualTo(0b110_100_100)
        assertThat(server.modeOf("/home/test/docs")).isEqualTo(0b111_101_101)
    }

    @Test
    fun aLostConnectionStopsTheWholeChange() {
        server.failures["/home/test/docs/deep/c.txt"] = SftpStatusCode.CONNECTION_LOST

        val failure = failureOf { chmod.apply(item("/home/test/docs", SftpKind.FOLDER), SftpMode(420), recursive = true) }

        assertThat(failure?.problem).isEqualTo(SftpProblem.CONNECTION_LOST)
        assertThat(server.paths).doesNotContain("/home/test/docs")
    }

    @Test
    fun oneItemThatFailsIsReportedAsAnError() {
        server.failures["/home/test/a.txt"] = SftpStatusCode.PERMISSION_DENIED

        val failure = failureOf { chmod.apply(item("/home/test/a.txt", SftpKind.FILE), SftpMode(420), recursive = false) }

        assertThat(failure?.problem).isEqualTo(SftpProblem.PERMISSION_DENIED)
    }

    @Test
    fun progressCountsEveryChangedItem() = runBlocking<Unit> {
        val seen = mutableListOf<Int>()

        chmod.apply(item("/home/test/docs", SftpKind.FOLDER), SftpMode(420), recursive = true) { seen += it }

        assertThat(seen).containsExactly(1, 2, 3, 4)
    }

    @Test
    fun aTreeDeeperThanTheLimitIsRefusedNotWalkedForever() {
        var path = "/home/test/deep-tree"
        fake.folders += path
        repeat(SftpBrowser.MAX_DEPTH + 2) {
            path += "/d"
            fake.folders += path
        }

        val failure = failureOf { chmod.apply(item("/home/test/deep-tree", SftpKind.FOLDER), SftpMode(493), recursive = true) }

        assertThat(failure?.problem).isEqualTo(SftpProblem.TOO_DEEP)
    }
}
