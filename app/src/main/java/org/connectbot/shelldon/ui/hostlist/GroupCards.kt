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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.connectbot.R
import org.connectbot.data.EntityIds
import org.connectbot.data.entity.Host
import org.connectbot.shelldon.data.groups.GroupSnapshot
import org.connectbot.shelldon.data.groups.GroupTree
import org.connectbot.shelldon.data.groups.HostGroup
import org.connectbot.shelldon.data.groups.Identity
import org.connectbot.shelldon.data.groups.Rejection
import org.connectbot.shelldon.ui.messageRes
import org.connectbot.ui.screens.hostlist.parseColor
import java.util.UUID

/** A folder in the Hosts list. Tap to open; the menu edits or deletes it. */
@Composable
internal fun GroupCard(
    entry: GroupEntry,
    identityName: String?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    val resources = LocalResources.current
    val tileColor = parseColor(entry.group.color)
    val iconColor = if (tileColor.luminance() > 0.5f) Color(0xFF0E1411) else Color.White
    val summary = buildString {
        append(resources.getQuantityString(R.plurals.shelldon_group_hosts, entry.hostCount, entry.hostCount))
        if (entry.subgroupCount > 0) {
            append(" · ")
            append(resources.getQuantityString(R.plurals.shelldon_group_folders, entry.subgroupCount, entry.subgroupCount))
        }
    }

    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .testTag("group_${entry.group.id}"),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(tileColor, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.group.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (identityName != null) {
                    Text(
                        text = stringResource(R.string.shelldon_group_login, identityName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.shelldon_group_options),
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shelldon_group_edit)) },
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

/** "All hosts > Work > Switches". Every part but the last is a button that jumps back up. */
@Composable
internal fun GroupBreadcrumb(
    path: List<HostGroup>,
    onOpen: (UUID?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onOpen(null) }) {
            Text(stringResource(R.string.shelldon_group_root))
        }
        path.forEachIndexed { index, group ->
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            if (index == path.lastIndex) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            } else {
                TextButton(onClick = { onOpen(group.id) }) {
                    Text(group.name)
                }
            }
        }
    }
}

/**
 * Create or rename a folder and choose its default identity. "Inherit" takes the parent folder's
 * identity, "No identity" stops inheriting here, so hosts below ask for credentials.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupEditorDialog(
    existing: HostGroup?,
    identities: List<Identity>,
    error: Rejection?,
    onDismiss: () -> Unit,
    onSave: (name: String, identityId: UUID?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var identityText by rememberSaveable { mutableStateOf(existing?.identityId?.toString()) }
    var expanded by remember { mutableStateOf(false) }

    val identityId = identityText?.let(UUID::fromString)
    val label = when {
        identityId == null -> stringResource(R.string.shelldon_group_identity_inherit)
        identityId == EntityIds.NONE -> stringResource(R.string.shelldon_group_identity_none)
        else -> identities.firstOrNull { it.id == identityId }?.name ?: stringResource(R.string.shelldon_group_identity_inherit)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (existing == null) R.string.shelldon_group_new else R.string.shelldon_group_edit))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.shelldon_group_name)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("group_name"),
                )
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.shelldon_group_identity)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.shelldon_group_identity_inherit)) },
                            onClick = {
                                identityText = null
                                expanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.shelldon_group_identity_none)) },
                            onClick = {
                                identityText = EntityIds.NONE.toString()
                                expanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                        identities.forEach { identity ->
                            DropdownMenuItem(
                                text = { Text("${identity.name} (${identity.username})") },
                                onClick = {
                                    identityText = identity.id.toString()
                                    expanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.shelldon_group_identity_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Text(
                        text = stringResource(error.messageRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, identityId) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("group_save"),
            ) {
                Text(stringResource(R.string.shelldon_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.shelldon_cancel))
            }
        },
    )
}

@Composable
internal fun GroupDeleteDialog(
    group: HostGroup,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shelldon_group_delete_title)) },
        text = { Text(stringResource(R.string.shelldon_group_delete_body, group.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("group_delete_confirm")) {
                Text(stringResource(R.string.shelldon_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.shelldon_cancel))
            }
        },
    )
}

/** Pick the folder a host should live in: the top level, or any folder by its full path. */
@Composable
internal fun MoveHostDialog(
    host: Host,
    snapshot: GroupSnapshot,
    onDismiss: () -> Unit,
    onMove: (UUID?) -> Unit,
) {
    val options = remember(snapshot.groups) {
        snapshot.groups
            .map { group -> group.id to GroupTree.path(snapshot.groupsById, group.id).joinToString(" / ") }
            .sortedBy { it.second.lowercase() }
    }
    val current = host.groupId?.takeIf { it in snapshot.groupsById }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shelldon_move_title, host.nickname)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                MoveOption(
                    label = stringResource(R.string.shelldon_group_root),
                    selected = current == null,
                    onClick = { onMove(null) },
                )
                options.forEach { (id, path) ->
                    MoveOption(label = path, selected = id == current, onClick = { onMove(id) })
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.shelldon_cancel))
            }
        },
    )
}

@Composable
private fun MoveOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
