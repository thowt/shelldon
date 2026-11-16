/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.snippets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import org.connectbot.R
import org.connectbot.service.TerminalBridge
import org.connectbot.shelldon.snippets.Snippet
import org.connectbot.shelldon.snippets.SnippetRejection
import org.connectbot.shelldon.snippets.SnippetResult
import org.connectbot.ui.LocalTerminalManager

private fun TerminalBridge.displayName(): String = host.nickname.ifBlank { host.hostname }

/**
 * The Snippets tab: the text you send to sessions over and over. Tap a snippet to edit it, tap the
 * arrow to send it to an open session. They are the same macros the keyboard uses, so a snippet can
 * also be put on a keyboard button from the keyboard settings.
 */
@Composable
fun ShelldonSnippetsScreen(
    modifier: Modifier = Modifier,
    viewModel: SnippetsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val terminalManager = LocalTerminalManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<SnippetRejection?>(null) }
    var deleting by remember { mutableStateOf<Snippet?>(null) }
    var pickingSession by remember { mutableStateOf<Pair<Snippet, List<TerminalBridge>>?>(null) }
    var confirming by remember { mutableStateOf<Pair<Snippet, TerminalBridge>?>(null) }

    fun say(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun send(snippet: Snippet, bridge: TerminalBridge) {
        bridge.sendKeyboardMacro(snippet.document)
        say(context.getString(R.string.shelldon_snippet_sent, snippet.name, bridge.displayName()))
    }

    fun sendTo(snippet: Snippet, bridge: TerminalBridge) {
        if (snippet.body.isScript) confirming = snippet to bridge else send(snippet, bridge)
    }

    fun requestSend(snippet: Snippet) {
        val sessions = terminalManager?.bridgesFlow?.value.orEmpty().filter { it.isSessionOpen }
        when (sessions.size) {
            0 -> say(context.getString(R.string.shelldon_snippet_no_session))
            1 -> sendTo(snippet, sessions.single())
            else -> pickingSession = snippet to sessions
        }
    }

    val shown = remember(state.snippets, query) { filterSnippets(state.snippets, query) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editError = null
                    creating = true
                },
                modifier = Modifier.testTag("snippet_new"),
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.shelldon_snippet_new))
            }
        },
    ) { padding ->
        if (state.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "title") {
                    Text(
                        text = stringResource(R.string.shelldon_snippets_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (state.snippets.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.shelldon_snippets_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("snippets_empty"),
                        )
                    }
                } else {
                    item(key = "search") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.shelldon_snippets_search)) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.shelldon_clear_search))
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("snippets_search"),
                        )
                    }
                    if (shown.isEmpty()) {
                        item(key = "no_match") {
                            Text(
                                text = stringResource(R.string.shelldon_snippets_no_match, query.trim()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(shown, key = { it.id }) { snippet ->
                        SnippetCard(
                            snippet = snippet,
                            onEdit = {
                                editError = null
                                editing = snippet
                            },
                            onSend = { requestSend(snippet) },
                        )
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        val existing = editing
        SnippetEditorDialog(
            existing = existing,
            error = editError,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { name, body ->
                viewModel.save(existing?.id, name, body) { result ->
                    when (result) {
                        is SnippetResult.Ok -> {
                            creating = false
                            editing = null
                        }

                        is SnippetResult.Rejected -> editError = result.reason

                        is SnippetResult.InUse -> Unit
                    }
                }
            },
            onDelete = existing?.let { snippet -> { deleting = snippet } },
        )
    }

    deleting?.let { snippet ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.shelldon_snippet_delete_title)) },
            text = { Text(stringResource(R.string.shelldon_snippet_delete_body, snippet.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        viewModel.delete(snippet.id) { result ->
                            when (result) {
                                is SnippetResult.InUse -> say(
                                    if (result.users.isEmpty()) {
                                        context.getString(R.string.shelldon_snippet_in_use_unknown)
                                    } else {
                                        context.getString(R.string.shelldon_snippet_in_use, result.users.joinToString(", "))
                                    },
                                )

                                is SnippetResult.Rejected -> say(context.getString(result.reason.messageRes()))

                                is SnippetResult.Ok -> {
                                    creating = false
                                    editing = null
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("snippet_delete_confirm"),
                ) {
                    Text(stringResource(R.string.shelldon_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.shelldon_cancel)) }
            },
        )
    }

    pickingSession?.let { (snippet, sessions) ->
        AlertDialog(
            onDismissRequest = { pickingSession = null },
            title = { Text(stringResource(R.string.shelldon_snippet_send_to)) },
            text = {
                Column {
                    sessions.forEach { bridge ->
                        Text(
                            text = bridge.displayName(),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pickingSession = null
                                    sendTo(snippet, bridge)
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickingSession = null }) { Text(stringResource(R.string.shelldon_cancel)) }
            },
        )
    }

    confirming?.let { (snippet, bridge) ->
        SnippetScriptConfirmDialog(
            snippet = snippet,
            targetName = bridge.displayName(),
            onConfirm = {
                confirming = null
                send(snippet, bridge)
            },
            onDismiss = { confirming = null },
        )
    }
}

@Composable
private fun SnippetCard(
    snippet: Snippet,
    onEdit: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("snippet_card_${snippet.name}"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onEdit)
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = snippet.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = snippet.body.firstLine,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (snippet.body.isScript) {
                    Text(
                        text = stringResource(R.string.shelldon_snippet_lines, snippet.body.lineCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onSend, modifier = Modifier.testTag("snippet_send_${snippet.name}")) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.shelldon_snippet_send_cd, snippet.name),
                )
            }
        }
    }
}
