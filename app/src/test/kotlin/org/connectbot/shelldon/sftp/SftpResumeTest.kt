/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpFileHandle
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SftpStatusCode
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

class SftpResumeTest {
    /** The unfinished copy of a download, in memory. */
    private class MemoryPartial(var data: ByteArray = ByteArray(0)) : PartialFile {
        override val length: Long get() = data.size.toLong()

        override suspend fun read(offset: Long, length: Int): ByteArray = data.copyOfRange(offset.toInt(), minOf(offset.toInt() + length, data.size))

        override fun openAppend(keep: Long): OutputStream {
            data = data.copyOf(keep.toInt())
            return object : OutputStream() {
                override fun write(b: Int) {
                    data += b.toByte()
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    data += b.copyOfRange(off, off + len)
                }
            }
        }
    }

    private class MemorySource(val data: ByteArray) : ResumableSource {
        override val length: Long get() = data.size.toLong()

        override suspend fun read(offset: Long, length: Int): ByteArray = data.copyOfRange(offset.toInt(), minOf(offset.toInt() + length, data.size))

        override fun openAt(offset: Long): InputStream = ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
    }

    /** The fake server with hooks: do something after the n-th read, or lose the link after n writes. */
    private class Hooked(val inner: FakeSftpClient) : SftpClient by inner {
        var afterRead: Pair<Int, () -> Unit>? = null
        var failWritesAfter: Int? = null
        private val reads = AtomicInteger()
        private val writes = AtomicInteger()

        override suspend fun read(handle: SftpFileHandle, offset: Long, length: Int): SftpResult<ByteArray?> {
            val result = inner.read(handle, offset, length)
            afterRead?.let { (count, action) -> if (reads.incrementAndGet() == count) action() }
            return result
        }

        override suspend fun write(handle: SftpFileHandle, offset: Long, data: ByteArray): SftpResult<Unit> {
            val limit = failWritesAfter
            if (limit != null && writes.incrementAndGet() > limit) {
                // Give the writes that were sent earlier time to land, as a server working through its queue would.
                delay(30)
                return SftpResult.ServerError(SftpStatusCode.CONNECTION_LOST, "link lost")
            }
            return inner.write(handle, offset, data)
        }
    }

    private val fake = FakeSftpClient().apply { folders += listOf("/up") }
    private val server = Hooked(fake)
    private val random = Random(11)

    private fun bytes(count: Int) = ByteArray(count).also(random::nextBytes)

    private fun versionOf(path: String) = RemoteVersion(fake.files.getValue(path).size.toLong(), 1_700_000_000L)

    private fun failureOf(block: suspend () -> Unit): SftpFailure? = try {
        runBlocking { block() }
        null
    } catch (e: SftpFailure) {
        e
    }

    // --- Which places are compared ---

    @Test
    fun aSmallPartIsComparedWholeWithoutGapsOrOverlap() {
        val ranges = SftpResume.ranges(100_000)

        assertThat(ranges.first().first).isEqualTo(0L)
        assertThat(ranges.sumOf { it.second.toLong() }).isEqualTo(100_000L)
        assertThat(ranges.zipWithNext().all { (a, b) -> a.first + a.second == b.first }).isTrue()
    }

    @Test
    fun aBigPartIsComparedAtSpreadPlacesAndAtItsEnd() {
        val bytes = 50L * 1024 * 1024
        val ranges = SftpResume.ranges(bytes)

        assertThat(ranges).hasSize(SftpResume.SAMPLES + 1)
        assertThat(ranges.first().first).isEqualTo(0L)
        assertThat(ranges.all { it.first >= 0 && it.first + it.second <= bytes }).isTrue()
        assertThat(ranges.last()).isEqualTo((bytes - SftpResume.TAIL_BYTES) to SftpResume.TAIL_BYTES)
        // The last sample touches the end of the part, and the others are spread over it.
        assertThat(ranges[SftpResume.SAMPLES - 1].first + SftpResume.SAMPLE_BYTES).isEqualTo(bytes)
        assertThat(ranges.map { it.first }.distinct()).hasSize(SftpResume.SAMPLES + 1)
    }

    @Test
    fun nothingIsComparedForAnEmptyPart() {
        assertThat(SftpResume.ranges(0)).isEmpty()
    }

    // --- Names ---

