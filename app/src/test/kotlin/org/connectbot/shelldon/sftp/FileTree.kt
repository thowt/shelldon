/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import java.io.File

/** A folder on the test machine standing in for the folder on the phone. Refuses to replace anything, as the real one does. */
class FileTree : LocalTree<File> {
    override fun find(parent: File, name: String): LocalEntry? {
        val file = File(parent, name)
        return when {
            file.isDirectory -> LocalEntry.Folder
            file.isFile -> LocalEntry.File(file.length())
            else -> null
        }
    }

    override fun enter(parent: File, name: String): File = File(parent, name)

    override fun createFolder(parent: File, name: String): File = File(parent, name).also { check(it.mkdir()) { "could not create $it" } }

    override fun store(parent: File, name: String, content: File) {
        val target = File(parent, name)
        check(!target.exists()) { "would replace $target" }
        content.copyTo(target)
    }
}
