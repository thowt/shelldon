/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.vault

import android.content.Context
import android.net.Uri
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
import org.connectbot.shelldon.backup.BackupCrypto
import org.connectbot.shelldon.backup.BackupException
import org.connectbot.shelldon.backup.BackupImportResult
import org.connectbot.shelldon.backup.BackupOptions
import org.connectbot.shelldon.backup.BackupProblem
import org.connectbot.shelldon.backup.ShelldonBackup
import org.connectbot.shelldon.sshconfig.SshConfigImportResult
import org.connectbot.shelldon.sshconfig.SshConfigImporter
import org.connectbot.shelldon.vault.VaultController
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.inject.Inject

/** One-off outcomes the Security screen reports in a dialog. */
sealed interface SecurityMessage {
    data object PasswordChanged : SecurityMessage
    data object CurrentPasswordWrong : SecurityMessage
    data object BackupExported : SecurityMessage
    data class BackupImported(val result: BackupImportResult) : SecurityMessage

    /** [problem] is null for failures that are not about the file or password (I/O, vault locked, ...). */
    data class BackupFailed(val problem: BackupProblem?) : SecurityMessage
    data class ConfigImported(val result: SshConfigImportResult) : SecurityMessage
    data object ConfigFailed : SecurityMessage
}

data class SecurityUiState(
    val autoLockSeconds: Int,
    val biometricAvailable: Boolean,
    val biometricEnabled: Boolean,
    val busy: Boolean = false,
    val message: SecurityMessage? = null,
)

@HiltViewModel
class SecurityViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: VaultController,
    private val backup: ShelldonBackup,
    private val sshConfig: SshConfigImporter,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        SecurityUiState(
            autoLockSeconds = controller.autoLockSeconds,
            biometricAvailable = controller.biometric.isAvailable(),
            biometricEnabled = controller.biometric.isEnabled(),
        ),
    )
    val uiState: StateFlow<SecurityUiState> = mutableUiState.asStateFlow()

    fun lockNow() = controller.lock()

    fun setAutoLock(seconds: Int) {
        controller.autoLockSeconds = seconds
        mutableUiState.update { it.copy(autoLockSeconds = seconds) }
    }

    fun beginBiometricEnable(): Cipher? = try {
        controller.biometric.beginEnable()
    } catch (e: Exception) {
        Timber.e(e, "Could not start enabling biometric unlock")
        null
    }

    fun finishBiometricEnable(cipher: Cipher) {
        val enabled = controller.biometric.finishEnable(cipher)
        mutableUiState.update { it.copy(biometricEnabled = enabled) }
    }

    fun disableBiometric() {
        controller.biometric.disable()
        mutableUiState.update { it.copy(biometricEnabled = false) }
    }

    fun changePassword(current: String, new: String) = runBusy {
        if (controller.changePassword(current, new)) SecurityMessage.PasswordChanged else SecurityMessage.CurrentPasswordWrong
    }

    fun exportBackup(uri: Uri, password: String, options: BackupOptions) = runBusy {
        try {
            val bytes = backup.export(password, options)
            withContext(Dispatchers.IO) {
                val out = context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Cannot open the file for writing")
                out.use { it.write(bytes) }
            }
            SecurityMessage.BackupExported
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Backup export failed")
            SecurityMessage.BackupFailed(null)
        }
    }

    fun importBackup(uri: Uri, password: String) = runBusy {
        try {
            val bytes = withContext(Dispatchers.IO) { readLimited(uri, BackupCrypto.MAX_FILE_BYTES) }
            SecurityMessage.BackupImported(backup.import(bytes, password))
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            SecurityMessage.BackupFailed(e.problem)
        } catch (e: Exception) {
            Timber.e(e, "Backup import failed")
            SecurityMessage.BackupFailed(null)
        }
    }

    fun importSshConfig(uri: Uri) = runBusy {
        try {
            val bytes = withContext(Dispatchers.IO) { readLimited(uri, MAX_CONFIG_BYTES) }
            SecurityMessage.ConfigImported(sshConfig.import(String(bytes, Charsets.UTF_8)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "ssh_config import failed")
            SecurityMessage.ConfigFailed
        }
    }

    /** Forgotten master password: removes the vault and what was sealed in it. The gate then asks for a new one. */
    fun reset() {
        viewModelScope.launch { controller.reset() }
    }

    fun messageShown() = mutableUiState.update { it.copy(message = null) }

    private fun runBusy(block: suspend () -> SecurityMessage) {
        if (mutableUiState.value.busy) return
        mutableUiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val message = block()
            mutableUiState.update { it.copy(busy = false, message = message) }
        }
    }

    private fun readLimited(uri: Uri, max: Int): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > max) throw IOException("The file is too large")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private companion object {
        const val MAX_CONFIG_BYTES = 1024 * 1024
    }
}
