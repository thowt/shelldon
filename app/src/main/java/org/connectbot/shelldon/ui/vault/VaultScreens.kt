/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.vault

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.connectbot.R
import org.connectbot.shelldon.vault.Vault
import org.connectbot.shelldon.vault.VaultState
import org.connectbot.ui.components.rememberBiometricPromptState
import javax.crypto.Cipher

/**
 * Puts the vault in front of the app: set a master password on first run, unlock afterwards.
 * Nothing in [content] is composed while the vault is missing or locked. The shortcut picker is
 * exempt because it only lists hosts and handles no secrets.
 */
@Composable
fun VaultGate(
    makingShortcut: Boolean,
    modifier: Modifier = Modifier,
    viewModel: VaultViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val state by viewModel.vaultState.collectAsState()
    val ui by viewModel.uiState.collectAsState()
    // One call site for [content]: a slot reused in several branches would lose its state when the
    // branch changes.
    val showContent = makingShortcut ||
        (state != VaultState.NO_VAULT && state != VaultState.LOCKED && !ui.offerBiometric)
    when {
        showContent -> content()

        state == VaultState.NO_VAULT -> VaultSetupScreen(
            ui = ui,
            onCreate = viewModel::create,
            modifier = modifier,
        )

        state == VaultState.LOCKED -> VaultUnlockScreen(
            ui = ui,
            onUnlock = viewModel::unlock,
            beginBiometric = viewModel::beginBiometricUnlock,
            finishBiometric = viewModel::finishBiometricUnlock,
            onReset = viewModel::reset,
            modifier = modifier,
        )

        else -> BiometricOfferScreen(
            begin = viewModel::beginBiometricEnable,
            finish = viewModel::finishBiometricEnable,
            onSkip = viewModel::dismissBiometricOffer,
            modifier = modifier,
        )
    }
}

@Composable
private fun VaultSetupScreen(
    ui: VaultUiState,
    onCreate: (password: String, confirm: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    VaultScaffold(modifier) {
        VaultTitle(title = stringResource(R.string.shelldon_vault_setup_title), body = stringResource(R.string.shelldon_vault_setup_body))
        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.shelldon_vault_password),
            enabled = !ui.busy,
            testTag = "vault_password",
        )
        PasswordField(
            value = confirm,
            onValueChange = { confirm = it },
            label = stringResource(R.string.shelldon_vault_password_confirm),
            enabled = !ui.busy,
            imeAction = ImeAction.Done,
            onDone = { onCreate(password, confirm) },
            testTag = "vault_password_confirm",
        )
        val error = ui.error
        Text(
            text = if (error != null) errorMessage(error) else stringResource(R.string.shelldon_vault_password_hint, Vault.MIN_PASSWORD_LENGTH),
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BusyButton(
            text = stringResource(R.string.shelldon_vault_create),
            busy = ui.busy,
            enabled = password.isNotEmpty(),
            onClick = { onCreate(password, confirm) },
            testTag = "vault_create",
        )
    }
}

@Composable
private fun VaultUnlockScreen(
    ui: VaultUiState,
    onUnlock: (String) -> Unit,
    beginBiometric: () -> Cipher?,
    finishBiometric: (Cipher) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }

    val title = stringResource(R.string.shelldon_vault_bio_prompt_title)
    val subtitle = stringResource(R.string.shelldon_vault_bio_prompt_unlock_subtitle)
    val negative = stringResource(R.string.shelldon_vault_bio_prompt_negative)
    val prompt = rememberBiometricPromptState(
        onSuccess = { result -> result.cryptoObject?.cipher?.let(finishBiometric) },
        onError = { _, _ -> },
    )
    fun launchBiometric() {
        val cipher = beginBiometric() ?: return
        prompt?.authenticate(title, subtitle, negative, BiometricPrompt.CryptoObject(cipher))
    }

    // Ask for the fingerprint straight away, as when coming back to a locked app.
    LaunchedEffect(Unit) {
        if (ui.biometricEnabled) launchBiometric()
    }

    VaultScaffold(modifier) {
        VaultTitle(title = stringResource(R.string.shelldon_vault_locked_title), body = null)
        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.shelldon_vault_password),
            enabled = !ui.busy,
            imeAction = ImeAction.Done,
            onDone = { onUnlock(password) },
            isError = ui.error == VaultError.WRONG_PASSWORD,
            testTag = "vault_password",
        )
        val message = when {
            ui.waitSeconds > 0 -> stringResource(R.string.shelldon_vault_err_wait, ui.waitSeconds)
            ui.error != null -> errorMessage(ui.error)
            else -> null
        }
        if (message != null) {
            Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        BusyButton(
            text = stringResource(R.string.shelldon_vault_unlock),
            busy = ui.busy,
            enabled = password.isNotEmpty() && ui.waitSeconds == 0,
            onClick = { onUnlock(password) },
            testTag = "vault_unlock",
        )
        if (ui.biometricEnabled) {
            OutlinedButton(onClick = { launchBiometric() }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.shelldon_vault_unlock_biometric), modifier = Modifier.padding(start = 8.dp))
            }
        }
        TextButton(onClick = { confirmReset = true }, enabled = !ui.busy) {
            Text(stringResource(R.string.shelldon_vault_forgot))
        }
    }

    if (confirmReset) {
        ResetVaultDialog(onDismiss = { confirmReset = false }, onConfirm = {
            confirmReset = false
            onReset()
        })
    }
}

