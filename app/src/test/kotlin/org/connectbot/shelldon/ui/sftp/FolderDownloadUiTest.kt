/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("NewApi")

package org.connectbot.shelldon.ui.sftp

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
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.R
import org.connectbot.shelldon.sftp.FolderFailure
import org.connectbot.shelldon.sftp.FolderResult
import org.connectbot.shelldon.sftp.SftpItem
import org.connectbot.shelldon.sftp.SftpKind
import org.connectbot.shelldon.sftp.SftpProblem
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What the folder download tells the user before, while and after it copies. */
@RunWith(AndroidJUnit4::class)
class FolderDownloadUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val summary = FolderSummary(files = 12, folders = 3, bytes = 4_000_000, linksSkipped = 0, othersSkipped = 0, unreadable = 0, truncated = false, limit = 20_000)

    private fun job(phase: FolderPhase, summary: FolderSummary? = this.summary, result: FolderResult? = null) = FolderJobUi(name = "proj", phase = phase, summary = summary, result = result)

    private fun showDialog(job: FolderJobUi, onChoose: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        composeTestRule.setContent { ConnectBotTheme { FolderConfirmDialog(job = job, onChoose = onChoose, onDismiss = onDismiss) } }
        composeTestRule.waitForIdle()
    }

    private fun showCard(job: FolderJobUi, onCancel: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        composeTestRule.setContent { ConnectBotTheme { FolderCard(job = job, onCancel = onCancel, onDismiss = onDismiss) } }
        composeTestRule.waitForIdle()
    }

    private fun result(downloaded: Int = 0, there: Int = 0, kept: Int = 0, failures: List<FolderFailure> = emptyList(), renamed: Int = 0) = FolderResult(downloaded, there, kept, failures, renamed)

    // --- The question ---

    @Test
    fun theQuestionSaysHowManyFilesAndThatNothingIsOverwritten() {
        showDialog(job(FolderPhase.CONFIRM))

        composeTestRule.onNodeWithTag("sftp_folder_summary").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.shelldon_sftp_folder_how), substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("sftp_folder_choose").assertIsEnabled()
    }

    @Test
    fun theSummaryNamesTheFolderAndCountsInTheRightForm() {
        showDialog(job(FolderPhase.CONFIRM, summary = summary.copy(files = 1, folders = 1)))

        composeTestRule.onNodeWithText("1 file", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("1 folder", substring = true).assertIsDisplayed()
    }

    @Test
    fun choosingAFolderAsksForOneAndCancellingDropsTheQuestion() {
        var chosen = 0
        var dismissed = 0
        showDialog(job(FolderPhase.CONFIRM), onChoose = { chosen++ }, onDismiss = { dismissed++ })

        composeTestRule.onNodeWithTag("sftp_folder_choose").performClick()
        composeTestRule.onNodeWithTag("sftp_folder_cancel").performClick()

        assertThat(chosen).isEqualTo(1)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun aFolderWithoutFilesCannotBeDownloaded() {
        showDialog(job(FolderPhase.CONFIRM, summary = summary.copy(files = 0, bytes = 0)))

        composeTestRule.onNodeWithTag("sftp_folder_choose").assertIsNotEnabled()
    }

    @Test
    fun whatIsLeftOutIsSaidBeforeAnythingIsCopied() {
        showDialog(job(FolderPhase.CONFIRM, summary = summary.copy(linksSkipped = 2, unreadable = 1, truncated = true)))

        composeTestRule.onNodeWithText("2 links", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("sftp_folder_unreadable").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sftp_folder_truncated").assertIsDisplayed()
    }

    @Test
    fun aCompleteScanSaysNothingAboutLeavingThingsOut() {
        showDialog(job(FolderPhase.CONFIRM))

        composeTestRule.onNodeWithTag("sftp_folder_unreadable").assertDoesNotExist()
        composeTestRule.onNodeWithTag("sftp_folder_truncated").assertDoesNotExist()
    }

    // --- The card ---

    @Test
    fun whileCopyingTheCardShowsHowFarItIsAndHowToStop() {
        showCard(FolderJobUi("proj", FolderPhase.DOWNLOADING, filesDone = 3, filesTotal = 12, bytesDone = 1_000_000, bytesTotal = 4_000_000))

        composeTestRule.onNodeWithTag("sftp_folder_progress").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.shelldon_sftp_folder_stop_hint)).assertIsDisplayed()
    }

    @Test
    fun theCloseButtonCancelsWhileRunningAndDismissesWhenDone() {
        var cancelled = 0
        var dismissed = 0
        showCard(FolderJobUi("proj", FolderPhase.DOWNLOADING), onCancel = { cancelled++ }, onDismiss = { dismissed++ })
        composeTestRule.onNodeWithTag("sftp_folder_close").performClick()

        assertThat(cancelled).isEqualTo(1)
        assertThat(dismissed).isEqualTo(0)
    }

    @Test
    fun aFinishedCardDismissesInsteadOfCancelling() {
        var cancelled = 0
        var dismissed = 0
        showCard(job(FolderPhase.DONE, result = result(downloaded = 4)), onCancel = { cancelled++ }, onDismiss = { dismissed++ })
        composeTestRule.onNodeWithTag("sftp_folder_close").performClick()

        assertThat(cancelled).isEqualTo(0)
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun theResultCountsWhatWasCopiedSkippedAndKept() {
        showCard(job(FolderPhase.DONE, result = result(downloaded = 4, there = 2, kept = 1)))

        composeTestRule.onNodeWithText("4 downloaded", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("2 already there", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("1 left alone", substring = true).assertIsDisplayed()
    }

    @Test
    fun aRunWithNothingNewSaysSo() {
        showCard(job(FolderPhase.DONE, result = result(there = 4)))

        composeTestRule.onNodeWithText(context.getString(R.string.shelldon_sftp_folder_nothing_new, "4 already there")).assertIsDisplayed()
    }

    @Test
    fun aFailedFileIsNamedWithItsReason() {
        val failed = job(FolderPhase.DONE, result = result(downloaded = 3, failures = listOf(FolderFailure("src/main.kt", SftpProblem.PERMISSION_DENIED, ""))))
            .copy(firstFailure = FolderFailureUi("src/main.kt", R.string.shelldon_sftp_problem_permission, ""))
        showCard(failed)

        composeTestRule.onNodeWithText("1 failed", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("sftp_folder_first_failure").assertIsDisplayed()
    }

    @Test
    fun aFailedJobShowsTheReason() {
        showCard(FolderJobUi("proj", FolderPhase.FAILED, problem = R.string.shelldon_sftp_problem_permission))

        composeTestRule.onNodeWithText(context.getString(R.string.shelldon_sftp_problem_permission)).assertIsDisplayed()
    }

    @Test
    fun aStoppedJobSaysItCanBeContinued() {
        showCard(FolderJobUi("proj", FolderPhase.CANCELLED))

        composeTestRule.onNodeWithText(context.getString(R.string.shelldon_sftp_folder_stop_hint), substring = true).assertIsDisplayed()
    }

    // --- The menu ---

    private fun item(kind: SftpKind) = SftpItem(name = "thing", path = "/home/test/thing", kind = kind, size = 10, modified = null, permissions = null)

    private fun showRow(item: SftpItem, onDownloadFolder: (() -> Unit)?) {
        composeTestRule.setContent {
            ConnectBotTheme { SftpRow(item = item, onOpen = {}, onDownload = {}, onRename = {}, onDelete = {}, onDownloadFolder = onDownloadFolder) }
        }
        composeTestRule.onNodeWithTag("sftp_menu_thing").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun aFolderOffersToDownloadItself() {
        var asked = 0
        showRow(item(SftpKind.FOLDER)) { asked++ }

        composeTestRule.onNodeWithTag("sftp_download_folder_thing").performClick()

        assertThat(asked).isEqualTo(1)
    }

    @Test
    fun aFileOffersNoFolderDownload() {
        showRow(item(SftpKind.FILE)) {}

        composeTestRule.onNodeWithTag("sftp_download_folder_thing").assertDoesNotExist()
    }

    @Test
    fun aLinkIsNeverDownloadedAsAFolder() {
        showRow(item(SftpKind.LINK).copy(linkToFolder = true)) {}

        composeTestRule.onNodeWithTag("sftp_download_folder_thing").assertDoesNotExist()
    }

    @Test
    fun noFolderDownloadIsOfferedWhileAnotherRuns() {
        showRow(item(SftpKind.FOLDER), onDownloadFolder = null)

        composeTestRule.onNodeWithTag("sftp_download_folder_thing").assertDoesNotExist()
    }
}
