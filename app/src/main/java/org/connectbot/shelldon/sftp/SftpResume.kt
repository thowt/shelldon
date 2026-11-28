/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpFileHandle
import org.connectbot.sshlib.SftpOpenFlag
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SftpStatusCode
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Something that can be read at any offset. A read inside the file returns exactly what was asked for. */
fun interface RangeReader {
    suspend fun read(offset: Long, length: Int): ByteArray
}

/** The half-finished copy of a download, on this phone. */
interface PartialFile : RangeReader {
    val length: Long

    /** Cuts the file back to [keep] bytes and returns a stream that adds to its end. */
    fun openAppend(keep: Long): OutputStream
}

/** The file that is being uploaded, readable from any point. */
interface ResumableSource : RangeReader {
    val length: Long

    /** A stream that starts at [offset]. */
    fun openAt(offset: Long): InputStream
}

/** One version of a file on the server: what it must still be when a transfer is continued or finished. */
data class RemoteVersion(val size: Long, val modified: Long?)

/**
 * Continuing a transfer that stopped (the link dropped, the person cancelled) instead of starting over.
 *
 * What is kept is only ever the start of the file, and it is kept only when it is checked first: the
 * part that is already there is compared with the other side at a spread of places and at its end, and
 * on any difference the transfer starts again from the beginning. A download also needs the file on the
 * server to have the size and time it had when the first part was fetched, and is checked once more at
 * the end. This is a sample, not a hash of every byte (a plain SFTP server has no way to hash a file);
 * the person can always replace a file instead.
 */
object SftpResume {
    /** A part up to this size is compared whole. */
    const val WHOLE_BELOW = 4L * 1024 * 1024
    const val SAMPLE_BYTES = 4096
    const val SAMPLES = 64
    const val TAIL_BYTES = 32 * 1024

    /** The places of the first [bytes] bytes that are compared, as offset and length. */
    fun ranges(bytes: Long): List<Pair<Long, Int>> {
        if (bytes <= 0) return emptyList()
        if (bytes <= WHOLE_BELOW) {
            return buildList {
                var at = 0L
                while (at < bytes) {
                    val length = minOf(TAIL_BYTES.toLong(), bytes - at).toInt()
                    add(at to length)
                    at += length
                }
            }
        }
        val spread = bytes - SAMPLE_BYTES
        return buildList {
            for (i in 0 until SAMPLES) add(spread * i / (SAMPLES - 1) to SAMPLE_BYTES)
            add((bytes - TAIL_BYTES) to TAIL_BYTES)
        }
    }

    /**
     * True if [first] and [second] hold the same bytes at every place [ranges] picks for the first [bytes] bytes.
     * The places are read a few at a time, so a slow link is not paid for once per place.
     */
    suspend fun agree(first: RangeReader, second: RangeReader, bytes: Long): Boolean = coroutineScope {
        val gate = Semaphore(SftpTransfers.WINDOW)
        ranges(bytes).map { (offset, length) ->
            async {
                gate.withPermit {
                    val a = first.read(offset, length)
                    val b = second.read(offset, length)
                    a.size == length && b.size == length && a.contentEquals(b)
                }
            }
        }.awaitAll().all { it }
    }

    /** A name for the unfinished copy of one version of a remote file: it differs as soon as the host, the path, the size or the time does. */
    fun partialName(hostKey: String, remotePath: String, version: RemoteVersion): String = hex(sha256("$hostKey\u0000$remotePath\u0000${version.size}\u0000${version.modified ?: ""}")) + ".part"

    /**
     * The name an upload has on the server until it is complete: a hidden file next to its destination.
     * It carries a short mark of the size and time of the local file, so a file that was changed since is not continued.
     */
    fun uploadPartName(finalName: String, size: Long, modified: Long?): String {
        val mark = hex(sha256("$size\u0000${modified ?: ""}")).take(MARK_LENGTH)
        return ".${truncateUtf8(finalName, MAX_NAME_PART_BYTES)}.$mark.shelldon-part"
    }

    /** How many bytes of [partial] can be kept: all of it if it is the start of the file on the server, else 0. */
    suspend fun reusableDownload(sftp: SftpClient, remotePath: String, partial: PartialFile, version: RemoteVersion): Long {
        val have = partial.length
        if (have <= 0 || have > version.size) return 0
        return withRemote(sftp, remotePath) { remote -> if (agree(partial, remote, have)) have else 0 }
    }

    /** How many bytes of the unfinished upload on the server can be kept: all of it if it is the start of [source], else 0. */
    suspend fun reusableUpload(sftp: SftpClient, partPath: String, source: ResumableSource): Long {
        val stat = sftp.lstat(partPath)
        if (stat is SftpResult.ServerError && stat.statusCode == SftpStatusCode.NO_SUCH_FILE) return 0
        val have = stat.orFail().size ?: return 0
        if (have <= 0 || have > source.length) return 0
        return withRemote(sftp, partPath) { remote -> if (agree(remote, source, have)) have else 0 }
    }

