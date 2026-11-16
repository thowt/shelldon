/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpOpenFlag
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SftpStatusCode
import java.io.InputStream
import java.io.OutputStream

/**
 * File transfers over one open SFTP session.
 *
 * Requests are pipelined: up to [WINDOW] reads or writes are in flight at once, so a slow link is
 * not limited to one round trip per 32 KiB. Data is still written to the local file in order, and a
 * short read (servers are allowed to answer with less than asked) is filled in, never skipped.
 *
 * The streams are plain blocking java.io streams. Call these on an I/O dispatcher.
 */
object SftpTransfers {
    const val CHUNK_SIZE = 32 * 1024
    const val WINDOW = 8
    private const val SMALLEST_CHUNK = 1024

    private class Slot(val offset: Long, val length: Int, val result: Deferred<ByteArray?>)

    /**
     * Copies [remotePath] into [out].
     *
     * @param expectedSize the size the server reported before the transfer, if it did. With it, a file that
     *   ends early is reported as [SftpProblem.TRUNCATED] instead of being saved incomplete.
     * @param onProgress total bytes written so far
     * @return the number of bytes written
     */
    suspend fun download(
        sftp: SftpClient,
        remotePath: String,
        out: OutputStream,
        expectedSize: Long?,
        onProgress: (Long) -> Unit = {},
    ): Long {
        // A reported size of zero is not trusted: some servers say so for files that do have content.
        val expected = expectedSize?.takeIf { it > 0 }
        val handle = sftp.open(remotePath, setOf(SftpOpenFlag.READ)).orFail()
        try {
            return coroutineScope {
                val window = ArrayDeque<Slot>()
                var nextOffset = 0L
                var written = 0L
                var chunk = CHUNK_SIZE
                var reachedEnd = false
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        while (!reachedEnd && window.size < WINDOW && (expected == null || nextOffset < expected)) {
                            val length = if (expected == null) chunk else minOf(chunk.toLong(), expected - nextOffset).toInt()
                            val offset = nextOffset
                            window.addLast(Slot(offset, length, async { sftp.read(handle, offset, length).orFail() }))
                            nextOffset += length
                        }
                        val slot = window.removeFirstOrNull() ?: break
                        var piece = slot.result.await() ?: ByteArray(0)
                        if (piece.size < slot.length && piece.isNotEmpty()) {
                            // The server answered with less than asked. Remember how much it gives per
                            // request, and ask again for the rest of this range before moving on.
                            chunk = minOf(chunk, maxOf(piece.size, SMALLEST_CHUNK))
                            while (piece.size < slot.length) {
                                val more = sftp.read(handle, slot.offset + piece.size, slot.length - piece.size).orFail() ?: break
                                if (more.isEmpty()) throw SftpFailure(SftpProblem.OTHER, "The server returned an empty read")
                                piece += more
                            }
                        }
                        if (piece.size < slot.length) {
                            // Real end of file.
                            if (expected != null && slot.offset + piece.size < expected) throw SftpFailure(SftpProblem.TRUNCATED)
                            reachedEnd = true
                        }
                        out.write(piece)
                        written += piece.size
                        onProgress(written)
                        if (reachedEnd) break
                        if (expected != null && written >= expected) break
                    }
                } finally {
                    window.forEach { it.result.cancel() }
                }
                written
            }
        } finally {
            withContext(NonCancellable) { sftp.close(handle) }
        }
    }

    /**
     * Copies [input] to [remotePath].
     *
     * @param overwrite when false, an existing file is never touched and the result is
     *   [SftpProblem.ALREADY_EXISTS]. When true the existing file is replaced, and if the transfer fails
     *   half way it stays incomplete; the caller has to say so.
     * @param onProgress total bytes the server has acknowledged so far
     * @return the number of bytes sent
     */
    suspend fun upload(
        sftp: SftpClient,
        input: InputStream,
        remotePath: String,
        overwrite: Boolean,
        onProgress: (Long) -> Unit = {},
    ): Long {
        val flags = setOf(SftpOpenFlag.WRITE, SftpOpenFlag.CREATE, if (overwrite) SftpOpenFlag.TRUNCATE else SftpOpenFlag.EXCLUDE)
        val opened = sftp.open(remotePath, flags)
        if (!overwrite && opened is SftpResult.ServerError && opened.statusCode == SftpStatusCode.FAILURE) {
            // Servers answer "failure" when O_EXCL meets an existing file.
            if (sftp.lstat(remotePath) is SftpResult.Success) throw SftpFailure(SftpProblem.ALREADY_EXISTS)
        }
        val handle = opened.orFail()
        var finished = false
        try {
            val sent = coroutineScope {
                val inFlight = ArrayDeque<Deferred<Int>>()
                var offset = 0L
                var acknowledged = 0L
                val buffer = ByteArray(CHUNK_SIZE)
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = readFully(input, buffer)
                        if (count <= 0) break
                        val data = buffer.copyOf(count)
                        if (inFlight.size >= WINDOW) {
                            acknowledged += inFlight.removeFirst().await()
                            onProgress(acknowledged)
                        }
                        val at = offset
                        inFlight.addLast(
                            async {
                                sftp.write(handle, at, data).orFail()
                                data.size
                            },
                        )
                        offset += count
                    }
                    while (inFlight.isNotEmpty()) {
                        acknowledged += inFlight.removeFirst().await()
                        onProgress(acknowledged)
                    }
                } finally {
                    inFlight.forEach { it.cancel() }
                }
                acknowledged
            }
            finished = true
            return sent
        } finally {
            withContext(NonCancellable) {
                sftp.close(handle)
                // A new file that did not arrive whole is worse than none: take it away again.
                if (!finished && !overwrite) sftp.remove(remotePath)
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }
}