@Composable
private fun BiometricOfferScreen(
    begin: () -> Cipher?,
    finish: (Cipher) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.shelldon_vault_bio_prompt_title)
    val subtitle = stringResource(R.string.shelldon_vault_bio_prompt_enable_subtitle)
    val negative = stringResource(R.string.shelldon_cancel)
    val prompt = rememberBiometricPromptState(
        onSuccess = { result ->
            val cipher = result.cryptoObject?.cipher
            if (cipher != null) finish(cipher) else onSkip()
        },
        // Cancelling is a "not now"; it can be switched on later under Security.
        onError = { _, _ -> onSkip() },
    )

    VaultScaffold(modifier) {
        VaultTitle(
            title = stringResource(R.string.shelldon_vault_bio_offer_title),
            body = stringResource(R.string.shelldon_vault_bio_offer_body),
            icon = Icons.Default.Fingerprint,
        )
        Button(
            onClick = {
                val cipher = begin()
                if (cipher != null && prompt != null) {
                    prompt.authenticate(title, subtitle, negative, BiometricPrompt.CryptoObject(cipher))
                } else {
                    onSkip()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("vault_bio_enable"),
        ) { Text(stringResource(R.string.shelldon_vault_bio_enable)) }
        TextButton(onClick = onSkip) { Text(stringResource(R.string.shelldon_vault_bio_not_now)) }
    }
}

@Composable
internal fun ResetVaultDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shelldon_vault_reset_title)) },
        text = { Text(stringResource(R.string.shelldon_vault_reset_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("vault_reset_confirm")) {
                Text(stringResource(R.string.shelldon_vault_reset_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.shelldon_cancel)) }
        },
    )
}

@Composable
private fun VaultScaffold(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                content()
            }
        }
    }
}

@Composable
private fun VaultTitle(
    title: String,
    body: String?,
    icon: ImageVector = Icons.Default.Lock,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text = title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BusyButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit, testTag: String) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.shelldon_vault_working), modifier = Modifier.padding(start = 8.dp))
        } else {
            Text(text)
        }
    }
}

/** A password box that never autocorrects and can reveal what was typed. */
@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
    testTag: String = "",
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(if (visible) R.string.shelldon_vault_hide_password else R.string.shelldon_vault_show_password),
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
    )
}

@Composable
internal fun errorMessage(error: VaultError): String = when (error) {
    VaultError.TOO_SHORT -> stringResource(R.string.shelldon_vault_err_short, Vault.MIN_PASSWORD_LENGTH)
    VaultError.MISMATCH -> stringResource(R.string.shelldon_vault_err_mismatch)
    VaultError.CREATE_FAILED -> stringResource(R.string.shelldon_vault_err_create)
    VaultError.WRONG_PASSWORD -> stringResource(R.string.shelldon_vault_err_wrong)
    VaultError.DAMAGED -> stringResource(R.string.shelldon_vault_err_damaged)
    VaultError.BIOMETRIC_CHANGED -> stringResource(R.string.shelldon_vault_bio_changed)
    VaultError.BIOMETRIC_FAILED -> stringResource(R.string.shelldon_vault_err_bio)
}
