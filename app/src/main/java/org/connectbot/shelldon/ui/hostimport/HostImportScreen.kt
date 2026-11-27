/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.hostimport

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.flow.StateFlow
import org.connectbot.R
import org.connectbot.shelldon.hostimport.CsvField
import org.connectbot.shelldon.hostimport.HostImportOptions
import org.connectbot.shelldon.hostimport.HostImportPlan
import org.connectbot.shelldon.hostimport.HostImportResult
import org.connectbot.shelldon.hostimport.HostToImport
import org.connectbot.shelldon.hostimport.SourceFormat
import org.connectbot.shelldon.ui.shell.shelldonBarHeight

/**
 * Bring hosts over from another SSH app: a CSV file, MobaXterm sessions, a PuTTY registry export or
 * an mRemoteNG file. Everything is shown first and nothing is written until the person taps Import.
 */
@Composable
fun HostImportScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HostImportViewModel = hiltViewModel(),
) {
    HostImportContent(
        uiState = viewModel.uiState,
        onNavigateBack = onNavigateBack,
        onFilePick = viewModel::filePicked,
        onOptionsChange = viewModel::optionsChanged,
        onColumnChange = viewModel::columnChosen,
        onHeaderChange = viewModel::headerChanged,
        onImport = viewModel::import,
        onStartOver = viewModel::startOver,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostImportContent(
    uiState: StateFlow<HostImportUiState>,
    onNavigateBack: () -> Unit,
    onFilePick: (Uri?) -> Unit,
    onOptionsChange: (HostImportOptions) -> Unit,
    onColumnChange: (CsvField, Int?) -> Unit,
    onHeaderChange: (Boolean) -> Unit,
    onImport: () -> Unit,
    onStartOver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by uiState.collectAsState()
    val currentOnFilePick by rememberUpdatedState(onFilePick)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> currentOnFilePick(uri) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                expandedHeight = shelldonBarHeight(),
                title = { Text(stringResource(R.string.shelldon_hostimport_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.button_close))
                    }
                },
            )
        },
        bottomBar = {
            val plan = ui.plan
            if (ui.phase == HostImportPhase.PREVIEW && plan != null) {
                PreviewButtons(canImport = plan.newHosts.isNotEmpty(), onImport = onImport, onStartOver = onStartOver)
            }
        },
    ) { padding ->
        val body = Modifier.padding(padding)
        when (ui.phase) {
            HostImportPhase.PICK -> PickBody(
                error = ui.error,
                onPick = { picker.launch(arrayOf("*/*")) },
                modifier = body,
            )

            HostImportPhase.ANALYZING -> ProgressBody(R.string.shelldon_hostimport_analyzing, body)

            HostImportPhase.IMPORTING -> ProgressBody(R.string.shelldon_hostimport_importing, body)

            HostImportPhase.PREVIEW -> ui.plan?.let { plan ->
                PreviewBody(
                    ui = ui,
                    plan = plan,
                    onOptionsChange = onOptionsChange,
                    onColumnChange = onColumnChange,
                    onHeaderChange = onHeaderChange,
                    modifier = body,
                )
            }

            HostImportPhase.DONE -> ui.result?.let { result ->
                DoneBody(result = result, onClose = onNavigateBack, modifier = body)
            }
        }
    }
}

@Composable
private fun PickBody(
    error: HostImportError?,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.shelldon_hostimport_pick_intro), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.shelldon_hostimport_passwords_note), style = MaterialTheme.typography.bodyMedium)
        error?.let {
            Text(
                text = stringResource(it.messageRes()),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("hostimport_error"),
            )
        }
        Button(onClick = onPick, modifier = Modifier.testTag("hostimport_pick")) {
            Text(stringResource(R.string.shelldon_hostimport_pick_button))
        }
    }
}

