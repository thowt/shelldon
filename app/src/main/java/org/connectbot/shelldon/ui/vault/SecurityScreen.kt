/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.vault

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.connectbot.R
import org.connectbot.shelldon.backup.BackupCrypto
import org.connectbot.shelldon.backup.BackupOptions
import org.connectbot.shelldon.backup.BackupProblem
import org.connectbot.shelldon.ui.shell.shelldonBarHeight
import org.connectbot.shelldon.vault.Vault
import org.connectbot.shelldon.vault.VaultController
import org.connectbot.ui.components.rememberBiometricPromptState

private class PendingExport(val password: String, val options: BackupOptions)

/**
 * Master password, auto-lock, biometric unlock, encrypted backup and restore, ssh import and
 * vault reset, all in one place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSshImport: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SecurityViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsState()

    var showAutoLock by remember { mutableStateOf(false) }
    var showChangePassword by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<PendingExport?>(null) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pending = pendingExport
        pendingExport = null
        if (uri != null && pending != null) viewModel.exportBackup(uri, pending.password, pending.options)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingImport = uri }

    val promptTitle = stringResource(R.string.shelldon_vault_bio_prompt_title)
    val promptSubtitle = stringResource(R.string.shelldon_vault_bio_prompt_enable_subtitle)
    val promptNegative = stringResource(R.string.shelldon_cancel)
    val biometricPrompt = rememberBiometricPromptState(
        onSuccess = { result -> result.cryptoObject?.cipher?.let(viewModel::finishBiometricEnable) },
        onError = { _, _ -> },
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                expandedHeight = shelldonBarHeight(),
                title = { Text(stringResource(R.string.shelldon_security_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.button_close))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            if (ui.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            SectionHeader(stringResource(R.string.shelldon_security_section_vault))
            ActionRow(
                title = stringResource(R.string.shelldon_security_lock_now),
                summary = stringResource(R.string.shelldon_security_lock_now_summary),
                onClick = viewModel::lockNow,
                testTag = "security_lock_now",
            )
            ActionRow(
                title = stringResource(R.string.shelldon_security_autolock),
                summary = autoLockLabel(ui.autoLockSeconds),
                onClick = { showAutoLock = true },
                testTag = "security_autolock",
            )
            SwitchRow(
                title = stringResource(R.string.shelldon_security_biometric),
                summary = stringResource(
                    when {
                        !ui.biometricAvailable && !ui.biometricEnabled -> R.string.shelldon_security_biometric_unavailable
                        ui.biometricEnabled -> R.string.shelldon_security_biometric_on
                        else -> R.string.shelldon_security_biometric_off
                    },
                ),
                checked = ui.biometricEnabled,
                enabled = ui.biometricAvailable || ui.biometricEnabled,
                onCheckedChange = { enable ->
                    if (!enable) {
                        viewModel.disableBiometric()
                    } else {
                        val cipher = viewModel.beginBiometricEnable()
                        if (cipher != null) {
                            biometricPrompt?.authenticate(promptTitle, promptSubtitle, promptNegative, BiometricPrompt.CryptoObject(cipher))
                        }
                    }
                },
            )
            ActionRow(
                title = stringResource(R.string.shelldon_security_change_password),
                summary = stringResource(R.string.shelldon_security_change_password_summary),
                onClick = { showChangePassword = true },
                testTag = "security_change_password",
            )

            SectionHeader(stringResource(R.string.shelldon_security_section_backup))
            ActionRow(
                title = stringResource(R.string.shelldon_backup_export),
                summary = stringResource(R.string.shelldon_backup_export_summary),
                onClick = { showExport = true },
                enabled = !ui.busy,
                testTag = "security_export",
            )
            ActionRow(
                title = stringResource(R.string.shelldon_backup_import),
                summary = stringResource(R.string.shelldon_backup_import_summary),
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                enabled = !ui.busy,
                testTag = "security_import",
            )

            SectionHeader(stringResource(R.string.shelldon_security_section_import))
            ActionRow(
                title = stringResource(R.string.shelldon_sshimport_title),
                summary = stringResource(R.string.shelldon_sshimport_summary),
                onClick = onNavigateToSshImport,
                enabled = !ui.busy,
                testTag = "security_sshconfig",
            )

            SectionHeader(stringResource(R.string.shelldon_security_section_danger))
            ActionRow(
                title = stringResource(R.string.shelldon_security_reset),
                summary = stringResource(R.string.shelldon_security_reset_summary),
                onClick = { showReset = true },
                testTag = "security_reset",
            )
        }
    }

    if (showAutoLock) {
        AutoLockDialog(
            current = ui.autoLockSeconds,
            onSelect = {
                viewModel.setAutoLock(it)
                showAutoLock = false
            },
            onDismiss = { showAutoLock = false },
        )
    }
    if (showChangePassword) {
        ChangePasswordDialog(
            onConfirm = { current, new ->
                showChangePassword = false
                viewModel.changePassword(current, new)
            },
            onDismiss = { showChangePassword = false },
        )
    }
    if (showExport) {
        ExportDialog(
            onConfirm = { password, options ->
                showExport = false
                pendingExport = PendingExport(password, options)
                exportLauncher.launch("shelldon-backup.shbk")
            },
            onDismiss = { showExport = false },
        )
    }
    pendingImport?.let { uri ->
        ImportPasswordDialog(
            onConfirm = { password ->
                pendingImport = null
                viewModel.importBackup(uri, password)
            },
            onDismiss = { pendingImport = null },
        )
    }
    if (showReset) {
        ResetVaultDialog(
            loadImpact = viewModel::resetImpact,
            unlocked = true,
            onDismiss = { showReset = false },
            onConfirm = {
                showReset = false
                viewModel.reset()
            },
            onBackUp = {
                showReset = false
                showExport = true
            },
        )
    }
    ui.message?.let { message ->
        val (title, body) = messageText(message)
        AlertDialog(
            onDismissRequest = viewModel::messageShown,
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = { TextButton(onClick = viewModel::messageShown) { Text(stringResource(R.string.shelldon_security_ok)) } },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun ActionRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    testTag: String = "",
) {
    Column {
        ListItem(
            supportingContent = { Text(summary) },
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .testTag(testTag),
        ) { Text(title) }
        HorizontalDivider()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column {
        ListItem(
            supportingContent = { Text(summary) },
            trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled) },
            modifier = Modifier.clickable(enabled = enabled) { onCheckedChange(!checked) },
        ) { Text(title) }
        HorizontalDivider()
    }
}

@Composable
internal fun autoLockLabel(seconds: Int): String = stringResource(
    when (seconds) {
        0 -> R.string.shelldon_security_autolock_0
        60 -> R.string.shelldon_security_autolock_60
        900 -> R.string.shelldon_security_autolock_900
        3600 -> R.string.shelldon_security_autolock_3600
        else -> R.string.shelldon_security_autolock_300
    },
)

@Composable
private fun AutoLockDialog(current: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shelldon_security_autolock)) },
        text = {
            Column {
                VaultController.AUTO_LOCK_OPTIONS.forEach { seconds ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(seconds) },
                    ) {
                        RadioButton(selected = seconds == current, onClick = { onSelect(seconds) })
                        Text(autoLockLabel(seconds))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.shelldon_cancel)) } },
    )
}

@Composable
private fun ChangePasswordDialog(onConfirm: (current: String, new: String) -> Unit, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = current.isNotEmpty() && new.length >= Vault.MIN_PASSWORD_LENGTH && new == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shelldon_security_change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField(value = current, onValueChange = { current = it }, label = stringResource(R.string.shelldon_security_current_password), testTag = "change_current")
                PasswordField(value = new, onValueChange = { new = it }, label = stringResource(R.string.shelldon_security_new_password), testTag = "change_new")
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = stringResource(R.string.shelldon_vault_password_confirm),
                    imeAction = ImeAction.Done,
                    isError = confirm.isNotEmpty() && new != confirm,
                    testTag = "change_confirm",
                )
                Text(
                    text = stringResource(R.string.shelldon_vault_password_hint, Vault.MIN_PASSWORD_LENGTH),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(current, new) }, enabled = valid) { Text(stringResource(R.string.shelldon_security_change_password)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.shelldon_cancel)) } },
    )
}

@Composable
private fun ExportDialog(onConfirm: (password: String, options: BackupOptions) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var includeKeys by remember { mutableStateOf(true) }
    var includePasswords by remember { mutableStateOf(false) }
    val valid = password.length >= BackupCrypto.MIN_PASSWORD_LENGTH && password == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shelldon_backup_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.shelldon_backup_password_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PasswordField(value = password, onValueChange = { password = it }, label = stringResource(R.string.shelldon_backup_password), testTag = "backup_password")
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = stringResource(R.string.shelldon_backup_password_confirm),
                    imeAction = ImeAction.Done,
                    isError = confirm.isNotEmpty() && password != confirm,
                    testTag = "backup_password_confirm",
                )
                CheckRow(stringResource(R.string.shelldon_backup_include_keys), includeKeys) { includeKeys = it }
                CheckRow(stringResource(R.string.shelldon_backup_include_passwords), includePasswords) { includePasswords = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password, BackupOptions(includeKeys, includePasswords)) }, enabled = valid) {
                Text(stringResource(R.string.shelldon_backup_continue))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.shelldon_cancel)) } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
private fun ImportPasswordDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shelldon_backup_import_title)) },
        text = {
            PasswordField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.shelldon_backup_password),
                imeAction = ImeAction.Done,
                onDone = { if (password.isNotEmpty()) onConfirm(password) },
                testTag = "backup_import_password",
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) { Text(stringResource(R.string.shelldon_backup_import_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.shelldon_cancel)) } },
    )
}

/** Title and body for a result dialog. */
@Composable
private fun messageText(message: SecurityMessage): Pair<String, String> {
    val security = stringResource(R.string.shelldon_security_title)
    return when (message) {
        SecurityMessage.PasswordChanged -> security to stringResource(R.string.shelldon_security_password_changed)

        SecurityMessage.CurrentPasswordWrong -> security to stringResource(R.string.shelldon_security_err_current)

        SecurityMessage.BackupExported -> security to stringResource(R.string.shelldon_backup_exported)

        is SecurityMessage.BackupImported -> {
            val r = message.result
            stringResource(R.string.shelldon_backup_result_title) to stringResource(
                R.string.shelldon_backup_result_body,
                r.hostsImported,
                r.hostsSkipped,
                r.keysImported,
                r.keysSkipped,
                r.foldersImported,
                r.tagsImported,
                r.identitiesImported,
                r.passwordsRestored,
            )
        }

        is SecurityMessage.BackupFailed -> security to stringResource(
            when (message.problem) {
                BackupProblem.WRONG_PASSWORD_OR_DAMAGED -> R.string.shelldon_backup_failed_wrong
                BackupProblem.NOT_A_BACKUP -> R.string.shelldon_backup_failed_not_backup
                BackupProblem.UNSUPPORTED_VERSION -> R.string.shelldon_backup_failed_new
                null -> R.string.shelldon_backup_failed_other
            },
        )
    }
}
