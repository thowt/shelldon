/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class SftpModeTest {
    @Test
    fun octalHasThreeDigitsAndFourWhenASpecialBitIsSet() {
        assertThat(SftpMode(420).octal).isEqualTo("644")
        assertThat(SftpMode(0b111_101_101).octal).isEqualTo("755")
        assertThat(SftpMode(0).octal).isEqualTo("000")
        assertThat(SftpMode(0b000_000_111).octal).isEqualTo("007")
        assertThat(SftpMode(SftpMode.SETUID or 0b111_101_101).octal).isEqualTo("4755")
        assertThat(SftpMode(SftpMode.STICKY or 0b111_111_111).octal).isEqualTo("1777")
        assertThat(SftpMode(SftpMode.SETUID or SftpMode.SETGID or SftpMode.STICKY).octal).isEqualTo("7000")
    }

    @Test
    fun symbolicLooksLikeLs() {
        assertThat(SftpMode(0b110_100_100).symbolic).isEqualTo("rw-r--r--")
        assertThat(SftpMode(0b111_101_101).symbolic).isEqualTo("rwxr-xr-x")
        assertThat(SftpMode(0).symbolic).isEqualTo("---------")
        assertThat(SftpMode(SftpMode.SETUID or 0b111_101_101).symbolic).isEqualTo("rwsr-xr-x")
        assertThat(SftpMode(SftpMode.SETUID or 0b110_100_100).symbolic).isEqualTo("rwSr--r--")
        assertThat(SftpMode(SftpMode.SETGID or 0b111_101_101).symbolic).isEqualTo("rwxr-sr-x")
        assertThat(SftpMode(SftpMode.STICKY or 0b111_111_111).symbolic).isEqualTo("rwxrwxrwt")
        assertThat(SftpMode(SftpMode.STICKY or 0b111_111_110).symbolic).isEqualTo("rwxrwxrwT")
    }

    @Test
    fun theTypeBitsOfAnAttributeBlockAreLeftOut() {
        assertThat(SftpMode.of(0x81A4)).isEqualTo(SftpMode(420))
        assertThat(SftpMode.of(0x41ED)).isEqualTo(SftpMode(493))
        assertThat(SftpMode.of(0xA1FF)).isEqualTo(SftpMode(511))
        assertThat(SftpMode.of(null)).isNull()
    }

    @Test
    fun parsingTakesThreeOrFourOctalDigitsAndNothingElse() {
        assertThat(SftpMode.parseOctal("644")).isEqualTo(SftpMode(420))
        assertThat(SftpMode.parseOctal("0644")).isEqualTo(SftpMode(420))
        assertThat(SftpMode.parseOctal(" 755 ")).isEqualTo(SftpMode(493))
        assertThat(SftpMode.parseOctal("4755")).isEqualTo(SftpMode(SftpMode.SETUID or 493))
        assertThat(SftpMode.parseOctal("7777")).isEqualTo(SftpMode(SftpMode.ALL))
        for (bad in listOf("", "6", "64", "888", "64a", "07777", "-644", "+644", "6 4 4", "rwx")) {
            assertThat(SftpMode.parseOctal(bad)).describedAs("'%s'", bad).isNull()
        }
    }

    @Test
    fun parsingAndFormattingAgree() {
        for (bits in 0..SftpMode.ALL) {
            val mode = SftpMode(bits)
            assertThat(SftpMode.parseOctal(mode.octal)).describedAs("%s", mode.octal).isEqualTo(mode)
        }
    }

    @Test
    fun aBitCanBeSwitchedOnAndOff() {
        val mode = SftpMode(420)
        assertThat(mode.with(SftpMode.GROUP_WRITE, true).octal).isEqualTo("664")
        assertThat(mode.with(SftpMode.OWNER_WRITE, false).octal).isEqualTo("444")
        assertThat(mode.with(SftpMode.OWNER_WRITE, true)).isEqualTo(mode)
        assertThat(mode.has(SftpMode.OWNER_READ)).isTrue()
        assertThat(mode.has(SftpMode.OWNER_EXECUTE)).isFalse()
    }

    @Test
    fun folderAccessAddsExecuteWhereThereIsRead() {
        assertThat(SftpMode(0b110_100_100).withFolderAccess().octal).isEqualTo("755")
        assertThat(SftpMode(0b110_100_000).withFolderAccess().octal).isEqualTo("750")
        assertThat(SftpMode(0b110_000_000).withFolderAccess().octal).isEqualTo("700")
        assertThat(SftpMode(0b100_100_100).withFolderAccess().octal).isEqualTo("555")
        assertThat(SftpMode(0b010_010_010).withFolderAccess().octal).isEqualTo("222")
        assertThat(SftpMode(0).withFolderAccess().octal).isEqualTo("000")
        assertThat(SftpMode(0b111_111_111).withFolderAccess().octal).isEqualTo("777")
    }

    @Test
    fun theRiskyCombinationsAreNamed() {
        assertThat(SftpMode(0b110_100_110).isWorldWritable).isTrue()
        assertThat(SftpMode(0b110_100_100).isWorldWritable).isFalse()
        assertThat(SftpMode(SftpMode.SETUID or 0b111_101_101).isSetId).isTrue()
        assertThat(SftpMode(SftpMode.SETGID or 0b111_101_101).isSetId).isTrue()
        assertThat(SftpMode(SftpMode.STICKY or 0b111_111_111).isSetId).isFalse()
        assertThat(SftpMode(SftpMode.STICKY or 0b111_111_111).special).isEqualTo(SftpMode.STICKY)
    }

    @Test
    fun aValueOutsideTheModeBitsIsRefused() {
        for (bad in listOf(-1, 0x1000, 0x81A4)) {
            val refused = try {
                SftpMode(bad)
                false
            } catch (e: IllegalArgumentException) {
                true
            }
            assertThat(refused).describedAs("%s", bad).isTrue()
        }
    }
}
