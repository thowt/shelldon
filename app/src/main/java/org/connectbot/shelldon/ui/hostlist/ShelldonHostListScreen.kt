/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.hostlist

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.R
import org.connectbot.data.JsonImportReader
import org.connectbot.data.JsonImportTooLargeException
import org.connectbot.data.entity.Host
import org.connectbot.ui.LocalTerminalManager
import org.connectbot.ui.components.ShortcutCustomizationDialog
import org.connectbot.ui.screens.hostlist.HostListViewModel
import org.connectbot.ui.screens.hostlist.StartupKeyPasswordDialog
import org.connectbot.util.IconStyle
import java.io.IOException

/**
 * Stateful Hosts screen: wires [HostListViewModel] to [ShelldonHostListContent] and handles the
 * file pickers, toasts, key prompts and quick connect that the content does not own.
 */
@Composable
fun ShelldonHostListScreen(
    onNavigateToConsole: (Host) -> Unit,
    onNavigateToEditHost: (Host?) -> Unit,
    onNavigateToPortForwards: (Host) -> Unit,
    onNavigateToProfiles: () -> Unit,
    onNavigateToHelp: () -> Unit,
    modifier: Modifier = Modifier,
    makingShortcut: Boolean = false,
    onSelectShortcut: (Host, String?, IconStyle) -> Unit = { _, _, _ -> },
    viewModel: HostListViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val terminalManager = LocalTerminalManager.current

    LaunchedEffect(terminalManager) {
        terminalManager?.let { viewModel.setTerminalManager(it) }
    }

    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val json = uiState.exportedJson
        if (uri != null && json != null) {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(json.toByteArray())
                    }
                    uiState.exportResult?.let { exportResult ->
                        Toast.makeText(
                            context,
                            resources.getString(
                                R.string.export_hosts_success,
                                exportResult.hostCount,
                                exportResult.profileCount,
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        resources.getString(R.string.export_hosts_failed, e.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                viewModel.clearExportedJson()
            }
        } else {
            viewModel.clearExportedJson()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val jsonString = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use(JsonImportReader::read)
                            ?: throw IOException(resources.getString(R.string.import_file_unavailable))
                    }
                    viewModel.importHosts(jsonString)
                } catch (e: JsonImportTooLargeException) {
                    Toast.makeText(context, R.string.import_json_file_too_large, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        resources.getString(R.string.import_hosts_failed, e.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { errorMessage ->
            Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    // Open the file picker as soon as the JSON to export is ready.
    LaunchedEffect(uiState.exportedJson) {
        if (uiState.exportedJson != null) {
            exportLauncher.launch(resources.getString(R.string.export_hosts_filename))
        }
    }

    LaunchedEffect(uiState.importResult) {
        uiState.importResult?.let { result ->
            Toast.makeText(
                context,
                resources.getString(
                    R.string.import_hosts_success,
                    result.hostsImported,
                    result.hostsSkipped,
                    result.profilesImported,
                    result.profilesSkipped,
                ),
                Toast.LENGTH_SHORT,
            ).show()
            viewModel.clearImportResult()
        }
    }

    var shortcutHost by remember { mutableStateOf<Host?>(null) }
    shortcutHost?.let { host ->
        ShortcutCustomizationDialog(
            host = host,
            onDismiss = { shortcutHost = null },
            onConfirm = { color, iconStyle ->
                onSelectShortcut(host, color, iconStyle)
                shortcutHost = null
            },
        )
    }

    uiState.startupKeyPrompt?.let { pendingKey ->
        StartupKeyPasswordDialog(
            pubkey = pendingKey,
            wrongPassword = uiState.startupKeyWrongPassword,
            onDismiss = viewModel::dismissStartupKeyPrompt,
            onProvidePassword = viewModel::submitStartupKeyPassword,
        )
    }

    ShelldonHostListContent(
        uiState = uiState,
        makingShortcut = makingShortcut,
        onNavigateToConsole = onNavigateToConsole,
        onSelectShortcut = { host -> shortcutHost = host },
        onNavigateToEditHost = onNavigateToEditHost,
        onNavigateToPortForwards = onNavigateToPortForwards,
        onNavigateToProfiles = onNavigateToProfiles,
        onNavigateToHelp = onNavigateToHelp,
        onToggleSortOrder = viewModel::toggleSortOrder,
        onDeleteHost = viewModel::deleteHost,
        onDuplicateHost = viewModel::duplicateHost,
        onForgetHostKeys = viewModel::forgetHostKeys,
        onDisconnectHost = viewModel::disconnectHost,
        onDisconnectAll = viewModel::disconnectAll,
        onExportHosts = viewModel::exportHosts,
        // Some document providers label JSON exports as text/plain or application/octet-stream.
        onImportHosts = { importLauncher.launch(arrayOf("*/*")) },
        onQuickConnect = { target ->
            scope.launch {
                val manager = terminalManager
                if (manager == null) {
                    Toast.makeText(context, R.string.shelldon_quick_connect_unavailable, Toast.LENGTH_LONG).show()
                    return@launch
                }
                try {
                    // Not saved: the connection is opened as a temporary host.
                    val uri = Host
                        .createSshHost(target.nickname, target.hostname, target.port, target.username)
                        .copy(isTemporary = true)
                        .getUri()
                    val bridge = manager.openConnection(uri)
                    onNavigateToConsole(bridge.host)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        e.message ?: resources.getString(R.string.shelldon_quick_connect_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        },
        modifier = modifier,
    )
}
