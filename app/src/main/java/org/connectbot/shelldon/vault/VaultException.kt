/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.vault

/** Base type for everything the vault can refuse to do. Messages never contain secret material. */
open class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The vault is locked (or does not exist yet), so nothing can be sealed or opened. */
class VaultLockedException : VaultException("The vault is locked")

/**
 * Sealed data did not authenticate: it was damaged, edited, or belongs to a different record or
 * vault. The cause is deliberately not distinguished.
 */
class VaultDataException(message: String, cause: Throwable? = null) : VaultException(message, cause)
