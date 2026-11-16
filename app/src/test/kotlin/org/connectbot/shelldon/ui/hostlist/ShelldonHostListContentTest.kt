/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.hostlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.connectbot.HiltComponentActivity
import org.connectbot.R
import org.connectbot.data.entity.Host
import org.connectbot.ui.screens.hostlist.ConnectionState
import org.connectbot.ui.screens.hostlist.HostListTestTags
import org.connectbot.ui.screens.hostlist.HostListUiState
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ShelldonHostListContentTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltComponentActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun emptyState_addButtonOpensTheEditor() {
        var editedHost: Host? = Host(nickname = "sentinel", hostname = "sentinel")

        setContent(
            uiState = HostListUiState(hosts = emptyList()),
            onNavigateToEditHost = { editedHost = it },
        )

        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.shelldon_empty_title))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.shelldon_empty_add))
            .performClick()

        assertNull(editedHost)
    }

    @Test
    fun hostCard_tapConnects() {
        var navigatedHost: Host? = null
        val host = testHost(id = UUID(0, 1), nickname = "prod")

        setContent(
            uiState = HostListUiState(hosts = listOf(host)),
            onNavigateToConsole = { navigatedHost = it },
        )

        composeTestRule.onNodeWithTag(HostListTestTags.itemRow(host.id)).performClick()

        assertEquals(host, navigatedHost)
    }

    @Test
    fun hostCard_inShortcutMode_selectsInsteadOfConnecting() {
        var selected: Host? = null
        var navigated = false
        val host = testHost(id = UUID(0, 2), nickname = "prod")

        setContent(
            uiState = HostListUiState(hosts = listOf(host)),
            makingShortcut = true,
            onNavigateToConsole = { navigated = true },
            onSelectShortcut = { selected = it },
        )

        composeTestRule.onNodeWithTag(HostListTestTags.itemRow(host.id)).performClick()

        assertEquals(host, selected)
        assertTrue(!navigated)
    }

    @Test
    fun search_hidesHostsThatDoNotMatch() {
        val web = testHost(id = UUID(0, 3), nickname = "web-01")
        val db = testHost(id = UUID(0, 4), nickname = "db-02")

        setContent(uiState = HostListUiState(hosts = listOf(web, db)))

        composeTestRule.onNodeWithTag("shelldon_search").performTextInput("web")

        composeTestRule.onNodeWithTag(HostListTestTags.itemRow(web.id)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(HostListTestTags.itemRow(db.id)).assertDoesNotExist()
    }

    @Test
    fun typingAnAddress_offersQuickConnect() {
        var target: QuickConnectTarget? = null
        val host = testHost(id = UUID(0, 5), nickname = "prod")

        setContent(
            uiState = HostListUiState(hosts = listOf(host)),
            onQuickConnect = { target = it },
        )

        composeTestRule.onNodeWithTag("shelldon_search").performTextInput("root@10.0.0.2:2222")
        composeTestRule.onNodeWithTag("shelldon_quick_connect_row").performClick()

        assertEquals(QuickConnectTarget("root", "10.0.0.2", 2222), target)
    }

    @Test
    fun connectedHost_isListedAsAnActiveSession() {
        var navigatedHost: Host? = null
        val host = testHost(id = UUID(0, 6), nickname = "web-01")

        setContent(
            uiState = HostListUiState(
                hosts = listOf(host),
                connectionStates = mapOf(host.id to ConnectionState.CONNECTED),
            ),
            onNavigateToConsole = { navigatedHost = it },
        )

        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.shelldon_sessions_title).uppercase())
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("shelldon_session_${host.id}").performClick()

        assertEquals(host, navigatedHost)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExist() =
        assertDoesNotExist()

    private fun setContent(
        uiState: HostListUiState = HostListUiState(),
        makingShortcut: Boolean = false,
        onNavigateToConsole: (Host) -> Unit = {},
        onSelectShortcut: (Host) -> Unit = {},
        onNavigateToEditHost: (Host?) -> Unit = {},
        onQuickConnect: (QuickConnectTarget) -> Unit = {},
    ) {
        composeTestRule.setContent {
            ConnectBotTheme {
                ShelldonHostListContent(
                    uiState = uiState,
                    makingShortcut = makingShortcut,
                    onNavigateToConsole = onNavigateToConsole,
                    onSelectShortcut = onSelectShortcut,
                    onNavigateToEditHost = onNavigateToEditHost,
                    onNavigateToPortForwards = {},
                    onNavigateToProfiles = {},
                    onNavigateToHelp = {},
                    onToggleSortOrder = {},
                    onDeleteHost = {},
                    onDuplicateHost = {},
                    onForgetHostKeys = {},
                    onDisconnectHost = {},
                    onDisconnectAll = {},
                    onQuickConnect = onQuickConnect,
                )
            }
        }
    }

    private fun testHost(id: UUID, nickname: String): Host = Host(
        id = id,
        nickname = nickname,
        protocol = "ssh",
        username = "user",
        hostname = "$nickname.example.com",
        port = 22,
        color = "#2196F3",
    )
}
