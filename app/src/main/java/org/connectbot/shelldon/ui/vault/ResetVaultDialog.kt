/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.connectbot.R
import org.connectbot.shelldon.vault.ResetImpact

/** The lines that say what a reset removes: passwords, keys, biometric unlock. Empty for none. */
@Composable
internal fun resetItems(impact: ResetImpact): List<String> = buildList {
    if (impact.passwords > 0) add(pluralStringResource(R.plurals.shelldon_vault_reset_passwords, impact.passwords, impact.passwords))
    if (impact.keys > 0) add(pluralStringResource(R.plurals.shelldon_vault_reset_keys, impact.keys, impact.keys))
    if (impact.biometric) add(stringResource(R.string.shelldon_vault_reset_item_biometric))
}

/**
 * Asks before the vault is reset and says exactly what goes: the counts are read when the dialog
 * opens, so what is shown is what is deleted. Nothing can be confirmed before they are known, and
 * when secrets would be lost the person has to tick that they understand.
 *
 * [unlocked] is whether the vault is open right now (reset from Settings): then [onBackUp] offers an
 * encrypted backup first. On the lock screen it can only point at biometric unlock, if that still works.
 */
@Composable
internal fun ResetVaultDialog(
    loadImpact: suspend () -> ResetImpact,
    unlocked: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onBackUp: (() -> Unit)? = null,
) {
    var impact by remember { mutableStateOf<ResetImpact?>(null) }
    var understood by remember { mutableStateOf(false) }
    val currentLoad by rememberUpdatedState(loadImpact)
    LaunchedEffect(Unit) { impact = currentLoad() }

    val known = impact
    val canConfirm = known != null && (!known.losesSecrets || understood)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shelldon_vault_reset_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (known == null) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    ResetDetails(known, unlocked, onBackUp)
                    if (known.losesSecrets) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = understood,
                                onCheckedChange = { understood = it },
                                modifier = Modifier.testTag("vault_reset_understood"),
                            )
                            Text(
                                text = stringResource(R.string.shelldon_vault_reset_understand),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canConfirm, modifier = Modifier.testTag("vault_reset_confirm")) {
                Text(
                    stringResource(R.string.shelldon_vault_reset_confirm),
                    color = if (canConfirm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.shelldon_cancel)) }
        },
    )
}

@Composable
private fun ResetDetails(impact: ResetImpact, unlocked: Boolean, onBackUp: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val items = resetItems(impact)
        if (items.isEmpty()) {
            Text(stringResource(R.string.shelldon_vault_reset_nothing), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.shelldon_vault_reset_deletes), style = MaterialTheme.typography.bodyMedium)
            Column(modifier = Modifier.testTag("vault_reset_items"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        Text(stringResource(R.string.shelldon_vault_reset_stays), style = MaterialTheme.typography.bodyMedium)
        if (impact.losesSecrets) {
            Text(stringResource(R.string.shelldon_vault_reset_readd), style = MaterialTheme.typography.bodyMedium)
            when {
                unlocked -> {
                    Text(
                        stringResource(R.string.shelldon_vault_reset_hint_unlocked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onBackUp != null) {
                        TextButton(onClick = onBackUp, modifier = Modifier.testTag("vault_reset_backup")) {
                            Text(stringResource(R.string.shelldon_vault_reset_backup))
                        }
                    }
                }

                impact.biometric -> Text(
                    stringResource(R.string.shelldon_vault_reset_hint_biometric),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> Text(
                    stringResource(R.string.shelldon_vault_reset_hint_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