    /**
     * Copies [remotePath] into [partial], continuing where an earlier try stopped if what is there still fits.
     *
     * @param version size and time of the file on the server as the person saw them in the list
     * @param onStart how many bytes were kept from earlier tries, before any more is fetched
     * @param onProgress total bytes in place, kept ones included
     * @return the number of bytes that were kept
     * @throws SftpFailure [SftpProblem.CHANGED] if the file on the server is not the version given, or changed
     *   while it was copied; [SftpProblem.TRUNCATED] if it ended early
     */
    suspend fun download(
        sftp: SftpClient,
        remotePath: String,
        version: RemoteVersion,
        partial: PartialFile,
        onStart: (kept: Long) -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): Long {
        require(version.size > 0) { "A file of unknown or zero size is not continued" }
        requireVersion(sftp, remotePath, version)
        val kept = reusableDownload(sftp, remotePath, partial, version)
        onStart(kept)
        partial.openAppend(kept).use { out ->
            SftpTransfers.download(sftp, remotePath, out, version.size, onProgress, startAt = kept)
        }
        if (partial.length != version.size) throw SftpFailure(SftpProblem.TRUNCATED)
        // If the file was written to while it was being copied, the copy may mix two versions.
        requireVersion(sftp, remotePath, version)
        return kept
    }

    /**
     * Sends [source] to [finalPath] through the hidden file [partPath], continuing an unfinished one if it still fits,
     * and gives it its real name only when it is complete. A file that already has that name is never replaced.
     *
     * @return the number of bytes that were kept from an earlier try
     */
    suspend fun upload(
        sftp: SftpClient,
        source: ResumableSource,
        finalPath: String,
        partPath: String,
        onStart: (kept: Long) -> Unit = {},
        onProgress: (Long) -> Unit = {},
    ): Long {
        if (exists(sftp, finalPath)) throw SftpFailure(SftpProblem.ALREADY_EXISTS)
        val kept = reusableUpload(sftp, partPath, source)
        onStart(kept)
        source.openAt(kept).use { input ->
            SftpTransfers.upload(sftp, input, partPath, overwrite = true, onProgress = onProgress, startAt = kept)
        }
        if (sftp.stat(partPath).orFail().size != source.length) throw SftpFailure(SftpProblem.TRUNCATED)
        // Looked at again, because it may have turned up while the file was being sent. rename() does not replace.
        if (exists(sftp, finalPath)) throw SftpFailure(SftpProblem.ALREADY_EXISTS)
        sftp.rename(partPath, finalPath).orFail()
        return kept
    }

    private suspend fun requireVersion(sftp: SftpClient, remotePath: String, version: RemoteVersion) {
        val now = sftp.stat(remotePath).orFail()
        val sameTime = version.modified == null || SftpItems.seconds(now.mtime) == version.modified
        if (now.size != version.size || !sameTime) throw SftpFailure(SftpProblem.CHANGED)
    }

    private suspend fun exists(sftp: SftpClient, path: String): Boolean {
        val result = sftp.lstat(path)
        if (result is SftpResult.ServerError && result.statusCode == SftpStatusCode.NO_SUCH_FILE) return false
        result.orFail()
        return true
    }

    private suspend fun <T> withRemote(sftp: SftpClient, path: String, block: suspend (RangeReader) -> T): T {
        val handle = sftp.open(path, setOf(SftpOpenFlag.READ)).orFail()
        try {
            return block(RemoteRange(sftp, handle))
        } finally {
            withContext(NonCancellable) { sftp.close(handle) }
        }
    }

    /** Reads from a file on the server and keeps asking until the whole range is there or the file ends. */
    private class RemoteRange(private val sftp: SftpClient, private val handle: SftpFileHandle) : RangeReader {
        override suspend fun read(offset: Long, length: Int): ByteArray {
            val out = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val piece = sftp.read(handle, offset + filled, length - filled).orFail()
                if (piece == null || piece.isEmpty()) break
                piece.copyInto(out, filled)
                filled += piece.size
            }
            return if (filled == length) out else out.copyOf(filled)
        }
    }

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Cuts [text] to at most [maxBytes] bytes of UTF-8 without splitting a character. */
    private fun truncateUtf8(text: String, maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val point = text.codePointAt(end)
            val size = String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(point)
        }
        return text.substring(0, end)
    }

    private const val MARK_LENGTH = 8

    /** Room for the dot, the mark and the suffix within the 255 bytes a name may have. */
    private const val MAX_NAME_PART_BYTES = 200
}
