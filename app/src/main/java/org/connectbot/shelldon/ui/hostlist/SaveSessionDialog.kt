/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.hostlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.connectbot.R
import org.connectbot.shelldon.data.groups.GroupSnapshot
import org.connectbot.shelldon.data.groups.GroupTree
import org.connectbot.shelldon.data.groups.Rejection
import org.connectbot.shelldon.ui.messageRes
import java.util.UUID

/** Where a session that is being saved as a host should go. */
sealed interface SaveFolder {
    /** The top level, no folder. */
    data object None : SaveFolder

    data class Existing(val id: UUID) : SaveFolder

    /** A new top-level folder, created together with the host. */
    data class New(val name: String) : SaveFolder
}

private const val CHOICE_NONE = "none"
private const val CHOICE_NEW = "new"

/**
 * Saving a quick-connect session as a host: one small question, which folder. It starts on "No folder",
 * so confirming right away saves to the top level; a new folder can be made in the same step.
 * [error] is why the last attempt was refused (a taken or invalid folder name); the dialog stays open then.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SaveSessionDialog(
    nickname: String,
    snapshot: GroupSnapshot,
    error: Rejection?,
    onDismiss: () -> Unit,
    onSave: (SaveFolder) -> Unit,
) {
    // "none", "new" or the id of an existing folder; plain text so it survives rotation.
    var choice by rememberSaveable { mutableStateOf(CHOICE_NONE) }
    var newName by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }

    val folders = remember(snapshot.groups) {
        snapshot.groups
            .map { group -> group.id to GroupTree.path(snapshot.groupsById, group.id).joinToString(" / ") }
            .sortedBy { it.second.lowercase() }
    }
    // A folder that was deleted while the dialog was open falls back to the top level.
    val existing = folders.firstOrNull { it.first.toString() == choice }
    val creating = choice == CHOICE_NEW
    val label = when {
        creating -> stringResource(R.string.shelldon_save_new_folder_option)
        existing != null -> existing.second
        else -> stringResource(R.string.shelldon_save_no_folder)
    }

    LaunchedEffect(creating) {
        // The field may not be laid out yet on the very first frame; the user can tap it then.
        if (creating) runCatching { nameFocus.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("save_session_dialog"),
        title = { Text(stringResource(R.string.shelldon_save_session_title, nickname)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.shelldon_folder_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                            .testTag("save_session_folder"),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.shelldon_save_no_folder)) },
                            onClick = {
                                choice = CHOICE_NONE
                                expanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            modifier = Modifier.testTag("save_session_option_none"),
                        )
                        folders.forEach { (id, path) ->
                            DropdownMenuItem(
                                text = { Text(path) },
                                onClick = {
                                    choice = id.toString()
                                    expanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                modifier = Modifier.testTag("save_session_option_$id"),
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.shelldon_save_new_folder_option)) },
                            onClick = {
                                choice = CHOICE_NEW
                                expanded = false
                            },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            modifier = Modifier.testTag("save_session_option_new"),
                        )
                    }
                }
                if (creating) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.shelldon_group_name)) },
                        singleLine = true,
                        isError = error != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(nameFocus)
                            .testTag("save_session_new_name"),
                    )
                }
                if (error != null) {
                    Text(
                        text = stringResource(error.messageRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("save_session_error"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        when {
                            creating -> SaveFolder.New(newName)
                            existing != null -> SaveFolder.Existing(existing.first)
                            else -> SaveFolder.None
                        },
                    )
                },
                enabled = !creating || newName.isNotBlank(),
                modifier = Modifier.testTag("save_session_confirm"),
            ) {
                Text(stringResource(R.string.shelldon_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("save_session_cancel")) {
                Text(stringResource(R.string.shelldon_cancel))
            }
        },
    )
}
