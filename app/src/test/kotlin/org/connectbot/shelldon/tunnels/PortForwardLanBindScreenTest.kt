/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.tunnels

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.connectbot.HiltComponentActivity
import org.connectbot.ui.screens.portforwardlist.PortForwardEditorScreenContent
import org.connectbot.ui.screens.portforwardlist.PortForwardEditorTestTags
import org.connectbot.ui.screens.portforwardlist.PortForwardEditorUiState
import org.connectbot.ui.screens.portforwardlist.SourceAddressOption
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.util.HostConstants
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The listen address of local and dynamic forwards in the editor, and the confirmation it asks for. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PortForwardLanBindScreenTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    private val acknowledged = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    private fun show(state: PortForwardEditorUiState) {
        composeTestRule.setContent {
            ConnectBotTheme {
                PortForwardEditorScreenContent(
                    uiState = state,
                    onNavigateBack = {},
                    onNicknameChange = {},
                    onTypeChange = {},
                    onSourcePortChange = {},
                    onSourceAddressOptionChange = {},
                    onSpecificAddressChange = {},
                    onDestinationChange = {},
                    onSave = {},
                    onLanAcknowledgedChange = { acknowledged.add(it) },
                )
            }
        }
    }

    private fun state(
        type: String = HostConstants.PORTFORWARD_LOCAL,
        option: SourceAddressOption = SourceAddressOption.LOCALHOST,
        specific: String = "",
        confirmed: Boolean = false,
    ) = PortForwardEditorUiState(
        isLoading = false,
        hasUnsavedChanges = true,
        type = type,
        sourceAddressOption = option,
        specificAddress = specific,
        lanAcknowledged = confirmed,
    )

    @Test
    fun aLocalForwardOnLoopbackShowsNoWarning() {
        show(state())
        composeTestRule.onNodeWithTag(PortForwardEditorTestTags.LAN_WARNING).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Create port forward").assertIsDisplayed()
    }

    @Test
    fun listeningOnTheNetworkShowsTheWarningAndHidesSaveUntilConfirmed() {
        show(state(option = SourceAddressOption.ALL))
        composeTestRule.onNodeWithTag(PortForwardEditorTestTags.LAN_WARNING).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Create port forward").assertDoesNotExist()
    }

    @Test
    fun theConfirmationReportsItsNewValueAndSaveAppearsOnceItIsSet() {
        show(state(type = HostConstants.PORTFORWARD_DYNAMIC5, option = SourceAddressOption.ALL_IPV4))
        composeTestRule.onNodeWithTag(PortForwardEditorTestTags.LAN_ACKNOWLEDGE).performScrollTo().performClick()
        assertEquals(listOf(true), acknowledged)
    }

    @Test
    fun aConfirmedForwardCanBeSaved() {
        show(state(option = SourceAddressOption.SPECIFIC, specific = "192.168.1.20", confirmed = true))
        composeTestRule.onNodeWithContentDescription("Create port forward").assertIsDisplayed()
    }

    @Test
    fun aHostNameIsRefusedAndSaveStaysHidden() {
        show(state(option = SourceAddressOption.SPECIFIC, specific = "my-laptop.local", confirmed = true))
        composeTestRule.onNodeWithContentDescription("Create port forward").assertDoesNotExist()
    }
}
