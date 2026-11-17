/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.tunnels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.connectbot.R
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.PortForward
import org.connectbot.shelldon.tunnels.TunnelBoard
import org.connectbot.shelldon.tunnels.TunnelHost
import org.connectbot.shelldon.tunnels.TunnelItem
import org.connectbot.shelldon.tunnels.TunnelState
import org.connectbot.shelldon.ui.components.ShelldonEmptyState
import org.connectbot.shelldon.ui.components.ShelldonNewButton
import org.connectbot.shelldon.ui.components.ShelldonQuietTile
import org.connectbot.shelldon.ui.components.ShelldonRowCard
import org.connectbot.shelldon.ui.components.ShelldonTile
import org.connectbot.shelldon.ui.hostlist.ShelldonBadge
import org.connectbot.shelldon.ui.theme.LocalShelldonExtraColors
import org.connectbot.ui.LocalTerminalManager
import org.connectbot.util.HostConstants
import java.util.UUID

/**
 * The Tunnels tab: every port forward of every host in one list, with what is running. A forward
 * runs inside its host's SSH session, so a host that is not connected can only be connected here.
 */
@Composable
fun ShelldonTunnelsScreen(
    onConnect: (Host) -> Unit,
    onEditForward: (hostId: UUID, forwardId: UUID?) -> Unit,
    onManageHost: (hostId: UUID) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TunnelsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val terminalManager = LocalTerminalManager.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var pickingHost by remember { mutableStateOf(false) }

    LaunchedEffect(terminalManager) {
        terminalManager?.let { viewModel.setTerminalManager(it) }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshState()
        onPauseOrDispose { }
    }
    LaunchedEffect(notice) {
        notice?.let {
            val text = when (it.kind) {
                TunnelNotice.Kind.START_FAILED -> context.getString(R.string.shelldon_tunnel_start_failed, it.name)
                TunnelNotice.Kind.STOP_FAILED -> context.getString(R.string.shelldon_tunnel_stop_failed, it.name)
                TunnelNotice.Kind.NOT_CONNECTED -> context.getString(R.string.shelldon_tunnel_not_connected, it.name)
            }
            snackbar.showSnackbar(text)
            viewModel.clearNotice()
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.sshHosts.isNotEmpty()) {
                ShelldonNewButton(
                    description = stringResource(R.string.shelldon_tunnel_new),
                    onClick = { pickingHost = true },
                    modifier = Modifier.testTag("tunnel_new"),
                )
            }
        },
    ) { padding ->
        if (state.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "title") {
                    Text(
                        text = stringResource(R.string.shelldon_tunnels_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (state.hosts.isEmpty()) {
                    item(key = "empty") {
                        val noHosts = state.sshHosts.isEmpty()
                        ShelldonEmptyState(
                            icon = Icons.Default.SwapHoriz,
                            title = stringResource(
                                if (noHosts) R.string.shelldon_tunnels_no_hosts_title else R.string.shelldon_tunnels_empty_title,
                            ),
                            body = stringResource(
                                if (noHosts) R.string.shelldon_tunnels_no_hosts else R.string.shelldon_tunnels_empty,
                            ),
                            modifier = Modifier.testTag("tunnels_empty"),
                        ) {
                            if (!noHosts) {
                                Button(
                                    onClick = { pickingHost = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Text(
                                        text = stringResource(R.string.shelldon_tunnel_new),
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                state.hosts.forEach { group ->
                    item(key = "host_${group.host.id}") {
                        TunnelHostHeader(
                            group = group,
                            onConnect = { onConnect(group.host) },
                            onManage = { onManageHost(group.host.id) },
                        )
                    }
                    items(group.items, key = { "forward_${it.forward.id}" }) { item ->
                        TunnelRow(
                            item = item,
                            onClick = { onEditForward(group.host.id, item.forward.id) },
                            onToggle = { run -> viewModel.setRunning(item, run) },
                        )
                    }
                }
                if (state.hosts.isNotEmpty()) {
                    item(key = "hint") {
                        Text(
                            text = stringResource(R.string.shelldon_tunnels_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }

    if (pickingHost) {
        AlertDialog(
            onDismissRequest = { pickingHost = false },
            title = { Text(stringResource(R.string.shelldon_tunnel_pick_host)) },
            text = {
                Column {
                    state.sshHosts.forEach { host ->
                        Text(
                            text = TunnelBoard.hostName(host),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pickingHost = false
                                    onEditForward(host.id, null)
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickingHost = false }) { Text(stringResource(R.string.shelldon_cancel)) }
            },
        )
    }
}

@Composable
private fun TunnelHostHeader(
    group: TunnelHost,
    onConnect: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (group.connected) LocalShelldonExtraColors.current.connected else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        ),
                )
                Text(
                    text = if (group.connected) {
                        stringResource(R.string.shelldon_tunnels_host_connected, group.runningCount, group.items.size)
                    } else {
                        stringResource(R.string.shelldon_tunnels_host_offline)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!group.connected) {
            TextButton(onClick = onConnect, modifier = Modifier.testTag("tunnel_connect_${group.name}")) {
                Text(stringResource(R.string.shelldon_tunnels_connect))
            }
        }
        TextButton(onClick = onManage, modifier = Modifier.testTag("tunnel_manage_${group.name}")) {
            Text(stringResource(R.string.shelldon_tunnels_manage))
        }
    }
}

@Composable
private fun TunnelRow(
    item: TunnelItem,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extras = LocalShelldonExtraColors.current
    val running = item.state == TunnelState.RUNNING
    val name = item.forward.nickname
    ShelldonRowCard(
        onClick = onClick,
        modifier = modifier.testTag("tunnel_row_$name"),
        tile = {
            if (running) {
                ShelldonTile(icon = Icons.Default.SwapHoriz, color = extras.connected)
            } else {
                ShelldonQuietTile(icon = Icons.Default.SwapHoriz)
            }
        },
        trailing = {
            // A host that is not connected cannot run anything, so its switches are shown off and disabled.
            Switch(
                checked = running,
                onCheckedChange = onToggle,
                enabled = item.state != TunnelState.HOST_OFFLINE,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .semantics { contentDescription = name }
                    .testTag("tunnel_switch_$name"),
            )
        },
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = describe(item.forward),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ShelldonBadge(text = typeLabel(item.forward), colors = extras.neutral)
            if (item.exposed) {
                ShelldonBadge(
                    text = stringResource(R.string.shelldon_tunnel_exposed, item.forward.sourceAddr),
                    colors = extras.plaintext,
                    icon = Icons.Default.Warning,
                )
            }
        }
    }
}

@Composable
private fun typeLabel(forward: PortForward): String = stringResource(
    when (forward.type) {
        HostConstants.PORTFORWARD_LOCAL -> R.string.portforward_local
        HostConstants.PORTFORWARD_REMOTE -> R.string.portforward_remote
        else -> R.string.portforward_dynamic
    },
)

private fun describe(forward: PortForward): String {
    val source = "${forward.sourceAddr}:${forward.sourcePort}"
    return when (forward.type) {
        HostConstants.PORTFORWARD_LOCAL, HostConstants.PORTFORWARD_REMOTE -> "$source → ${forward.destAddr}:${forward.destPort}"
        else -> source
    }
}
