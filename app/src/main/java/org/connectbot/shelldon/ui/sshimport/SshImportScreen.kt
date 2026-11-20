/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.sshimport

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.flow.StateFlow
import org.connectbot.R
import org.connectbot.shelldon.sshconfig.IdentityLinkState
import org.connectbot.shelldon.sshconfig.KeyFileState
import org.connectbot.shelldon.sshconfig.PlannedHost
import org.connectbot.shelldon.sshconfig.PlannedKey
import org.connectbot.shelldon.sshconfig.SshImportOptions
import org.connectbot.shelldon.sshconfig.SshImportPlan
import org.connectbot.shelldon.sshconfig.SshImportResult

/**
 * Bring hosts, algorithm settings, host keys and private keys over from an OpenSSH `~/.ssh` folder.
 * Everything is shown first; nothing is written until the person taps Import.
 */
@Composable
fun SshImportScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SshImportViewModel = hiltViewModel(),
) {
    SshImportContent(
        uiState = viewModel.uiState,
        onNavigateBack = onNavigateBack,
        onFilesPick = viewModel::filesPicked,
        onUnlock = viewModel::unlock,
        onOptionsChange = viewModel::optionsChanged,
        onImport = viewModel::import,
        onStartOver = viewModel::startOver,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SshImportContent(
    uiState: StateFlow<SshImportUiState>,
    onNavigateBack: () -> Unit,
    onFilesPick: (List<Uri>) -> Unit,
    onUnlock: (fileName: String, passphrase: String) -> Unit,
    onOptionsChange: (SshImportOptions) -> Unit,
    onImport: () -> Unit,
    onStartOver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by uiState.collectAsState()
    val currentOnFilesPick by rememberUpdatedState(onFilesPick)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> currentOnFilesPick(uris) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shelldon_sshimport_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.button_close))
                    }
                },
            )
        },
        bottomBar = {
            val plan = ui.plan
            if (ui.phase == SshImportPhase.PREVIEW && plan != null) {
                PreviewButtons(
                    canImport = willWriteSomething(plan, ui.options),
                    onImport = onImport,
                    onStartOver = onStartOver,
                )
            }
        },
    ) { padding ->
        val body = Modifier.padding(padding)
        when (ui.phase) {
            SshImportPhase.PICK -> PickBody(
                error = ui.error,
                onPick = { picker.launch(arrayOf("*/*")) },
                modifier = body,
            )

            SshImportPhase.ANALYZING -> ProgressBody(R.string.shelldon_sshimport_analyzing, body)

            SshImportPhase.IMPORTING -> ProgressBody(R.string.shelldon_sshimport_importing, body)

            SshImportPhase.PREVIEW -> ui.plan?.let { plan ->
                PreviewBody(
                    plan = plan,
                    options = ui.options,
                    error = ui.error,
                    onOptionsChange = onOptionsChange,
                    onUnlock = onUnlock,
                    modifier = body,
                )
            }

            SshImportPhase.DONE -> ui.result?.let { result ->
                DoneBody(result = result, onClose = onNavigateBack, modifier = body)
            }
        }
    }
}

private fun willWriteSomething(plan: SshImportPlan, options: SshImportOptions): Boolean = plan.newHosts.isNotEmpty() ||
    (options.keys && plan.keys.any { it.state == KeyFileState.IMPORT }) ||
    (options.knownHosts && plan.knownHosts?.add?.isNotEmpty() == true)

@Composable
private fun PickBody(
    error: SshImportError?,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.shelldon_sshimport_pick_intro), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.shelldon_sshimport_keys_note), style = MaterialTheme.typography.bodyMedium)
        error?.let {
            Text(
                text = stringResource(it.messageRes()),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("sshimport_error"),
            )
        }
        Button(onClick = onPick, modifier = Modifier.testTag("sshimport_pick")) {
            Text(stringResource(R.string.shelldon_sshimport_pick_button))
        }
    }
}

private fun SshImportError.messageRes(): Int = when (this) {
    SshImportError.READ_FAILED -> R.string.shelldon_sshimport_error_read
    SshImportError.NOTHING_FOUND -> R.string.shelldon_sshimport_error_nothing
    SshImportError.VAULT_LOCKED -> R.string.shelldon_sshimport_error_vault
    SshImportError.IMPORT_FAILED -> R.string.shelldon_sshimport_error_failed
}

