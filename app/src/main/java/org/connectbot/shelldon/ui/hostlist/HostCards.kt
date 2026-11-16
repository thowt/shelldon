/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.hostlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.connectbot.R
import org.connectbot.data.entity.Host
import org.connectbot.service.DisconnectReason
import org.connectbot.service.descriptionResource
import org.connectbot.shelldon.ui.theme.BadgeColors
import org.connectbot.shelldon.ui.theme.LocalShelldonExtraColors
import org.connectbot.ui.screens.hostlist.ConnectionState
import org.connectbot.ui.screens.hostlist.ForgetHostKeysDialog
import org.connectbot.ui.screens.hostlist.HostDeleteDialog
import org.connectbot.ui.screens.hostlist.HostDisconnectDialog
import org.connectbot.ui.screens.hostlist.HostListTestTags
import org.connectbot.ui.screens.hostlist.parseColor

private const val DEFAULT_SSH_PORT = 22
private const val DEFAULT_TELNET_PORT = 23

/** `user@host`, with the port only when it is not the protocol's default. */
internal fun hostAddress(host: Host, localLabel: String): String = when (host.protocol) {
    "local" -> localLabel

    "telnet" -> if (host.port == DEFAULT_TELNET_PORT) host.hostname else "${host.hostname}:${host.port}"

    else -> buildString {
        if (host.username.isNotEmpty()) {
            append(host.username)
            append('@')
        }
        append(host.hostname)
        if (host.port != DEFAULT_SSH_PORT) {
            append(':')
            append(host.port)
        }
    }
}

@Composable
private fun statusColor(state: ConnectionState): Color {
    val extras = LocalShelldonExtraColors.current
    return when (state) {
        ConnectionState.CONNECTED -> extras.connected
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionState.UNREAD_OUTPUT -> extras.attention
        ConnectionState.ERROR -> MaterialTheme.colorScheme.error
        ConnectionState.UNKNOWN -> Color.Transparent
    }
}

@Composable
private fun statusDescription(state: ConnectionState): String = when (state) {
    ConnectionState.CONNECTED -> stringResource(R.string.image_description_connected)
    ConnectionState.DISCONNECTED -> stringResource(R.string.image_description_disconnected)
    ConnectionState.UNREAD_OUTPUT -> stringResource(R.string.host_status_unread_output)
    ConnectionState.ERROR -> stringResource(R.string.host_status_action_required)
    ConnectionState.UNKNOWN -> ""
}

/** A small rounded label such as "Mosh" or "Legacy crypto". */
@Composable
internal fun ShelldonBadge(
    text: String,
    colors: BadgeColors,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .background(colors.container, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.content,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = text,
            color = colors.content,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** Badges that tell the owner how this host connects; nothing is shown for a plain default SSH host. */
@Composable
private fun HostBadges(
    host: Host,
    modifier: Modifier = Modifier,
) {
    val extras = LocalShelldonExtraColors.current
    val isLegacy = host.crypto.preset == "LEGACY"
    val isCustom = host.crypto.preset == "CUSTOM"
    val hasJumpHost = host.jumpHostId != null
    val isMosh = host.protocol == "mosh"
    val isTelnet = host.protocol == "telnet"
    if (!(isMosh || isTelnet || isLegacy || isCustom || hasJumpHost)) return

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isTelnet) {
            ShelldonBadge(
                text = stringResource(R.string.shelldon_badge_telnet),
                colors = extras.plaintext,
                icon = Icons.Default.Warning,
            )
        }
        if (isMosh) {
            ShelldonBadge(text = stringResource(R.string.shelldon_badge_mosh), colors = extras.mosh)
        }
        if (isLegacy) {
            ShelldonBadge(
                text = stringResource(R.string.shelldon_badge_legacy),
                colors = extras.legacy,
                icon = Icons.Default.Warning,
            )
        }
        if (isCustom) {
            ShelldonBadge(text = stringResource(R.string.shelldon_badge_custom), colors = extras.neutral)
        }
        if (hasJumpHost) {
            ShelldonBadge(text = stringResource(R.string.shelldon_badge_jump), colors = extras.neutral)
        }
    }
}

/** Card for a session that is connected or has unread output. */
@Composable
internal fun SessionCard(
    host: Host,
    connectionState: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSave: (() -> Unit)? = null,
) {
    val extras = LocalShelldonExtraColors.current
    val unread = connectionState == ConnectionState.UNREAD_OUTPUT
    val dotColor = if (unread) extras.attention else extras.connected
    val statusText = stringResource(
        if (unread) R.string.host_status_unread_output else R.string.image_description_connected,
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = extras.sessionCard,
        modifier = modifier
            .width(176.dp)
            .testTag("shelldon_session_${host.id}"),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(dotColor, CircleShape),
                )
                Text(
                    text = host.nickname,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = hostAddress(host, stringResource(R.string.shelldon_local_shell)),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // Only quick-connect sessions that are not in the host list get a Save action.
                if (onSave != null) {
                    SaveSessionButton(
                        description = stringResource(R.string.shelldon_save_session_cd, host.nickname),
                        onClick = onSave,
                        modifier = Modifier.testTag("shelldon_session_save_${host.id}"),
                    )
                }
            }
        }
    }
}

