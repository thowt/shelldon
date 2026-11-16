/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.keychain

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.connectbot.R
import org.connectbot.data.entity.Pubkey
import org.connectbot.shelldon.data.groups.Identity
import org.connectbot.shelldon.data.groups.Rejection
import java.util.UUID

/**
 * The Keychain tab: identities first, then the stored keys and the known host keys. Everything that
 * logs you in lives here. Key management itself (generate, import, export, FIDO2) stays on the
 * existing key screen, one tap away.
 */
@Composable
fun ShelldonKeychainScreen(
    onNavigateToKeys: () -> Unit,
    onNavigateToEditKey: (UUID) -> Unit,
    onNavigateToKnownHosts: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KeychainViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var editing by remember { mutableStateOf<Identity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<Rejection?>(null) }
    var deleting by remember { mutableStateOf<IdentityRow?>(null) }

    ShelldonKeychainContent(
        state = state,
        onNewIdentity = {
            editError = null
            creating = true
        },
        onEditIdentity = {
            editError = null
            editing = it
        },
        onDeleteIdentity = { deleting = it },
        onNavigateToKeys = onNavigateToKeys,
        onNavigateToEditKey = onNavigateToEditKey,
        onNavigateToKnownHosts = onNavigateToKnownHosts,
        modifier = modifier,
    )

    if (creating || editing != null) {
        val existing = editing
        IdentityEditorDialog(
            existing = existing,
            keys = state.keys,
            error = editError,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { name, username, keyId ->
                viewModel.saveIdentity(existing, name, username, keyId) { rejection ->
                    editError = rejection
                    if (rejection == null) {
                        creating = false
                        editing = null
                    }
                }
            },
        )
    }

    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.shelldon_identity_delete_title)) },
            text = { Text(stringResource(R.string.shelldon_identity_delete_body, row.identity.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteIdentity(row.identity.id)
                        deleting = null
                    },
                    modifier = Modifier.testTag("identity_delete_confirm"),
                ) {
                    Text(stringResource(R.string.shelldon_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.shelldon_cancel))
                }
            },
        )
    }
}

@Composable
internal fun ShelldonKeychainContent(
    state: KeychainUiState,
    onNewIdentity: () -> Unit,
    onEditIdentity: (Identity) -> Unit,
    onDeleteIdentity: (IdentityRow) -> Unit,
    onNavigateToKeys: () -> Unit,
    onNavigateToEditKey: (UUID) -> Unit,
    onNavigateToKnownHosts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
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
                    bottom = padding.calculateBottomPadding() + 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "title") {
                    Text(
                        text = stringResource(R.string.shelldon_keychain_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // Identities
                item(key = "identities_header") {
                    KeychainSectionHeader(
                        title = stringResource(R.string.shelldon_identities_title),
                        actionLabel = stringResource(R.string.shelldon_identity_new),
                        onAction = onNewIdentity,
                        actionTag = "identity_new",
                    )
                }
                if (state.identities.isEmpty()) {
                    item(key = "identities_empty") {
                        HintCard(text = stringResource(R.string.shelldon_identities_hint))
                    }
                } else {
                    items(items = state.identities, key = { "identity_${it.identity.id}" }) { row ->
                        IdentityCard(
                            row = row,
                            onEdit = { onEditIdentity(row.identity) },
                            onDelete = { onDeleteIdentity(row) },
                        )
                    }
                }

                // Keys
                item(key = "keys_header") {
                    KeychainSectionHeader(
                        title = stringResource(R.string.shelldon_keys_title),
                        actionLabel = stringResource(R.string.shelldon_keys_manage),
                        onAction = onNavigateToKeys,
                        actionTag = "keys_manage",
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (state.keys.isEmpty()) {
                    item(key = "keys_empty") {
                        HintCard(text = stringResource(R.string.shelldon_keys_empty))
                    }
                } else {
                    items(items = state.keys, key = { "key_${it.id}" }) { key ->
                        KeyRow(key = key, onClick = { onNavigateToEditKey(key.id) })
                    }
                }

                // Known hosts
                item(key = "known_hosts_header") {
                    KeychainSectionHeader(
                        title = stringResource(R.string.shelldon_known_hosts_title),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                item(key = "known_hosts") {
                    NavigationRow(
                        icon = Icons.Default.Dns,
                        text = stringResource(R.string.shelldon_known_hosts_summary, state.knownHostCount),
                        onClick = onNavigateToKnownHosts,
                        modifier = Modifier.testTag("known_hosts_row"),
                    )
                }
            }
        }
    }
}

@Composable
private fun KeychainSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    actionTag: String = "",
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null) {
            TextButton(onClick = onAction, modifier = Modifier.testTag(actionTag)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun HintCard(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun IdentityCard(
    row: IdentityRow,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    Surface(
        onClick = onEdit,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .testTag("identity_${row.identity.id}"),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
            ) {
                Text(
                    text = row.identity.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val keyPart = when {
                    row.keyName != null -> row.keyName
                    row.keyMissing -> stringResource(R.string.shelldon_identity_key_missing)
                    else -> stringResource(R.string.shelldon_identity_key_none)
                }
                Text(
                    text = "${row.identity.username} · $keyPart",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.keyMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (row.hostCount + row.groupCount == 0) {
                        stringResource(R.string.shelldon_identity_unused)
                    } else {
                        stringResource(R.string.shelldon_identity_usage, row.hostCount, row.groupCount)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.keyMissing) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = stringResource(R.string.shelldon_identity_key_missing),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.button_more_options),
                )
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shelldon_identity_edit)) },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shelldon_delete)) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyRow(
    key: Pubkey,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRow(
        icon = Icons.Default.Key,
        text = key.nickname,
        supporting = key.type,
        onClick = onClick,
        modifier = modifier.testTag("key_${key.id}"),
    )
}

/** A tappable row with an icon, a line of text and a chevron. */
@Composable
private fun NavigationRow(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supporting != null) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
