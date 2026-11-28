/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.Random

class SftpEditTextTest {
    private fun text(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    private fun decoded(bytes: ByteArray): EditableText = (SftpEditText.decode(bytes) as TextDecoding.Text).value

    private fun refusal(bytes: ByteArray): EditRefusal = (SftpEditText.decode(bytes) as TextDecoding.Refused).reason

    @Test
    fun plainTextWithLfIsShownAsItIs() {
        val result = decoded("PermitRootLogin no\nPort 22\n".toByteArray())

        assertThat(result.text).isEqualTo("PermitRootLogin no\nPort 22\n")
        assertThat(result.lineEnding).isEqualTo(LineEnding.LF)
        assertThat(result.bom).isFalse()
    }

    @Test
    fun crlfFilesAreShownWithLfAndWrittenBackWithCrlf() {
        val bytes = "one\r\ntwo\r\n".toByteArray()

        val result = decoded(bytes)

        assertThat(result.text).isEqualTo("one\ntwo\n")
        assertThat(result.lineEnding).isEqualTo(LineEnding.CRLF)
        assertThat(SftpEditText.encode("one\ntwo\nthree\n", result)).isEqualTo("one\r\ntwo\r\nthree\r\n".toByteArray())
    }

    @Test
    fun aByteOrderMarkIsKeptOutOfTheTextAndPutBack() {
        val bytes = text(0xEF, 0xBB, 0xBF) + "hello\n".toByteArray()

        val result = decoded(bytes)

        assertThat(result.text).isEqualTo("hello\n")
        assertThat(result.bom).isTrue()
        assertThat(SftpEditText.encode(result.text, result)).isEqualTo(bytes)
    }

    @Test
    fun anUntouchedFileComesBackIdentical() {
        val samples = listOf(
            "".toByteArray(),
            "\n".toByteArray(),
            "no newline at the end".toByteArray(),
            "café 中文 😀\n".toByteArray(),
            "a\r\nb\r\n\r\n".toByteArray(),
            "tab\there\n".toByteArray(),
            text(0xEF, 0xBB, 0xBF) + "x\r\ny".toByteArray(),
        )
        for (bytes in samples) {
            assertThat(SftpEditText.encode(decoded(bytes).text, decoded(bytes))).isEqualTo(bytes)
        }
    }

    @Test
    fun randomTextRoundTripsInBothLineEndingStyles() {
        val random = Random(11)
        val pieces = listOf("a", "b", "c", " ", "\t", "\n", "\u00e9", "\u4e2d", "\ud83d\ude00", "#", "/")
        repeat(200) { round ->
            val source = buildString { repeat(random.nextInt(60)) { append(pieces[random.nextInt(pieces.size)]) } }
            val bytes = (if (round % 2 == 0) source else source.replace("\n", "\r\n")).toByteArray()
            assertThat(SftpEditText.encode(decoded(bytes).text, decoded(bytes))).isEqualTo(bytes)
        }
    }

    @Test
    fun aFileWithZeroBytesIsRefusedAsBinary() {
        assertThat(refusal(text(0x7F, 0x45, 0x4C, 0x46, 0, 1, 2))).isEqualTo(EditRefusal.BINARY)
    }

    @Test
    fun bytesThatAreNotUtf8AreRefusedInsteadOfBeingReplaced() {
        // Latin-1 "café": the lone 0xE9 would become U+FFFD and be saved as three other bytes.
        assertThat(refusal(text(0x63, 0x61, 0x66, 0xE9))).isEqualTo(EditRefusal.NOT_UTF8)
    }

    @Test
    fun aLoneCarriageReturnIsRefused() {
        assertThat(refusal("a\rb\n".toByteArray())).isEqualTo(EditRefusal.LINE_ENDINGS)
        assertThat(refusal("a\r".toByteArray())).isEqualTo(EditRefusal.LINE_ENDINGS)
    }

    @Test
    fun aMixOfLfAndCrlfIsRefused() {
        assertThat(refusal("a\r\nb\nc\r\n".toByteArray())).isEqualTo(EditRefusal.LINE_ENDINGS)
        assertThat(refusal("a\nb\r\n".toByteArray())).isEqualTo(EditRefusal.LINE_ENDINGS)
    }

    @Test
    fun aFileOverTheLimitIsRefusedWithoutBeingRead() {
        assertThat(refusal(ByteArray(SftpEditText.MAX_BYTES + 1) { 'a'.code.toByte() })).isEqualTo(EditRefusal.TOO_LARGE)
        assertThat(SftpEditText.decode(ByteArray(SftpEditText.MAX_BYTES) { 'a'.code.toByte() })).isInstanceOf(TextDecoding.Text::class.java)
    }

    @Test
    fun lineBreaksPastedInTheOtherStyleAreMadeToMatch() {
        val lf = decoded("a\n".toByteArray())
        val crlf = decoded("a\r\n".toByteArray())

        assertThat(SftpEditText.encode("a\r\nb\r\n", lf)).isEqualTo("a\nb\n".toByteArray())
        assertThat(SftpEditText.encode("a\r\nb\n", crlf)).isEqualTo("a\r\nb\r\n".toByteArray())
    }
}
