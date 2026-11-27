/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("NewApi")

package org.connectbot.shelldon.ui.sftp

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.R
import org.connectbot.shelldon.sftp.SftpItem
import org.connectbot.shelldon.sftp.SftpKind
import org.connectbot.shelldon.sftp.SftpMode
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The permissions dialog: boxes and number stay in step, and what is risky is said. */
@RunWith(AndroidJUnit4::class)
class PermissionsDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val applied = mutableListOf<Pair<SftpMode, Boolean>>()
    private var dismissed = 0

    private fun file(permissions: Int = 0x81A4) = SftpItem("a.txt", "/home/test/a.txt", SftpKind.FILE, size = 5, modified = null, permissions = permissions)

    private fun folder(permissions: Int = 0x41ED) = SftpItem("docs", "/home/test/docs", SftpKind.FOLDER, size = null, modified = null, permissions = permissions)

    private fun show(item: SftpItem) {
        composeTestRule.setContent {
            ConnectBotTheme {
                PermissionsDialog(
                    item = item,
                    initial = checkNotNull(SftpMode.of(item.permissions)),
                    onApply = { mode, recursive -> applied.add(mode to recursive) },
                    onDismiss = { dismissed++ },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun node(tag: String): SemanticsNodeInteraction = composeTestRule.onNodeWithTag(tag).performScrollTo()

    private fun click(tag: String) {
        node(tag).performClick()
    }

    private fun apply() = composeTestRule.onNodeWithTag("sftp_perm_apply")

    @Test
    fun showsTheCurrentModeAsBoxesAndAsANumber() {
        show(file(0x81A4))

        node("sftp_perm_octal").assertTextContains("644")
        node("sftp_perm_owner_read").assertIsOn()
        node("sftp_perm_owner_write").assertIsOn()
        node("sftp_perm_owner_execute").assertIsOff()
        node("sftp_perm_group_read").assertIsOn()
        node("sftp_perm_group_write").assertIsOff()
        node("sftp_perm_others_read").assertIsOn()
        node("sftp_perm_others_write").assertIsOff()
        node("sftp_perm_others_execute").assertIsOff()
    }

    @Test
    fun tickingABoxUpdatesTheNumber() {
        show(file(0x81A4))

        click("sftp_perm_group_write")

        node("sftp_perm_octal").assertTextContains("664")
        node("sftp_perm_group_write").assertIsOn()
    }

    @Test
    fun typingANumberUpdatesTheBoxes() {
        show(file(0x81A4))

        node("sftp_perm_octal").performTextReplacement("700")

        node("sftp_perm_owner_execute").assertIsOn()
        node("sftp_perm_group_read").assertIsOff()
        node("sftp_perm_others_read").assertIsOff()
    }

    @Test
    fun aNumberThatIsNotWholeShowsAnErrorAndApplyWaits() {
        show(file(0x81A4))

        node("sftp_perm_octal").performTextReplacement("75")

        composeTestRule.onNodeWithText(context.getString(R.string.shelldon_sftp_perm_octal_error)).assertIsDisplayed()
        apply().assertIsNotEnabled()
    }

    @Test
    fun digitsThatAreNotOctalAreDropped() {
        show(file(0x81A4))

        node("sftp_perm_octal").performTextReplacement("7x5y8-5")

        node("sftp_perm_octal").assertTextContains("755")
        apply().assertIsEnabled()
    }

    @Test
    fun applyWaitsUntilSomethingChanged() {
        show(file(0x81A4))
        apply().assertIsNotEnabled()

        click("sftp_perm_others_read")

        apply().assertIsEnabled()
        click("sftp_perm_others_read")
        apply().assertIsNotEnabled()
    }

    @Test
    fun applyReportsTheModeAndThatItIsNotRecursiveForAFile() {
        show(file(0x81A4))
        click("sftp_perm_owner_execute")

        apply().performClick()

        assertEquals(listOf(SftpMode(0b111_100_100) to false), applied)
        composeTestRule.onNodeWithTag("sftp_perm_recursive").assertDoesNotExist()
    }

    @Test
    fun aFolderCanBeChangedWithEverythingInsideIt() {
        show(folder(0x41ED))
        click("sftp_perm_others_read")
        click("sftp_perm_recursive")

        apply().performClick()

        assertEquals(listOf(SftpMode(0b111_101_100) to true), applied)
    }

    @Test
    fun recursionAloneIsEnoughToApply() {
        show(folder(0x41ED))
        apply().assertIsNotEnabled()

        click("sftp_perm_recursive")

        apply().assertIsEnabled()
    }

    @Test
    fun worldWritableAndSetIdAreWarnedAbout() {
        show(file(0x81A4))
        composeTestRule.onNodeWithTag("sftp_perm_warn_world").assertDoesNotExist()

        click("sftp_perm_others_write")
        node("sftp_perm_warn_world").assertIsDisplayed()

        click("sftp_perm_special_toggle")
        composeTestRule.onNodeWithTag("sftp_perm_warn_setid").assertDoesNotExist()
        click("sftp_perm_setuid")
        node("sftp_perm_warn_setid").assertIsDisplayed()
        node("sftp_perm_octal").assertTextContains("4646")
    }

    @Test
    fun specialBitsAreShownWhenTheModeHasThemAndSwitchRecursionOff() {
        show(folder(0x41ED or 0x800))

        node("sftp_perm_setuid").assertIsOn()
        node("sftp_perm_octal").assertTextContains("4755")
        node("sftp_perm_recursive").assertIsNotEnabled()
    }

    @Test
    fun specialBitsTakeBackARecursiveChoice() {
        show(folder(0x41ED))
        click("sftp_perm_recursive")
        click("sftp_perm_special_toggle")
        click("sftp_perm_sticky")

        apply().performClick()

        assertEquals(listOf(SftpMode(SftpMode.STICKY or 493) to false), applied)
    }

    @Test
    fun cancelLeavesWithoutApplying() {
        show(file(0x81A4))
        click("sftp_perm_owner_execute")

        composeTestRule.onNodeWithText(context.getString(R.string.shelldon_cancel)).performClick()

        assertEquals(1, dismissed)
        assertEquals(emptyList<Pair<SftpMode, Boolean>>(), applied)
    }
}