@Composable
private fun ProgressBody(textRes: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(stringResource(textRes))
    }
}

@Composable
private fun PreviewButtons(
    canImport: Boolean,
    onImport: () -> Unit,
    onStartOver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onStartOver, modifier = Modifier.testTag("sshimport_start_over")) {
            Text(stringResource(R.string.shelldon_sshimport_start_over))
        }
        Button(onClick = onImport, enabled = canImport, modifier = Modifier.testTag("sshimport_import")) {
            Text(stringResource(R.string.shelldon_sshimport_import_button))
        }
    }
}

@Composable
private fun PreviewBody(
    plan: SshImportPlan,
    options: SshImportOptions,
    error: SshImportError?,
    onOptionsChange: (SshImportOptions) -> Unit,
    onUnlock: (fileName: String, passphrase: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val newHosts = plan.newHosts
    LazyColumn(modifier = modifier.fillMaxSize().testTag("sshimport_preview")) {
        error?.let {
            item {
                Text(
                    text = stringResource(it.messageRes()),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp).testTag("sshimport_error"),
                )
            }
        }

        if (plan.hosts.isNotEmpty()) {
            item { SectionHeader(R.string.shelldon_sshimport_section_hosts) }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.shelldon_sshimport_hosts_new, newHosts.size), modifier = Modifier.testTag("sshimport_hosts_new"))
                    Text(stringResource(R.string.shelldon_sshimport_hosts_existing, plan.hosts.size - newHosts.size))
                }
            }
        }

        algorithmItems(newHosts, options, onOptionsChange)
        keyItems(plan, options, onOptionsChange, onUnlock)
        knownHostItems(plan, options, onOptionsChange)
        notImportedItems(plan)

        if (newHosts.isNotEmpty()) {
            item { SectionHeader(R.string.shelldon_sshimport_section_hosts) }
            items(newHosts, key = { it.id }) { HostRow(it) }
        }
    }
}

private fun LazyListScope.algorithmItems(
    newHosts: List<PlannedHost>,
    options: SshImportOptions,
    onOptionsChange: (SshImportOptions) -> Unit,
) {
    val explicit = newHosts.filter { host -> host.algorithms.any { !it.fromWildcard } }
    val wildcard = newHosts.filter { host -> host.algorithms.any { it.fromWildcard } }
    val dropped = newHosts.flatMap { it.droppedAlgorithms }.distinct()
    if (explicit.isEmpty() && wildcard.isEmpty() && dropped.isEmpty()) return

    item { SectionHeader(R.string.shelldon_sshimport_section_algorithms) }
    if (explicit.isNotEmpty()) {
        item {
            SwitchRow(
                title = stringResource(R.string.shelldon_sshimport_alg_explicit_title),
                summary = stringResource(
                    R.string.shelldon_sshimport_alg_explicit_summary,
                    explicit.size,
                    explicit.count { host -> host.algorithms.any { !it.fromWildcard && it.older } },
                ),
                checked = options.algorithms,
                onCheckedChange = { onOptionsChange(options.copy(algorithms = it)) },
                testTag = "sshimport_option_algorithms",
            )
        }
    }
    if (wildcard.isNotEmpty()) {
        item {
            SwitchRow(
                title = stringResource(R.string.shelldon_sshimport_alg_wildcard_title),
                summary = stringResource(R.string.shelldon_sshimport_alg_wildcard_summary, wildcard.size),
                checked = options.wildcardAlgorithms,
                onCheckedChange = { onOptionsChange(options.copy(wildcardAlgorithms = it)) },
                testTag = "sshimport_option_wildcard",
            )
        }
    }
    val lowered = newHosts.count { it.lowersDhMinimum }
    if (lowered > 0) item { Note(stringResource(R.string.shelldon_sshimport_alg_dh, lowered)) }
    if (dropped.isNotEmpty()) item { Note(stringResource(R.string.shelldon_sshimport_alg_dropped, dropped.preview())) }
}

