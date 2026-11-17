/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.keychain

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.connectbot.R
import org.connectbot.piv.YubiKeyPiv
import org.connectbot.ui.components.YubiKeyPivDialog
import org.connectbot.ui.screens.pubkeylist.ImportFromClipboardDialog
import org.connectbot.ui.screens.pubkeylist.ImportPasswordDialog
import org.connectbot.ui.screens.pubkeylist.NicknameConfirmationDialog
import org.connectbot.ui.screens.pubkeylist.PubkeyListViewModel

/**
 * Adding a key from the Keychain without a detour: a sheet with the five ways to get a key onto the
 * phone, and the dialogs the import paths need. The work is done by the same view model the key list
 * uses, so a key imported here behaves exactly like one imported there.
 *
 * Generating a key and importing from a security key have screens of their own and are handed back
 * through [onGenerate] and [onImportFido2].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddKeyHost(
    sheetVisible: Boolean,
    onDismissSheet: () -> Unit,
    onGenerate: () -> Unit,
    onImportFido2: () -> Unit,
    onMessage: suspend (String) -> Unit,
    viewModel: PubkeyListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            onMessage(message)
            viewModel.clearError()
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { viewModel.importKeyFromUri(it) }
    }

    var showClipboardDialog by rememberSaveable { mutableStateOf(false) }
    if (showClipboardDialog) {
        ImportFromClipboardDialog(
            onDismiss = { showClipboardDialog = false },
            onImport = { keyText ->
                showClipboardDialog = false
                viewModel.importKeyFromText(keyText, "clipboard-key")
            },
        )
    }

    var showYubiKeyDialog by remember { mutableStateOf(false) }
    if (showYubiKeyDialog) {
        YubiKeyPivDialog(
            onDismiss = { showYubiKeyDialog = false },
            onComplete = { slot, publicKey ->
                showYubiKeyDialog = false
                viewModel.registerYubiKey(slot, publicKey)
            },
            operation = { activity, slot, _ -> YubiKeyPiv.readPublicKey(activity, slot) },
        )
    }

    val pendingNickname = uiState.pendingNicknameConfirmation
    if (pendingNickname != null) {
        NicknameConfirmationDialog(
            initialNickname = pendingNickname.nickname,
            isSaving = uiState.isSavingImportNickname,
            error = uiState.importNicknameError,
            onDismiss = { viewModel.cancelImportNickname() },
            onConfirm = { nickname -> viewModel.confirmImportNickname(nickname) },
        )
    }

    val pendingImport = uiState.pendingImport
    if (pendingImport != null) {
        ImportPasswordDialog(
            keyType = pendingImport.keyType,
            initialNickname = pendingImport.nickname,
            onDismiss = { viewModel.cancelImport() },
            onImport = { nickname, decryptPassword, encrypt, encryptPassword ->
                viewModel.completeImportWithPassword(nickname, decryptPassword, encrypt, encryptPassword)
            },
        )
    }

    if (sheetVisible) {
        ModalBottomSheet(
            onDismissRequest = onDismissSheet,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            modifier = Modifier.testTag("key_add_sheet"),
        ) {
            Text(
                text = stringResource(R.string.shelldon_key_add),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            AddKeyOption(
                icon = Icons.Default.Add,
                title = stringResource(R.string.shelldon_key_add_generate),
                subtitle = stringResource(R.string.shelldon_key_add_generate_sub),
                tag = "key_add_generate",
                onClick = {
                    onDismissSheet()
                    onGenerate()
                },
            )
            AddKeyOption(
                icon = Icons.Default.FileOpen,
                title = stringResource(R.string.shelldon_key_add_file),
                subtitle = stringResource(R.string.shelldon_key_add_file_sub),
                tag = "key_add_file",
                onClick = {
                    onDismissSheet()
                    filePicker.launch(arrayOf("*/*"))
                },
            )
            AddKeyOption(
                icon = Icons.Default.ContentPaste,
                title = stringResource(R.string.shelldon_key_add_clipboard),
                subtitle = stringResource(R.string.shelldon_key_add_clipboard_sub),
                tag = "key_add_clipboard",
                onClick = {
                    onDismissSheet()
                    showClipboardDialog = true
                },
            )
            AddKeyOption(
                icon = Icons.Default.Key,
                title = stringResource(R.string.shelldon_key_add_fido2),
                subtitle = stringResource(R.string.shelldon_key_add_fido2_sub),
                tag = "key_add_fido2",
                onClick = {
                    onDismissSheet()
                    onImportFido2()
                },
            )
            AddKeyOption(
                icon = Icons.Outlined.Lock,
                title = stringResource(R.string.shelldon_key_add_piv),
                subtitle = stringResource(R.string.shelldon_key_add_piv_sub),
                tag = "key_add_piv",
                onClick = {
                    onDismissSheet()
                    showYubiKeyDialog = true
                },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AddKeyOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tag: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag(tag),
    )
}