private fun HostImportError.messageRes(): Int = when (this) {
    HostImportError.READ_FAILED -> R.string.shelldon_hostimport_error_read
    HostImportError.TOO_BIG -> R.string.shelldon_hostimport_error_big
    HostImportError.UNRECOGNIZED -> R.string.shelldon_hostimport_error_unrecognized
    HostImportError.ENCRYPTED -> R.string.shelldon_hostimport_error_encrypted
    HostImportError.UNSAFE_XML -> R.string.shelldon_hostimport_error_xml
    HostImportError.MALFORMED -> R.string.shelldon_hostimport_error_malformed
    HostImportError.NOTHING_FOUND -> R.string.shelldon_hostimport_error_nothing
    HostImportError.IMPORT_FAILED -> R.string.shelldon_hostimport_error_failed
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
        OutlinedButton(onClick = onStartOver, modifier = Modifier.testTag("hostimport_start_over")) {
            Text(stringResource(R.string.shelldon_hostimport_start_over))
        }
        Button(onClick = onImport, enabled = canImport, modifier = Modifier.testTag("hostimport_import")) {
            Text(stringResource(R.string.shelldon_hostimport_import_button))
        }
    }
}

@Composable
private fun PreviewBody(
    ui: HostImportUiState,
    plan: HostImportPlan,
    onOptionsChange: (HostImportOptions) -> Unit,
    onColumnChange: (CsvField, Int?) -> Unit,
    onHeaderChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val newHosts = plan.newHosts
    LazyColumn(modifier = modifier.fillMaxSize().testTag("hostimport_preview")) {
        ui.error?.let {
            item {
                Text(
                    text = stringResource(it.messageRes()),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp).testTag("hostimport_error"),
                )
            }
        }
        item { SourceLine(ui.format) }
        ui.csv?.let { csv -> columnItems(csv, onColumnChange, onHeaderChange) }
        summaryItems(plan)
        telnetItems(plan, ui.options, onOptionsChange)
        notImportedItems(plan)

        if (newHosts.isNotEmpty()) {
            item { SectionHeader(R.string.shelldon_hostimport_section_list) }
            items(newHosts.take(MAX_LISTED_HOSTS), key = { it.id }) { HostRow(it) }
            if (newHosts.size > MAX_LISTED_HOSTS) {
                item { Note(stringResource(R.string.shelldon_hostimport_more, newHosts.size - MAX_LISTED_HOSTS)) }
            }
        }
    }
}

@Composable
private fun SourceLine(format: SourceFormat?) {
    val name = when (format) {
        SourceFormat.CSV -> R.string.shelldon_hostimport_format_csv
        SourceFormat.MOBAXTERM -> R.string.shelldon_hostimport_format_mobaxterm
        SourceFormat.PUTTY -> R.string.shelldon_hostimport_format_putty
        SourceFormat.MREMOTENG -> R.string.shelldon_hostimport_format_mremoteng
        null -> return
    }
    Text(
        text = stringResource(R.string.shelldon_hostimport_found, stringResource(name)),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(16.dp).testTag("hostimport_format"),
    )
}

private fun LazyListScope.columnItems(
    csv: CsvView,
    onColumnChange: (CsvField, Int?) -> Unit,
    onHeaderChange: (Boolean) -> Unit,
) {
    item { SectionHeader(R.string.shelldon_hostimport_section_columns) }
    item {
        SwitchRow(
            title = stringResource(R.string.shelldon_hostimport_header_switch),
            summary = stringResource(R.string.shelldon_hostimport_header_summary),
            checked = csv.hasHeader,
            onCheckedChange = onHeaderChange,
            testTag = "hostimport_header",
        )
    }
    items(CsvField.entries.toList(), key = { it.name }) { field ->
        ColumnRow(
            field = field,
            names = csv.columnNames,
            selected = csv.columns[field],
            onChoose = { onColumnChange(field, it) },
        )
    }
    if (!csv.columns.containsKey(CsvField.HOST)) {
        item { Note(stringResource(R.string.shelldon_hostimport_need_host), modifier = Modifier.testTag("hostimport_need_host")) }
    }
}

@Composable
private fun ColumnRow(
    field: CsvField,
    names: List<String>,
    selected: Int?,
    onChoose: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(field.labelRes()), style = MaterialTheme.typography.bodyLarge)
        Box {
            TextButton(onClick = { open = true }, modifier = Modifier.testTag("hostimport_column_${field.name}")) {
                Text(selected?.let { names.getOrNull(it) } ?: stringResource(R.string.shelldon_hostimport_column_none))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.shelldon_hostimport_column_none)) },
                    onClick = {
                        open = false
                        onChoose(null)
                    },
                )
                names.forEachIndexed { index, name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            open = false
                            onChoose(index)
                        },
                    )
                }
            }
        }
    }
}

