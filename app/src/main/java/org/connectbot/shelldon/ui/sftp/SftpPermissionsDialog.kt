/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.sftp

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.connectbot.R
import org.connectbot.shelldon.sftp.SftpItem
import org.connectbot.shelldon.sftp.SftpKind
import org.connectbot.shelldon.sftp.SftpMode
import org.connectbot.shelldon.sftp.SftpPaths

private class PermissionRow(
    @param:StringRes val label: Int,
    val read: Int,
    val write: Int,
    val execute: Int,
    val tag: String,
)

private val PermissionRows = listOf(
    PermissionRow(R.string.shelldon_sftp_perm_owner, SftpMode.OWNER_READ, SftpMode.OWNER_WRITE, SftpMode.OWNER_EXECUTE, "owner"),
    PermissionRow(R.string.shelldon_sftp_perm_group, SftpMode.GROUP_READ, SftpMode.GROUP_WRITE, SftpMode.GROUP_EXECUTE, "group"),
    PermissionRow(R.string.shelldon_sftp_perm_others, SftpMode.OTHERS_READ, SftpMode.OTHERS_WRITE, SftpMode.OTHERS_EXECUTE, "others"),
)

/**
 * Changes the permissions of one file or folder on the server, like `chmod`: a grid of read, write and
 * execute boxes for owner, group and others that stays in step with the octal number beside it.
 * Setuid, setgid and sticky are behind their own heading. A folder can also be changed with everything
 * inside it; that is not offered together with the special bits, because nobody means to set those on
 * every file.
 */
@Composable
internal fun PermissionsDialog(
    item: SftpItem,
    initial: SftpMode,
    onApply: (mode: SftpMode, recursive: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var bits by rememberSaveable { mutableIntStateOf(initial.bits) }
    var octal by rememberSaveable { mutableStateOf(initial.octal) }
    var showSpecial by rememberSaveable { mutableStateOf(initial.special != 0) }
    var recursive by rememberSaveable { mutableStateOf(false) }

    val mode = SftpMode(bits)
    val typedMode = SftpMode.parseOctal(octal)
    val isFolder = item.kind == SftpKind.FOLDER
    val canRecurse = isFolder && mode.special == 0
    val willRecurse = recursive && canRecurse
    val changesSomething = mode != initial || willRecurse

    fun choose(next: SftpMode) {
        bits = next.bits
        octal = next.octal
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shelldon_sftp_perm_title, SftpPaths.printable(item.name))) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.width(LABEL_WIDTH))
                    for (heading in listOf(R.string.shelldon_sftp_perm_read, R.string.shelldon_sftp_perm_write, R.string.shelldon_sftp_perm_execute)) {
                        Text(
                            text = stringResource(heading),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                for (row in PermissionRows) {
                    val who = stringResource(row.label)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = who, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(LABEL_WIDTH))
                        for ((bit, what, kind) in listOf(
                            Triple(row.read, R.string.shelldon_sftp_perm_read, "read"),
                            Triple(row.write, R.string.shelldon_sftp_perm_write, "write"),
                            Triple(row.execute, R.string.shelldon_sftp_perm_execute, "execute"),
                        )) {
                            val name = stringResource(R.string.shelldon_sftp_perm_cell, who, stringResource(what))
                            Box(modifier = Modifier.weight(1f)) {
                                Checkbox(
                                    checked = mode.has(bit),
                                    onCheckedChange = { choose(mode.with(bit, it)) },
                                    modifier = Modifier
                                        .semantics { contentDescription = name }
                                        .testTag("sftp_perm_${row.tag}_$kind"),
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = octal,
                    onValueChange = { text ->
                        val digits = text.filter { it in '0'..'7' }.take(MAX_DIGITS)
                        octal = digits
                        SftpMode.parseOctal(digits)?.let { bits = it.bits }
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.shelldon_sftp_perm_octal)) },
                    isError = typedMode == null,
                    supportingText = {
                        Text(
                            text = if (typedMode == null) {
                                stringResource(R.string.shelldon_sftp_perm_octal_error)
                            } else {
                                typedMode.symbolic
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sftp_perm_octal"),
                )

                if (mode.isWorldWritable) Warning(R.string.shelldon_sftp_perm_warn_world, "sftp_perm_warn_world")
                if (mode.isSetId) Warning(R.string.shelldon_sftp_perm_warn_setid, "sftp_perm_warn_setid")

                TextButton(onClick = { showSpecial = !showSpecial }, modifier = Modifier.testTag("sftp_perm_special_toggle")) {
                    Text(stringResource(if (showSpecial) R.string.shelldon_sftp_perm_special_hide else R.string.shelldon_sftp_perm_special_show))
                }
                if (showSpecial) {
                    CheckRow(R.string.shelldon_sftp_perm_setuid, mode.has(SftpMode.SETUID), enabled = true, tag = "sftp_perm_setuid") { choose(mode.with(SftpMode.SETUID, it)) }
                    CheckRow(R.string.shelldon_sftp_perm_setgid, mode.has(SftpMode.SETGID), enabled = true, tag = "sftp_perm_setgid") { choose(mode.with(SftpMode.SETGID, it)) }
                    CheckRow(R.string.shelldon_sftp_perm_sticky, mode.has(SftpMode.STICKY), enabled = true, tag = "sftp_perm_sticky") { choose(mode.with(SftpMode.STICKY, it)) }
                }

                if (isFolder) {
                    CheckRow(R.string.shelldon_sftp_perm_recursive, willRecurse, enabled = canRecurse, tag = "sftp_perm_recursive") { recursive = it }
                    Text(
                        text = stringResource(if (canRecurse) R.string.shelldon_sftp_perm_recursive_hint else R.string.shelldon_sftp_perm_recursive_special),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(mode, willRecurse) },
                enabled = typedMode != null && changesSomething,
                modifier = Modifier.testTag("sftp_perm_apply"),
            ) {
                Text(stringResource(R.string.shelldon_sftp_perm_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.shelldon_cancel)) }
        },
    )
}

@Composable
private fun Warning(@StringRes text: Int, tag: String) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun CheckRow(
    @StringRes label: Int,
    checked: Boolean,
    enabled: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    val text = stringResource(label)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .testTag(tag),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

private val LABEL_WIDTH = 72.dp
private const val MAX_DIGITS = 4
