/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.keychain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.connectbot.R
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.Pubkey
import org.connectbot.shelldon.keyinstall.AuthorizedKeyLine
import org.connectbot.shelldon.keyinstall.KeyInstallResult
import org.connectbot.shelldon.keyinstall.toAuthorizedKeyLine
import org.connectbot.ui.LocalTerminalManager
import java.util.UUID

/** What a finished install says, in words. */
@Composable
private fun installResultText(result: KeyInstallResult): String = when (result) {
    KeyInstallResult.Added -> stringResource(R.string.shelldon_key_install_added)
    KeyInstallResult.AlreadyPresent -> stringResource(R.string.shelldon_key_install_present)
    KeyInstallResult.KeyUnusable -> stringResource(R.string.shelldon_key_install_unusable)
    KeyInstallResult.NoCommandChannel -> stringResource(R.string.shelldon_key_install_no_channel)
    is KeyInstallResult.Failed -> stringResource(R.string.shelldon_key_install_failed, result.detail.ifBlank { "?" })
    is KeyInstallResult.NotConfirmed -> stringResource(R.string.shelldon_key_install_not_confirmed, result.detail)
}

private val KeyInstallResult.isSuccess: Boolean
    get() = this == KeyInstallResult.Added || this == KeyInstallResult.AlreadyPresent

/** Writing to a server's login file is a change of who may log in, so it is confirmed with the key's fingerprint. */
@Composable
private fun InstallConfirmDialog(
    keyName: String,
    fingerprint: String,
    hostName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shelldon_key_install_confirm_title)) },
        text = { Text(stringResource(R.string.shelldon_key_install_confirm_body, keyName, fingerprint, hostName)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("key_install_confirm")) {
                Text(stringResource(R.string.shelldon_key_install_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.shelldon_cancel)) }
        },
    )
}

/** The right-hand side of a row: a spinner while it runs, otherwise the action that fits. */
@Composable
private fun InstallRowAction(
    status: InstallStatus?,
    enabled: Boolean,
    actionLabel: String,
    tag: String,
    onAction: () -> Unit,
) {
    when {
        status == InstallStatus.Running -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        status is InstallStatus.Done && status.result.isSuccess -> Unit
        else -> TextButton(onClick = onAction, enabled = enabled, modifier = Modifier.testTag(tag)) { Text(actionLabel) }
    }
}

/** One line under a row's title: what happened, shown in the error colour when it did not work. */
@Composable
private fun InstallResultLine(status: InstallStatus?, tag: String) {
    when (status) {
        InstallStatus.Running -> Text(
            text = stringResource(R.string.shelldon_key_install_running),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(tag),
        )

        is InstallStatus.Done -> Text(
            text = installResultText(status.result),
            style = MaterialTheme.typography.bodySmall,
            color = if (status.result.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag(tag),
        )

        null -> Unit
    }
}

/**
 * Keychain: one key, and the SSH servers to put it on. A server with an open connection is one tap
 * (and one confirmation) away; any other has a Connect button, which opens its session.
 */
@Composable
internal fun KeyInstallDialog(
    key: Pubkey,
    onConnect: (Host) -> Unit,
    onDismiss: () -> Unit,
    viewModel: KeyInstallViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val terminalManager = LocalTerminalManager.current
    val line: AuthorizedKeyLine? = remember(key) { key.toAuthorizedKeyLine() }
    var confirming by remember { mutableStateOf<InstallHost?>(null) }

    LaunchedEffect(terminalManager) {
        terminalManager?.let { viewModel.setTerminalManager(it) }
    }
    LaunchedEffect(key.id) { viewModel.clearResults() }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("key_install_dialog"),
        title = { Text(stringResource(R.string.shelldon_key_install_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.shelldon_key_install_intro, key.nickname, line?.fingerprint ?: "-"))
                Text(
                    text = stringResource(R.string.shelldon_key_install_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    line == null -> Text(
                        text = stringResource(R.string.shelldon_key_install_unusable),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("key_install_unusable"),
                    )

                    state.hosts.isEmpty() && !state.isLoading -> Text(
                        text = stringResource(R.string.shelldon_key_install_no_hosts),
                        modifier = Modifier.testTag("key_install_no_hosts"),
                    )

                    else -> LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(state.hosts, key = { it.host.id }) { item ->
                            val status = state.statuses[InstallTarget(key.id, item.host.id)]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .testTag("key_install_host_${item.name}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = listOf(item.host.username, item.host.hostname).filter { it.isNotBlank() }.joinToString("@"),
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    InstallResultLine(status, "key_install_result_${item.name}")
                                }
                                if (item.connected) {
                                    InstallRowAction(
                                        status = status,
                                        enabled = true,
                                        actionLabel = stringResource(R.string.shelldon_key_install_button),
                                        tag = "key_install_run_${item.name}",
                                        onAction = { confirming = item },
                                    )
                                } else {
                                    TextButton(onClick = { onConnect(item.host) }, modifier = Modifier.testTag("key_install_connect_${item.name}")) {
                                        Text(stringResource(R.string.shelldon_tunnels_connect))
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("key_install_close")) {
                Text(stringResource(R.string.shelldon_key_install_done))
            }
        },
    )

    val chosen = confirming
    if (chosen != null && line != null) {
        InstallConfirmDialog(
            keyName = key.nickname,
            fingerprint = line.fingerprint,
            hostName = chosen.name,
            onConfirm = {
                confirming = null
                viewModel.install(key.id, line, chosen.host.id)
            },
            onDismiss = { confirming = null },
        )
    }
}

/**
 * Console: the server is the one this session is logged in to; pick which key to put there.
 */
@Composable
internal fun KeyPickerInstallDialog(
    hostId: UUID,
    hostName: String,
    onDismiss: () -> Unit,
    viewModel: KeyInstallViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val terminalManager = LocalTerminalManager.current
    var confirming by remember { mutableStateOf<Pubkey?>(null) }

    LaunchedEffect(terminalManager) {
        terminalManager?.let { viewModel.setTerminalManager(it) }
    }
    LaunchedEffect(hostId) { viewModel.clearResults() }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("key_pick_dialog"),
        title = { Text(stringResource(R.string.shelldon_key_pick_title, hostName)) },
        text = {
            if (state.keys.isEmpty() && !state.isLoading) {
                Text(stringResource(R.string.shelldon_key_pick_empty), modifier = Modifier.testTag("key_pick_empty"))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(state.keys, key = { it.id }) { item ->
                        val usable = remember(item) { item.toAuthorizedKeyLine() != null }
                        val status = state.statuses[InstallTarget(item.id, hostId)]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .testTag("key_pick_${item.nickname}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.nickname,
                                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(item.type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                InstallResultLine(status, "key_pick_result_${item.nickname}")
                            }
                            InstallRowAction(
                                status = status,
                                enabled = usable,
                                actionLabel = stringResource(R.string.shelldon_key_install_button),
                                tag = "key_pick_run_${item.nickname}",
                                onAction = { confirming = item },
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("key_pick_close")) {
                Text(stringResource(R.string.shelldon_key_install_done))
            }
        },
    )

    val chosen = confirming
    val line = chosen?.toAuthorizedKeyLine()
    if (chosen != null && line != null) {
        InstallConfirmDialog(
            keyName = chosen.nickname,
            fingerprint = line.fingerprint,
            hostName = hostName,
            onConfirm = {
                confirming = null
                viewModel.install(chosen.id, line, hostId)
            },
            onDismiss = { confirming = null },
        )
    }
}
