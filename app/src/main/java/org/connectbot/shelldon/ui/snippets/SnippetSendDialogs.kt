/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.snippets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.connectbot.R
import org.connectbot.shelldon.snippets.Snippet

/**
 * A script (more than one line) starts running as soon as it is sent, so it is shown first and
 * only sent after a tap. A single line is sent without asking.
 */
@Composable
internal fun SnippetScriptConfirmDialog(
    snippet: Snippet,
    targetName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shelldon_snippet_script_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.shelldon_snippet_script_body, snippet.body.lineCount, targetName))
                Text(
                    text = snippet.body.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.testTag("snippet_script_text"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("snippet_script_send")) {
                Text(stringResource(R.string.shelldon_snippet_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.shelldon_cancel)) }
        },
    )
}

/**
 * The snippet list as a dialog, for sending one to the open session from the console.
 * [onSend] is called after any confirmation; the dialog then closes itself.
 */
@Composable
internal fun SnippetPickerDialog(
    targetName: String,
    onSend: (Snippet) -> Unit,
    onDismiss: () -> Unit,
    viewModel: SnippetsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var query by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf<Snippet?>(null) }
    val shown = remember(state.snippets, query) { filterSnippets(state.snippets, query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shelldon_snippets_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.snippets.isEmpty() && !state.isLoading) {
                    Text(stringResource(R.string.shelldon_snippets_empty_short))
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.shelldon_snippets_search)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("snippet_picker_search"),
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(shown, key = { it.id }) { snippet ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (snippet.body.isScript) confirming = snippet else onSend(snippet)
                                    }
                                    .padding(vertical = 10.dp)
                                    .testTag("snippet_pick_${snippet.name}"),
                            ) {
                                Text(snippet.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = snippet.body.firstLine,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.shelldon_cancel)) }
        },
    )

    confirming?.let { snippet ->
        SnippetScriptConfirmDialog(
            snippet = snippet,
            targetName = targetName,
            onConfirm = {
                confirming = null
                onSend(snippet)
            },
            onDismiss = { confirming = null },
        )
    }
}

/** Case-insensitive match on the name and the text. */
internal fun filterSnippets(snippets: List<Snippet>, query: String): List<Snippet> {
    val q = query.trim()
    return if (q.isEmpty()) {
        snippets
    } else {
        snippets.filter { it.name.contains(q, ignoreCase = true) || it.body.text.contains(q, ignoreCase = true) }
    }
}
