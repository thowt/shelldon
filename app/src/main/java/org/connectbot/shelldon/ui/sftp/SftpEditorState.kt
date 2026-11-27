/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.sftp

import androidx.annotation.StringRes

enum class EditorPhase { LOADING, EDITING, SAVING, REFUSED, FAILED }

/** The text editor of this screen: one remote file, from reading it to saving it. */
data class EditorUi(
    val name: String,
    val path: String,
    val phase: EditorPhase = EditorPhase.LOADING,
    val text: String = "",
    /** The text as it is on the server, as far as this editor knows. */
    val original: String = "",
    /** Why the file was refused or could not be read or saved. */
    @param:StringRes val problem: Int? = null,
    val problemDetail: String = "",
    /** The file changed on the server after it was opened; the user decides what to do. */
    val conflict: Boolean = false,
    /** Set when a save was cut short: where the complete copy of the new text is on the server. */
    val interruptedCopy: String? = null,
    /** True from a successful save until the next change of the text. */
    val saved: Boolean = false,
    /** Set after "save as a copy": the path of the copy. */
    val savedCopy: String? = null,
) {
    val dirty: Boolean get() = text != original
}
