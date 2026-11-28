/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.sftp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.connectbot.R
import org.connectbot.shelldon.sftp.SftpPaths

/** The keys a phone keyboard hides behind other layers, and that config files are full of. */
private val EXTRA_KEYS = listOf("#", "/", "~", "|", "$")

/**
 * A full-screen plain-text editor for one remote file. It never closes with unsaved changes without
 * asking, and it does not leave the text anywhere but in memory: nothing is written to the phone.
 */
@Composable
internal fun SftpEditorDialog(
    editor: EditorUi,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onOverwrite: () -> Unit,
    onSaveCopy: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    val resources = LocalResources.current
    var confirmDiscard by remember { mutableStateOf(false) }
    val busy = editor.phase == EditorPhase.SAVING
    val requestClose = {
        if (!busy) {
            if (editor.dirty) confirmDiscard = true else onClose()
        }
    }
    Dialog(
        onDismissRequest = requestClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .systemBarsPadding()
                    .imePadding(),
            ) {
                EditorBar(editor = editor, busy = busy, onClose = requestClose, onSave = onSave)
                when (editor.phase) {
                    EditorPhase.LOADING -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("sftp_edit_loading"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                    EditorPhase.REFUSED, EditorPhase.FAILED -> Column(modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = buildString {
                                append(editor.problem?.let { resources.getString(it) } ?: resources.getString(R.string.shelldon_sftp_problem_other))
                                if (editor.problemDetail.isNotEmpty()) append(" (").append(editor.problemDetail).append(')')
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.testTag("sftp_edit_problem"),
                        )
                        TextButton(onClick = onClose, modifier = Modifier.testTag("sftp_edit_leave")) { Text(stringResource(R.string.shelldon_sftp_back)) }
                    }

                    EditorPhase.EDITING, EditorPhase.SAVING -> EditorBody(editor = editor, busy = busy, onTextChange = onTextChange)
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.shelldon_sftp_edit_discard_title)) },
            text = { Text(stringResource(R.string.shelldon_sftp_edit_discard_body, SftpPaths.printable(editor.name))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onClose()
                    },
                    modifier = Modifier.testTag("sftp_edit_discard"),
                ) {
                    Text(stringResource(R.string.shelldon_sftp_edit_discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }, modifier = Modifier.testTag("sftp_edit_keep")) {
                    Text(stringResource(R.string.shelldon_sftp_edit_keep))
                }
            },
        )
    }

    if (editor.conflict) {
        AlertDialog(
            onDismissRequest = onKeepEditing,
            title = { Text(stringResource(R.string.shelldon_sftp_edit_conflict_title)) },
            text = { Text(stringResource(R.string.shelldon_sftp_edit_conflict_body, SftpPaths.printable(editor.name))) },
            confirmButton = {
                TextButton(onClick = onOverwrite, modifier = Modifier.testTag("sftp_edit_overwrite")) {
                    Text(stringResource(R.string.shelldon_sftp_edit_overwrite), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onSaveCopy, modifier = Modifier.testTag("sftp_edit_copy")) { Text(stringResource(R.string.shelldon_sftp_edit_save_copy)) }
                    TextButton(onClick = onKeepEditing, modifier = Modifier.testTag("sftp_edit_conflict_keep")) { Text(stringResource(R.string.shelldon_sftp_edit_keep)) }
                }
            },
        )
    }
}

@Composable
private fun EditorBar(editor: EditorUi, busy: Boolean, onClose: () -> Unit, onSave: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, enabled = !busy, modifier = Modifier.testTag("sftp_edit_close")) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.shelldon_sftp_edit_close))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.shelldon_sftp_edit_title, SftpPaths.printable(editor.name)),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = SftpPaths.printable(editor.path),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
        if (editor.phase == EditorPhase.EDITING || editor.phase == EditorPhase.SAVING) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else if (editor.saved && !editor.dirty) {
                Text(
                    text = stringResource(R.string.shelldon_sftp_edit_saved),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .testTag("sftp_edit_saved"),
                )
            } else {
                TextButton(onClick = onSave, enabled = editor.dirty, modifier = Modifier.testTag("sftp_edit_save")) {
                    Text(stringResource(R.string.shelldon_sftp_edit_save))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.EditorBody(editor: EditorUi, busy: Boolean, onTextChange: (String) -> Unit) {
    val resources = LocalResources.current
    // The text lives here while it is typed, so the cursor does not jump; the view model gets every change.
    var value by remember { mutableStateOf(TextFieldValue(editor.text)) }
    val label = stringResource(R.string.shelldon_sftp_edit_text)
    val insert = { extra: String ->
        val selection = value.selection
        val text = value.text.replaceRange(selection.min, selection.max, extra)
        value = TextFieldValue(text, TextRange(selection.min + extra.length))
        onTextChange(text)
    }

    editor.problem?.let { problem ->
        Text(
            text = buildString {
                append(resources.getString(problem))
                if (editor.problemDetail.isNotEmpty()) append(" (").append(editor.problemDetail).append(')')
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag("sftp_edit_error"),
        )
    }
    editor.interruptedCopy?.let { copy ->
        Text(
            text = stringResource(R.string.shelldon_sftp_edit_interrupted, SftpPaths.printable(editor.path), SftpPaths.printable(copy)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag("sftp_edit_interrupted"),
        )
    }
    editor.savedCopy?.let { copy ->
        Text(
            text = stringResource(R.string.shelldon_sftp_edit_copy_saved, SftpPaths.printable(copy)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag("sftp_edit_copy_saved"),
        )
    }
    BasicTextField(
        value = value,
        onValueChange = { changed ->
            val textChanged = changed.text != value.text
            value = changed
            if (textChanged) onTextChange(changed.text)
        },
        enabled = !busy,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = label }
            .testTag("sftp_edit_text"),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = { insert("\t") }, enabled = !busy, modifier = Modifier.testTag("sftp_edit_key_tab")) {
            Text(stringResource(R.string.shelldon_sftp_edit_key_tab))
        }
        EXTRA_KEYS.forEach { key ->
            TextButton(onClick = { insert(key) }, enabled = !busy, modifier = Modifier.testTag("sftp_edit_key_$key")) {
                Text(key, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}
