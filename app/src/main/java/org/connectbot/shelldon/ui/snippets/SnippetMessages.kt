/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.snippets

import androidx.annotation.StringRes
import org.connectbot.R
import org.connectbot.shelldon.snippets.SnippetRejection

@StringRes
internal fun SnippetRejection.messageRes(): Int = when (this) {
    SnippetRejection.BLANK_NAME -> R.string.shelldon_err_blank_name
    SnippetRejection.NAME_TOO_LONG -> R.string.shelldon_err_name_too_long
    SnippetRejection.NAME_TAKEN -> R.string.shelldon_err_name_taken
    SnippetRejection.BLANK_TEXT -> R.string.shelldon_err_blank_text
    SnippetRejection.TOO_LARGE -> R.string.shelldon_err_text_too_large
    SnippetRejection.NOT_PLAIN -> R.string.shelldon_err_not_plain
    SnippetRejection.NOT_FOUND -> R.string.shelldon_err_not_found
}
