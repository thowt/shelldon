/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.snippets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.connectbot.shelldon.snippets.Snippet
import org.connectbot.shelldon.snippets.SnippetBody
import org.connectbot.shelldon.snippets.SnippetRepository
import org.connectbot.shelldon.snippets.SnippetResult
import java.util.UUID
import javax.inject.Inject

data class SnippetsUiState(
    val snippets: List<Snippet> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class SnippetsViewModel @Inject constructor(private val repository: SnippetRepository) : ViewModel() {
    val uiState: StateFlow<SnippetsUiState> = repository.observeSnippets()
        .map { SnippetsUiState(snippets = it, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SnippetsUiState())

    /** @param existing the snippet being edited, or null for a new one */
    fun save(existing: UUID?, name: String, body: SnippetBody, onResult: (SnippetResult<UUID>) -> Unit) {
        viewModelScope.launch { onResult(repository.save(existing, name, body)) }
    }

    fun delete(id: UUID, onResult: (SnippetResult<Unit>) -> Unit) {
        viewModelScope.launch { onResult(repository.delete(id)) }
    }
}
