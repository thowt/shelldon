/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui

import androidx.annotation.StringRes
import org.connectbot.R
import org.connectbot.shelldon.data.groups.Rejection

/** The message shown when a group, tag or identity change is refused. */
@StringRes
internal fun Rejection.messageRes(): Int = when (this) {
    Rejection.BLANK_NAME -> R.string.shelldon_err_blank_name
    Rejection.NAME_TOO_LONG -> R.string.shelldon_err_name_too_long
    Rejection.NAME_TAKEN -> R.string.shelldon_err_name_taken
    Rejection.BLANK_USERNAME -> R.string.shelldon_err_blank_username
    Rejection.NOT_FOUND -> R.string.shelldon_err_not_found
    Rejection.WOULD_LOOP -> R.string.shelldon_err_would_loop
    Rejection.TOO_DEEP -> R.string.shelldon_err_too_deep
    Rejection.OTHER_REPOSITORY -> R.string.shelldon_err_other_repository
}
