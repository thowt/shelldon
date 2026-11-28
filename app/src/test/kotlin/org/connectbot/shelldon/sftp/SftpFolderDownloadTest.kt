/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpDirectoryEntry
import org.connectbot.sshlib.SftpFileHandle
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SftpStatusCode
import org.junit.Test
import java.io.File
import java.util.Random

class SftpFolderDownloadTest {
    /** The fake server that can refuse to open some folders or to read some files. */
    private class Refusing(val inner: FakeSftpClient) : SftpClient by inner {
        val folders = HashMap<String, SftpStatusCode>()
        val files = HashMap<String, SftpStatusCode>()
        private val names = HashMap<SftpFileHandle, String>()

        override suspend fun opendir(path: String): SftpResult<SftpFileHandle> {
            folders[path]?.let { return SftpResult.ServerError(it, it.name) }
            return inner.opendir(path)
        }

        // listdir is a default method of the interface, and `by inner` forwards it to the inner client
        // without going through the opendir above, so it is overridden here as well.
        override suspend fun listdir(path: String): SftpResult<List<SftpDirectoryEntry>> {
            folders[path]?.let { return SftpResult.ServerError(it, it.name) }
            return inner.listdir(path)
        }

        override suspend fun open(path: String, flags: Set<org.connectbot.sshlib.SftpOpenFlag>, attrs: org.connectbot.sshlib.SftpAttributes): SftpResult<SftpFileHandle> {
            files[path]?.let { return SftpResult.ServerError(it, it.name) }
            return inner.open(path, flags, attrs)
        }
    }

    private val fake = FakeSftpClient()
    private val server = Refusing(fake)
    private val random = Random(5)
    private val destination: File = newTempDir("shelldon-folder-dest")
    private val temp: File = newTempDir("shelldon-folder-temp")
    private val tree = FileTree()

    private fun bytes(count: Int) = ByteArray(count).also(random::nextBytes)

    init {
        fake.folders += listOf("/home", "/home/test", "/home/test/proj", "/home/test/proj/src", "/home/test/proj/src/util", "/home/test/proj/empty")
        fake.files["/home/test/proj/readme.txt"] = bytes(100)
        fake.files["/home/test/proj/data.bin"] = bytes(200_000)
        fake.files["/home/test/proj/src/main.kt"] = bytes(5000)
        fake.files["/home/test/proj/src/util/helpers.kt"] = bytes(70_000)
        fake.links["/home/test/proj/link-out"] = "/etc"
    }

    private suspend fun scan(maxEntries: Int = SftpTreeScan.MAX_ENTRIES) = SftpTreeScan.scan(server, "/home/test/proj", "proj", maxEntries)

    private suspend fun run(scan: RemoteTree, onProgress: (FolderProgress) -> Unit = {}) = FolderDownload(server, tree, temp).run("/home/test/proj", scan, destination, onProgress)

    private fun failureOf(block: suspend () -> Unit): SftpFailure? = try {
        runBlocking { block() }
        null
    } catch (e: SftpFailure) {
        e
    }

    private fun contentOf(vararg parts: String) = File(File(destination, "proj"), parts.joinToString("/")).readBytes()

    // --- Scanning ---

    @Test
    fun theScanFindsEverythingBelowTheFolderAndSkipsLinks() = runBlocking<Unit> {
        val result = scan()

        assertThat(result.rootName).isEqualTo("proj")
        assertThat(result.folders).containsExactly(listOf("empty"), listOf("src"), listOf("src", "util"))
        assertThat(result.files.map { it.parts.joinToString("/") }).containsExactly("data.bin", "readme.txt", "src/main.kt", "src/util/helpers.kt")
        assertThat(result.totalBytes).isEqualTo(275_100L)
        assertThat(result.linksSkipped).isEqualTo(1)
        assertThat(result.truncated).isFalse()
        assertThat(result.unreadable).isEqualTo(0)
    }

    @Test
    fun theScanStopsAtTheLimitAndSaysSo() = runBlocking<Unit> {
        val result = scan(maxEntries = 3)

        assertThat(result.truncated).isTrue()
        assertThat(result.files.size + result.folders.size).isEqualTo(3)
    }

