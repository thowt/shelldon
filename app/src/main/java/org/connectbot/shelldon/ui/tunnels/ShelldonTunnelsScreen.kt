/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.tunnels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
                FloatingActionButton(onClick = { pickingHost = true }, modifier = Modifier.testTag("tunnel_new")) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.shelldon_tunnel_new))
                }
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
                        Text(
                            text = stringResource(
                                if (state.sshHosts.isEmpty()) R.string.shelldon_tunnels_no_hosts else R.string.shelldon_tunnels_empty,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("tunnels_empty"),
                        )
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (group.connected) {
                    stringResource(R.string.shelldon_tunnels_host_connected, group.runningCount, group.items.size)
                } else {
                    stringResource(R.string.shelldon_tunnels_host_offline)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("tunnel_row_${item.forward.nickname}"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.forward.nickname,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${typeLabel(item.forward)}: ${describe(item.forward)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.exposed) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(14.dp),
                        )
                        Text(
                            text = stringResource(R.string.shelldon_tunnel_exposed, item.forward.sourceAddr),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            when (item.state) {
                TunnelState.HOST_OFFLINE -> Text(
                    text = stringResource(R.string.shelldon_tunnel_state_offline),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                TunnelState.RUNNING, TunnelState.STOPPED -> Switch(
                    checked = item.state == TunnelState.RUNNING,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("tunnel_switch_${item.forward.nickname}"),
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
