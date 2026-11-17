/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.hosteditor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.connectbot.R
import org.connectbot.data.EntityIds
import org.connectbot.shelldon.data.groups.GroupSnapshot
import org.connectbot.shelldon.data.groups.GroupTree
import org.connectbot.shelldon.data.groups.IdentityResolver
import org.connectbot.shelldon.data.groups.IdentitySource
import org.connectbot.shelldon.data.groups.Rejection
import org.connectbot.shelldon.ui.messageRes
import org.connectbot.util.HostConstants
import java.util.UUID

/**
 * Where a host lives and how it signs in: folder, tags and identity, with a plain-language preview
 * of the login that will actually be used. Inserted into the host editor through its
 * `organizationContent` slot, so the upstream editor only changed by one call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostOrganizationSection(
    snapshot: GroupSnapshot,
    groupId: UUID?,
    identityId: UUID?,
    tagIds: List<UUID>,
    username: String,
    pubkeyId: UUID,
    keyNames: Map<UUID, String>,
    onGroupChange: (UUID?) -> Unit,
    onIdentityChange: (UUID?) -> Unit,
    onTagsChange: (List<UUID>) -> Unit,
    onCreateTag: (name: String, onResult: (Rejection?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text(
            text = stringResource(R.string.shelldon_organise_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))

        // Folder
        val folders = remember(snapshot) {
            snapshot.groups
                .map { it.id to GroupTree.path(snapshot.groupsById, it.id).joinToString(" / ") }
                .sortedBy { it.second.lowercase() }
        }
        val noFolder = stringResource(R.string.shelldon_folder_none)
        val folderLabel = groupId?.let { id -> folders.firstOrNull { it.first == id }?.second } ?: noFolder
        PickerField(
            label = stringResource(R.string.shelldon_folder_label),
            value = folderLabel,
            tag = "host_folder",
            options = listOf<Pair<String, () -> Unit>>(noFolder to { onGroupChange(null) }) +
                folders.map { (id, path) -> path to { onGroupChange(id) } },
        )

        // Tags
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.shelldon_tags_label),
            style = MaterialTheme.typography.labelLarge,
        )
        var creatingTag by rememberSaveable { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            snapshot.tags.sortedBy { it.name.lowercase() }.forEach { tag ->
                val selected = tag.id in tagIds
                FilterChip(
                    selected = selected,
                    onClick = { onTagsChange(if (selected) tagIds - tag.id else tagIds + tag.id) },
                    label = { Text("#${tag.name}") },
                )
            }
            AssistChip(
                onClick = { creatingTag = true },
                label = { Text(stringResource(R.string.shelldon_tag_new)) },
                modifier = Modifier.testTag("host_tag_new"),
            )
        }
        if (creatingTag) {
            NewTagDialog(
                onDismiss = { creatingTag = false },
                onCreate = onCreateTag,
            )
        }

        // Identity
        Spacer(Modifier.height(12.dp))
        val inherited = IdentityResolver.resolveIdentity(null, groupId, snapshot.groupsById, snapshot.identitiesById)
        val inheritLabel = inherited.identity?.let { identity ->
            stringResource(R.string.shelldon_identity_inherit_from, identity.name, inherited.group?.name.orEmpty())
        } ?: stringResource(R.string.shelldon_identity_inherit_none)
        val noIdentity = stringResource(R.string.shelldon_identity_none)
        val identityLabel = when (identityId) {
            null -> inheritLabel
            EntityIds.NONE -> noIdentity
            else -> snapshot.identitiesById[identityId]?.let { "${it.name} (${it.username})" } ?: inheritLabel
        }
        PickerField(
            label = stringResource(R.string.shelldon_identity_label),
            value = identityLabel,
            tag = "host_identity",
            options = listOf<Pair<String, () -> Unit>>(
                inheritLabel to { onIdentityChange(null) },
                noIdentity to { onIdentityChange(EntityIds.NONE) },
            ) + snapshot.identities.sortedBy { it.name.lowercase() }.map { identity ->
                "${identity.name} (${identity.username})" to { onIdentityChange(identity.id) }
            },
        )

        if (snapshot.identities.isNotEmpty()) {
            LoginPreview(
                snapshot = snapshot,
                groupId = groupId,
                identityId = identityId,
                username = username,
                pubkeyId = pubkeyId,
                keyNames = keyNames,
            )
        }
    }
}

/** What a connection will really use, so nobody has to work out the inheritance in their head. */
@Composable
private fun LoginPreview(
    snapshot: GroupSnapshot,
    groupId: UUID?,
    identityId: UUID?,
    username: String,
    pubkeyId: UUID,
    keyNames: Map<UUID, String>,
) {
    val login = IdentityResolver.effectiveLogin(username, pubkeyId, identityId, groupId, snapshot.groupsById, snapshot.identitiesById)
    val resolved = login.identity
    val identityLine = when (resolved.source) {
        IdentitySource.HOST -> stringResource(R.string.shelldon_login_preview_from_host, resolved.identity?.name.orEmpty())

        IdentitySource.GROUP -> stringResource(
            R.string.shelldon_login_preview_from_group,
            resolved.identity?.name.orEmpty(),
            resolved.group?.name.orEmpty(),
        )

        IdentitySource.NONE -> if (identityId == EntityIds.NONE || resolved.group != null) {
            stringResource(R.string.shelldon_login_preview_opted_out)
        } else {
            stringResource(R.string.shelldon_login_preview_none)
        }
    }
    val userLine = if (login.username.isBlank()) {
        stringResource(R.string.shelldon_login_preview_user_ask)
    } else {
        stringResource(R.string.shelldon_login_preview_user, login.username)
    }
    val keyLine = when {
        login.keyId == HostConstants.PUBKEYID_NEVER -> stringResource(R.string.shelldon_login_preview_key_never)
        login.keyId == HostConstants.PUBKEYID_ANY -> stringResource(R.string.shelldon_login_preview_key_any)
        else -> stringResource(R.string.shelldon_login_preview_key_named, keyNames[login.keyId] ?: "?")
    }
    Column(modifier = Modifier.padding(top = 8.dp).testTag("host_login_preview")) {
        Text(
            text = identityLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "$userLine · $keyLine",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerField(
    label: String,
    value: String,
    tag: String,
    options: List<Pair<String, () -> Unit>>,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.padding(top = 4.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .testTag(tag),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (text, action) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        action()
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun NewTagDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, onResult: (Rejection?) -> Unit) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<Rejection?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shelldon_tag_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.shelldon_tag_name)) },
                    isError = error != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tag_name"),
                )
                Text(
                    text = error?.let { stringResource(it.messageRes()) } ?: stringResource(R.string.shelldon_tag_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(name) { rejection ->
                        error = rejection
                        if (rejection == null) onDismiss()
                    }
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("tag_save"),
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
