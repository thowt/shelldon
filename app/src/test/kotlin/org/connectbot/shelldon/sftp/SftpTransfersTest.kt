/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Random

class SftpTransfersTest {
    private val fake = FakeSftpClient()
    private val random = Random(42)

    private fun bytes(count: Int) = ByteArray(count).also(random::nextBytes)

    private fun failureOf(block: suspend () -> Unit): SftpFailure? = try {
        runBlocking { block() }
        null
    } catch (e: SftpFailure) {
        e
    }

    private suspend fun download(path: String, size: Long?, progress: (Long) -> Unit = {}): ByteArray {
        val out = ByteArrayOutputStream()
        SftpTransfers.download(fake, path, out, size, progress)
        return out.toByteArray()
    }

    @Test
    fun downloadsAFileLargerThanTheWindowInOrder() = runBlocking<Unit> {
        val data = bytes(SftpTransfers.CHUNK_SIZE * 20 + 123)
        fake.files["/f"] = data
        assertThat(download("/f", data.size.toLong())).isEqualTo(data)
    }

    @Test
    fun downloadKeepsSeveralRequestsInFlight() = runBlocking<Unit> {
        val data = bytes(SftpTransfers.CHUNK_SIZE * 30)
        fake.files["/f"] = data
        download("/f", data.size.toLong())
        assertThat(fake.inFlightPeak.get() > 1).describedAs("peak in flight %s", fake.inFlightPeak.get()).isTrue()
        assertThat(fake.inFlightPeak.get() <= SftpTransfers.WINDOW).describedAs("peak in flight %s", fake.inFlightPeak.get()).isTrue()
    }

    @Test
    fun shortReadsAreFilledInNotSkipped() = runBlocking<Unit> {
        val data = bytes(100_000)
        fake.files["/f"] = data
        fake.readCap = 5000
        assertThat(download("/f", data.size.toLong())).isEqualTo(data)
    }

    @Test
    fun aServerThatCapsReadsLowerThanTheChunkIsAdaptedTo() = runBlocking<Unit> {
        val data = bytes(400_000)
        fake.files["/f"] = data
        fake.readCap = 16 * 1024
        assertThat(download("/f", data.size.toLong())).isEqualTo(data)
    }

    @Test
    fun anUnknownSizeReadsUntilTheEnd() = runBlocking<Unit> {
        val data = bytes(SftpTransfers.CHUNK_SIZE * 3 + 7)
        fake.files["/f"] = data
        assertThat(download("/f", null)).isEqualTo(data)
    }

    @Test
    fun aReportedSizeOfZeroIsNotTrusted() = runBlocking<Unit> {
        val data = bytes(1000)
        fake.files["/proc-like"] = data
        assertThat(download("/proc-like", 0L)).isEqualTo(data)
    }

    @Test
    fun anEmptyFileGivesAnEmptyFile() = runBlocking<Unit> {
        fake.files["/empty"] = ByteArray(0)
        assertThat(download("/empty", 0L)).isEqualTo(ByteArray(0))
    }

    @Test
    fun aFileThatEndsEarlyIsReportedNotSavedShort() {
        val data = bytes(SftpTransfers.CHUNK_SIZE * 12)
        fake.files["/f"] = data
        fake.cutOffAfterReads = 5
        val failure = failureOf { download("/f", data.size.toLong()) }
        assertThat(failure).isNotNull()
        assertThat(failure!!.problem).isEqualTo(SftpProblem.TRUNCATED)
    }

    @Test
    fun progressCountsBytesWrittenAndEndsAtTheSize() = runBlocking<Unit> {
        val data = bytes(SftpTransfers.CHUNK_SIZE * 5 + 1)
        fake.files["/f"] = data
        val seen = mutableListOf<Long>()
        download("/f", data.size.toLong()) { seen += it }
        assertThat(seen.last()).isEqualTo(data.size.toLong())
        assertThat(seen.zipWithNext().all { (a, b) -> b > a }).isTrue()
    }

    @Test
    fun downloadingAMissingFileIsNotFound() {
        val failure = failureOf { download("/missing", 10) }
        assertThat(failure!!.problem).isEqualTo(SftpProblem.NOT_FOUND)
    }

    @Test
    fun uploadsInOrderWithSeveralWritesInFlight() = runBlocking<Unit> {
        val data = bytes(SftpTransfers.CHUNK_SIZE * 25 + 99)
        val sent = SftpTransfers.upload(fake, ByteArrayInputStream(data), "/up", overwrite = false)
        assertThat(sent).isEqualTo(data.size.toLong())
        assertThat(fake.files.getValue("/up")).isEqualTo(data)
        assertThat(fake.inFlightPeak.get() > 1).describedAs("peak in flight %s", fake.inFlightPeak.get()).isTrue()
        assertThat(fake.inFlightPeak.get() <= SftpTransfers.WINDOW).isTrue()
    }

    @Test
    fun uploadingAnEmptyFileCreatesIt() = runBlocking<Unit> {
        assertThat(SftpTransfers.upload(fake, ByteArrayInputStream(ByteArray(0)), "/empty", overwrite = false)).isEqualTo(0L)
        assertThat(fake.files.getValue("/empty")).isEqualTo(ByteArray(0))
    }

    @Test
    fun anExistingFileIsNeverOverwrittenUnlessAskedTo() = runBlocking<Unit> {
        fake.files["/up"] = "original".toByteArray()
        val failure = failureOf { SftpTransfers.upload(fake, ByteArrayInputStream(bytes(10)), "/up", overwrite = false) }
        assertThat(failure!!.problem).isEqualTo(SftpProblem.ALREADY_EXISTS)
        assertThat(String(fake.files.getValue("/up"))).isEqualTo("original")

        val replacement = bytes(10)
        SftpTransfers.upload(fake, ByteArrayInputStream(replacement), "/up", overwrite = true)
        assertThat(fake.files.getValue("/up")).isEqualTo(replacement)
    }

    @Test
    fun aFailedUploadOfANewFileLeavesNothingBehind() {
        val broken = object : InputStream() {
            private var served = 0
            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= SftpTransfers.CHUNK_SIZE * 3) throw java.io.IOException("the phone's storage went away")
                val n = minOf(len, 1000)
                served += n
                return n
            }
        }
        val thrown = try {
            runBlocking { SftpTransfers.upload(fake, broken, "/partial", overwrite = false) }
            null
        } catch (e: java.io.IOException) {
            e
        }
        assertThat(thrown).isNotNull()
        assertThat("/partial" in fake.files).isFalse()
    }

    @Test
    fun uploadProgressEndsAtTheSize() = runBlocking<Unit> {
        val data = bytes(SftpTransfers.CHUNK_SIZE * 11)
        var last = 0L
        SftpTransfers.upload(fake, ByteArrayInputStream(data), "/up", overwrite = false) { last = it }
        assertThat(last).isEqualTo(data.size.toLong())
    }

    @Test
    fun aRoundTripThroughUploadAndDownloadIsIdentical() = runBlocking<Unit> {
        val data = bytes(3 * 1024 * 1024 + 17)
        SftpTransfers.upload(fake, ByteArrayInputStream(data), "/big", overwrite = false)
        assertThat(download("/big", data.size.toLong())).isEqualTo(data)
    }
}
