/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.vault

/**
 * Argon2id cost parameters, stored in the vault header next to the salt.
 *
 * The header is read from disk, so the values are range-checked before they reach the KDF: a
 * damaged or hostile file must not be able to ask for gigabytes of memory.
 */
data class KdfParams(
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    init {
        require(memoryKib in MIN_MEMORY_KIB..MAX_MEMORY_KIB) { "Argon2 memory out of range: $memoryKib KiB" }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Argon2 iterations out of range: $iterations" }
        require(parallelism in MIN_PARALLELISM..MAX_PARALLELISM) { "Argon2 parallelism out of range: $parallelism" }
    }

    companion object {
        const val MIN_MEMORY_KIB = 8 * 1024
        const val MAX_MEMORY_KIB = 256 * 1024
        const val MIN_ITERATIONS = 1
        const val MAX_ITERATIONS = 10
        const val MIN_PARALLELISM = 1
        const val MAX_PARALLELISM = 8

        /**
         * 64 MiB, 3 passes, one lane. This is the RFC 9106 "memory-constrained" recommendation, a
         * good fit for a phone: about a quarter of a second on a desktop JVM, well under two
         * seconds on a phone. BouncyCastle's Argon2 runs lanes one after the other, so more than
         * one lane would only cost time.
         */
        val DEFAULT = KdfParams(memoryKib = 64 * 1024, iterations = 3, parallelism = 1)
    }
}