    @Test
    fun theNameOfAnUnfinishedDownloadFollowsHostPathSizeAndTime() {
        val version = RemoteVersion(1000, 5)
        val name = SftpResume.partialName("host-1", "/a/b.bin", version)

        assertThat(name).isEqualTo(SftpResume.partialName("host-1", "/a/b.bin", version))
        assertThat(name.endsWith(".part")).isTrue()
        assertThat(name.removeSuffix(".part").all { it in '0'..'9' || it in 'a'..'f' }).isTrue()
        assertThat(setOf(name, SftpResume.partialName("host-2", "/a/b.bin", version), SftpResume.partialName("host-1", "/a/c.bin", version), SftpResume.partialName("host-1", "/a/b.bin", RemoteVersion(1001, 5)), SftpResume.partialName("host-1", "/a/b.bin", RemoteVersion(1000, 6)), SftpResume.partialName("host-1", "/a/b.bin", RemoteVersion(1000, null)))).hasSize(6)
    }

    @Test
    fun theHiddenNameOfAnUnfinishedUploadStaysWithinTheNameLimit() {
        val plain = SftpResume.uploadPartName("photo.jpg", 4_000_000, 7)
        assertThat(plain.startsWith(".photo.jpg.")).isTrue()
        assertThat(plain.endsWith(".shelldon-part")).isTrue()
        assertThat(plain).isEqualTo(SftpResume.uploadPartName("photo.jpg", 4_000_000, 7))
        assertThat(plain).isNotEqualTo(SftpResume.uploadPartName("photo.jpg", 4_000_001, 7))
        assertThat(plain).isNotEqualTo(SftpResume.uploadPartName("photo.jpg", 4_000_000, 8))

        for (name in listOf("x".repeat(255), "é".repeat(127), "日".repeat(85), "😀".repeat(63))) {
            val part = SftpResume.uploadPartName(name, 1, null)
            assertThat(part.toByteArray(Charsets.UTF_8).size <= SftpPaths.MAX_NAME_BYTES).describedAs("%s bytes", part.toByteArray(Charsets.UTF_8).size).isTrue()
            assertThat(SftpPaths.isValidNewName(part)).isTrue()
        }
    }

    // --- Download ---

    private suspend fun fetch(path: String, partial: MemoryPartial, progress: (Long) -> Unit = {}, started: (Long) -> Unit = {}): Long = SftpResume.download(server, path, versionOf(path), partial, onStart = started, onProgress = progress)

    @Test
    fun aFreshDownloadFillsThePartialAndKeepsNothing() = runBlocking<Unit> {
        val data = bytes(300_000)
        fake.files["/f"] = data
        val partial = MemoryPartial()

        assertThat(fetch("/f", partial)).isEqualTo(0L)
        assertThat(partial.data).isEqualTo(data)
    }

    @Test
    fun aDownloadContinuesAfterTheKnownStartAndOnlyFetchesTheRest() = runBlocking<Unit> {
        val data = bytes(2_000_000)
        fake.files["/f"] = data
        val kept = 1_200_000
        val partial = MemoryPartial(data.copyOf(kept))
        val seen = mutableListOf<Long>()
        var started = -1L

        assertThat(fetch("/f", partial, progress = { seen += it }, started = { started = it })).isEqualTo(kept.toLong())

        assertThat(partial.data).isEqualTo(data)
        assertThat(started).isEqualTo(kept.toLong())
        assertThat(seen.first() > kept).isTrue()
        assertThat(seen.last()).isEqualTo(data.size.toLong())
        // A part this small is compared whole (one read per 32 KiB of it), then only the missing 800 000 bytes are fetched.
        val compared = (kept + SftpResume.TAIL_BYTES - 1) / SftpResume.TAIL_BYTES
        val missing = (data.size - kept + SftpTransfers.CHUNK_SIZE - 1) / SftpTransfers.CHUNK_SIZE
        assertThat(fake.readRequests.get()).describedAs("reads").isEqualTo(compared + missing)
    }

    @Test
    fun aBigPartIsCheckedByAHandfulOfReadsNotByFetchingItAgain() = runBlocking<Unit> {
        val data = bytes(8_000_000)
        fake.files["/f"] = data
        val kept = 6_000_000
        val partial = MemoryPartial(data.copyOf(kept))

        assertThat(fetch("/f", partial)).isEqualTo(kept.toLong())

        assertThat(partial.data).isEqualTo(data)
        val missing = (data.size - kept + SftpTransfers.CHUNK_SIZE - 1) / SftpTransfers.CHUNK_SIZE
        assertThat(fake.readRequests.get()).describedAs("reads").isEqualTo(SftpResume.SAMPLES + 1 + missing)
        assertThat(fake.readRequests.get() < data.size / SftpTransfers.CHUNK_SIZE).describedAs("reads").isTrue()
    }

    @Test
    fun aPartThatIsNotTheStartOfTheFileIsThrownAway() = runBlocking<Unit> {
        val data = bytes(400_000)
        fake.files["/f"] = data
        val wrong = data.copyOf(250_000).also { it[100_000] = (it[100_000] + 1).toByte() }
        val partial = MemoryPartial(wrong)

        assertThat(fetch("/f", partial)).isEqualTo(0L)
        assertThat(partial.data).isEqualTo(data)
    }

