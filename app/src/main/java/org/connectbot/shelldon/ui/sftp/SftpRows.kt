/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.sftp

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.connectbot.R
import org.connectbot.shelldon.sftp.SftpItem
import org.connectbot.shelldon.sftp.SftpItems
import org.connectbot.shelldon.sftp.SftpKind
import org.connectbot.shelldon.sftp.SftpPaths

@Composable
internal fun SftpRow(
    item: SftpItem,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val details = buildList {
        if (item.kind == SftpKind.FILE) item.size?.let { add(Formatter.formatShortFileSize(context, it)) }
        item.modified?.let {
            add(DateUtils.formatDateTime(context, it * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH))
        }
        SftpItems.permissionString(item)?.let { add(it) }
    }.joinToString(" · ")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                onClick = {
                    if (item.opensAsFolder) {
                        onOpen()
                    } else if (item.kind == SftpKind.FILE) {
                        onDownload()
                    }
                },
            )
            .padding(start = 20.dp, top = 6.dp, bottom = 6.dp, end = 4.dp)
            .testTag("sftp_row_${item.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                item.opensAsFolder -> Icons.Default.Folder
                item.kind == SftpKind.LINK -> Icons.Default.Link
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            tint = if (item.opensAsFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
        ) {
            Text(
                text = SftpPaths.printable(item.name),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (details.isNotEmpty()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("sftp_menu_${item.name}")) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.shelldon_sftp_more))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (item.kind == SftpKind.FILE) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.shelldon_sftp_download)) },
                    leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                    onClick = {
                        menu = false
                        onDownload()
                    },
                )
            }
            if (item.kind == SftpKind.FILE && onEdit != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.shelldon_sftp_edit)) },
                    leadingIcon = { Icon(Icons.Default.Create, contentDescription = null) },
                    onClick = {
                        menu = false
                        onEdit()
                    },
                    modifier = Modifier.testTag("sftp_edit_${item.name}"),
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.shelldon_sftp_rename)) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    menu = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.shelldon_delete)) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

/** One line per transfer: name, progress, and a button to cancel it or to dismiss the result. */
@Composable
internal fun TransferCard(
    transfer: TransferUi,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val active = transfer.status == TransferStatus.RUNNING || transfer.status == TransferStatus.QUEUED
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("sftp_transfer_${transfer.name}"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(if (transfer.upload) R.string.shelldon_sftp_uploading else R.string.shelldon_sftp_downloading, transfer.name),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when (transfer.status) {
                    TransferStatus.QUEUED -> Text(
                        text = stringResource(R.string.shelldon_sftp_waiting),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    TransferStatus.RUNNING -> {
                        val total = transfer.total
                        if (total != null && total > 0) {
                            LinearProgressIndicator(progress = { (transfer.done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            text = Formatter.formatShortFileSize(context, transfer.done) + (total?.let { " / " + Formatter.formatShortFileSize(context, it) } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    TransferStatus.DONE -> Text(
                        text = stringResource(R.string.shelldon_sftp_done),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    TransferStatus.CANCELLED -> Text(
                        text = stringResource(R.string.shelldon_sftp_cancelled),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    TransferStatus.FAILED -> Text(
                        text = buildString {
                            append(transfer.problem?.let { resources.getString(it) } ?: resources.getString(R.string.shelldon_sftp_problem_other))
                            if (transfer.problemDetail.isNotEmpty()) append(" (").append(transfer.problemDetail).append(')')
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = if (active) onCancel else onDismiss, modifier = Modifier.testTag("sftp_transfer_close_${transfer.name}")) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(if (active) R.string.shelldon_cancel else R.string.shelldon_sftp_dismiss),
                )
            }
        }
    }
}
