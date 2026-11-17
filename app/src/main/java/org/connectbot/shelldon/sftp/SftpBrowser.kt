/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SftpStatusCode

/**
 * Listing, creating, renaming and deleting on one open SFTP session. Every failure is an
 * [SftpFailure]; nothing here touches Android, so it is tested against a fake and a real OpenSSH.
 */
class SftpBrowser(private val sftp: SftpClient) {
    /** The folder the server starts us in, as an absolute path. */
    suspend fun home(): String = SftpPaths.normalize(sftp.realpath(".").orFail())

    /** What is in [path], folders first. Hidden files are included; the screen decides whether to show them. */
    suspend fun list(path: String): List<SftpItem> {
        val entries = sftp.listdir(path).orFail().filter { SftpPaths.isListable(it.filename) }
        val gate = Semaphore(LINK_LOOKUPS)
        val items = coroutineScope {
            entries.map { entry ->
                async {
                    val full = SftpPaths.child(path, entry.filename)
                    var kind = SftpItems.kindOf(entry.attrs.permissions)
                    var attrs = entry.attrs
                    if (kind == null) {
                        // A server that sends no mode bits in a listing: ask about the entry itself.
                        val own = gate.withPermit { sftp.lstat(full) }
                        if (own is SftpResult.Success) {
                            attrs = own.value
                            kind = SftpItems.kindOf(own.value.permissions)
                        }
                    }
                    val resolved = kind ?: SftpKind.OTHER
                    val linkToFolder = resolved == SftpKind.LINK &&
                        gate.withPermit { (sftp.stat(full) as? SftpResult.Success)?.value?.permissions }.let { SftpItems.kindOf(it) } == SftpKind.FOLDER
                    SftpItem(
                        name = entry.filename,
                        path = full,
                        kind = resolved,
                        size = attrs.size,
                        modified = SftpItems.seconds(attrs.mtime),
                        permissions = attrs.permissions,
                        linkToFolder = linkToFolder,
                    )
                }
            }.awaitAll()
        }
        return SftpItems.sorted(items)
    }

    suspend fun exists(path: String): Boolean {
        val result = sftp.lstat(path)
        if (result is SftpResult.ServerError && result.statusCode == SftpStatusCode.NO_SUCH_FILE) return false
        result.orFail()
        return true
    }

    suspend fun size(path: String): Long? = sftp.stat(path).orFail().size

    /** @return the path of the new folder */
    suspend fun createFolder(parent: String, name: String): String {
        if (!SftpPaths.isValidNewName(name)) throw SftpFailure(SftpProblem.INVALID_NAME)
        val target = SftpPaths.child(parent, name)
        if (exists(target)) throw SftpFailure(SftpProblem.ALREADY_EXISTS)
        sftp.mkdir(target).orFail()
        return target
    }

    /** Renames within the same folder. Never replaces something that is already there. */
    suspend fun rename(item: SftpItem, newName: String): String {
        if (!SftpPaths.isValidNewName(newName)) throw SftpFailure(SftpProblem.INVALID_NAME)
        val target = SftpPaths.child(SftpPaths.parent(item.path), newName)
        if (target == item.path) return target
        if (exists(target)) throw SftpFailure(SftpProblem.ALREADY_EXISTS)
        sftp.rename(item.path, target).orFail()
        return target
    }

    /**
     * Deletes a file or link, or a folder with everything in it. Links are removed, never followed, so a
     * link to somewhere else cannot lead the deletion out of the folder.
     *
     * @return how many entries were removed
     */
    suspend fun delete(item: SftpItem, onRemoved: (Int) -> Unit = {}): Int {
        var removed = 0
        suspend fun remove(path: String, kind: SftpKind, depth: Int) {
            currentCoroutineContext().ensureActive()
            if (kind != SftpKind.FOLDER) {
                sftp.remove(path).orFail()
            } else {
                if (depth > MAX_DEPTH) throw SftpFailure(SftpProblem.TOO_DEEP)
                for (child in sftp.listdir(path).orFail().filter { SftpPaths.isListable(it.filename) }) {
                    val childPath = SftpPaths.child(path, child.filename)
                    val childKind = SftpItems.kindOf(child.attrs.permissions)
                        ?: SftpItems.kindOf(sftp.lstat(childPath).orFail().permissions)
                        ?: SftpKind.OTHER
                    remove(childPath, childKind, depth + 1)
                }
                sftp.rmdir(path).orFail()
            }
            removed++
            onRemoved(removed)
        }
        remove(item.path, item.kind, 0)
        return removed
    }

    companion object {
        const val MAX_DEPTH = 64
        private const val LINK_LOOKUPS = 16
    }
}
