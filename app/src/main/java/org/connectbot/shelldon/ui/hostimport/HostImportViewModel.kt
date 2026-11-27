/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.hostimport

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.shelldon.hostimport.CsvField
import org.connectbot.shelldon.hostimport.CsvImport
import org.connectbot.shelldon.hostimport.CsvSource
import org.connectbot.shelldon.hostimport.HostImportOptions
import org.connectbot.shelldon.hostimport.HostImportPlan
import org.connectbot.shelldon.hostimport.HostImportResult
import org.connectbot.shelldon.hostimport.HostImporter
import org.connectbot.shelldon.hostimport.HostSource
import org.connectbot.shelldon.hostimport.HostSources
import org.connectbot.shelldon.hostimport.SavedHost
import org.connectbot.shelldon.hostimport.SourceFormat
import org.connectbot.shelldon.hostimport.SourceProblem
import org.connectbot.shelldon.hostimport.SourceResult
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject

enum class HostImportPhase { PICK, ANALYZING, PREVIEW, IMPORTING, DONE }

enum class HostImportError { READ_FAILED, TOO_BIG, UNRECOGNIZED, ENCRYPTED, UNSAFE_XML, MALFORMED, NOTHING_FOUND, IMPORT_FAILED }

/** How the columns of a CSV file are read, for the mapping controls. */
data class CsvView(
    val columnNames: List<String>,
    val hasHeader: Boolean,
    val columns: Map<CsvField, Int>,
)

data class HostImportUiState(
    val phase: HostImportPhase = HostImportPhase.PICK,
    val format: SourceFormat? = null,
    val plan: HostImportPlan? = null,
    val csv: CsvView? = null,
    val options: HostImportOptions = HostImportOptions(),
    val result: HostImportResult? = null,
    val error: HostImportError? = null,
)

/**
 * Reads one picked file, shows what it holds, and saves it once the person confirms. The file's
 * content stays in memory only while this screen is open.
 */
@HiltViewModel
class HostImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: HostImporter,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(HostImportUiState())
    val uiState: StateFlow<HostImportUiState> = mutableUiState.asStateFlow()

    private var source: HostSource? = null
    private var saved: List<SavedHost> = emptyList()
    private var planJob: Job? = null

    fun filePicked(uri: Uri?) {
        if (uri == null) return
        mutableUiState.update { it.copy(phase = HostImportPhase.ANALYZING, error = null) }
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { read(uri) }
                if (bytes == null) {
                    fail(HostImportError.TOO_BIG)
                    return@launch
                }
                val result = withContext(Dispatchers.Default) { HostSources.read(bytes) }
                when (result) {
                    is SourceResult.Failed -> fail(result.problem.toError())

                    is SourceResult.Found -> {
                        source = result.source
                        saved = importer.saved()
                        replan(openPreviewEvenWhenEmpty = result.source is CsvSource)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Reading the host file failed")
                fail(HostImportError.READ_FAILED)
            }
        }
    }

    fun optionsChanged(options: HostImportOptions) {
        mutableUiState.update { it.copy(options = options) }
        replanInBackground()
    }

    fun columnChosen(field: CsvField, column: Int?) {
        val csv = source as? CsvSource ?: return
        val columns = csv.settings.columns.toMutableMap()
        if (column == null) columns.remove(field) else columns[field] = column
        source = csv.withSettings(csv.settings.copy(columns = columns))
        replanInBackground()
    }

    fun headerChanged(hasHeader: Boolean) {
        val csv = source as? CsvSource ?: return
        source = csv.withSettings(csv.settings.copy(hasHeader = hasHeader))
        replanInBackground()
    }

    fun startOver() {
        source = null
        saved = emptyList()
        mutableUiState.value = HostImportUiState()
    }

    fun import() {
        val state = mutableUiState.value
        val plan = state.plan ?: return
        if (state.phase != HostImportPhase.PREVIEW) return
        mutableUiState.update { it.copy(phase = HostImportPhase.IMPORTING, error = null) }
        viewModelScope.launch {
            try {
                val result = importer.import(plan)
                source = null
                saved = emptyList()
                mutableUiState.update { it.copy(phase = HostImportPhase.DONE, result = result, plan = null, csv = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Host import failed")
                mutableUiState.update { it.copy(phase = HostImportPhase.PREVIEW, error = HostImportError.IMPORT_FAILED) }
            }
        }
    }

    override fun onCleared() {
        source = null
        saved = emptyList()
    }

    private fun replanInBackground() {
        planJob?.cancel()
        planJob = viewModelScope.launch {
            try {
                replan(openPreviewEvenWhenEmpty = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Planning the host import failed")
                fail(HostImportError.READ_FAILED)
            }
        }
    }

    /**
     * A CSV file always opens the preview, even with no hosts yet, because the column choices are
     * there. Any other format with no hosts is a dead end and goes back to the start with a message.
     */
    private suspend fun replan(openPreviewEvenWhenEmpty: Boolean) {
        val current = source ?: return
        val options = mutableUiState.value.options
        val plan = importer.plan(current, saved, options)
        val csv = (current as? CsvSource)?.let {
            CsvView(CsvImport.columnNames(it.table, it.settings.hasHeader), it.settings.hasHeader, it.settings.columns)
        }
        val empty = plan.hosts.isEmpty() && plan.telnetLeftOut == 0 && plan.otherProtocols == 0 && plan.invalid == 0
        if (empty && !openPreviewEvenWhenEmpty) {
            source = null
            fail(HostImportError.NOTHING_FOUND)
            return
        }
        mutableUiState.update {
            it.copy(phase = HostImportPhase.PREVIEW, format = current.format, plan = plan, csv = csv, error = null)
        }
    }

    private fun fail(error: HostImportError) {
        mutableUiState.update { it.copy(phase = HostImportPhase.PICK, plan = null, csv = null, format = null, error = error) }
    }

    /** The file's bytes, or null when it is over the size limit. */
    private fun read(uri: Uri): ByteArray? {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > HostSources.MAX_BYTES) return null
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
    }

    private fun SourceProblem.toError(): HostImportError = when (this) {
        SourceProblem.UNRECOGNIZED -> HostImportError.UNRECOGNIZED
        SourceProblem.ENCRYPTED -> HostImportError.ENCRYPTED
        SourceProblem.UNSAFE_XML -> HostImportError.UNSAFE_XML
        SourceProblem.MALFORMED -> HostImportError.MALFORMED
    }

    private companion object {
        const val BUFFER_BYTES = 8192
    }
}
