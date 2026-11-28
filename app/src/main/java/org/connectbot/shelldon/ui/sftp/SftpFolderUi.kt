/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.sftp

import android.content.res.Resources
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.connectbot.R
import org.connectbot.shelldon.sftp.FolderResult
import org.connectbot.shelldon.sftp.SftpPaths

/** The question before a folder is copied: what is in it, what is left out, and that nothing is overwritten. */
@Composable
internal fun FolderConfirmDialog(
    job: FolderJobUi,
    onChoose: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val summary = job.summary ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shelldon_sftp_folder_title, SftpPaths.printable(job.name))) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.shelldon_sftp_folder_summary,
                        SftpPaths.printable(job.name),
                        pluralStringResource(R.plurals.shelldon_sftp_folder_files, summary.files, summary.files),
                        pluralStringResource(R.plurals.shelldon_sftp_folder_subfolders, summary.folders, summary.folders),
                        Formatter.formatShortFileSize(context, summary.bytes),
                    ),
                    modifier = Modifier.testTag("sftp_folder_summary"),
                )
                if (summary.files == 0) Text(stringResource(R.string.shelldon_sftp_folder_nothing))
                if (summary.linksSkipped > 0) {
                    Text(pluralStringResource(R.plurals.shelldon_sftp_folder_links_skipped, summary.linksSkipped, summary.linksSkipped), style = MaterialTheme.typography.bodySmall)
                }
                if (summary.othersSkipped > 0) {
                    Text(pluralStringResource(R.plurals.shelldon_sftp_folder_others_skipped, summary.othersSkipped, summary.othersSkipped), style = MaterialTheme.typography.bodySmall)
                }
                if (summary.unreadable > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.shelldon_sftp_folder_unreadable, summary.unreadable, summary.unreadable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("sftp_folder_unreadable"),
                    )
                }
                if (summary.truncated) {
                    Text(
                        text = stringResource(R.string.shelldon_sftp_folder_truncated, summary.limit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("sftp_folder_truncated"),
                    )
                }
                Text(
                    text = stringResource(R.string.shelldon_sftp_folder_how),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onChoose, enabled = summary.files > 0, modifier = Modifier.testTag("sftp_folder_choose")) {
                Text(stringResource(R.string.shelldon_sftp_folder_choose))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("sftp_folder_cancel")) { Text(stringResource(R.string.shelldon_cancel)) }
        },
    )
}

/** One line for the folder job: looking, copying with progress, or the result with what was and was not copied. */
@Composable
internal fun FolderCard(
    job: FolderJobUi,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("sftp_folder_card"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val title = when (job.phase) {
                    FolderPhase.SCANNING -> R.string.shelldon_sftp_folder_scanning
                    else -> R.string.shelldon_sftp_folder_downloading
                }
                Text(
                    text = stringResource(title, SftpPaths.printable(job.name)),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when (job.phase) {
                    FolderPhase.SCANNING -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = stringResource(R.string.shelldon_sftp_folder_found, job.found),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    FolderPhase.DOWNLOADING -> {
                        if (job.bytesTotal > 0) {
                            LinearProgressIndicator(progress = { (job.bytesDone.toFloat() / job.bytesTotal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            text = stringResource(
                                R.string.shelldon_sftp_folder_progress,
                                job.filesDone,
                                job.filesTotal,
                                Formatter.formatShortFileSize(context, job.bytesDone),
                                Formatter.formatShortFileSize(context, job.bytesTotal),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("sftp_folder_progress"),
                        )
                        Text(
                            text = stringResource(R.string.shelldon_sftp_folder_stop_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    FolderPhase.DONE -> {
                        val result = job.result
                        Text(
                            text = if (result == null) "" else resultLine(resources, result),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (result?.failures.isNullOrEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("sftp_folder_result"),
                        )
                        job.firstFailure?.let { first ->
                            Text(
                                text = resources.getString(
                                    R.string.shelldon_sftp_folder_first_failure,
                                    SftpPaths.printable(first.path),
                                    resources.getString(first.problem) + if (first.detail.isEmpty()) "" else " (${first.detail})",
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("sftp_folder_first_failure"),
                            )
                        }
                    }

                    FolderPhase.CANCELLED -> Text(
                        text = stringResource(R.string.shelldon_sftp_cancelled) + " · " + stringResource(R.string.shelldon_sftp_folder_stop_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    FolderPhase.FAILED -> Text(
                        text = buildString {
                            append(job.problem?.let { resources.getString(it) } ?: resources.getString(R.string.shelldon_sftp_problem_other))
                            if (job.problemDetail.isNotEmpty()) append(" (").append(job.problemDetail).append(')')
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    FolderPhase.CONFIRM -> Unit
                }
            }
            IconButton(onClick = if (job.active) onCancel else onDismiss, modifier = Modifier.testTag("sftp_folder_close")) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(if (job.active) R.string.shelldon_cancel else R.string.shelldon_sftp_dismiss),
                )
            }
        }
    }
}

/** "3 downloaded · 2 already there · 1 failed": only what happened, in the order of how much it matters. */
private fun resultLine(resources: Resources, result: FolderResult): String {
    val parts = buildList {
        if (result.downloaded > 0 || (result.alreadyThere == 0 && result.keptDifferent == 0 && result.failures.isEmpty())) {
            add(resources.getString(R.string.shelldon_sftp_folder_result_downloaded, result.downloaded))
        }
        if (result.alreadyThere > 0) add(resources.getString(R.string.shelldon_sftp_folder_result_there, result.alreadyThere))
        if (result.keptDifferent > 0) add(resources.getString(R.string.shelldon_sftp_folder_result_kept, result.keptDifferent))
        if (result.renamed > 0) add(resources.getString(R.string.shelldon_sftp_folder_result_renamed, result.renamed))
        if (result.failures.isNotEmpty()) add(resources.getString(R.string.shelldon_sftp_folder_result_failed, result.failures.size))
    }.joinToString(" · ")
    val nothingNew = result.downloaded == 0 && result.failures.isEmpty()
    return resources.getString(if (nothingNew) R.string.shelldon_sftp_folder_nothing_new else R.string.shelldon_sftp_folder_done, parts)
}
