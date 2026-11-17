/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.keychain

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.connectbot.R
import org.connectbot.data.entity.KeyStorageType
import org.connectbot.data.entity.Pubkey
import org.connectbot.shelldon.data.groups.Identity
import org.connectbot.shelldon.data.groups.Rejection
import org.connectbot.shelldon.ui.components.ShelldonEmptyState
import org.connectbot.shelldon.ui.components.ShelldonNewButton
import org.connectbot.shelldon.ui.components.ShelldonQuietTile
import org.connectbot.shelldon.ui.components.ShelldonRowCard
import org.connectbot.shelldon.ui.components.ShelldonSectionLabel
import org.connectbot.shelldon.ui.components.ShelldonSegmentedTabs
import org.connectbot.shelldon.ui.components.ShelldonTile
import org.connectbot.shelldon.ui.components.tileColorFor
import org.connectbot.shelldon.ui.hostlist.ShelldonBadge
import org.connectbot.shelldon.ui.theme.LocalShelldonExtraColors
import org.connectbot.shelldon.ui.vault.autoLockLabel
import org.connectbot.shelldon.vault.VaultState
import java.util.UUID

private const val TAB_IDENTITIES = 0
private const val TAB_KEYS = 1
private const val TAB_KNOWN_HOSTS = 2

/**
 * The Keychain tab: everything that logs you in, in three views (identities, keys, known hosts) under
 * a card that shows whether the vault is open. "New" on the Keys tab opens a sheet with every way to add
 * a key; copying, exporting and deleting keys stays on the key list behind "Manage".
 */
@Composable
fun ShelldonKeychainScreen(
    onNavigateToGenerateKey: () -> Unit,
    onNavigateToImportFido2: () -> Unit,
    onNavigateToManageKeys: () -> Unit,
    onNavigateToEditKey: (UUID) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KeychainViewModel = hiltViewModel(),
    knownHostsViewModel: KnownHostsOverviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val knownHosts by knownHostsViewModel.rows.collectAsState()
    var tab by rememberSaveable { mutableStateOf(TAB_IDENTITIES) }
    var editing by remember { mutableStateOf<Identity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<Rejection?>(null) }
    var deleting by remember { mutableStateOf<IdentityRow?>(null) }
    var forgetting by remember { mutableStateOf<KnownHostRow?>(null) }
    var addingKey by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    ShelldonKeychainContent(
        state = state,
        knownHosts = knownHosts,
        tab = tab,
        onTabChange = { tab = it },
        onNewIdentity = {
            editError = null
            creating = true
        },
        onEditIdentity = {
            editError = null
            editing = it
        },
        onDeleteIdentity = { deleting = it },
        onAddKey = { addingKey = true },
        onManageKeys = onNavigateToManageKeys,
        onNavigateToEditKey = onNavigateToEditKey,
        onForgetKnownHost = { forgetting = it },
        onLockVault = viewModel::lockVault,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )

    AddKeyHost(
        sheetVisible = addingKey,
        onDismissSheet = { addingKey = false },
        onGenerate = onNavigateToGenerateKey,
        onImportFido2 = onNavigateToImportFido2,
        onMessage = { message -> snackbarHostState.showSnackbar(message) },
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

    forgetting?.let { row ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(stringResource(R.string.shelldon_known_hosts_forget)) },
            text = { Text(stringResource(R.string.shelldon_known_hosts_forget_body, row.endpoint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        knownHostsViewModel.forget(row.knownHost)
                        forgetting = null
                    },
                    modifier = Modifier.testTag("known_host_forget_confirm"),
                ) {
                    Text(stringResource(R.string.shelldon_known_hosts_forget))
                }
            },
            dismissButton = {
                TextButton(onClick = { forgetting = null }) {
                    Text(stringResource(R.string.shelldon_cancel))
                }
            },
        )
    }
}