private fun LazyListScope.keyItems(
    plan: SshImportPlan,
    options: SshImportOptions,
    onOptionsChange: (SshImportOptions) -> Unit,
    onUnlock: (fileName: String, passphrase: String) -> Unit,
) {
    val linked = plan.newHosts.count { it.identityKeyId != null && (it.identityState != IdentityLinkState.IMPORTED_KEY || options.keys) }
    val missing = plan.newHosts.filter { it.identityState == IdentityLinkState.MISSING }.mapNotNull { it.identityName }.distinct()
    if (plan.keys.isEmpty() && linked == 0 && missing.isEmpty()) return

    item { SectionHeader(R.string.shelldon_sshimport_section_keys) }
    if (plan.keys.any { it.state == KeyFileState.IMPORT }) {
        item {
            SwitchRow(
                title = stringResource(R.string.shelldon_sshimport_keys_switch),
                summary = stringResource(R.string.shelldon_sshimport_keys_note),
                checked = options.keys,
                onCheckedChange = { onOptionsChange(options.copy(keys = it)) },
                testTag = "sshimport_option_keys",
            )
        }
    }
    items(plan.keys, key = { it.fileName }) { key -> KeyRow(key, onUnlock) }
    if (linked > 0) item { Note(stringResource(R.string.shelldon_sshimport_identity_linked, linked)) }
    if (missing.isNotEmpty()) item { Note(stringResource(R.string.shelldon_sshimport_identity_missing, missing.preview())) }
}

private fun LazyListScope.knownHostItems(
    plan: SshImportPlan,
    options: SshImportOptions,
    onOptionsChange: (SshImportOptions) -> Unit,
) {
    val known = plan.knownHosts ?: return
    item { SectionHeader(R.string.shelldon_sshimport_section_known) }
    if (known.add.isNotEmpty()) {
        item {
            SwitchRow(
                title = stringResource(R.string.shelldon_sshimport_known_switch),
                summary = stringResource(R.string.shelldon_sshimport_known_add, known.add.size, known.add.map { it.hostId }.distinct().size),
                checked = options.knownHosts,
                onCheckedChange = { onOptionsChange(options.copy(knownHosts = it)) },
                testTag = "sshimport_option_known_hosts",
            )
        }
    }
    item {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.shelldon_sshimport_known_lines, known.totalLines), modifier = Modifier.testTag("sshimport_known_lines"))
            if (known.add.isEmpty()) Text(stringResource(R.string.shelldon_sshimport_known_add, 0, 0))
            if (known.alreadyKnown > 0) Text(stringResource(R.string.shelldon_sshimport_known_already, known.alreadyKnown))
            if (known.unmatchedLines > 0) Text(stringResource(R.string.shelldon_sshimport_known_unmatched, known.unmatchedLines), modifier = Modifier.testTag("sshimport_known_unmatched"))
            if (known.conflicts.isNotEmpty()) Text(stringResource(R.string.shelldon_sshimport_known_conflicts, known.conflicts.preview()), color = MaterialTheme.colorScheme.error)
            if (known.ambiguous.isNotEmpty()) Text(stringResource(R.string.shelldon_sshimport_known_ambiguous, known.ambiguous.preview()))
            if (known.certAuthorityLines + known.revokedLines + known.unreadableLines > 0) {
                Text(stringResource(R.string.shelldon_sshimport_known_other, known.certAuthorityLines, known.revokedLines, known.unreadableLines))
            }
        }
    }
}

private fun LazyListScope.notImportedItems(plan: SshImportPlan) {
    if (plan.refused.isEmpty() && plan.unsupportedOptions.isEmpty() && plan.matchBlocks == 0 && plan.includes.isEmpty() && plan.ignoredFiles.isEmpty()) return
    item { SectionHeader(R.string.shelldon_sshimport_section_not_imported) }
    item {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            plan.refused.forEach { (option, aliases) ->
                Text(stringResource(R.string.shelldon_sshimport_refused, option, aliases.preview()), modifier = Modifier.testTag("sshimport_refused"))
            }
            if (plan.unsupportedOptions.isNotEmpty()) Text(stringResource(R.string.shelldon_sshimport_unsupported, plan.unsupportedOptions.toList().preview(MAX_OPTIONS)))
            if (plan.matchBlocks > 0 || plan.includes.isNotEmpty()) Text(stringResource(R.string.shelldon_sshimport_blocks))
            if (plan.ignoredFiles.isNotEmpty()) Text(stringResource(R.string.shelldon_sshimport_ignored, plan.ignoredFiles.preview()))
        }
    }
}

