/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import kotlinx.coroutines.delay
import org.connectbot.sshlib.SftpAttributes
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpDirectoryEntry
import org.connectbot.sshlib.SftpFileHandle
import org.connectbot.sshlib.SftpOpenFlag
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SftpStatusCode
import java.util.concurrent.atomic.AtomicInteger

/**
 * An in-memory SFTP server for the tests: a tree of folders, files and links, with knobs for the
 * ways real servers differ (short reads, files that shrink, no mode bits in listings).
 */
class FakeSftpClient : SftpClient {
    val files = LinkedHashMap<String, ByteArray>()
    val folders = linkedSetOf("/")
    val links = LinkedHashMap<String, String>()

    /** The most bytes one read returns. */
    var readCap = Int.MAX_VALUE

    /** Make the listing leave out mode bits, as some old servers do. */
    var listingWithoutModes = false

    /** After this many reads, the file reads as if it had been cut off. Null: never. */
    var cutOffAfterReads: Int? = null

    /** Names to add to every listing, as a hostile server could. */
    var extraListedNames: List<String> = emptyList()

    val inFlightPeak = AtomicInteger(0)
    private val inFlight = AtomicInteger(0)
    val readRequests = AtomicInteger(0)
    val writeRequests = AtomicInteger(0)

    private val open = HashMap<SftpFileHandle, OpenFile>()
    private var counter = 0

    private class OpenFile(val path: String, val writable: Boolean)

    override val protocolVersion: Int = 3
    override val extensions: Set<String> = emptySet()
    override var isOpen: Boolean = true
        private set

    private fun newHandle(): SftpFileHandle {
        // The constructor is internal to sshlib; a test double has to go around that.
        val ctor = SftpFileHandle::class.java.getDeclaredConstructor(ByteArray::class.java)
        ctor.isAccessible = true
        return ctor.newInstance("h${counter++}".toByteArray())
    }

    private fun fail(code: SftpStatusCode, message: String = code.name) = SftpResult.ServerError(code, message)

    private fun attrsOf(path: String, followLinks: Boolean): SftpAttributes? {
        if (!followLinks && path in links) return SftpAttributes(size = links.getValue(path).length.toLong(), permissions = 0xA1FF, mtime = 1_700_000_000)
        val resolved = if (followLinks) resolve(path) else path
        return when {
            resolved in folders -> SftpAttributes(size = 4096, permissions = 0x41ED, mtime = 1_700_000_000)
            resolved in files -> SftpAttributes(size = files.getValue(resolved).size.toLong(), permissions = 0x81A4, mtime = 1_700_000_000)
            else -> null
        }
    }

    private fun resolve(path: String): String {
        var current = path
        repeat(8) { current = links[current] ?: return current }
        return current
    }

    override suspend fun open(path: String, flags: Set<SftpOpenFlag>, attrs: SftpAttributes): SftpResult<SftpFileHandle> {
        val write = SftpOpenFlag.WRITE in flags
        val exists = path in files
        if (!write && !exists) return fail(SftpStatusCode.NO_SUCH_FILE)
        if (write) {
            if (SftpOpenFlag.EXCLUDE in flags && exists) return fail(SftpStatusCode.FAILURE)
            if (!exists && SftpOpenFlag.CREATE !in flags) return fail(SftpStatusCode.NO_SUCH_FILE)
            if (!exists || SftpOpenFlag.TRUNCATE in flags) files[path] = ByteArray(0)
        }
        return SftpResult.Success(newHandle().also { open[it] = OpenFile(path, write) })
    }

    override suspend fun close(handle: SftpFileHandle): SftpResult<Unit> {
        open.remove(handle)
        return SftpResult.Success(Unit)
    }

    override suspend fun read(handle: SftpFileHandle, offset: Long, length: Int): SftpResult<ByteArray?> {
        val file = open[handle] ?: return fail(SftpStatusCode.FAILURE, "bad handle")
        readRequests.incrementAndGet()
        val now = inFlight.incrementAndGet()
        inFlightPeak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try {
            delay(1)
            val data = files[file.path] ?: return fail(SftpStatusCode.FAILURE)
            val cut = cutOffAfterReads
            val visible = if (cut != null && readRequests.get() > cut) 0 else data.size
            if (offset >= visible) return SftpResult.Success(null)
            val count = minOf(length, readCap, visible - offset.toInt())
            return SftpResult.Success(data.copyOfRange(offset.toInt(), offset.toInt() + count))
        } finally {
            inFlight.decrementAndGet()
        }
    }