@Composable
internal fun ShelldonKeychainContent(
    state: KeychainUiState,
    knownHosts: List<KnownHostRow>?,
    tab: Int,
    onTabChange: (Int) -> Unit,
    onNewIdentity: () -> Unit,
    onEditIdentity: (Identity) -> Unit,
    onDeleteIdentity: (IdentityRow) -> Unit,
    onAddKey: () -> Unit,
    onManageKeys: () -> Unit,
    onNavigateToEditKey: (UUID) -> Unit,
    onForgetKnownHost: (KnownHostRow) -> Unit,
    onLockVault: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val tabLabels = listOf(
        stringResource(R.string.shelldon_identities_title),
        stringResource(R.string.shelldon_keys_title),
        stringResource(R.string.shelldon_known_hosts_title),
    )

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            when (tab) {
                TAB_IDENTITIES -> ShelldonNewButton(
                    description = stringResource(R.string.shelldon_identity_new),
                    onClick = onNewIdentity,
                    modifier = Modifier.testTag("identity_new"),
                )

                TAB_KEYS -> ShelldonNewButton(
                    description = stringResource(R.string.shelldon_key_new),
                    onClick = onAddKey,
                    modifier = Modifier.testTag("key_new"),
                )

                else -> Unit
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
                        text = stringResource(R.string.shelldon_keychain_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                state.vault?.takeIf { it.state != VaultState.NO_VAULT }?.let { vault ->
                    item(key = "vault") {
                        VaultCard(status = vault, onLock = onLockVault, modifier = Modifier.padding(top = 4.dp))
                    }
                }

                item(key = "tabs") {
                    ShelldonSegmentedTabs(
                        labels = tabLabels,
                        selected = tab,
                        onSelect = onTabChange,
                        modifier = Modifier.padding(top = 6.dp),
                        tagPrefix = "keychain_tab_",
                    )
                }

                when (tab) {
                    TAB_IDENTITIES -> identityItems(
                        rows = state.identities,
                        onNew = onNewIdentity,
                        onEdit = onEditIdentity,
                        onDelete = onDeleteIdentity,
                    )

                    TAB_KEYS -> keyItems(
                        keys = state.keys,
                        onNew = onAddKey,
                        onManage = onManageKeys,
                        onOpen = onNavigateToEditKey,
                    )

                    else -> knownHostItems(rows = knownHosts, onForget = onForgetKnownHost)
                }
            }
        }
    }
}