@Composable
private fun KeyRow(
    key: PlannedKey,
    onUnlock: (fileName: String, passphrase: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = when (key.state) {
        KeyFileState.IMPORT -> R.string.shelldon_sshimport_key_import
        KeyFileState.ALREADY_IN_KEYCHAIN -> R.string.shelldon_sshimport_key_already
        KeyFileState.NEEDS_PASSPHRASE -> R.string.shelldon_sshimport_key_passphrase
        KeyFileState.WRONG_PASSPHRASE -> R.string.shelldon_sshimport_key_wrong
        KeyFileState.UNREADABLE -> R.string.shelldon_sshimport_key_unreadable
        KeyFileState.NAME_TAKEN -> R.string.shelldon_sshimport_key_name_taken
    }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(key.fileName, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(state), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("sshimport_key_state_${key.fileName}"))
        if (key.state == KeyFileState.NEEDS_PASSPHRASE || key.state == KeyFileState.WRONG_PASSPHRASE) {
            var passphrase by remember(key.fileName) { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.shelldon_sshimport_key_passphrase_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.weight(1f).testTag("sshimport_key_passphrase_${key.fileName}"),
                )
                TextButton(
                    onClick = { onUnlock(key.fileName, passphrase) },
                    enabled = passphrase.isNotEmpty(),
                    modifier = Modifier.testTag("sshimport_key_unlock_${key.fileName}"),
                ) { Text(stringResource(R.string.shelldon_sshimport_key_unlock)) }
            }
        }
    }
}

@Composable
private fun HostRow(planned: PlannedHost, modifier: Modifier = Modifier) {
    val entry = planned.entry
    val address = buildString {
        if (!entry.user.isNullOrEmpty()) append(entry.user).append('@')
        append(entry.hostName)
        if (entry.port != DEFAULT_PORT) append(':').append(entry.port)
    }
    val older = stringResource(R.string.shelldon_sshimport_host_older)
    val tags = buildList {
        if (planned.algorithms.any { it.older }) add(older)
        planned.identityName?.takeIf { planned.identityState != IdentityLinkState.MISSING }?.let { add(stringResource(R.string.shelldon_sshimport_host_key, it)) }
    }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(entry.alias, style = MaterialTheme.typography.bodyLarge)
        Text((listOf(address) + tags).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DoneBody(
    result: SshImportResult,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp).testTag("sshimport_done"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.shelldon_sshimport_done_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.shelldon_sshimport_done_hosts, result.hostsAdded, result.hostsSkipped))
        if (result.hostsWithAlgorithms > 0) Text(stringResource(R.string.shelldon_sshimport_done_algorithms, result.hostsWithAlgorithms))
        if (result.keysImported > 0 || result.hostsLinkedToKey > 0) Text(stringResource(R.string.shelldon_sshimport_done_keys, result.keysImported, result.hostsLinkedToKey))
        if (result.knownHostsAdded > 0) Text(stringResource(R.string.shelldon_sshimport_done_known, result.knownHostsAdded))
        if (result.jumpHostsLinked > 0) Text(stringResource(R.string.shelldon_sshimport_done_jump, result.jumpHostsLinked))
        if (result.jumpHostsUnresolved.isNotEmpty()) Text(stringResource(R.string.shelldon_sshimport_done_jump_unresolved, result.jumpHostsUnresolved.preview()))
        Button(onClick = onClose, modifier = Modifier.testTag("sshimport_done_close")) {
            Text(stringResource(R.string.shelldon_sshimport_done_close))
        }
    }
}

@Composable
private fun SectionHeader(titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ListItem(
            supportingContent = { Text(summary) },
            trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
            modifier = Modifier.clickable { onCheckedChange(!checked) }.testTag(testTag),
        ) { Text(title) }
        HorizontalDivider()
    }
}

private fun List<String>.preview(max: Int = MAX_LISTED): String = take(max).joinToString(", ") + if (size > max) ", …" else ""

private const val MAX_LISTED = 6
private const val MAX_OPTIONS = 10
private const val DEFAULT_PORT = 22
