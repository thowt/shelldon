/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.connectbot.sshlib.SftpAttributes
import org.connectbot.sshlib.SftpClient

/** What a permission change did. [firstFailure] is why the first item that could not be changed failed. */
data class ChmodResult(val changed: Int, val failed: Int, val firstFailure: SftpFailure? = null)

/**
 * Changing permissions on the server, like `chmod` and `chmod -R`.
 *
 * Only the permission bits are sent, never the file type, owner or times. Links are never followed:
 * `chmod` on the server would change the file the link points at, which may be outside the folder the
 * person is looking at, so links (and sockets, devices and pipes) are left alone.
 */
class SftpChmod(private val sftp: SftpClient) {
    /**
     * Gives [item] the permissions [mode].
     *
     * With [recursive] on a folder, every file and folder below it gets the same mode, except that
     * folders also get execute wherever they have read, so they stay openable (`chmod -R u=rwX`). The
     * contents are changed first and the folder last, because a folder that has just lost its execute
     * bit can no longer be listed. An item that cannot be changed does not stop the others; it is counted.
     * Setuid, setgid and sticky are refused for a recursive change: no one means to set them on every file.
     *
     * Without [recursive] this changes the one item and throws if it cannot.
     */
    suspend fun apply(item: SftpItem, mode: SftpMode, recursive: Boolean, onChanged: (Int) -> Unit = {}): ChmodResult {
        if (item.kind == SftpKind.LINK) throw SftpFailure(SftpProblem.UNSUPPORTED, "A link has no permissions of its own")
        if (item.kind == SftpKind.OTHER) throw SftpFailure(SftpProblem.UNSUPPORTED, "Only files and folders can be changed")
        if (!recursive || item.kind != SftpKind.FOLDER) {
            set(item.path, mode)
            onChanged(1)
            return ChmodResult(changed = 1, failed = 0)
        }
        require(mode.special == 0) { "Setuid, setgid and sticky cannot be set recursively" }

        var changed = 0
        var failed = 0
        var first: SftpFailure? = null
        fun failedWith(e: SftpFailure) {
            // Nothing more can be done once the connection is gone.
            if (e.problem == SftpProblem.CONNECTION_LOST) throw e
            failed++
            if (first == null) first = e
        }

        suspend fun change(path: String, target: SftpMode) {
            try {
                set(path, target)
                changed++
                onChanged(changed)
            } catch (e: SftpFailure) {
                failedWith(e)
            }
        }

        suspend fun walk(path: String, kind: SftpKind, depth: Int) {
            currentCoroutineContext().ensureActive()
            when (kind) {
                SftpKind.FILE -> change(path, mode)

                SftpKind.FOLDER -> {
                    if (depth > SftpBrowser.MAX_DEPTH) throw SftpFailure(SftpProblem.TOO_DEEP)
                    val entries = try {
                        sftp.listdir(path).orFail().filter { SftpPaths.isListable(it.filename) }
                    } catch (e: SftpFailure) {
                        failedWith(e)
                        emptyList()
                    }
                    for (entry in entries) {
                        val childPath = SftpPaths.child(path, entry.filename)
                        val childKind = try {
                            SftpItems.kindOf(entry.attrs.permissions)
                                ?: SftpItems.kindOf(sftp.lstat(childPath).orFail().permissions)
                                ?: SftpKind.OTHER
                        } catch (e: SftpFailure) {
                            failedWith(e)
                            continue
                        }
                        walk(childPath, childKind, depth + 1)
                    }
                    change(path, mode.withFolderAccess())
                }

                // Links, sockets, devices and pipes are not touched.
                SftpKind.LINK, SftpKind.OTHER -> Unit
            }
        }

        walk(item.path, SftpKind.FOLDER, 0)
        return ChmodResult(changed, failed, first)
    }

    private suspend fun set(path: String, mode: SftpMode) {
        // Only the permission field is present, so owner, size and times stay as they are.
        sftp.setstat(path, SftpAttributes(permissions = mode.bits)).orFail()
    }
}