    @Test
    fun aBigPartWhoseEndIsDamagedIsThrownAway() = runBlocking<Unit> {
        // The usual damage after a crash: the last bytes written are not the right ones.
        val data = bytes(8_000_000)
        fake.files["/f"] = data
        val damaged = data.copyOf(6_000_000).also { it[5_999_999] = (it[5_999_999] + 1).toByte() }
        val partial = MemoryPartial(damaged)

        assertThat(fetch("/f", partial)).isEqualTo(0L)
        assertThat(partial.data).isEqualTo(data)
    }

    @Test
    fun aBigPartWhoseStartIsWrongIsThrownAway() = runBlocking<Unit> {
        val data = bytes(8_000_000)
        fake.files["/f"] = data
        val wrong = data.copyOf(6_000_000).also { it[10] = (it[10] + 1).toByte() }
        val partial = MemoryPartial(wrong)

        assertThat(fetch("/f", partial)).isEqualTo(0L)
        assertThat(partial.data).isEqualTo(data)
    }

    @Test
    fun aPartLongerThanTheFileIsThrownAway() = runBlocking<Unit> {
        val data = bytes(100_000)
        fake.files["/f"] = data
        val partial = MemoryPartial(data + bytes(5000))

        assertThat(fetch("/f", partial)).isEqualTo(0L)
        assertThat(partial.data).isEqualTo(data)
    }

    @Test
    fun aPartThatIsAlreadyTheWholeFileIsKeptAndNothingMoreIsFetched() = runBlocking<Unit> {
        val data = bytes(100_000)
        fake.files["/f"] = data
        val partial = MemoryPartial(data.copyOf())

        assertThat(fetch("/f", partial)).isEqualTo(100_000L)
        assertThat(partial.data).isEqualTo(data)
    }

    @Test
    fun aDownloadThatDiedHalfWayIsContinuedByTheNextTry() = runBlocking<Unit> {
        val data = bytes(SftpTransfers.CHUNK_SIZE * 40)
        fake.files["/f"] = data
        val partial = MemoryPartial()
        fake.cutOffAfterReads = 12

        val failure = failureOf { fetch("/f", partial) }
        assertThat(failure?.problem).isEqualTo(SftpProblem.TRUNCATED)
        val firstTry = partial.data.size
        assertThat(firstTry in 1 until data.size).describedAs("kept %s", firstTry).isTrue()
        assertThat(partial.data).isEqualTo(data.copyOf(firstTry))

        fake.cutOffAfterReads = null
        assertThat(fetch("/f", partial)).isEqualTo(firstTry.toLong())
        assertThat(partial.data).isEqualTo(data)
    }

    @Test
    fun aFileThatChangedSinceTheListIsNotFetched() {
        fake.files["/f"] = bytes(50_000)
        val stale = RemoteVersion(fake.files.getValue("/f").size.toLong(), 1_600_000_000L)

        val failure = failureOf { SftpResume.download(server, "/f", stale, MemoryPartial()) }

        assertThat(failure?.problem).isEqualTo(SftpProblem.CHANGED)
    }

    @Test
    fun aFileThatGrowsWhileItIsCopiedIsReported() {
        val data = bytes(SftpTransfers.CHUNK_SIZE * 20)
        fake.files["/f"] = data
        server.afterRead = 3 to { fake.files["/f"] = data + byteArrayOf(1) }

        val failure = failureOf { fetch("/f", MemoryPartial()) }

        assertThat(failure?.problem).isEqualTo(SftpProblem.CHANGED)
    }

    @Test
    fun aFileOfZeroSizeIsNotContinued() {
        fake.files["/f"] = ByteArray(0)
        val refused = try {
            runBlocking { SftpResume.download(server, "/f", RemoteVersion(0, 1_700_000_000L), MemoryPartial()) }
            false
        } catch (e: IllegalArgumentException) {
            true
        }

        assertThat(refused).isTrue()
    }

    // --- Upload ---

    private suspend fun send(data: ByteArray, finalPath: String = "/up/f.bin", started: (Long) -> Unit = {}, progress: (Long) -> Unit = {}): Long = SftpResume.upload(server, MemorySource(data), finalPath, "/up/.f.bin.part", onStart = started, onProgress = progress)

    @Test
    fun aFreshUploadGoesThroughTheHiddenFileAndEndsWithTheRealName() = runBlocking<Unit> {
        val data = bytes(500_000)
        val seen = mutableListOf<Long>()

        assertThat(send(data, progress = { seen += it })).isEqualTo(0L)

        assertThat(fake.files.getValue("/up/f.bin")).isEqualTo(data)
        assertThat(fake.files.containsKey("/up/.f.bin.part")).isFalse()
        assertThat(seen.last()).isEqualTo(data.size.toLong())
    }

