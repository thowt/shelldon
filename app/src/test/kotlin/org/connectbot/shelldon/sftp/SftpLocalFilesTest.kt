/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.Random

class SftpLocalFilesTest {
    private val dir: File = Files.createTempDirectory("shelldon-sftp-test").toFile().also { it.deleteOnExit() }

    private fun bytes(count: Int) = ByteArray(count).also(Random(3)::nextBytes)

    private fun fileOf(name: String, data: ByteArray) = File(dir, name).also { it.writeBytes(data) }

    @Test
    fun aPartialThatDoesNotExistIsEmpty() {
        assertThat(FilePartial(File(dir, "none.part")).length).isEqualTo(0L)
    }

    @Test
    fun aPartialIsAppendedToAfterBeingCutBack() = runBlocking<Unit> {
        val data = bytes(10_000)
        val partial = FilePartial(fileOf("a.part", data))

        partial.openAppend(keep = 4000).use { it.write(data, 4000, 6000) }

        assertThat(partial.length).isEqualTo(10_000L)
        assertThat(partial.file.readBytes()).isEqualTo(data)
        assertThat(partial.read(5000, 100)).isEqualTo(data.copyOfRange(5000, 5100))
    }

    @Test
    fun aPartialIsCreatedAndEmptiedWhenNothingIsKept() = runBlocking<Unit> {
        val partial = FilePartial(File(File(dir, "sub"), "b.part"))
        partial.openAppend(keep = 0).use { it.write(byteArrayOf(1, 2, 3)) }
        assertThat(partial.length).isEqualTo(3L)

        partial.openAppend(keep = 0).use { }

        assertThat(partial.length).isEqualTo(0L)
    }

    @Test
    fun aSourceIsReadAtAnyOffsetAndSentFromAnyOffset() = runBlocking<Unit> {
        val data = bytes(100_000)
        val file = fileOf("up.bin", data)
        val source = checkNotNull(ChannelSource.openOrNull { FileInputStream(file) })

        source.use {
            assertThat(it.length).isEqualTo(100_000L)
            assertThat(it.read(60_000, 500)).isEqualTo(data.copyOfRange(60_000, 60_500))
            assertThat(it.read(99_900, 500)).isEqualTo(data.copyOfRange(99_900, 100_000))
            assertThat(it.openAt(70_000).use { stream -> stream.readBytes() }).isEqualTo(data.copyOfRange(70_000, 100_000))
            assertThat(it.openAt(0).use { stream -> stream.readBytes() }).isEqualTo(data)
        }
    }

    @Test
    fun anEmptyOrMissingFileIsNotASource() {
        val empty = fileOf("empty.bin", ByteArray(0))
        assertThat(ChannelSource.openOrNull { FileInputStream(empty) }).isNull()
        assertThat(ChannelSource.openOrNull { FileInputStream(File(dir, "missing.bin")) }).isNull()
    }

    @Test
    fun onlyOldUnfinishedDownloadsArePruned() {
        val folder = File(dir, "cache").also { it.mkdirs() }
        val now = 1_000_000_000_000L
        val week = SftpPartials.MAX_AGE_MILLIS
        File(folder, "old.part").also { it.writeBytes(byteArrayOf(1)) }.setLastModified(now - week - 1000)
        File(folder, "new.part").also { it.writeBytes(byteArrayOf(1)) }.setLastModified(now - week + 60_000)
        File(folder, "old.txt").also { it.writeBytes(byteArrayOf(1)) }.setLastModified(now - 2 * week)

        assertThat(SftpPartials.prune(folder, now)).isEqualTo(1)

        assertThat(File(folder, "old.part").exists()).isFalse()
        assertThat(File(folder, "new.part").exists()).isTrue()
        assertThat(File(folder, "old.txt").exists()).isTrue()
        assertThat(SftpPartials.prune(File(dir, "no-such-folder"), now)).isEqualTo(0)
    }
}