    @Test
    fun aFolderThatCannotBeReadIsCountedAndTheRestIsStillListed() = runBlocking<Unit> {
        server.folders["/home/test/proj/src/util"] = SftpStatusCode.PERMISSION_DENIED

        val result = scan()

        assertThat(result.unreadable).isEqualTo(1)
        assertThat(result.files.map { it.parts.last() }).containsExactly("data.bin", "readme.txt", "main.kt")
        // "Left out" means left out: no empty stand-in folder on the phone either.
        assertThat(result.folders).doesNotContain(listOf("src", "util"))
        run(result)
        assertThat(File(destination, "proj/src/util").exists()).isFalse()
        assertThat(File(destination, "proj/src").isDirectory).isTrue()
    }

    @Test
    fun aTopFolderThatCannotBeReadIsAnError() {
        server.folders["/home/test/proj"] = SftpStatusCode.PERMISSION_DENIED

        assertThat(failureOf { scan() }?.problem).isEqualTo(SftpProblem.PERMISSION_DENIED)
    }

    @Test
    fun aTreeThatIsTooDeepIsRefused() {
        var path = "/home/test/proj/deep"
        fake.folders += path
        repeat(SftpBrowser.MAX_DEPTH + 2) {
            path += "/d"
            fake.folders += path
        }

        assertThat(failureOf { scan() }?.problem).isEqualTo(SftpProblem.TOO_DEEP)
    }

    @Test
    fun namesAHostileServerMakesUpAreNotListed() = runBlocking<Unit> {
        fake.extraListedNames = listOf("../escape", "a/b", "..", ".")

        val result = scan()

        assertThat(result.files.map { it.parts.last() }).containsExactly("data.bin", "readme.txt", "main.kt", "helpers.kt")
    }

    // --- Downloading ---

    @Test
    fun theFolderIsMirroredWithEveryFileIntact() = runBlocking<Unit> {
        val result = run(scan())

        assertThat(result.downloaded).isEqualTo(4)
        assertThat(result.failures).isEmpty()
        assertThat(contentOf("readme.txt")).isEqualTo(fake.files.getValue("/home/test/proj/readme.txt"))
        assertThat(contentOf("data.bin")).isEqualTo(fake.files.getValue("/home/test/proj/data.bin"))
        assertThat(contentOf("src", "util", "helpers.kt")).isEqualTo(fake.files.getValue("/home/test/proj/src/util/helpers.kt"))
        assertThat(File(File(destination, "proj"), "empty").isDirectory).isTrue()
        assertThat(File(File(destination, "proj"), "link-out").exists()).isFalse()
        assertThat(temp.listFiles()?.toList().orEmpty()).isEmpty()
    }

    @Test
    fun progressCountsBytesAndEndsAtTheTotal() = runBlocking<Unit> {
        val seen = mutableListOf<FolderProgress>()

        run(scan()) { seen += it }

        assertThat(seen.last().bytesDone).isEqualTo(275_100L)
        assertThat(seen.last().filesDone).isEqualTo(4)
        assertThat(seen.zipWithNext().all { (a, b) -> b.bytesDone >= a.bytesDone }).isTrue()
    }

    @Test
    fun runningItAgainSkipsWhatIsThereAndChangesNothing() = runBlocking<Unit> {
        run(scan())
        val before = File(destination, "proj").walkTopDown().filter { it.isFile }.associate { it.path to it.lastModified() }
        Thread.sleep(20)

        val again = run(scan())

        assertThat(again.downloaded).isEqualTo(0)
        assertThat(again.alreadyThere).isEqualTo(4)
        assertThat(File(destination, "proj").walkTopDown().filter { it.isFile }.associate { it.path to it.lastModified() }).isEqualTo(before)
        assertThat(File(destination, "proj (1)").exists()).isFalse()
    }

    @Test
    fun aFileOfTheSameNameAndAnotherSizeIsLeftAloneAndReported() = runBlocking<Unit> {
        File(destination, "proj").mkdirs()
        File(destination, "proj/readme.txt").writeText("my own notes")

        val result = run(scan())

        assertThat(result.keptDifferent).isEqualTo(1)
        assertThat(result.downloaded).isEqualTo(3)
        assertThat(File(destination, "proj/readme.txt").readText()).isEqualTo("my own notes")
    }