    override suspend fun write(handle: SftpFileHandle, offset: Long, data: ByteArray): SftpResult<Unit> {
        val file = open[handle] ?: return fail(SftpStatusCode.FAILURE, "bad handle")
        if (!file.writable) return fail(SftpStatusCode.PERMISSION_DENIED)
        writeRequests.incrementAndGet()
        val now = inFlight.incrementAndGet()
        inFlightPeak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try {
            delay(1)
            synchronized(files) {
                val old = files.getValue(file.path)
                val end = offset.toInt() + data.size
                val grown = if (end > old.size) old.copyOf(end) else old
                data.copyInto(grown, offset.toInt())
                files[file.path] = grown
            }
            return SftpResult.Success(Unit)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    override suspend fun stat(path: String): SftpResult<SftpAttributes> = attrsOf(path, true)?.let { SftpResult.Success(it) } ?: fail(SftpStatusCode.NO_SUCH_FILE)

    override suspend fun lstat(path: String): SftpResult<SftpAttributes> = attrsOf(path, false)?.let { SftpResult.Success(it) } ?: fail(SftpStatusCode.NO_SUCH_FILE)

    override suspend fun fstat(handle: SftpFileHandle): SftpResult<SftpAttributes> = fail(SftpStatusCode.OP_UNSUPPORTED)

    override suspend fun setstat(path: String, attrs: SftpAttributes): SftpResult<Unit> = SftpResult.Success(Unit)

    override suspend fun fsetstat(handle: SftpFileHandle, attrs: SftpAttributes): SftpResult<Unit> = SftpResult.Success(Unit)

    override suspend fun opendir(path: String): SftpResult<SftpFileHandle> {
        if (resolve(path) !in folders) return fail(SftpStatusCode.NO_SUCH_FILE)
        return SftpResult.Success(newHandle().also { open[it] = OpenFile(resolve(path), false) })
    }

    private val delivered = HashSet<SftpFileHandle>()

    override suspend fun readdir(handle: SftpFileHandle): SftpResult<List<SftpDirectoryEntry>?> {
        val dir = open[handle]?.path ?: return fail(SftpStatusCode.FAILURE, "bad handle")
        if (!delivered.add(handle)) return SftpResult.Success(null)
        val prefix = if (dir == "/") "/" else "$dir/"
        val names = (files.keys + folders + links.keys).filter { it != dir && it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }
        val entries = names.map { full ->
            val attrs = attrsOf(full, false)!!
            SftpDirectoryEntry(full.removePrefix(prefix), "", if (listingWithoutModes) attrs.copy(permissions = null) else attrs)
        } + listOf(".", "..").map { SftpDirectoryEntry(it, "", SftpAttributes(permissions = 0x41ED)) } +
            extraListedNames.map { SftpDirectoryEntry(it, "", SftpAttributes(permissions = 0x81A4, size = 1)) }
        return SftpResult.Success(entries)
    }

    override suspend fun mkdir(path: String, attrs: SftpAttributes): SftpResult<Unit> {
        if (path in folders || path in files) return fail(SftpStatusCode.FAILURE)
        folders += path
        return SftpResult.Success(Unit)
    }

    override suspend fun rmdir(path: String): SftpResult<Unit> {
        if (path !in folders) return fail(SftpStatusCode.NO_SUCH_FILE)
        val prefix = "$path/"
        if ((files.keys + folders + links.keys).any { it.startsWith(prefix) }) return fail(SftpStatusCode.FAILURE, "directory not empty")
        folders -= path
        return SftpResult.Success(Unit)
    }

    override suspend fun remove(path: String): SftpResult<Unit> = when {
        path in links -> {
            links -= path
            SftpResult.Success(Unit)
        }

        path in files -> {
            files -= path
            SftpResult.Success(Unit)
        }

        else -> fail(SftpStatusCode.NO_SUCH_FILE)
    }

    override suspend fun rename(oldPath: String, newPath: String): SftpResult<Unit> {
        if (newPath in files || newPath in folders) return fail(SftpStatusCode.FAILURE)
        val data = files.remove(oldPath)
        if (data != null) {
            files[newPath] = data
            return SftpResult.Success(Unit)
        }
        if (oldPath in folders) {
            folders -= oldPath
            folders += newPath
            return SftpResult.Success(Unit)
        }
        return fail(SftpStatusCode.NO_SUCH_FILE)
    }

    override suspend fun copyData(srcHandle: SftpFileHandle, srcOffset: Long, length: Long, dstHandle: SftpFileHandle, dstOffset: Long, timeoutMs: Long): SftpResult<Unit> = fail(SftpStatusCode.OP_UNSUPPORTED)

    override suspend fun realpath(path: String): SftpResult<String> = SftpResult.Success(if (path == ".") "/home/test" else path)

    override suspend fun readlink(path: String): SftpResult<String> = links[path]?.let { SftpResult.Success(it) } ?: fail(SftpStatusCode.NO_SUCH_FILE)

    override suspend fun symlink(targetPath: String, linkPath: String): SftpResult<Unit> {
        links[linkPath] = targetPath
        return SftpResult.Success(Unit)
    }

    override fun close() {
        isOpen = false
    }
}
