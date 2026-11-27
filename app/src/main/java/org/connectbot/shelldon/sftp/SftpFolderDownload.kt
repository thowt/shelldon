/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.connectbot.sshlib.SftpClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** One file in a folder that is about to be downloaded. [parts] are the names from the top folder down, the file's own last. */
data class RemoteFile(val parts: List<String>, val size: Long)

/**
 * Everything found in a folder on the server before anything is downloaded, so the person can see what
 * they are about to start. Links are never followed: a link could lead to the whole disk, or round in a circle.
 */
data class RemoteTree(
    /** The name of the folder that was scanned. */
    val rootName: String,
    /** Folders below it, each as the names from the top down, parents before their children. */
    val folders: List<List<String>>,
    val files: List<RemoteFile>,
    val linksSkipped: Int,
    /** Sockets, devices and pipes. */
    val othersSkipped: Int,
    /** Folders that could not be read. */
    val unreadable: Int,
    /** True if the scan stopped at the limit: there is more than what is listed. */
    val truncated: Boolean,
) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

object SftpTreeScan {
    const val MAX_ENTRIES = 20_000
    private const val PARALLEL_LISTINGS = 8

    /**
     * Lists the folder at [rootPath] and everything below it.
     *
     * @param maxEntries the most files and folders to collect; the scan stops there and says so
     * @param onFound called with how many files and folders have been found so far
     */
    suspend fun scan(
        sftp: SftpClient,
        rootPath: String,
        rootName: String,
        maxEntries: Int = MAX_ENTRIES,
        onFound: (Int) -> Unit = {},
    ): RemoteTree = coroutineScope {
        val gate = Semaphore(PARALLEL_LISTINGS)
        val lock = Any()
        val folders = ArrayList<List<String>>()
        val files = ArrayList<RemoteFile>()
        var links = 0
        var others = 0
        var unreadable = 0
        var truncated = false

        fun found(): Int = folders.size + files.size

        suspend fun visit(path: String, parts: List<String>, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (depth > SftpBrowser.MAX_DEPTH) throw SftpFailure(SftpProblem.TOO_DEEP)
            val entries = try {
                gate.withPermit { sftp.listdir(path).orFail() }
            } catch (e: SftpFailure) {
                if (e.problem == SftpProblem.CONNECTION_LOST || depth == 0) throw e
                synchronized(lock) { unreadable++ }
                return
            }
            val children = ArrayList<Pair<String, List<String>>>()
            for (entry in entries.filter { SftpPaths.isListable(it.filename) }) {
                val childPath = SftpPaths.child(path, entry.filename)
                val kind = SftpItems.kindOf(entry.attrs.permissions)
                    ?: SftpItems.kindOf(gate.withPermit { sftp.lstat(childPath).orFail() }.permissions)
                    ?: SftpKind.OTHER
                val childParts = parts + entry.filename
                synchronized(lock) {
                    if (truncated) return@synchronized
                    when (kind) {
                        SftpKind.FOLDER -> if (found() >= maxEntries) truncated = true else folders.add(childParts)
                        SftpKind.FILE -> if (found() >= maxEntries) truncated = true else files.add(RemoteFile(childParts, entry.attrs.size ?: 0L))
                        SftpKind.LINK -> links++
                        SftpKind.OTHER -> others++
                    }
                    onFound(found())
                }
                if (kind == SftpKind.FOLDER && !synchronized(lock) { truncated }) children.add(childPath to childParts)
            }
            children.map { (childPath, childParts) -> async { visit(childPath, childParts, depth + 1) } }.awaitAll()
        }

        visit(rootPath, emptyList(), 0)
        RemoteTree(
            rootName = rootName,
            folders = folders.sortedWith(compareBy({ it.size }, { it.joinToString("/") })),
            files = files.sortedBy { it.parts.joinToString("/") },
            linksSkipped = links,
            othersSkipped = others,
            unreadable = unreadable,
            truncated = truncated,
        )
    }
}

/** What is already on this phone, as far as a folder download needs to know. */
sealed interface LocalEntry {
    /** A file; [size] is null if the provider does not say. */
    data class File(val size: Long?) : LocalEntry

    data object Folder : LocalEntry
}

/**
 * The folder on this phone that a folder download fills. [N] stands for one folder in it (a document
 * address on Android). Nothing here ever replaces or deletes what is already there.
 */
interface LocalTree<N> {
    fun find(parent: N, name: String): LocalEntry?

    /** The existing folder [name] in [parent]. */
    fun enter(parent: N, name: String): N

    fun createFolder(parent: N, name: String): N

    /** Creates the file [name] in [parent] with the content of [content]. If that fails nothing is left behind. */
    fun store(parent: N, name: String, content: File)
}

/** How far a folder download has come. */
data class FolderProgress(
    val filesDone: Int,
    val filesTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val currentName: String,
)

data class FolderFailure(val path: String, val problem: SftpProblem, val detail: String)

data class FolderResult(
    val downloaded: Int,
    /** Skipped because a file of that name and size was already there. */
    val alreadyThere: Int,
    /** Skipped because a file of that name but another size was already there, which is left alone. */
    val keptDifferent: Int,
    val failures: List<FolderFailure>,
    /** Local names that differ from the remote ones, because the remote name could not be used or was taken. */
    val renamed: Int,
)

