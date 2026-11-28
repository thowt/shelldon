/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.SftpAttributes
import org.connectbot.sshlib.SftpClient
import org.connectbot.sshlib.SftpOpenFlag
import org.connectbot.sshlib.SftpResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/** What the file on the server looked like when the editor opened it, to notice that somebody else changed it. */
data class EditSnapshot(val path: String, val size: Long, val modified: Int?)

sealed interface EditOpen {
    data class Opened(val snapshot: EditSnapshot, val file: EditableText) : EditOpen

    data class Refused(val reason: EditRefusal) : EditOpen
}

sealed interface EditSave {
    data class Saved(val snapshot: EditSnapshot) : EditSave

    /** The file on the server is not the one that was opened. Nothing was written. */
    data object ChangedOnServer : EditSave

    /**
     * The new content is complete in [completeCopy] on the server, but writing it over the file did not
     * finish, so the file itself may be cut off. Nothing was removed, so no text is lost.
     */
    data class Interrupted(val failure: SftpFailure, val completeCopy: String) : EditSave
}

/**
 * Editing a text file on the server.
 *
 * SFTP (version 3, which is what servers speak) cannot replace a file by renaming over it, and a
 * half-written config file is worse than an unchanged one. So a save goes in steps, each of which can fail
 * without harming the file:
 *
 * 1. the file on the server must still be the one that was opened (size and modification time);
 * 2. the new content is written in full to a hidden file next to it, created readable by the owner only. This
 *    is where a full disk shows up, while the original is still untouched;
 * 3. the original is checked again and then overwritten in place, which keeps its owner, mode, links and
 *    ACLs. This step is not cancelled once it has begun;
 * 4. the hidden file is removed. If step 3 fails, it is kept and named in the result.
 *
 * SFTP has no locking, so a change between the check and the overwrite cannot be ruled out, only made
 * unlikely.
 */
object SftpEdit {
    private const val OWNER_ONLY = 0x180 // 0600

    /** Reads the file for editing, or says why it cannot be edited. Regular files only; links are never followed. */
    suspend fun open(sftp: SftpClient, path: String): EditOpen {
        val attrs = sftp.lstat(path).orFail()
        val kind = SftpItems.kindOf(attrs.permissions)
        if (kind != null && kind != SftpKind.FILE) throw SftpFailure(SftpProblem.OTHER, "not a regular file")
        val size = attrs.size
        if (size != null && size > SftpEditText.MAX_BYTES) return EditOpen.Refused(EditRefusal.TOO_LARGE)
        val sink = LimitedSink(SftpEditText.MAX_BYTES)
        try {
            SftpTransfers.download(sftp, path, sink, size)
        } catch (e: TooLarge) {
            // The file grew while it was being read, or the server did not say how big it is.
            return EditOpen.Refused(EditRefusal.TOO_LARGE)
        }
        val bytes = sink.toByteArray()
        return when (val decoded = SftpEditText.decode(bytes)) {
            is TextDecoding.Refused -> EditOpen.Refused(decoded.reason)
            is TextDecoding.Text -> EditOpen.Opened(EditSnapshot(path, attrs.size ?: bytes.size.toLong(), attrs.mtime), decoded.value)
        }
    }

    /**
     * @param force true after the user chose to overwrite a file that changed on the server
     */
    suspend fun save(sftp: SftpClient, snapshot: EditSnapshot, content: ByteArray, force: Boolean): EditSave {
        if (!force && changed(sftp, snapshot)) return EditSave.ChangedOnServer
        val copy = SftpPaths.child(SftpPaths.parent(snapshot.path), ".${leaf(snapshot.path)}.${UUID.randomUUID().toString().take(8)}.shelldon-edit")
        writeCopy(sftp, copy, content)
        return withContext(NonCancellable) {
            // Until the first byte of the original is touched, anything that goes wrong leaves it as it was.
            try {
                if (!force && changed(sftp, snapshot)) {
                    sftp.remove(copy)
                    return@withContext EditSave.ChangedOnServer
                }
                // Opening for writing without truncating: a file we may not write is found out here, not half way.
                sftp.close(sftp.open(snapshot.path, setOf(SftpOpenFlag.WRITE)).orFail())
            } catch (e: SftpFailure) {
                sftp.remove(copy)
                throw e
            }
            // From here on the original may be cut off, and the complete copy is what is left to go back to.
            try {
                SftpTransfers.upload(sftp, ByteArrayInputStream(content), snapshot.path, overwrite = true)
                val now = sftp.lstat(snapshot.path).orFail()
                if (now.size != content.size.toLong()) throw SftpFailure(SftpProblem.TRUNCATED)
                sftp.remove(copy)
                EditSave.Saved(EditSnapshot(snapshot.path, content.size.toLong(), now.mtime))
            } catch (e: SftpFailure) {
                EditSave.Interrupted(e, copy)
            }
        }
    }

    /** Saves [content] as a new file next to the original, which is not touched. */
    suspend fun saveCopy(sftp: SftpClient, snapshot: EditSnapshot, content: ByteArray): String {
        val folder = SftpPaths.parent(snapshot.path)
        val name = leaf(snapshot.path)
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var attempt = 1
        while (true) {
            val candidate = name.substring(0, dot) + (if (attempt == 1) " (edited)" else " (edited $attempt)") + name.substring(dot)
            val path = SftpPaths.child(folder, candidate)
            if (sftp.lstat(path) is SftpResult.Success) {
                attempt++
                continue
            }
            // The exclusive create inside makes a file that appeared meanwhile fail instead of being replaced.
            writeCopy(sftp, path, content)
            return path
        }
    }

    private suspend fun changed(sftp: SftpClient, snapshot: EditSnapshot): Boolean {
        val now = sftp.lstat(snapshot.path).orFail()
        if (SftpItems.kindOf(now.permissions)?.let { it != SftpKind.FILE } == true) return true
        if (now.size != null && now.size != snapshot.size) return true
        return snapshot.modified != null && now.mtime != null && now.mtime != snapshot.modified
    }

    /** Creates [path] (never replacing a file) readable by the owner only, writes [content] and checks the size on the server. */
    private suspend fun writeCopy(sftp: SftpClient, path: String, content: ByteArray) {
        sftp.close(sftp.open(path, setOf(SftpOpenFlag.WRITE, SftpOpenFlag.CREATE, SftpOpenFlag.EXCLUDE), SftpAttributes(permissions = OWNER_ONLY)).orFail())
        try {
            SftpTransfers.upload(sftp, ByteArrayInputStream(content), path, overwrite = true)
            val size = sftp.lstat(path).orFail().size
            if (size != null && size != content.size.toLong()) throw SftpFailure(SftpProblem.TRUNCATED)
        } catch (e: Exception) {
            withContext(NonCancellable) { sftp.remove(path) }
            throw e
        }
    }

    private fun leaf(path: String): String = path.substringAfterLast('/')

    private class TooLarge : Exception()

    /** Collects up to a limit of bytes and fails beyond it, so a file that grew on the way cannot fill the phone's memory. */
    private class LimitedSink(private val limit: Int) : ByteArrayOutputStream() {
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (count + len > limit) throw TooLarge()
            super.write(b, off, len)
        }

        override fun write(b: Int) {
            if (count + 1 > limit) throw TooLarge()
            super.write(b)
        }
    }
}
