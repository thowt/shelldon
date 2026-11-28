/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("NewApi")

package org.connectbot.shelldon.ui.sftp

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.R
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What a transfer card says about continuing: while it runs, and when it has stopped. */
@RunWith(AndroidJUnit4::class)
class TransferCardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun show(transfer: TransferUi) {
        composeTestRule.setContent {
            ConnectBotTheme {
                TransferCard(transfer = transfer, onCancel = {}, onDismiss = {})
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun transfer(status: TransferStatus, resumable: Boolean = true, resumedFrom: Long = 0) = TransferUi(id = 1, name = "big.bin", upload = false, done = 3_000_000, total = 6_000_000, status = status, resumable = resumable, resumedFrom = resumedFrom)

    @Test
    fun aRunningTransferSaysWhatItContinuedFrom() {
        show(transfer(TransferStatus.RUNNING, resumedFrom = 2_000_000))

        composeTestRule.onNodeWithTag("sftp_transfer_resumed_big.bin").assertIsDisplayed()
    }

    @Test
    fun aFreshTransferSaysNothingAboutContinuing() {
        show(transfer(TransferStatus.RUNNING, resumedFrom = 0))

        composeTestRule.onNodeWithTag("sftp_transfer_resumed_big.bin").assertDoesNotExist()
    }

    @Test
    fun aCancelledTransferTellsHowToContinue() {
        show(transfer(TransferStatus.CANCELLED))

        composeTestRule.onNodeWithText(context.getString(R.string.shelldon_sftp_resume_hint), substring = true).assertIsDisplayed()
    }

    @Test
    fun aFailedTransferTellsHowToContinue() {
        show(transfer(TransferStatus.FAILED).copy(problem = R.string.shelldon_sftp_problem_lost))

        composeTestRule.onNodeWithText(context.getString(R.string.shelldon_sftp_resume_hint), substring = true).assertIsDisplayed()
    }

    @Test
    fun aTransferThatCannotBeContinuedDoesNotPromiseIt() {
        show(transfer(TransferStatus.CANCELLED, resumable = false))

        composeTestRule.onNodeWithText(context.getString(R.string.shelldon_sftp_resume_hint), substring = true).assertDoesNotExist()
    }
}
