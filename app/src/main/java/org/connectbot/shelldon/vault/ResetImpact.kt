/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.vault

/**
 * What a vault reset removes, counted without opening anything: [passwords] saved host passwords,
 * [keys] private keys sealed in the vault, and whether [biometric] unlock is set up. Hosts, folders,
 * tags and identities are not part of it, they stay.
 */
data class ResetImpact(
    val passwords: Int,
    val keys: Int,
    val biometric: Boolean,
) {
    /** True when the reset throws away secrets the person may not have a copy of. */
    val losesSecrets: Boolean get() = passwords > 0 || keys > 0
}