private fun LazyListScope.identityItems(
    rows: List<IdentityRow>,
    onNew: () -> Unit,
    onEdit: (Identity) -> Unit,
    onDelete: (IdentityRow) -> Unit,
) {
    if (rows.isEmpty()) {
        item(key = "identities_empty") {
            ShelldonEmptyState(
                icon = Icons.Default.Person,
                title = stringResource(R.string.shelldon_identities_empty_title),
                body = stringResource(R.string.shelldon_identities_hint),
            ) {
                Button(
                    onClick = onNew,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.shelldon_identity_new),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        return
    }
    item(key = "identities_header") {
        ShelldonSectionLabel(
            title = stringResource(R.string.shelldon_identities_title),
            count = rows.size,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    items(items = rows, key = { "identity_${it.identity.id}" }) { row ->
        IdentityCard(
            row = row,
            onEdit = { onEdit(row.identity) },
            onDelete = { onDelete(row) },
        )
    }
}

private fun LazyListScope.keyItems(
    keys: List<Pubkey>,
    onNew: () -> Unit,
    onManage: () -> Unit,
    onOpen: (UUID) -> Unit,
) {
    if (keys.isEmpty()) {
        item(key = "keys_empty") {
            ShelldonEmptyState(
                icon = Icons.Default.Key,
                title = stringResource(R.string.shelldon_keys_empty_title),
                body = stringResource(R.string.shelldon_keys_empty_body),
            ) {
                Button(
                    onClick = onNew,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.shelldon_key_add),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        return
    }
    item(key = "keys_header") {
        ShelldonSectionLabel(
            title = stringResource(R.string.shelldon_keys_title),
            count = keys.size,
            modifier = Modifier.padding(top = 10.dp),
            action = {
                TextButton(
                    onClick = onManage,
                    modifier = Modifier.testTag("keys_manage"),
                ) {
                    Text(stringResource(R.string.shelldon_keys_manage))
                }
            },
        )
    }
    items(items = keys, key = { "key_${it.id}" }) { key ->
        KeyCard(key = key, onClick = { onOpen(key.id) })
    }
}

private fun LazyListScope.knownHostItems(
    rows: List<KnownHostRow>?,
    onForget: (KnownHostRow) -> Unit,
) {
    when {
        rows == null -> item(key = "known_hosts_loading") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
        }

        rows.isEmpty() -> item(key = "known_hosts_empty") {
            ShelldonEmptyState(
                icon = Icons.Default.Dns,
                title = stringResource(R.string.shelldon_known_hosts_empty_title),
                body = stringResource(R.string.shelldon_known_hosts_empty),
            )
        }

        else -> {
            item(key = "known_hosts_header") {
                ShelldonSectionLabel(
                    title = stringResource(R.string.shelldon_known_hosts_title),
                    count = rows.size,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            items(items = rows, key = { "known_host_${it.knownHost.id}" }) { row ->
                KnownHostCard(row = row, onForget = { onForget(row) })
            }
        }
    }
}

/** Whether the vault is open, with the way to close it now. Locking leaves open sessions running. */
@Composable
private fun VaultCard(
    status: VaultStatus,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unlocked = status.state == VaultState.UNLOCKED
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = LocalShelldonExtraColors.current.sessionCard,
        modifier = modifier
            .fillMaxWidth()
            .testTag("vault_card"),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShelldonQuietTile(icon = Icons.Default.Lock)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (unlocked) R.string.shelldon_keychain_vault_unlocked else R.string.shelldon_keychain_vault_locked,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                val detail = if (unlocked) {
                    // The vault locks after time spent away from the app, not on a timer while it is open,
                    // so this says when, not a countdown.
                    val autoLock = stringResource(R.string.shelldon_keychain_vault_autolock, autoLockLabel(status.autoLockSeconds))
                    if (status.biometricEnabled) {
                        stringResource(R.string.shelldon_keychain_vault_biometric) + " · " + autoLock
                    } else {
                        autoLock
                    }
                } else {
                    stringResource(R.string.shelldon_keychain_vault_locked_sub)
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (unlocked) {
                FilledTonalButton(onClick = onLock, modifier = Modifier.testTag("vault_lock")) {
                    Text(stringResource(R.string.shelldon_keychain_vault_lock))
                }
            }
        }
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
    val extras = LocalShelldonExtraColors.current
    ShelldonRowCard(
        onClick = onEdit,
        modifier = modifier.testTag("identity_${row.identity.id}"),
        tile = {
            ShelldonTile(icon = Icons.Default.Person, color = tileColorFor(row.identity.id))
        },
        trailing = {
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
        },
    ) {
        Text(
            text = row.identity.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val keyPart = row.keyName ?: stringResource(R.string.shelldon_identity_row_no_key)
        Text(
            text = if (row.keyMissing) row.identity.username else "${row.identity.username} · $keyPart",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (row.keyMissing) {
                ShelldonBadge(
                    text = stringResource(R.string.shelldon_identity_key_missing),
                    colors = extras.legacy,
                    icon = Icons.Default.Warning,
                )
            }
            if (row.keyStorage.isHardwareBacked) {
                ShelldonBadge(text = stringResource(R.string.shelldon_badge_hardware_key), colors = extras.mosh)
            }
            if (row.hostCount + row.groupCount == 0) {
                ShelldonBadge(text = stringResource(R.string.shelldon_identity_unused), colors = extras.neutral)
            } else {
                if (row.hostCount > 0) {
                    ShelldonBadge(
                        text = pluralStringResource(R.plurals.shelldon_identity_used_hosts, row.hostCount, row.hostCount),
                        colors = extras.neutral,
                    )
                }
                if (row.groupCount > 0) {
                    ShelldonBadge(
                        text = pluralStringResource(R.plurals.shelldon_identity_used_folders, row.groupCount, row.groupCount),
                        colors = extras.neutral,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyCard(
    key: Pubkey,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ShelldonRowCard(
        onClick = onClick,
        modifier = modifier.testTag("key_${key.id}"),
        tile = { ShelldonQuietTile(icon = Icons.Default.Key) },
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 10.dp),
            )
        },
    ) {
        Text(
            text = key.nickname,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = keySubtitle(key),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The key type, and where the key lives or when it was added. */
@Composable
private fun keySubtitle(key: Pubkey): String {
    val context = LocalContext.current
    return when (key.storageType) {
        KeyStorageType.FIDO2_RESIDENT_KEY -> stringResource(R.string.shelldon_key_subtitle_fido2, key.type)

        KeyStorageType.PIV -> stringResource(R.string.shelldon_key_subtitle_piv, key.type)

        KeyStorageType.ANDROID_KEYSTORE -> stringResource(R.string.shelldon_key_subtitle_keystore, key.type)

        KeyStorageType.EXPORTABLE -> if (key.createdDate > 0) {
            val added = DateUtils.formatDateTime(context, key.createdDate, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
            stringResource(R.string.shelldon_key_subtitle_added, key.type, added)
        } else {
            key.type
        }
    }
}

@Composable
private fun KnownHostCard(
    row: KnownHostRow,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ShelldonRowCard(
        modifier = modifier.testTag("known_host_${row.knownHost.id}"),
        tile = { ShelldonQuietTile(icon = Icons.Default.Dns) },
        trailing = {
            IconButton(onClick = onForget) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.shelldon_known_hosts_forget),
                )
            }
        },
    ) {
        Text(
            text = row.endpoint,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = row.knownHost.hostKeyAlgo,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = row.fingerprint,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
