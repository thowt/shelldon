/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class LineEnding { LF, CRLF }

/** A text file as the editor shows it, and what is needed to write it back byte for byte the way it was. */
class EditableText(val text: String, val lineEnding: LineEnding, val bom: Boolean)

enum class EditRefusal {
    /** More than [SftpEditText.MAX_BYTES]. */
    TOO_LARGE,

    /** Contains zero bytes: a program, an archive, a picture. */
    BINARY,

    /** Not valid UTF-8; saving would change characters. */
    NOT_UTF8,

    /** Lone carriage returns or a mix of LF and CRLF; the editor would silently change them. */
    LINE_ENDINGS,
}

sealed interface TextDecoding {
    data class Text(val value: EditableText) : TextDecoding

    data class Refused(val reason: EditRefusal) : TextDecoding
}

/**
 * Turns the bytes of a remote file into text for the editor and back. Only a file that survives the round
 * trip unchanged is offered for editing: a file that cannot be written back exactly is refused rather than
 * quietly altered.
 */
object SftpEditText {
    /** Config files and scripts. A bigger file belongs in a real editor, after a download. */
    const val MAX_BYTES = 256 * 1024

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun decode(bytes: ByteArray): TextDecoding {
        if (bytes.size > MAX_BYTES) return TextDecoding.Refused(EditRefusal.TOO_LARGE)
        if (bytes.any { it == 0.toByte() }) return TextDecoding.Refused(EditRefusal.BINARY)
        val bom = bytes.size >= BOM.size && bytes.copyOfRange(0, BOM.size).contentEquals(BOM)
        val body = if (bom) bytes.copyOfRange(BOM.size, bytes.size) else bytes
        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (e: CharacterCodingException) {
            return TextDecoding.Refused(EditRefusal.NOT_UTF8)
        }
        val lineEnding = lineEndingOf(decoded) ?: return TextDecoding.Refused(EditRefusal.LINE_ENDINGS)
        val text = if (lineEnding == LineEnding.CRLF) decoded.replace("\r\n", "\n") else decoded
        return TextDecoding.Text(EditableText(text, lineEnding, bom))
    }

    /** [like] decides the line ending and the byte order mark. Line breaks pasted in the other style are made to match. */
    fun encode(text: String, like: EditableText): ByteArray {
        val unified = text.replace("\r\n", "\n")
        val body = (if (like.lineEnding == LineEnding.CRLF) unified.replace("\n", "\r\n") else unified).toByteArray(Charsets.UTF_8)
        return if (like.bom) BOM + body else body
    }

    /** LF if there is no carriage return, CRLF if every line break is one, null for anything else. */
    private fun lineEndingOf(text: String): LineEnding? {
        if ('\r' !in text) return LineEnding.LF
        var index = text.indexOf('\r')
        while (index >= 0) {
            if (index + 1 >= text.length || text[index + 1] != '\n') return null
            index = text.indexOf('\r', index + 2)
        }
        // Every carriage return is followed by a line feed; now every line feed must have one before it.
        index = text.indexOf('\n')
        while (index >= 0) {
            if (index == 0 || text[index - 1] != '\r') return null
            index = text.indexOf('\n', index + 1)
        }
        return LineEnding.CRLF
    }
}
