/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("NewApi")

package org.connectbot.shelldon.ui.vault

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.connectbot.R
import org.connectbot.shelldon.vault.ResetImpact
import org.connectbot.shelldon.vault.VaultBiometric
import org.connectbot.shelldon.vault.VaultController
import org.connectbot.shelldon.vault.VaultState
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** What the person is told before the vault is reset, and what has to happen before Reset works. */
@RunWith(AndroidJUnit4::class)
class ResetVaultDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(id: Int) = context.getString(id)

    private fun passwords(count: Int) = context.resources.getQuantityString(R.plurals.shelldon_vault_reset_passwords, count, count)

    private fun keys(count: Int) = context.resources.getQuantityString(R.plurals.shelldon_vault_reset_keys, count, count)

    private var confirmed = 0
    private var backedUp = 0
    private var dismissed = 0

    private fun show(
        impact: ResetImpact,
        unlocked: Boolean = false,
        withBackUp: Boolean = false,
    ) {
        composeTestRule.setContent {
            ConnectBotTheme {
                ResetVaultDialog(
                    loadImpact = { impact },
                    unlocked = unlocked,
                    onDismiss = { dismissed++ },
                    onConfirm = { confirmed++ },
                    onBackUp = if (withBackUp) ({ backedUp++ }) else null,
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun listsExactlyWhatIsDeletedAndWhatStays() {
        show(ResetImpact(passwords = 3, keys = 1, biometric = true))

        composeTestRule.onNodeWithText("• ${passwords(3)}").assertIsDisplayed()
        composeTestRule.onNodeWithText("• ${keys(1)}").assertIsDisplayed()
        composeTestRule.onNodeWithText("• ${string(R.string.shelldon_vault_reset_item_biometric)}").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.shelldon_vault_reset_stays)).assertIsDisplayed()
    }

    @Test
    fun leavesOutWhatIsNotThere() {
        show(ResetImpact(passwords = 0, keys = 2, biometric = false))

        composeTestRule.onNodeWithText("• ${keys(2)}").assertIsDisplayed()
        composeTestRule.onNodeWithText("• ${passwords(0)}").assertDoesNotExist()
        composeTestRule.onNodeWithText("• ${string(R.string.shelldon_vault_reset_item_biometric)}").assertDoesNotExist()
    }

    @Test
    fun losingSecretsNeedsAnExplicitTick() {
        show(ResetImpact(passwords = 1, keys = 0, biometric = false))

        composeTestRule.onNodeWithTag("vault_reset_confirm").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("vault_reset_confirm").performClick()
        assertEquals(0, confirmed)

        composeTestRule.onNodeWithTag("vault_reset_understood").performClick()
        composeTestRule.onNodeWithTag("vault_reset_confirm").assertIsEnabled()
        composeTestRule.onNodeWithTag("vault_reset_confirm").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun untickingTheBoxLocksResetAgain() {
        show(ResetImpact(passwords = 0, keys = 1, biometric = false))

        composeTestRule.onNodeWithTag("vault_reset_understood").performClick()
        composeTestRule.onNodeWithTag("vault_reset_confirm").assertIsEnabled()
        composeTestRule.onNodeWithTag("vault_reset_understood").performClick()

        composeTestRule.onNodeWithTag("vault_reset_confirm").assertIsNotEnabled()
    }

    @Test
    fun anEmptyVaultNeedsNoTickAndSaysNothingIsLost() {
        show(ResetImpact(passwords = 0, keys = 0, biometric = false))

        composeTestRule.onNodeWithText(string(R.string.shelldon_vault_reset_nothing)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("vault_reset_understood").assertDoesNotExist()
        composeTestRule.onNodeWithTag("vault_reset_confirm").assertIsEnabled()
    }

    @Test
    fun nothingCanBeConfirmedUntilTheCountsAreKnown() {
        val counts = CompletableDeferred<ResetImpact>()
        composeTestRule.setContent {
            ConnectBotTheme {
                ResetVaultDialog(
                    loadImpact = { counts.await() },
                    unlocked = false,
                    onDismiss = {},
                    onConfirm = { confirmed++ },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("vault_reset_confirm").assertIsNotEnabled()

        composeTestRule.runOnIdle { counts.complete(ResetImpact(0, 0, false)) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("vault_reset_confirm").assertIsEnabled()
    }

    @Test
    fun anOpenVaultOffersABackupFirst() {
        show(ResetImpact(passwords = 2, keys = 2, biometric = true), unlocked = true, withBackUp = true)

        composeTestRule.onNodeWithText(string(R.string.shelldon_vault_reset_hint_unlocked)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("vault_reset_backup").performClick()

        assertEquals(1, backedUp)
        assertEquals(0, confirmed)
    }

    @Test
    fun aLockedVaultCannotBeBackedUp() {
        show(ResetImpact(passwords = 2, keys = 2, biometric = false), unlocked = false, withBackUp = true)

        composeTestRule.onNodeWithTag("vault_reset_backup").assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.shelldon_vault_reset_hint_locked)).assertIsDisplayed()
    }

    @Test
    fun aLockedVaultWithWorkingBiometricsPointsAtThem() {
        show(ResetImpact(passwords = 2, keys = 2, biometric = true), unlocked = false)

        composeTestRule.onNodeWithText(string(R.string.shelldon_vault_reset_hint_biometric)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.shelldon_vault_reset_hint_locked)).assertDoesNotExist()
    }

    @Test
    fun cancellingResetsNothing() {
        show(ResetImpact(passwords = 1, keys = 1, biometric = false))

        composeTestRule.onNodeWithText(string(R.string.shelldon_cancel)).performClick()

        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
    }

    // --- the note on the setup screen after a reset ---------------------------------------------

    private fun gateAfter(removed: ResetImpact?) {
        val biometric = mock<VaultBiometric>()
        val controller = mock<VaultController>()
        whenever(controller.state).thenReturn(MutableStateFlow(VaultState.NO_VAULT))
        whenever(controller.lastReset).thenReturn(MutableStateFlow(removed))
        whenever(controller.biometric).thenReturn(biometric)
        whenever(biometric.isEnabled()).thenReturn(false)
        composeTestRule.setContent {
            ConnectBotTheme {
                VaultGate(makingShortcut = false, viewModel = VaultViewModel(controller)) {}
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun theSetupScreenSaysWhatTheResetRemoved() {
        gateAfter(ResetImpact(passwords = 3, keys = 2, biometric = false))

        composeTestRule.onNodeWithTag("vault_reset_note").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            context.getString(R.string.shelldon_vault_reset_done, "${passwords(3)}, ${keys(2)}"),
        ).assertIsDisplayed()
    }

    @Test
    fun theSetupScreenOfAFreshInstallHasNoResetNote() {
        gateAfter(null)

        composeTestRule.onNodeWithTag("vault_reset_note").assertDoesNotExist()
    }

    @Test
    fun aResetOfAnEmptyVaultSaysSoPlainly() {
        gateAfter(ResetImpact(passwords = 0, keys = 0, biometric = false))

        composeTestRule.onNodeWithText(string(R.string.shelldon_vault_reset_done_nothing)).assertIsDisplayed()
    }
}
