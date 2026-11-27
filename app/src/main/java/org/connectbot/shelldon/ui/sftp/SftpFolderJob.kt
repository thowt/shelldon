/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.sftp

import androidx.annotation.StringRes
import org.connectbot.shelldon.sftp.FolderResult
import org.connectbot.shelldon.sftp.RemoteTree

enum class FolderPhase { SCANNING, CONFIRM, DOWNLOADING, DONE, FAILED, CANCELLED }

/** What a scan found, in numbers; the screen shows it before anything is copied. */
data class FolderSummary(
    val files: Int,
    val folders: Int,
    val bytes: Long,
    val linksSkipped: Int,
    val othersSkipped: Int,
    val unreadable: Int,
    val truncated: Boolean,
    val limit: Int,
) {
    companion object {
        fun of(tree: RemoteTree, limit: Int) = FolderSummary(
            files = tree.files.size,
            folders = tree.folders.size,
            bytes = tree.totalBytes,
            linksSkipped = tree.linksSkipped,
            othersSkipped = tree.othersSkipped,
            unreadable = tree.unreadable,
            truncated = tree.truncated,
            limit = limit,
        )
    }
}

/** One file of a folder download that could not be copied, with the reason as a message the screen can show. */
data class FolderFailureUi(val path: String, @param:StringRes val problem: Int, val detail: String)

/** The one folder download of this screen: from the scan, through the question, to the result. */
data class FolderJobUi(
    val name: String,
    val phase: FolderPhase,
    /** While scanning: how many files and folders have been found. */
    val found: Int = 0,
    val summary: FolderSummary? = null,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val result: FolderResult? = null,
    val firstFailure: FolderFailureUi? = null,
    @param:StringRes val problem: Int? = null,
    val problemDetail: String = "",
) {
    val active: Boolean get() = phase == FolderPhase.SCANNING || phase == FolderPhase.DOWNLOADING
}