    @Test
    fun aFileInTheWayOfTheTopFolderMakesANewFolder() = runBlocking<Unit> {
        File(destination, "proj").writeText("I am a file")

        val result = run(scan())

        assertThat(result.downloaded).isEqualTo(4)
        assertThat(File(destination, "proj").readText()).isEqualTo("I am a file")
        assertThat(File(destination, "proj (1)/readme.txt").exists()).isTrue()
    }

    @Test
    fun namesThatCannotBeUsedOrClashAreChangedNotMerged() = runBlocking<Unit> {
        fake.files["/home/test/proj/a\\b"] = "one".toByteArray()
        fake.files["/home/test/proj/a_b"] = "two".toByteArray()
        fake.files["/home/test/proj/Notes"] = "three".toByteArray()
        fake.files["/home/test/proj/NOTES"] = "four".toByteArray()

        val result = run(scan())

        val names = File(destination, "proj").list().orEmpty().toSet()
        assertThat(names.filter { it.startsWith("a_b") }).hasSize(2)
        assertThat(names.filter { it.lowercase().startsWith("notes") }).hasSize(2)
        assertThat(result.renamed >= 2).describedAs("renamed %s", result.renamed).isTrue()
        assertThat(result.failures).isEmpty()
        val allContent = File(destination, "proj").listFiles().orEmpty().filter { it.isFile }.map { it.readText() }.toSet()
        assertThat(allContent.containsAll(listOf("one", "two", "three", "four"))).isTrue()
    }

    @Test
    fun aFileThatChangedSizeSinceTheScanIsReportedAndNotKeptCutOff() = runBlocking<Unit> {
        val scanned = scan()
        fake.files["/home/test/proj/readme.txt"] = bytes(150)

        val result = run(scanned)

        assertThat(result.downloaded).isEqualTo(3)
        assertThat(result.failures.map { it.path to it.problem }).containsExactly("readme.txt" to SftpProblem.OTHER)
        assertThat(result.failures.single().detail).contains("changed")
        assertThat(File(destination, "proj/readme.txt").exists()).isFalse()
        assertThat(temp.listFiles()?.toList().orEmpty()).isEmpty()

        // A new scan sees the new size, and the file arrives whole.
        val again = run(scan())
        assertThat(again.failures).isEmpty()
        assertThat(File(destination, "proj/readme.txt").length()).isEqualTo(150L)
    }

    @Test
    fun oneFileThatFailsDoesNotStopTheRest() = runBlocking<Unit> {
        server.files["/home/test/proj/data.bin"] = SftpStatusCode.PERMISSION_DENIED

        val result = run(scan())

        assertThat(result.downloaded).isEqualTo(3)
        assertThat(result.failures).hasSize(1)
        assertThat(result.failures.single().problem).isEqualTo(SftpProblem.PERMISSION_DENIED)
        assertThat(result.failures.single().path).isEqualTo("data.bin")
        assertThat(File(destination, "proj/data.bin").exists()).isFalse()
        assertThat(temp.listFiles()?.toList().orEmpty()).isEmpty()
    }

    @Test
    fun aLostConnectionStopsEverythingAndLeavesNothingHalfWritten() {
        server.files["/home/test/proj/src/main.kt"] = SftpStatusCode.CONNECTION_LOST

        val failure = failureOf { run(scan()) }

        assertThat(failure?.problem).isEqualTo(SftpProblem.CONNECTION_LOST)
        assertThat(temp.listFiles()?.toList().orEmpty()).isEmpty()
        assertThat(
            File(destination, "proj").walkTopDown().filter { it.isFile }.all { file ->
                file.readBytes().contentEquals(fake.files.getValue("/home/test/proj/" + file.relativeTo(File(destination, "proj")).path))
            },
        ).isTrue()
    }

    @Test
    fun cancellingLeavesOnlyCompleteFilesAndNoTemporaryOnes() {
        val plan = runBlocking { scan() }
        var calls = 0

        val cancelled = try {
            runBlocking { run(plan) { if (++calls > 6) throw CancellationException("stop") } }
            false
        } catch (e: CancellationException) {
            true
        }

        assertThat(cancelled).isTrue()
        assertThat(temp.listFiles()?.toList().orEmpty()).isEmpty()
        val root = File(destination, "proj")
        for (file in root.walkTopDown().filter { it.isFile }) {
            assertThat(file.readBytes()).describedAs(file.path).isEqualTo(fake.files.getValue("/home/test/proj/" + file.relativeTo(root).path))
        }
    }
}
