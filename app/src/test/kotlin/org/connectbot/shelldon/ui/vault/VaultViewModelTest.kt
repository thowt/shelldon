/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.vault

import androidx.biometric.BiometricPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.shelldon.vault.BiometricStart
import org.connectbot.shelldon.vault.BiometricStatus
import org.connectbot.shelldon.vault.VaultBiometric
import org.connectbot.shelldon.vault.VaultController
import org.connectbot.shelldon.vault.VaultState
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import javax.crypto.Cipher

class VaultViewModelTest {
    private val biometric = mock<VaultBiometric>()
    private val controller = mock<VaultController>()

    private lateinit var viewModel: VaultViewModel

    private val ui get() = viewModel.uiState.value

    @Before
    fun setUp() {
        whenever(controller.state).thenReturn(MutableStateFlow(VaultState.LOCKED))
        whenever(controller.lastReset).thenReturn(MutableStateFlow(null))
        whenever(controller.biometric).thenReturn(biometric)
        whenever(biometric.isEnabled()).thenReturn(true)
        viewModel = VaultViewModel(controller)
    }

    // --- what the unlock screen reads when it appears -------------------------------------------

    @Test
    fun refreshingReadsWhatIsReallySetUp() {
        // The view model was created while biometric unlock was off ...
        whenever(biometric.isEnabled()).thenReturn(false)
        viewModel = VaultViewModel(controller)
        assertThat(ui.biometricEnabled).isFalse()

        // ... and was switched on in Settings afterwards.
        whenever(biometric.status()).thenReturn(BiometricStatus.READY)

        assertThat(viewModel.refreshBiometric()).isTrue()
        assertThat(ui.biometricEnabled).isTrue()
    }

    @Test
    fun refreshingAfterItWasSwitchedOffInSettingsShowsNoMessage() {
        whenever(biometric.status()).thenReturn(BiometricStatus.NOT_SET_UP)

        assertThat(viewModel.refreshBiometric()).isFalse()
        assertThat(ui.biometricEnabled).isFalse()
        assertThat(ui.error).isNull()
    }

    @Test
    fun refreshingClearsAMessageFromAnEarlierVisit() {
        viewModel.onBiometricError(BiometricPrompt.ERROR_LOCKOUT)
        assertThat(ui.error).isEqualTo(VaultError.BIOMETRIC_LOCKED_OUT)
        whenever(biometric.status()).thenReturn(BiometricStatus.READY)

        viewModel.refreshBiometric()

        assertThat(ui.error).isNull()
    }

    @Test
    fun aKeyTheSystemDeletedIsReportedAndSwitchedOff() {
        whenever(biometric.status()).thenReturn(BiometricStatus.KEY_LOST)

        assertThat(viewModel.refreshBiometric()).isFalse()

        verify(biometric).disable()
        assertThat(ui.biometricEnabled).isFalse()
        assertThat(ui.error).isEqualTo(VaultError.BIOMETRIC_LOST)
    }

    // --- starting an unlock ---------------------------------------------------------------------

    @Test
    fun aReadyUnlockHandsOutTheCipherAndSaysNothing() {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        whenever(biometric.beginUnlock()).thenReturn(BiometricStart.Ready(cipher))

        assertThat(viewModel.beginBiometricUnlock()).isSameAs(cipher)
        assertThat(ui.biometricEnabled).isTrue()
        assertThat(ui.error).isNull()
    }

    @Test
    fun changedBiometricsSwitchTheButtonOffAndSayWhy() {
        whenever(biometric.beginUnlock()).thenReturn(BiometricStart.Invalidated)

        assertThat(viewModel.beginBiometricUnlock()).isNull()
        assertThat(ui.biometricEnabled).isFalse()
        assertThat(ui.error).isEqualTo(VaultError.BIOMETRIC_CHANGED)
    }

    @Test
    fun aLostKeySwitchesTheButtonOffAndSaysSo() {
        whenever(biometric.beginUnlock()).thenReturn(BiometricStart.Lost)

        assertThat(viewModel.beginBiometricUnlock()).isNull()
        assertThat(ui.biometricEnabled).isFalse()
        assertThat(ui.error).isEqualTo(VaultError.BIOMETRIC_LOST)
    }

    @Test
    fun aTemporaryRefusalKeepsTheButtonAndNeverClaimsTheBiometricsChanged() {
        whenever(biometric.beginUnlock()).thenReturn(BiometricStart.Unavailable)

        assertThat(viewModel.beginBiometricUnlock()).isNull()
        assertThat(ui.biometricEnabled).isTrue()
        assertThat(ui.error).isEqualTo(VaultError.BIOMETRIC_UNAVAILABLE)
        verify(biometric, never()).disable()
    }

    // --- what the system prompt reports ---------------------------------------------------------

    @Test
    fun choosingThePasswordOrLeavingTheAppIsNotAnError() {
        listOf(
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_CANCELED,
        ).forEach { code ->
            viewModel.onBiometricError(code)
            assertThat(ui.error).describedAs("code $code").isNull()
        }
    }

    @Test
    fun tooManyAttemptsAreSaidOutLoud() {
        viewModel.onBiometricError(BiometricPrompt.ERROR_LOCKOUT)
        assertThat(ui.error).isEqualTo(VaultError.BIOMETRIC_LOCKED_OUT)

        viewModel.clearError()
        viewModel.onBiometricError(BiometricPrompt.ERROR_LOCKOUT_PERMANENT)
        assertThat(ui.error).isEqualTo(VaultError.BIOMETRIC_LOCKED_OUT)
    }

    @Test
    fun otherPromptErrorsSayTheSensorIsNotAvailableAndKeepTheButton() {
        viewModel.onBiometricError(BiometricPrompt.ERROR_HW_UNAVAILABLE)

        assertThat(ui.error).isEqualTo(VaultError.BIOMETRIC_UNAVAILABLE)
        assertThat(ui.biometricEnabled).isTrue()
    }
}
