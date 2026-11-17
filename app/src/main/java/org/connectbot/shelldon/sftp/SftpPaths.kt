/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

/** One step of the path shown above the file list. */
data class Crumb(val label: String, val path: String)

/**
 * Remote paths. They are POSIX paths whatever this phone is, and the server is not trusted: a name
 * that a listing hands us is checked before it is ever joined onto a path.
 */
object SftpPaths {
    const val ROOT = "/"
    const val MAX_NAME_BYTES = 255

    /** Makes [path] absolute and removes `.`, `..` and repeated slashes. `..` never climbs above the root. */
    fun normalize(path: String): String {
        val parts = ArrayDeque<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        return if (parts.isEmpty()) ROOT else parts.joinToString("/", prefix = "/")
    }

    fun parent(path: String): String = normalize(path).let { if (it == ROOT) ROOT else normalize("$it/..") }

    fun breadcrumbs(path: String): List<Crumb> {
        val normal = normalize(path)
        val crumbs = mutableListOf(Crumb(ROOT, ROOT))
        var walked = ""
        if (normal != ROOT) {
            for (part in normal.removePrefix("/").split('/')) {
                walked += "/$part"
                crumbs += Crumb(part, walked)
            }
        }
        return crumbs
    }

    /**
     * Can an entry with this name from a directory listing be used at all? Servers send names; a
     * name with a slash or a NUL, or the entries `.` and `..`, would let a hostile server make us
     * build a path somewhere else, so such entries are dropped.
     */
    fun isListable(name: String): Boolean = name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name

    /** Is this acceptable as the name of something the user is creating or renaming? */
    fun isValidNewName(name: String): Boolean = isListable(name) &&
        name.isNotBlank() &&
        name.none { it < ' ' || it == '\u007f' } &&
        name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES

    /** The path of [name] inside [dir]. Only for names that passed [isListable]. */
    fun child(dir: String, name: String): String {
        require(isListable(name)) { "Not a usable file name" }
        val base = normalize(dir)
        return if (base == ROOT) "/$name" else "$base/$name"
    }

    /** [name] made safe to show: control characters, which could break the layout, become a replacement mark. */
    fun printable(name: String): String = if (name.none { it < ' ' || it == '\u007f' }) {
        name
    } else {
        name.map { if (it < ' ' || it == '\u007f') '\uFFFD' else it }.joinToString("")
    }

    /**
     * A name for a file that is about to be created on the server, taken from a local name: usable by
     * [isValidNewName] whatever the phone's file was called, and cut to the length limit.
     */
    fun remoteFileName(localName: String): String {
        var name = localFileName(localName)
        while (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) name = name.dropLast(1)
        return name.ifBlank { "upload" }
    }

    /**
     * A name for a file on this phone, taken from a remote name: nothing that could mean a folder,
     * nothing that is a control character, never empty.
     */
    fun localFileName(name: String): String {
        val cleaned = name.map { if (it == '/' || it == '\\' || it < ' ' || it == '\u007f') '_' else it }.joinToString("").trim()
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "download" else cleaned
    }
}