/**
 * A saved host. Tapping the card connects; the status dot explains why a session is not connected;
 * the overflow menu holds the per-host actions.
 */
@Composable
internal fun HostCard(
    host: Host,
    connectionState: ConnectionState,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onPortForwards: () -> Unit,
    onDuplicate: () -> Unit,
    onForgetHostKeys: () -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    makingShortcut: Boolean = false,
    disconnectReason: DisconnectReason? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showForgetHostKeysDialog by remember { mutableStateOf(false) }
    var showStatusDialog by remember { mutableStateOf(false) }

    val statusColor = statusColor(connectionState)
    val statusDescription = statusDescription(connectionState)
    val reasonDescription = stringResource(
        (disconnectReason ?: DisconnectReason.UNKNOWN).descriptionResource(),
    )
    val canExplainStatus = !makingShortcut &&
        connectionState != ConnectionState.UNKNOWN &&
        connectionState != ConnectionState.CONNECTED

    if (showStatusDialog) {
        AlertDialog(
            onDismissRequest = { showStatusDialog = false },
            title = { Text(statusDescription) },
            text = {
                Text(
                    if (connectionState == ConnectionState.UNREAD_OUTPUT) {
                        stringResource(R.string.host_status_unread_explanation, reasonDescription)
                    } else {
                        reasonDescription
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { showStatusDialog = false }) {
                    Text(stringResource(R.string.button_ok))
                }
            },
        )
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .testTag(HostListTestTags.itemRow(host.id)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HostTile(
                host = host,
                connectionState = connectionState,
                statusColor = statusColor,
                statusDescription = statusDescription,
                onStatusClick = if (canExplainStatus) {
                    { showStatusDialog = true }
                } else {
                    null
                },
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = host.nickname,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = hostAddress(host, stringResource(R.string.shelldon_local_shell)),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HostBadges(host = host, modifier = Modifier.padding(top = 6.dp))
            }

            if (!makingShortcut) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.testTag(HostListTestTags.itemMenuButton(host.id)),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.button_host_options),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        HostMenuItems(
                            host = host,
                            connectionState = connectionState,
                            onDismiss = { showMenu = false },
                            onEdit = onEdit,
                            onPortForwards = onPortForwards,
                            onDuplicate = onDuplicate,
                            onForgetHostKeys = { showForgetHostKeysDialog = true },
                            onDisconnect = { showDisconnectDialog = true },
                            onDelete = { showDeleteDialog = true },
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        HostDeleteDialog(
            host = host,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                onDelete()
            },
        )
    }

    if (showDisconnectDialog) {
        HostDisconnectDialog(
            host = host,
            onDismiss = { showDisconnectDialog = false },
            onConfirm = {
                showDisconnectDialog = false
                onDisconnect()
            },
        )
    }

    if (showForgetHostKeysDialog) {
        ForgetHostKeysDialog(
            host = host,
            onDismiss = { showForgetHostKeysDialog = false },
            onConfirm = {
                showForgetHostKeysDialog = false
                onForgetHostKeys()
            },
        )
    }
}

@Composable
private fun HostTile(
    host: Host,
    connectionState: ConnectionState,
    statusColor: Color,
    statusDescription: String,
    onStatusClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tileColor = parseColor(host.color)
    val iconColor = if (tileColor.luminance() > 0.5f) Color(0xFF0E1411) else Color.White

    Box(
        modifier = modifier
            .size(44.dp)
            .then(
                if (onStatusClick != null) {
                    Modifier.clickable(onClickLabel = statusDescription, onClick = onStatusClick)
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(tileColor, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (host.protocol == "local") Icons.Default.Terminal else Icons.Default.Dns,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(24.dp),
            )
        }
        if (connectionState != ConnectionState.UNKNOWN) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 3.dp, y = 3.dp)
                    .size(16.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                    .padding(3.dp)
                    .background(statusColor, CircleShape)
                    .semantics { contentDescription = statusDescription },
            )
        }
    }
}

@Composable
private fun HostMenuItems(
    host: Host,
    connectionState: ConnectionState,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onPortForwards: () -> Unit,
    onDuplicate: () -> Unit,
    onForgetHostKeys: () -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.list_host_edit)) },
        onClick = {
            onDismiss()
            onEdit()
        },
        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
    )
    if (host.protocol == "ssh") {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.list_host_portforwards)) },
            onClick = {
                onDismiss()
                onPortForwards()
            },
            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.list_host_duplicate)) },
        onClick = {
            onDismiss()
            onDuplicate()
        },
        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
    )
    if (host.protocol == "ssh") {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.list_host_forget_keys)) },
            onClick = {
                onDismiss()
                onForgetHostKeys()
            },
            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.list_host_disconnect)) },
        onClick = {
            onDismiss()
            onDisconnect()
        },
        enabled = connectionState == ConnectionState.CONNECTED,
        leadingIcon = { Icon(Icons.Default.LinkOff, contentDescription = null) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.list_host_delete)) },
        onClick = {
            onDismiss()
            onDelete()
        },
        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
    )
}
