/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.connectbot.shelldon.vault.UnlockResult
import org.connectbot.shelldon.vault.Vault
import org.connectbot.shelldon.vault.VaultController
import org.connectbot.shelldon.vault.VaultState
import javax.crypto.Cipher
import javax.inject.Inject

enum class VaultError { TOO_SHORT, MISMATCH, CREATE_FAILED, WRONG_PASSWORD, DAMAGED, BIOMETRIC_CHANGED, BIOMETRIC_FAILED }

data class VaultUiState(
    val busy: Boolean = false,
    val error: VaultError? = null,
    /** Seconds left of the pause after repeated wrong passwords; 0 when typing is allowed. */
    val waitSeconds: Int = 0,
    val biometricEnabled: Boolean = false,
    /** Shown once, right after the vault is created, if the phone has a strong biometric. */
    val offerBiometric: Boolean = false,
)

/**
 * Drives the first-run setup, the unlock screen and the biometric offer. Passwords are passed in
 * by the screens as plain strings and are not kept here.
 */
@HiltViewModel
class VaultViewModel @Inject constructor(
    private val controller: VaultController,
) : ViewModel() {
    val vaultState: StateFlow<VaultState> = controller.state

    private val mutableUiState = MutableStateFlow(VaultUiState(biometricEnabled = controller.biometric.isEnabled()))
    val uiState: StateFlow<VaultUiState> = mutableUiState.asStateFlow()

    private var failures = 0
    private var waitJob: Job? = null

    fun clearError() = mutableUiState.update { it.copy(error = null) }

    fun create(password: String, confirm: String) {
        when {
            mutableUiState.value.busy -> return

            password.length < Vault.MIN_PASSWORD_LENGTH -> mutableUiState.update { it.copy(error = VaultError.TOO_SHORT) }

            password != confirm -> mutableUiState.update { it.copy(error = VaultError.MISMATCH) }

            else -> {
                // The offer flag is set before the vault flips to unlocked, so the gate never flashes the app first.
                mutableUiState.update { it.copy(busy = true, error = null, offerBiometric = controller.biometric.isAvailable()) }
                viewModelScope.launch {
                    val created = controller.create(password)
                    mutableUiState.update {
                        if (created) {
                            it.copy(busy = false, biometricEnabled = false)
                        } else {
                            it.copy(busy = false, error = VaultError.CREATE_FAILED, offerBiometric = false)
                        }
                    }
                }
            }
        }
    }

    fun unlock(password: String) {
        val current = mutableUiState.value
        if (current.busy || current.waitSeconds > 0 || password.isEmpty()) return
        mutableUiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (controller.unlock(password)) {
                UnlockResult.OK -> {
                    failures = 0
                    mutableUiState.update { it.copy(busy = false) }
                }

                UnlockResult.WRONG_PASSWORD -> {
                    failures++
                    mutableUiState.update { it.copy(busy = false, error = VaultError.WRONG_PASSWORD) }
                    if (failures >= FREE_ATTEMPTS) startWait(minOf(PAUSE_STEP_SECONDS * (failures - FREE_ATTEMPTS + 1), MAX_PAUSE_SECONDS))
                }

                UnlockResult.DAMAGED -> mutableUiState.update { it.copy(busy = false, error = VaultError.DAMAGED) }

                UnlockResult.NO_VAULT -> mutableUiState.update { it.copy(busy = false) }
            }
        }
    }

    /** A cipher to authenticate with the system biometric prompt, or null if biometric unlock cannot be used now. */
    fun beginBiometricUnlock(): Cipher? {
        val cipher = controller.biometric.beginUnlock()
        if (cipher == null) {
            val wasEnabled = mutableUiState.value.biometricEnabled
            mutableUiState.update {
                it.copy(biometricEnabled = false, error = if (wasEnabled) VaultError.BIOMETRIC_CHANGED else it.error)
            }
        }
        return cipher
    }

    fun finishBiometricUnlock(cipher: Cipher) {
        viewModelScope.launch {
            if (!controller.finishBiometricUnlock(cipher)) {
                mutableUiState.update { it.copy(biometricEnabled = controller.biometric.isEnabled(), error = VaultError.BIOMETRIC_FAILED) }
            }
        }
    }

    /** Step 1 of enabling biometric unlock from the offer screen. */
    fun beginBiometricEnable(): Cipher? = try {
        controller.biometric.beginEnable()
    } catch (_: Exception) {
        null
    }

    fun finishBiometricEnable(cipher: Cipher) {
        val enabled = controller.biometric.finishEnable(cipher)
        mutableUiState.update { it.copy(biometricEnabled = enabled, offerBiometric = false) }
    }

    fun dismissBiometricOffer() = mutableUiState.update { it.copy(offerBiometric = false) }

    /** For a forgotten master password. Removes the vault and everything sealed in it. */
    fun reset() {
        viewModelScope.launch {
            controller.reset()
            failures = 0
            waitJob?.cancel()
            mutableUiState.value = VaultUiState()
        }
    }

    private fun startWait(seconds: Int) {
        waitJob?.cancel()
        waitJob = viewModelScope.launch {
            for (remaining in seconds downTo 1) {
                mutableUiState.update { it.copy(waitSeconds = remaining) }
                delay(1000)
            }
            mutableUiState.update { it.copy(waitSeconds = 0) }
        }
    }

    private companion object {
        const val FREE_ATTEMPTS = 3
        const val PAUSE_STEP_SECONDS = 5
        const val MAX_PAUSE_SECONDS = 60
    }
}
