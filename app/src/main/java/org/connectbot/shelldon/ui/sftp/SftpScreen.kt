/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.sftp

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.connectbot.R
import org.connectbot.shelldon.sftp.SftpItem
import org.connectbot.shelldon.sftp.SftpKind
import org.connectbot.shelldon.sftp.SftpMode
import org.connectbot.shelldon.sftp.SftpPaths
import org.connectbot.shelldon.ui.shell.shelldonBarHeight
import org.connectbot.ui.LocalTerminalManager

/**
 * Files on the server of an open session, over the same SSH connection. Tap a folder to open it, a
 * file to download it; the arrow button at the top goes back to the terminal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SftpViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val terminalManager = LocalTerminalManager.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }

    var menu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SftpItem?>(null) }
    var deleting by remember { mutableStateOf<SftpItem?>(null) }
    var permissions by remember { mutableStateOf<SftpItem?>(null) }
    var leaving by remember { mutableStateOf(false) }
    // Kept across a rotation or a process restart while the file picker is open.
    var downloadPath by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(terminalManager) {
        terminalManager?.let { viewModel.setTerminalManager(it) }
    }
    LaunchedEffect(notice) {
        notice?.let { n ->
            val text = buildString {
                append(resources.getString(n.what, n.subject))
                append(": ")
                append(resources.getString(n.problem))
                if (n.detail.isNotEmpty()) append(" (").append(n.detail).append(')')
            }
            snackbar.showSnackbar(text)
            viewModel.clearNotice()
        }
    }

    val pickDestination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val path = downloadPath
        downloadPath = null
        val item = state.items.firstOrNull { it.path == path }
        if (uri != null && item != null) viewModel.download(item, uri)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.upload(uris)
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.downloadFolder(uri) else viewModel.cancelFolder()
    }

    val busy = state.transfers.any { it.status == TransferStatus.RUNNING || it.status == TransferStatus.QUEUED } || state.folder?.active == true
    val requestLeave = {
        if (busy) leaving = true else onNavigateBack()
    }
    BackHandler { if (!viewModel.up()) requestLeave() }

    fun startDownload(item: SftpItem) {
        downloadPath = item.path
        pickDestination.launch(SftpPaths.localFileName(item.name))
    }

    val ready = !state.connecting && state.unavailable == null

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                expandedHeight = shelldonBarHeight(),
                title = {
                    Column {
                        Text(
                            text = state.hostName.ifBlank { stringResource(R.string.shelldon_sftp_title) },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (ready) {
                            Text(
                                text = SftpPaths.printable(state.path),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.StartEllipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { requestLeave() }, modifier = Modifier.testTag("sftp_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.shelldon_sftp_back))
                    }
                },
                actions = {
                    if (ready) {
                        IconButton(onClick = viewModel::refresh, modifier = Modifier.testTag("sftp_refresh")) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.shelldon_sftp_refresh))
                        }
                        IconButton(onClick = viewModel::toggleHidden, modifier = Modifier.testTag("sftp_hidden")) {
                            Icon(
                                imageVector = if (state.showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = stringResource(if (state.showHidden) R.string.shelldon_sftp_hide_hidden else R.string.shelldon_sftp_show_hidden),
                            )
                        }
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.shelldon_sftp_more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.shelldon_sftp_new_folder)) },
                                leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    newFolder = true
                                },
                                modifier = Modifier.testTag("sftp_new_folder"),
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (ready) {
                ExtendedFloatingActionButton(
                    onClick = { pickFiles.launch(arrayOf("*/*")) },
                    icon = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                    text = { Text(stringResource(R.string.shelldon_sftp_upload)) },
                    modifier = Modifier.testTag("sftp_upload"),
                )
            }
        },
        bottomBar = {
            val folderCard = state.folder?.takeIf { it.phase != FolderPhase.CONFIRM }
            if (state.transfers.isNotEmpty() || folderCard != null) {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    folderCard?.let { FolderCard(job = it, onCancel = viewModel::cancelFolder, onDismiss = viewModel::dismissFolder) }
                    state.transfers.forEach { transfer ->
                        TransferCard(
                            transfer = transfer,
                            onCancel = { viewModel.cancelTransfer(transfer.id) },
                            onDismiss = { viewModel.dismissTransfer(transfer.id) },
                        )
                    }
                    if (state.transfers.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.shelldon_sftp_transfer_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) { padding ->
        when {
            state.connecting -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }

            state.unavailable != null -> UnavailableMessage(
                reason = state.unavailable!!,
                detail = state.unavailableDetail,
                onBack = onNavigateBack,
                modifier = Modifier.padding(padding),
            )

            else -> Column(modifier = Modifier.padding(padding)) {
                Breadcrumbs(path = state.path, onNavigate = viewModel::navigate)
                if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                val shown = state.visibleItems
                if (shown.isEmpty() && !state.loading) {
                    Text(
                        text = if (state.hiddenCount > 0) {
                            stringResource(R.string.shelldon_sftp_empty_hidden, state.hiddenCount)
                        } else {
                            stringResource(R.string.shelldon_sftp_empty)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(20.dp)
                            .testTag("sftp_empty"),
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    items(shown, key = { it.path }) { item ->
                        SftpRow(
                            item = item,
                            onOpen = { viewModel.open(item) },
                            onDownload = { startDownload(item) },
                            onRename = { renaming = item },
                            onDelete = { deleting = item },
                            onDownloadFolder = if (state.folder?.active == true) null else ({ viewModel.scanFolder(item) }),
                            onPermissions = { permissions = item },
                        )
                    }
                }
            }
        }
    }

    if (newFolder) {
        NameDialog(
            title = stringResource(R.string.shelldon_sftp_new_folder),
            label = stringResource(R.string.shelldon_sftp_folder_name),
            initial = "",
            confirmLabel = stringResource(R.string.shelldon_sftp_create),
            onConfirm = {
                newFolder = false
                viewModel.createFolder(it)
            },
            onDismiss = { newFolder = false },
        )
    }

    renaming?.let { item ->
        NameDialog(
            title = stringResource(R.string.shelldon_sftp_rename),
            label = stringResource(R.string.shelldon_sftp_new_name),
            initial = item.name,
            confirmLabel = stringResource(R.string.shelldon_sftp_rename),
            onConfirm = {
                renaming = null
                viewModel.rename(item, it)
            },
            onDismiss = { renaming = null },
        )
    }

    permissions?.let { item ->
        val current = SftpMode.of(item.permissions)
        if (current != null) {
            PermissionsDialog(
                item = item,
                initial = current,
                onApply = { mode, recursive ->
                    permissions = null
                    viewModel.changePermissions(item, mode, recursive)
                },
                onDismiss = { permissions = null },
            )
        }
    }

    deleting?.let { item ->
        val body = when (item.kind) {
            SftpKind.FOLDER -> R.string.shelldon_sftp_delete_folder_body
            SftpKind.LINK -> R.string.shelldon_sftp_delete_link_body
            else -> R.string.shelldon_sftp_delete_file_body
        }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.shelldon_sftp_delete_title)) },
            text = { Text(stringResource(body, SftpPaths.printable(item.name))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        viewModel.delete(item)
                    },
                    modifier = Modifier.testTag("sftp_delete_confirm"),
                ) {
                    Text(stringResource(R.string.shelldon_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.shelldon_cancel)) }
            },
        )
    }

    state.folder?.takeIf { it.phase == FolderPhase.CONFIRM }?.let { job ->
        FolderConfirmDialog(job = job, onChoose = { pickFolder.launch(null) }, onDismiss = viewModel::cancelFolder)
    }

    state.conflict?.let { conflict ->
        val shownNames = conflict.names.take(5).joinToString(", ") { SftpPaths.printable(it) } +
            if (conflict.names.size > 5) " …" else ""
        AlertDialog(
            onDismissRequest = viewModel::dismissConflict,
            title = { Text(stringResource(R.string.shelldon_sftp_conflict_title)) },
            text = { Text(stringResource(R.string.shelldon_sftp_conflict_body, shownNames)) },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveConflict(replace = true) }, modifier = Modifier.testTag("sftp_conflict_replace")) {
                    Text(stringResource(R.string.shelldon_sftp_replace), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.resolveConflict(replace = false) }, modifier = Modifier.testTag("sftp_conflict_skip")) {
                        Text(stringResource(R.string.shelldon_sftp_skip))
                    }
                    TextButton(onClick = viewModel::dismissConflict) { Text(stringResource(R.string.shelldon_cancel)) }
                }
            },
        )
    }

    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.shelldon_sftp_leave_title)) },
            text = { Text(stringResource(R.string.shelldon_sftp_leave_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        leaving = false
                        onNavigateBack()
                    },
                    modifier = Modifier.testTag("sftp_leave_confirm"),
                ) {
                    Text(stringResource(R.string.shelldon_sftp_leave_stop))
                }
            },
            dismissButton = {
                TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.shelldon_sftp_leave_stay)) }
            },
        )
    }
}

