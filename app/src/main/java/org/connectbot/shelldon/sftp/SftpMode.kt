/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

/**
 * The permission bits of a file or folder: read, write and execute for owner, group and others, plus
 * setuid, setgid and sticky. The file type bits that a server sends along with them are not part of it.
 */
data class SftpMode(val bits: Int) {
    init {
        require(bits in 0..ALL) { "Not a permission mode: $bits" }
    }

    fun has(bit: Int): Boolean = bits and bit == bit

    fun with(bit: Int, on: Boolean): SftpMode = SftpMode(if (on) bits or bit else bits and bit.inv())

    /** setuid, setgid and sticky, the bits that chmod's fourth octal digit holds. */
    val special: Int get() = bits and SPECIAL

    /** Anybody with an account on the machine may change the file. */
    val isWorldWritable: Boolean get() = has(OTHERS_WRITE)

    /** Runs with the owner's or the group's rights whoever starts it. */
    val isSetId: Boolean get() = has(SETUID) || has(SETGID)

    /** `644`, or `4755` when a special bit is set. */
    val octal: String
        get() {
            val digits = Integer.toOctalString(bits)
            return digits.padStart(if (special == 0) 3 else 4, '0')
        }

    /** `rw-r--r--`, with `s`, `S`, `t` and `T` where ls shows them. */
    val symbolic: String
        get() = buildString {
            append(if (has(OWNER_READ)) 'r' else '-')
            append(if (has(OWNER_WRITE)) 'w' else '-')
            append(execute(OWNER_EXECUTE, SETUID, 's'))
            append(if (has(GROUP_READ)) 'r' else '-')
            append(if (has(GROUP_WRITE)) 'w' else '-')
            append(execute(GROUP_EXECUTE, SETGID, 's'))
            append(if (has(OTHERS_READ)) 'r' else '-')
            append(if (has(OTHERS_WRITE)) 'w' else '-')
            append(execute(OTHERS_EXECUTE, STICKY, 't'))
        }

    private fun execute(execute: Int, special: Int, letter: Char): Char = when {
        has(special) && has(execute) -> letter
        has(special) -> letter.uppercaseChar()
        has(execute) -> 'x'
        else -> '-'
    }

    /** Execute added wherever read is set, so a folder with this mode can still be opened. */
    fun withFolderAccess(): SftpMode = SftpMode(bits or ((bits and READ_BITS) shr 2))

    companion object {
        const val SETUID = 0x800
        const val SETGID = 0x400
        const val STICKY = 0x200
        const val OWNER_READ = 0x100
        const val OWNER_WRITE = 0x80
        const val OWNER_EXECUTE = 0x40
        const val GROUP_READ = 0x20
        const val GROUP_WRITE = 0x10
        const val GROUP_EXECUTE = 0x8
        const val OTHERS_READ = 0x4
        const val OTHERS_WRITE = 0x2
        const val OTHERS_EXECUTE = 0x1

        const val SPECIAL = SETUID or SETGID or STICKY
        const val READ_BITS = OWNER_READ or GROUP_READ or OTHERS_READ
        const val ALL = 0xFFF

        /** From the mode of an SFTP attribute block, which also carries the file type; null if the server sent none. */
        fun of(permissions: Int?): SftpMode? = permissions?.let { SftpMode(it and ALL) }

        /** `644` or `0644` or `4755`: three or four octal digits, nothing else. */
        fun parseOctal(text: String): SftpMode? {
            val digits = text.trim()
            if (digits.length !in 3..4 || digits.any { it !in '0'..'7' }) return null
            return SftpMode(digits.toInt(8))
        }
    }
}
