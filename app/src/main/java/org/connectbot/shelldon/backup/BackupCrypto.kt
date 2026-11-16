/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.backup

import org.connectbot.shelldon.vault.KdfParams
import org.connectbot.shelldon.vault.VaultCrypto
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom

/** Why a backup file could not be opened. */
enum class BackupProblem {
    /** Not a Shelldon backup file at all. */
    NOT_A_BACKUP,

    /** Written by a newer version of the format than this app understands. */
    UNSUPPORTED_VERSION,

    /** The password is wrong or the file was changed. These cannot be told apart, by design. */
    WRONG_PASSWORD_OR_DAMAGED,
}

class BackupException(val problem: BackupProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/**
 * The file container for encrypted backups. A backup has its own password, independent of the
 * master password, so it can be restored on a phone that has never seen this vault.
 *
 * Layout, big-endian:
 * ```
 *   0  magic "SHBK"
 *   4  format version (1)
 *   5  Argon2id memory KiB (4), iterations (4), parallelism (4)
 *  17  salt (16)
 *  33  nonce (12)
 *  45  AES-256-GCM ciphertext and tag
 * ```
 * The first 45 bytes are authenticated as associated data, so none of them can be altered.
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'H'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION: Byte = 1
    private const val SALT_BYTES = 16
    private const val HEADER_BYTES = 4 + 1 + 12 + SALT_BYTES + VaultCrypto.NONCE_BYTES

    /** Refuse anything larger before allocating for it. Real backups are a few hundred kilobytes. */
    const val MAX_FILE_BYTES = 64 * 1024 * 1024

    const val MIN_PASSWORD_LENGTH = 8

    /** 64 MiB, 4 passes: a little costlier than the vault, because a backup file may sit in cloud storage. */
    val DEFAULT_PARAMS = KdfParams(memoryKib = 64 * 1024, iterations = 4, parallelism = 1)

    fun encrypt(plaintext: ByteArray, password: String, params: KdfParams = DEFAULT_PARAMS, random: SecureRandom = SecureRandom()): ByteArray {
        require(password.length >= MIN_PASSWORD_LENGTH) { "Backup password is too short" }
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val nonce = ByteArray(VaultCrypto.NONCE_BYTES).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC)
            .put(VERSION)
            .putInt(params.memoryKib)
            .putInt(params.iterations)
            .putInt(params.parallelism)
            .put(salt)
            .put(nonce)
            .array()
        val key = VaultCrypto.deriveKey(password, salt, params)
        try {
            return header + VaultCrypto.encrypt(key, nonce, header, plaintext)
        } finally {
            key.fill(0)
        }
    }

    /** @throws BackupException if this is not a backup, is too new, or the password is wrong or the data was changed */
    fun decrypt(file: ByteArray, password: String): ByteArray {
        if (file.size < HEADER_BYTES + VaultCrypto.TAG_BYTES || file.size > MAX_FILE_BYTES) throw BackupException(BackupProblem.NOT_A_BACKUP)
        val buffer = ByteBuffer.wrap(file)
        val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
        if (!magic.contentEquals(MAGIC)) throw BackupException(BackupProblem.NOT_A_BACKUP)
        if (buffer.get() != VERSION) throw BackupException(BackupProblem.UNSUPPORTED_VERSION)
        val params = try {
            KdfParams(memoryKib = buffer.getInt(), iterations = buffer.getInt(), parallelism = buffer.getInt())
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupProblem.NOT_A_BACKUP, e)
        }
        val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
        val nonce = ByteArray(VaultCrypto.NONCE_BYTES).also { buffer.get(it) }
        val key = VaultCrypto.deriveKey(password, salt, params)
        try {
            return VaultCrypto.decrypt(key, nonce, file.copyOf(HEADER_BYTES), file.copyOfRange(HEADER_BYTES, file.size))
        } catch (e: GeneralSecurityException) {
            throw BackupException(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, e)
        } finally {
            key.fill(0)
        }
    }

    /** Cheap check, without a password, that [file] starts like a Shelldon backup. */
    fun looksLikeBackup(file: ByteArray): Boolean = file.size >= MAGIC.size && file.copyOf(MAGIC.size).contentEquals(MAGIC)
}
