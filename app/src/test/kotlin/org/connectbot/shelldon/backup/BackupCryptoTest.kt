/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.backup

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.connectbot.shelldon.vault.KdfParams
import org.junit.Test

class BackupCryptoTest {
    private val fast = KdfParams(memoryKib = 8 * 1024, iterations = 1, parallelism = 1)
    private val password = "backup passphrase"
    private val payload = """{"hosts":[{"nickname":"router"}],"secret":"hunter2"}""".toByteArray()

    private fun problemOf(block: () -> Unit): BackupProblem = (catchThrowable(block) as BackupException).problem

    private fun catchThrowable(block: () -> Unit): Throwable? = try {
        block()
        null
    } catch (e: Throwable) {
        e
    }

    @Test
    fun roundTrips() {
        val file = BackupCrypto.encrypt(payload, password, fast)
        assertThat(BackupCrypto.looksLikeBackup(file)).isTrue()
        assertThat(BackupCrypto.decrypt(file, password)).isEqualTo(payload)
    }

    @Test
    fun theFileDoesNotContainThePlaintext() {
        val file = BackupCrypto.encrypt(payload, password, fast)
        assertThat(String(file, Charsets.ISO_8859_1)).doesNotContain("hunter2").doesNotContain("router")
    }

    @Test
    fun everyBackupIsDifferent() {
        assertThat(BackupCrypto.encrypt(payload, password, fast)).isNotEqualTo(BackupCrypto.encrypt(payload, password, fast))
    }

    @Test
    fun wrongPasswordIsRefused() {
        val file = BackupCrypto.encrypt(payload, password, fast)
        assertThat(problemOf { BackupCrypto.decrypt(file, "another passphrase") }).isEqualTo(BackupProblem.WRONG_PASSWORD_OR_DAMAGED)
    }

    @Test
    fun anyChangedByteIsRefused() {
        val file = BackupCrypto.encrypt(payload, password, fast)
        // version, KDF cost, salt, nonce, ciphertext, last tag byte
        for (index in listOf(12, 20, 40, 50, file.size / 2 + 20, file.size - 1)) {
            val damaged = file.copyOf().also { it[index] = (it[index].toInt() xor 2).toByte() }
            assertThat(problemOf { BackupCrypto.decrypt(damaged, password) }).describedAs("byte $index").isEqualTo(BackupProblem.WRONG_PASSWORD_OR_DAMAGED)
        }
    }

    @Test
    fun otherFilesAreNotBackups() {
        assertThat(problemOf { BackupCrypto.decrypt("""{"hosts":[]}""".toByteArray(), password) }).isEqualTo(BackupProblem.NOT_A_BACKUP)
        assertThat(problemOf { BackupCrypto.decrypt(ByteArray(0), password) }).isEqualTo(BackupProblem.NOT_A_BACKUP)
        assertThat(BackupCrypto.looksLikeBackup("""{"hosts":[]}""".toByteArray())).isFalse()
    }

    @Test
    fun newerVersionsAreReportedAsSuch() {
        val file = BackupCrypto.encrypt(payload, password, fast).also { it[4] = 2 }
        assertThat(problemOf { BackupCrypto.decrypt(file, password) }).isEqualTo(BackupProblem.UNSUPPORTED_VERSION)
    }

    @Test
    fun absurdCostParametersAreRefusedBeforeRunningTheKdf() {
        val file = BackupCrypto.encrypt(payload, password, fast).also {
            it[5] = 0x7f
            it[6] = 0xff.toByte()
            it[7] = 0xff.toByte()
            it[8] = 0xff.toByte()
        }
        assertThat(problemOf { BackupCrypto.decrypt(file, password) }).isEqualTo(BackupProblem.NOT_A_BACKUP)
    }

    @Test
    fun shortPasswordsAreNotAccepted() {
        assertThatThrownBy { BackupCrypto.encrypt(payload, "short", fast) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