private fun CsvField.labelRes(): Int = when (this) {
    CsvField.NAME -> R.string.shelldon_hostimport_field_name
    CsvField.HOST -> R.string.shelldon_hostimport_field_host
    CsvField.PORT -> R.string.shelldon_hostimport_field_port
    CsvField.USER -> R.string.shelldon_hostimport_field_user
    CsvField.FOLDER -> R.string.shelldon_hostimport_field_folder
    CsvField.TAGS -> R.string.shelldon_hostimport_field_tags
    CsvField.PROTOCOL -> R.string.shelldon_hostimport_field_protocol
}

private fun LazyListScope.summaryItems(plan: HostImportPlan) {
    if (plan.hosts.isEmpty()) return
    item { SectionHeader(R.string.shelldon_hostimport_section_hosts) }
    item {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.shelldon_hostimport_hosts_new, plan.newHosts.size), modifier = Modifier.testTag("hostimport_hosts_new"))
            Text(stringResource(R.string.shelldon_hostimport_hosts_existing, plan.existing))
            val renamed = plan.newHosts.count { it.renamedFrom != null }
            if (renamed > 0) Text(stringResource(R.string.shelldon_hostimport_hosts_renamed, renamed))
            if (plan.folderPaths.isNotEmpty()) Text(stringResource(R.string.shelldon_hostimport_folders, plan.folderPaths.size))
            if (plan.tagNames.isNotEmpty()) Text(stringResource(R.string.shelldon_hostimport_tags, plan.tagNames.size))
        }
    }
}

private fun LazyListScope.telnetItems(
    plan: HostImportPlan,
    options: HostImportOptions,
    onOptionsChange: (HostImportOptions) -> Unit,
) {
    val telnet = plan.telnetLeftOut + plan.hosts.count { it.protocol == "telnet" }
    if (telnet == 0) return
    item { SectionHeader(R.string.shelldon_hostimport_section_telnet) }
    item {
        SwitchRow(
            title = stringResource(R.string.shelldon_hostimport_telnet_switch),
            summary = stringResource(R.string.shelldon_hostimport_telnet_summary, telnet),
            checked = options.includeTelnet,
            onCheckedChange = { onOptionsChange(options.copy(includeTelnet = it)) },
            testTag = "hostimport_telnet",
        )
    }
}

private fun LazyListScope.notImportedItems(plan: HostImportPlan) {
    if (plan.otherProtocols == 0 && plan.invalid == 0 && plan.passwordsLeftBehind == 0) return
    item { SectionHeader(R.string.shelldon_hostimport_section_not_imported) }
    if (plan.otherProtocols > 0) item { Note(stringResource(R.string.shelldon_hostimport_other, plan.otherProtocols)) }
    if (plan.invalid > 0) item { Note(stringResource(R.string.shelldon_hostimport_invalid, plan.invalid), modifier = Modifier.testTag("hostimport_invalid")) }
    if (plan.passwordsLeftBehind > 0) item { Note(stringResource(R.string.shelldon_hostimport_passwords, plan.passwordsLeftBehind), modifier = Modifier.testTag("hostimport_passwords")) }
}

@Composable
private fun HostRow(host: HostToImport, modifier: Modifier = Modifier) {
    val address = buildString {
        if (host.username.isNotEmpty()) append(host.username).append('@')
        append(host.hostname)
        append(':').append(host.port)
    }
    val telnet = stringResource(R.string.shelldon_hostimport_row_telnet)
    val details = buildList {
        add(address)
        if (host.protocol == "telnet") add(telnet)
        if (host.folder.isNotEmpty()) add(host.folder.joinToString(" / "))
        if (host.tags.isNotEmpty()) add(host.tags.joinToString(", ") { "#$it" })
    }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(host.nickname, style = MaterialTheme.typography.bodyLarge)
        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DoneBody(
    result: HostImportResult,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp).testTag("hostimport_done"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.shelldon_hostimport_done_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.shelldon_hostimport_done_hosts, result.hostsAdded, result.hostsSkipped))
        if (result.foldersCreated > 0) Text(stringResource(R.string.shelldon_hostimport_done_folders, result.foldersCreated))
        if (result.tagsCreated > 0) Text(stringResource(R.string.shelldon_hostimport_done_tags, result.tagsCreated))
        Button(onClick = onClose, modifier = Modifier.testTag("hostimport_done_close")) {
            Text(stringResource(R.string.shelldon_hostimport_done_close))
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

private const val MAX_LISTED_HOSTS = 200
