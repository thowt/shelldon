/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.sftp

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.connectbot.shelldon.sftp.LocalEntry
import org.connectbot.shelldon.sftp.LocalTree
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * A folder the user picked with the system folder picker, as a [LocalTree]. Files and folders are made
 * through the Storage Access Framework, so no storage permission is needed and nothing outside the
 * chosen folder can be reached. Existing documents are only ever listed, never changed or deleted;
 * the one thing deleted is a file this class created itself and could not finish writing.
 *
 * Folder contents are listed once per folder and remembered, so a tree with thousands of files costs one
 * query per folder, not one per file. Call it from one thread at a time.
 */
class SafTree(private val resolver: ContentResolver, private val tree: Uri) : LocalTree<Uri> {
    private class Child(val id: String, val folder: Boolean, val size: Long?)

    private val listed = HashMap<Uri, HashMap<String, Child>>()

    /** The chosen folder itself. */
    val root: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    override fun find(parent: Uri, name: String): LocalEntry? {
        val child = children(parent)[name] ?: return null
        return if (child.folder) LocalEntry.Folder else LocalEntry.File(child.size)
    }

    override fun enter(parent: Uri, name: String): Uri {
        val child = children(parent)[name]?.takeIf { it.folder } ?: throw IOException("The folder $name is gone")
        return DocumentsContract.buildDocumentUriUsingTree(tree, child.id)
    }

    override fun createFolder(parent: Uri, name: String): Uri {
        val created = create(parent, Document.MIME_TYPE_DIR, name)
        children(parent)[name] = Child(DocumentsContract.getDocumentId(created), folder = true, size = null)
        listed[created] = HashMap()
        return created
    }

    override fun store(parent: Uri, name: String, content: File) {
        val created = create(parent, MIME_FILE, name)
        try {
            val out = resolver.openOutputStream(created, "wt") ?: throw IOException("Cannot write $name to the chosen folder")
            out.use { target -> content.inputStream().use { it.copyTo(target) } }
            val stored = sizeOf(created)
            if (stored != null && stored != content.length()) throw IOException("$name was not written completely")
            children(parent)[name] = Child(DocumentsContract.getDocumentId(created), folder = false, size = content.length())
        } catch (e: Exception) {
            discard(created)
            throw e as? IOException ?: IOException(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun create(parent: Uri, mime: String, name: String): Uri {
        val created = try {
            DocumentsContract.createDocument(resolver, parent, mime, name)
        } catch (e: Exception) {
            throw IOException(e.message ?: e.javaClass.simpleName, e)
        }
        return created ?: throw IOException("Cannot create $name in the chosen folder")
    }

    private fun children(parent: Uri): HashMap<String, Child> = listed.getOrPut(parent) { list(parent) }

    private fun list(parent: Uri): HashMap<String, Child> {
        val found = HashMap<String, Child>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        val cursor = try {
            resolver.query(uri, columns, null, null, null)
        } catch (e: Exception) {
            throw IOException(e.message ?: e.javaClass.simpleName, e)
        } ?: throw IOException("The chosen folder cannot be read")
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(1) ?: continue
                val size = if (it.isNull(3)) null else it.getLong(3)
                // Two documents of one name: the first one is the one that counts.
                if (name !in found) found[name] = Child(it.getString(0), it.getString(2) == Document.MIME_TYPE_DIR, size)
            }
        }
        return found
    }

    private fun sizeOf(document: Uri): Long? = try {
        resolver.query(document, arrayOf(Document.COLUMN_SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    } catch (e: Exception) {
        null
    }

    private fun discard(document: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, document)
        } catch (e: Exception) {
            Timber.w(e, "Could not remove a file that was not finished")
        }
    }

    private companion object {
        const val MIME_FILE = "application/octet-stream"
    }
}