@Composable
private fun Breadcrumbs(path: String, onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    val crumbs = remember(path) { SftpPaths.breadcrumbs(path) }
    val listState = rememberLazyListState()
    LaunchedEffect(crumbs) { listState.scrollToItem(crumbs.lastIndex) }
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(crumbs, key = { _, crumb -> crumb.path }) { index, crumb ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                TextButton(
                    onClick = { onNavigate(crumb.path) },
                    enabled = index != crumbs.lastIndex,
                    modifier = Modifier.testTag("sftp_crumb_${crumb.path}"),
                ) {
                    Text(SftpPaths.printable(crumb.label), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun UnavailableMessage(
    reason: SftpUnavailable,
    detail: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(
                when (reason) {
                    SftpUnavailable.NO_SESSION -> R.string.shelldon_sftp_unavailable_no_session
                    SftpUnavailable.NOT_SUPPORTED -> R.string.shelldon_sftp_unavailable_not_supported
                    SftpUnavailable.LOST -> R.string.shelldon_sftp_unavailable_lost
                    SftpUnavailable.OPEN_FAILED -> R.string.shelldon_sftp_unavailable_open_failed
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("sftp_unavailable"),
        )
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.shelldon_sftp_back))
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val valid = SftpPaths.isValidNewName(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(label) },
                    isError = name.isNotEmpty() && !valid,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sftp_name"),
                )
                if (name.isNotEmpty() && !valid) {
                    Text(
                        text = stringResource(R.string.shelldon_sftp_problem_name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = valid, modifier = Modifier.testTag("sftp_name_confirm")) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.shelldon_cancel)) }
        },
    )
}