/**
 * Downloads a scanned folder into a folder on this phone, mirroring its structure.
 *
 * What is already there is never overwritten, so running it again after a stop carries on: a file that is
 * there with the same size counts as done (like `rsync --size-only`), one with another size is left alone
 * and reported. Each file is fetched into a temporary file first and is put into the folder only when whole,
 * so a stop never leaves a half-written file among the finished ones. One file that fails does not stop
 * the rest; a lost connection does.
 */
class FolderDownload<N>(
    private val sftp: SftpClient,
    private val tree: LocalTree<N>,
    private val tempFolder: File,
) {
    private class Dir<N>(val node: N, val assigned: HashMap<String, String> = HashMap())

    /**
     * @param remoteRoot the path of the folder that was scanned
     * @param destination where the folder is put: a subfolder named like the scanned one is made there, or used if it exists
     */
    suspend fun run(remoteRoot: String, scan: RemoteTree, destination: N, onProgress: (FolderProgress) -> Unit = {}): FolderResult {
        tempFolder.mkdirs()
        var renamed = 0
        val destinationDir = Dir(destination)
        val rootName = pickFolderName(destinationDir, scan.rootName, scan.rootName) { renamed++ }
        val rootDir = Dir(rootName.second)
        val dirs = HashMap<List<String>, Dir<N>>()
        dirs[emptyList()] = rootDir

        for (parts in scan.folders) {
            currentCoroutineContext().ensureActive()
            val parent = dirs[parts.dropLast(1)] ?: continue
            val (_, node) = pickFolderName(parent, parts.last(), parts.joinToString("/")) { renamed++ }
            dirs[parts] = Dir(node)
        }

        var downloaded = 0
        var alreadyThere = 0
        var keptDifferent = 0
        val failures = ArrayList<FolderFailure>()
        var bytesDone = 0L
        val bytesTotal = scan.totalBytes

        for ((index, file) in scan.files.withIndex()) {
            currentCoroutineContext().ensureActive()
            val remotePath = file.parts.fold(remoteRoot) { path, name -> SftpPaths.child(path, name) }
            val display = file.parts.joinToString("/")
            val dir = dirs[file.parts.dropLast(1)]
            if (dir == null) {
                // Its folder could not be made; the failure was counted there.
                failures.add(FolderFailure(display, SftpProblem.OTHER, "its folder is missing"))
                continue
            }
            fun report(current: Long) = onProgress(FolderProgress(index, scan.files.size, bytesDone + current, bytesTotal, display))
            report(0)

            val name = chooseFileName(dir, file.parts.last())
            if (name != file.parts.last()) renamed++
            val existing = tree.find(dir.node, name)
            if (existing is LocalEntry.File) {
                if (existing.size == file.size) alreadyThere++ else keptDifferent++
                bytesDone += file.size
                continue
            }
            val temp = File.createTempFile("folder-", ".part", tempFolder)
            try {
                FileOutputStream(temp).use { out -> SftpTransfers.download(sftp, remotePath, out, file.size, onProgress = ::report) }
                tree.store(dir.node, name, temp)
                downloaded++
                bytesDone += file.size
            } catch (e: CancellationException) {
                throw e
            } catch (e: SftpFailure) {
                if (e.problem == SftpProblem.CONNECTION_LOST) throw e
                failures.add(FolderFailure(display, e.problem, e.detail))
            } catch (e: IOException) {
                failures.add(FolderFailure(display, SftpProblem.OTHER, e.message.orEmpty()))
            } finally {
                temp.delete()
            }
        }
        onProgress(FolderProgress(scan.files.size, scan.files.size, bytesTotal, bytesTotal, ""))
        return FolderResult(downloaded, alreadyThere, keptDifferent, failures, renamed)
    }

    /** The local name for the folder [remote] in [parent]: an existing folder is used again, a file in the way is stepped around. */
    private fun pickFolderName(parent: Dir<N>, remote: String, key: String, onRenamed: () -> Unit): Pair<String, N> {
        val base = SftpPaths.localFileName(remote)
        var attempt = 0
        while (true) {
            val candidate = if (attempt == 0) base else "$base ($attempt)"
            attempt++
            val taken = parent.assigned[candidate.lowercase()]
            if (taken != null && taken != key) continue
            val node = when (tree.find(parent.node, candidate)) {
                null -> tree.createFolder(parent.node, candidate)
                LocalEntry.Folder -> tree.enter(parent.node, candidate)
                is LocalEntry.File -> continue
            }
            parent.assigned[candidate.lowercase()] = key
            if (candidate != remote) onRenamed()
            return candidate to node
        }
    }

    /** The local name of a file: cleaned, and different from every other name given out in this folder. */
    private fun chooseFileName(parent: Dir<N>, remote: String): String {
        val clean = SftpPaths.localFileName(remote)
        val dot = clean.lastIndexOf('.').takeIf { it > 0 } ?: clean.length
        var attempt = 0
        while (true) {
            val candidate = if (attempt == 0) clean else clean.substring(0, dot) + " ($attempt)" + clean.substring(dot)
            attempt++
            val taken = parent.assigned[candidate.lowercase()]
            if (taken != null && taken != remote) continue
            // A folder of that name, from an earlier run or the phone's own, is in the way.
            if (tree.find(parent.node, candidate) == LocalEntry.Folder) continue
            parent.assigned[candidate.lowercase()] = remote
            return candidate
        }
    }
}
