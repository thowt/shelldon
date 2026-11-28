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
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.R
import org.connectbot.shelldon.sftp.SftpItem
import org.connectbot.shelldon.sftp.SftpKind
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The text editor for a remote file: what it asks, what it refuses and what it tells after a save. */
@RunWith(AndroidJUnit4::class)
class SftpEditorDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Calls {
        val texts = mutableListOf<String>()
        var saved = 0
        var closed = 0
        var overwritten = 0
        var copied = 0
        var kept = 0
    }

    private val calls = Calls()

    private fun editor(
        phase: EditorPhase = EditorPhase.EDITING,
        text: String = "Port 22\n",
        original: String = text,
        conflict: Boolean = false,
        problem: Int? = null,
        interruptedCopy: String? = null,
        savedCopy: String? = null,
        saved: Boolean = false,
    ) = EditorUi("sshd_config", "/etc/ssh/sshd_config", phase, text, original, problem, "", conflict, interruptedCopy, saved, savedCopy)

    private fun show(editor: EditorUi) {
        composeTestRule.setContent {
            ConnectBotTheme {
                SftpEditorDialog(
                    editor = editor,
                    onTextChange = { calls.texts += it },
                    onSave = { calls.saved++ },
                    onClose = { calls.closed++ },
                    onOverwrite = { calls.overwritten++ },
                    onSaveCopy = { calls.copied++ },
                    onKeepEditing = { calls.kept++ },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun whileTheFileIsBeingReadThereIsNothingToSave() {
        show(editor(EditorPhase.LOADING, text = ""))

        composeTestRule.onNodeWithTag("sftp_edit_loading").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sftp_edit_save").assertDoesNotExist()
    }

    @Test
    fun saveIsOnlyOfferedForChanges() {
        show(editor(text = "Port 22\n", original = "Port 22\n"))

        composeTestRule.onNodeWithTag("sftp_edit_save").assertIsNotEnabled()
    }

    @Test
    fun aChangedTextCanBeSaved() {
        show(editor(text = "Port 2222\n", original = "Port 22\n"))

        composeTestRule.onNodeWithTag("sftp_edit_save").assertIsEnabled().performClick()

        assertThat(calls.saved).isEqualTo(1)
    }

    @Test
    fun typingTellsTheViewModelTheWholeNewText() {
        show(editor(text = "", original = ""))

        composeTestRule.onNodeWithTag("sftp_edit_text").performTextInput("hello")

        assertThat(calls.texts.last()).isEqualTo("hello")
    }

    @Test
    fun theExtraKeysInsertAtTheCursor() {
        show(editor(text = "", original = ""))

        composeTestRule.onNodeWithTag("sftp_edit_key_#").performClick()
        composeTestRule.onNodeWithTag("sftp_edit_key_tab").performClick()

        assertThat(calls.texts).containsExactly("#", "#\t")
    }

    @Test
    fun closingAfterChangesAsksFirstAndDiscardingCloses() {
        show(editor(text = "Port 2222\n", original = "Port 22\n"))

        composeTestRule.onNodeWithTag("sftp_edit_close").performClick()

        assertThat(calls.closed).isEqualTo(0)
        composeTestRule.onNodeWithText(context.getString(R.string.shelldon_sftp_edit_discard_title)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("sftp_edit_discard").performClick()
        assertThat(calls.closed).isEqualTo(1)
    }

    @Test
    fun keepEditingFromTheDiscardQuestionClosesNothing() {
        show(editor(text = "Port 2222\n", original = "Port 22\n"))

        composeTestRule.onNodeWithTag("sftp_edit_close").performClick()
        composeTestRule.onNodeWithTag("sftp_edit_keep").performClick()

        assertThat(calls.closed).isEqualTo(0)
        composeTestRule.onNodeWithText(context.getString(R.string.shelldon_sftp_edit_discard_title)).assertDoesNotExist()
    }

    @Test
    fun closingWithoutChangesClosesAtOnce() {
        show(editor())

        composeTestRule.onNodeWithTag("sftp_edit_close").performClick()

        assertThat(calls.closed).isEqualTo(1)
    }

    @Test
    fun whileSavingNothingCanBeClosedOrSavedAgain() {
        show(editor(EditorPhase.SAVING, text = "Port 2222\n", original = "Port 22\n"))

        composeTestRule.onNodeWithTag("sftp_edit_close").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("sftp_edit_save").assertDoesNotExist()
    }

    @Test
    fun afterASaveTheBarSaysSaved() {
        show(editor(saved = true))

        composeTestRule.onNodeWithTag("sftp_edit_saved").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sftp_edit_save").assertDoesNotExist()
    }

    @Test
    fun aFileThatChangedOnTheServerOffersOverwriteCopyAndKeepEditing() {
        show(editor(text = "Port 2222\n", original = "Port 22\n", conflict = true))

        composeTestRule.onNodeWithText(context.getString(R.string.shelldon_sftp_edit_conflict_title)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("sftp_edit_copy").performClick()
        composeTestRule.onNodeWithTag("sftp_edit_conflict_keep").performClick()
        composeTestRule.onNodeWithTag("sftp_edit_overwrite").performClick()

        assertThat(calls.copied).isEqualTo(1)
        assertThat(calls.kept).isEqualTo(1)
        assertThat(calls.overwritten).isEqualTo(1)
    }

    @Test
    fun aRefusedFileSaysWhyAndOffersNoEditor() {
        show(editor(EditorPhase.REFUSED, text = "", problem = R.string.shelldon_sftp_edit_binary))

        composeTestRule.onNodeWithText(context.getString(R.string.shelldon_sftp_edit_binary)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("sftp_edit_text").assertDoesNotExist()
        composeTestRule.onNodeWithTag("sftp_edit_leave").performClick()
        assertThat(calls.closed).isEqualTo(1)
    }

    @Test
    fun anInterruptedSaveNamesTheCopyThatHoldsTheNewText() {
        show(editor(text = "Port 2222\n", original = "Port 22\n", problem = R.string.shelldon_sftp_problem_lost, interruptedCopy = "/etc/ssh/.sshd_config.ab12cd34.shelldon-edit"))

        composeTestRule.onNodeWithTag("sftp_edit_interrupted").assertIsDisplayed()
        composeTestRule.onNodeWithText(".sshd_config.ab12cd34.shelldon-edit", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("sftp_edit_error").assertIsDisplayed()
    }

    @Test
    fun aSavedCopyIsNamed() {
        show(editor(savedCopy = "/etc/ssh/sshd_config (edited)"))

        composeTestRule.onNodeWithTag("sftp_edit_copy_saved").assertIsDisplayed()
    }

    // --- The menu ---

    private fun showRow(kind: SftpKind, onEdit: (() -> Unit)?) {
        composeTestRule.setContent {
            ConnectBotTheme {
                SftpRow(
                    item = SftpItem(name = "thing", path = "/home/test/thing", kind = kind, size = 10, modified = null, permissions = null),
                    onOpen = {},
                    onDownload = {},
                    onRename = {},
                    onDelete = {},
                    onEdit = onEdit,
                )
            }
        }
        composeTestRule.onNodeWithTag("sftp_menu_thing").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun aFileOffersEdit() {
        var asked = 0
        showRow(SftpKind.FILE) { asked++ }

        composeTestRule.onNodeWithTag("sftp_edit_thing").performClick()

        assertThat(asked).isEqualTo(1)
    }

    @Test
    fun aFolderOffersNoEdit() {
        showRow(SftpKind.FOLDER) {}

        composeTestRule.onNodeWithTag("sftp_edit_thing").assertDoesNotExist()
    }

    @Test
    fun aLinkOffersNoEdit() {
        showRow(SftpKind.LINK) {}

        composeTestRule.onNodeWithTag("sftp_edit_thing").assertDoesNotExist()
    }

    @Test
    fun aFileThatCannotBeEditedOffersNoEdit() {
        showRow(SftpKind.FILE, onEdit = null)

        composeTestRule.onNodeWithTag("sftp_edit_thing").assertDoesNotExist()
    }
}