    @Test
    fun anUploadContinuesAnUnfinishedOneAndOnlySendsTheRest() = runBlocking<Unit> {
        val data = bytes(2_000_000)
        val kept = 1_300_000
        fake.files["/up/.f.bin.part"] = data.copyOf(kept)
        var started = -1L

        assertThat(send(data, started = { started = it })).isEqualTo(kept.toLong())

        assertThat(fake.files.getValue("/up/f.bin")).isEqualTo(data)
        assertThat(started).isEqualTo(kept.toLong())
        val chunks = (data.size - kept + SftpTransfers.CHUNK_SIZE - 1) / SftpTransfers.CHUNK_SIZE
        assertThat(fake.writeRequests.get()).isEqualTo(chunks)
    }

    @Test
    fun anUnfinishedUploadThatDoesNotMatchTheFileIsStartedAgain() = runBlocking<Unit> {
        val data = bytes(1_000_000)
        val other = data.copyOf(600_000).also { it[300_000] = (it[300_000] + 1).toByte() }
        fake.files["/up/.f.bin.part"] = other

        assertThat(send(data)).isEqualTo(0L)

        assertThat(fake.files.getValue("/up/f.bin")).isEqualTo(data)
    }

    @Test
    fun anUnfinishedUploadLongerThanTheFileIsStartedAgain() = runBlocking<Unit> {
        val data = bytes(100_000)
        fake.files["/up/.f.bin.part"] = data + bytes(1000)

        assertThat(send(data)).isEqualTo(0L)

        assertThat(fake.files.getValue("/up/f.bin")).isEqualTo(data)
    }

    @Test
    fun anUnfinishedUploadThatHasEverythingJustGetsItsName() = runBlocking<Unit> {
        val data = bytes(100_000)
        fake.files["/up/.f.bin.part"] = data.copyOf()

        assertThat(send(data)).isEqualTo(100_000L)

        assertThat(fake.files.getValue("/up/f.bin")).isEqualTo(data)
        assertThat(fake.writeRequests.get()).isEqualTo(0)
    }

    @Test
    fun anUploadThatLostTheLinkLeavesTheHiddenFileAndTheNextTryContinues() = runBlocking<Unit> {
        val data = bytes(SftpTransfers.CHUNK_SIZE * 40)
        server.failWritesAfter = 15

        val failure = failureOf { send(data) }
        assertThat(failure?.problem).isEqualTo(SftpProblem.CONNECTION_LOST)
        assertThat(fake.files.containsKey("/up/f.bin")).isFalse()
        val firstTry = fake.files.getValue("/up/.f.bin.part").size
        assertThat(firstTry in 1 until data.size).describedAs("kept %s", firstTry).isTrue()
        assertThat(fake.files.getValue("/up/.f.bin.part")).isEqualTo(data.copyOf(firstTry))

        server.failWritesAfter = null
        assertThat(send(data)).isEqualTo(firstTry.toLong())
        assertThat(fake.files.getValue("/up/f.bin")).isEqualTo(data)
        assertThat(fake.files.containsKey("/up/.f.bin.part")).isFalse()
    }

    @Test
    fun anExistingFileIsNeverReplacedAndNothingIsSent() {
        fake.files["/up/f.bin"] = "original".toByteArray()

        val failure = failureOf { send(bytes(10_000)) }

        assertThat(failure?.problem).isEqualTo(SftpProblem.ALREADY_EXISTS)
        assertThat(String(fake.files.getValue("/up/f.bin"))).isEqualTo("original")
        assertThat(fake.files.containsKey("/up/.f.bin.part")).isFalse()
        assertThat(fake.writeRequests.get()).isEqualTo(0)
    }

    @Test
    fun aFileThatTurnsUpDuringTheUploadIsNotReplacedEither() {
        val data = bytes(SftpTransfers.CHUNK_SIZE * 10)
        server.afterRead = 1 to {}
        // The competing file appears while the part is being written: the first write of this try is the trigger.
        val competing = Hooked(fake).also { it.failWritesAfter = null }
        val source = object : ResumableSource {
            override val length: Long = data.size.toLong()

            override suspend fun read(offset: Long, length: Int): ByteArray = data.copyOfRange(offset.toInt(), offset.toInt() + length)

            override fun openAt(offset: Long): InputStream {
                fake.files["/up/f.bin"] = "someone else".toByteArray()
                return ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
            }
        }

        val failure = failureOf { SftpResume.upload(competing, source, "/up/f.bin", "/up/.f.bin.part") }

        assertThat(failure?.problem).isEqualTo(SftpProblem.ALREADY_EXISTS)
        assertThat(String(fake.files.getValue("/up/f.bin"))).isEqualTo("someone else")
    }
}
