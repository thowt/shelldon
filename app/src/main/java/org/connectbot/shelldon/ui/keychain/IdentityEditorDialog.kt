/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.keychain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import org.connectbot.data.entity.Pubkey
import org.connectbot.shelldon.data.groups.Identity
import org.connectbot.shelldon.data.groups.Rejection
import org.connectbot.shelldon.ui.messageRes
import java.util.UUID

/**
 * Create or edit an identity: a name for the list, the user name, and optionally the key to use.
 * There is no password field on purpose: stored secrets wait for the vault.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IdentityEditorDialog(
    existing: Identity?,
    keys: List<Pubkey>,
    error: Rejection?,
    onDismiss: () -> Unit,
    onSave: (name: String, username: String, keyId: UUID?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(existing?.username.orEmpty()) }
    var keyIdText by rememberSaveable { mutableStateOf(existing?.keyId?.toString()) }
    var expanded by remember { mutableStateOf(false) }

    val keyId = keyIdText?.let(UUID::fromString)
    val selectedKey = keyId?.let { id -> keys.firstOrNull { it.id == id } }
    val keyLabel = when {
        keyId == null -> stringResource(R.string.shelldon_identity_key_none)
        selectedKey != null -> selectedKey.nickname
        else -> stringResource(R.string.shelldon_identity_key_missing)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (existing == null) R.string.shelldon_identity_new else R.string.shelldon_identity_edit))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.shelldon_identity_name)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("identity_name"),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.shelldon_identity_username)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("identity_username"),
                )
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = keyLabel,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.shelldon_identity_key)) },
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
                            text = { Text(stringResource(R.string.shelldon_identity_key_none)) },
                            onClick = {
                                keyIdText = null
                                expanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                        keys.forEach { key ->
                            DropdownMenuItem(
                                text = { Text(key.nickname) },
                                onClick = {
                                    keyIdText = key.id.toString()
                                    expanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.shelldon_identity_key_hint),
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
                onClick = { onSave(name, username, keyId) },
                enabled = name.isNotBlank() && username.isNotBlank(),
                modifier = Modifier.testTag("identity_save"),
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
