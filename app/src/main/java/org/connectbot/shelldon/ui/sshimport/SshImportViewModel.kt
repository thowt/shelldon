/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.sshimport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.shelldon.sshconfig.PickedFile
import org.connectbot.shelldon.sshconfig.PickedFiles
import org.connectbot.shelldon.sshconfig.SshImportOptions
import org.connectbot.shelldon.sshconfig.SshImportPlan
import org.connectbot.shelldon.sshconfig.SshImportResult
import org.connectbot.shelldon.sshconfig.SshImporter
import org.connectbot.shelldon.vault.VaultLockedException
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject

enum class SshImportPhase { PICK, ANALYZING, PREVIEW, IMPORTING, DONE }

enum class SshImportError { READ_FAILED, NOTHING_FOUND, VAULT_LOCKED, IMPORT_FAILED }

data class SshImportUiState(
    val phase: SshImportPhase = SshImportPhase.PICK,
    val plan: SshImportPlan? = null,
    val options: SshImportOptions = SshImportOptions(),
    val result: SshImportResult? = null,
    val error: SshImportError? = null,
)

/**
 * Reads the picked files into memory, asks the importer for a plan and, once the person confirms,
 * for the import. Key text and typed passphrases live here only until the screen is left.
 */
@HiltViewModel
class SshImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importer: SshImporter,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SshImportUiState())
    val uiState: StateFlow<SshImportUiState> = mutableUiState.asStateFlow()

    private var files: List<PickedFile> = emptyList()
    private var passphrases: Map<String, String> = emptyMap()

    fun filesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        mutableUiState.update { it.copy(phase = SshImportPhase.ANALYZING, error = null) }
        viewModelScope.launch {
            try {
                files = withContext(Dispatchers.IO) { uris.map(::read) }
                passphrases = emptyMap()
                replan()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Reading the picked files failed")
                mutableUiState.update { it.copy(phase = SshImportPhase.PICK, error = SshImportError.READ_FAILED) }
            }
        }
    }

    /** Tries [passphrase] on the encrypted key file [fileName] and plans again. */
    fun unlock(fileName: String, passphrase: String) {
        if (passphrase.isEmpty()) return
        passphrases = passphrases + (fileName to passphrase)
        viewModelScope.launch {
            try {
                replan()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Planning the import failed")
                mutableUiState.update { it.copy(phase = SshImportPhase.PICK, error = SshImportError.READ_FAILED) }
            }
        }
    }

    fun optionsChanged(options: SshImportOptions) = mutableUiState.update { it.copy(options = options) }

    fun startOver() {
        files = emptyList()
        passphrases = emptyMap()
        mutableUiState.value = SshImportUiState()
    }

    fun import() {
        val state = mutableUiState.value
        val plan = state.plan ?: return
        if (state.phase != SshImportPhase.PREVIEW) return
        mutableUiState.update { it.copy(phase = SshImportPhase.IMPORTING, error = null) }
        viewModelScope.launch {
            try {
                val result = importer.import(plan, state.options)
                files = emptyList()
                passphrases = emptyMap()
                mutableUiState.update { it.copy(phase = SshImportPhase.DONE, result = result, plan = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultLockedException) {
                Timber.w(e, "Import stopped: the vault is locked")
                mutableUiState.update { it.copy(phase = SshImportPhase.PREVIEW, error = SshImportError.VAULT_LOCKED) }
            } catch (e: Exception) {
                Timber.e(e, "SSH import failed")
                mutableUiState.update { it.copy(phase = SshImportPhase.PREVIEW, error = SshImportError.IMPORT_FAILED) }
            }
        }
    }

    override fun onCleared() {
        files = emptyList()
        passphrases = emptyMap()
    }

    private suspend fun replan() {
        val plan = importer.plan(files, passphrases)
        val nothing = plan.hosts.isEmpty() && plan.keys.isEmpty() && plan.knownHosts == null
        mutableUiState.update {
            it.copy(
                phase = if (nothing) SshImportPhase.PICK else SshImportPhase.PREVIEW,
                plan = if (nothing) null else plan,
                error = if (nothing) SshImportError.NOTHING_FOUND else null,
            )
        }
    }

    /** A file over the size limit becomes an empty file, which the planner lists as not used. */
    private fun read(uri: Uri): PickedFile {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $name")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > PickedFiles.MAX_TEXT_BYTES) return PickedFile(name, ByteArray(0))
                out.write(buffer, 0, count)
            }
            return PickedFile(name, out.toByteArray())
        }
    }

    private companion object {
        const val BUFFER_BYTES = 8192
    }
}
