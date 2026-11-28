/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

private fun FileChannel.readFully(offset: Long, length: Int): ByteArray {
    val buffer = ByteBuffer.allocate(length)
    var position = offset
    while (buffer.hasRemaining()) {
        val count = read(buffer, position)
        if (count < 0) break
        position += count
    }
    return buffer.array().copyOf(buffer.position())
}

/** The unfinished copy of a download, as a file in this app's own storage. */
class FilePartial(val file: File) : PartialFile {
    override val length: Long get() = if (file.isFile) file.length() else 0L

    override suspend fun read(offset: Long, length: Int): ByteArray = FileInputStream(file).use { it.channel.readFully(offset, length) }

    override fun openAppend(keep: Long): OutputStream {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(keep.coerceAtLeast(0)) }
        return FileOutputStream(file, true)
    }
}

/**
 * A local file that is uploaded, read at any offset. [open] must give a new stream on the same file
 * each time (so one can be read for checking while another is being sent).
 */
class ChannelSource private constructor(
    override val length: Long,
    private val open: () -> FileInputStream,
    private val shared: FileInputStream,
) : ResumableSource,
    AutoCloseable {
    override suspend fun read(offset: Long, length: Int): ByteArray = shared.channel.readFully(offset, length)

    override fun openAt(offset: Long): InputStream {
        val stream = open()
        try {
            stream.channel.position(offset)
        } catch (e: IOException) {
            stream.close()
            throw e
        }
        return stream
    }

    override fun close() = shared.close()

    companion object {
        /**
         * Opens the file and finds out whether it can be read from any point and how long it really is.
         *
         * @return null for something that is not a seekable file with content (a pipe, a stream from an
         *   app that makes its data on the fly), which is then sent from the start without resuming
         */
        fun openOrNull(open: () -> FileInputStream): ChannelSource? {
            val stream = try {
                open()
            } catch (e: IOException) {
                return null
            } catch (e: SecurityException) {
                return null
            }
            val size = try {
                stream.channel.size().also { stream.channel.position(0) }
            } catch (e: IOException) {
                stream.close()
                return null
            }
            if (size <= 0) {
                stream.close()
                return null
            }
            return ChannelSource(size, open, stream)
        }
    }
}

/** Housekeeping for the unfinished downloads in this app's cache. */
object SftpPartials {
    const val SUFFIX = ".part"
    const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** Deletes unfinished downloads that were last written to more than [maxAgeMillis] ago. Returns how many. */
    fun prune(directory: File, now: Long, maxAgeMillis: Long = MAX_AGE_MILLIS): Int {
        val old = directory.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) && now - file.lastModified() > maxAgeMillis }
            ?: return 0
        return old.count { it.delete() }
    }
}
