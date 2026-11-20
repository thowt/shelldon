/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.hostlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.connectbot.shelldon.data.groups.GroupSnapshot
import org.connectbot.shelldon.data.groups.HostGroup
import org.connectbot.shelldon.data.groups.Rejection
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

    @Test
    fun lastSessions_unsavedOneCanBeSavedAndReopened() {
        var opened: LastSession? = null
        var saved: LastSession.Unsaved? = null
        var savedFolder: SaveFolder? = null
        val unsaved = LastSession.Unsaved(QuickConnectTarget("root", "10.0.0.2", 2222), System.currentTimeMillis() - 60_000)

        setContent(
            uiState = HostListUiState(hosts = listOf(testHost(id = UUID(0, 7), nickname = "prod"))),
            lastSessions = listOf(unsaved),
            onOpenLastSession = { opened = it },
            onSaveLastSession = { session, folder, done ->
                saved = session
                savedFolder = folder
                done(null)
            },
        )

        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.shelldon_last_sessions_title).uppercase())
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("shelldon_last_save_${unsaved.key}").performClick()
        // Saving asks which folder first; nothing is stored until that is confirmed.
        assertNull(saved)
        composeTestRule.onNodeWithTag("save_session_confirm").performClick()
        assertEquals(unsaved, saved)
        assertEquals(SaveFolder.None, savedFolder)
        composeTestRule.onNodeWithTag("save_session_dialog").assertDoesNotExist()
        assertNull(opened)

        composeTestRule.onNodeWithTag("shelldon_last_${unsaved.key}").performClick()
        assertEquals(unsaved, opened)
    }

    @Test
    fun lastSessions_savedHostHasNoSaveButton() {
        val host = testHost(id = UUID(0, 8), nickname = "db")
        val session = LastSession.Saved(host.copy(lastConnect = System.currentTimeMillis()))

        setContent(
            uiState = HostListUiState(hosts = listOf(host)),
            lastSessions = listOf(session),
        )

        composeTestRule.onNodeWithTag("shelldon_last_${session.key}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("shelldon_last_save_${session.key}").assertDoesNotExist()
    }

    @Test
    fun lastSessions_areNarrowedBySearching() {
        val unsaved = LastSession.Unsaved(QuickConnectTarget("root", "10.0.0.2", 22), System.currentTimeMillis())

        setContent(
            uiState = HostListUiState(hosts = listOf(testHost(id = UUID(0, 9), nickname = "prod"))),
            lastSessions = listOf(unsaved),
        )
        composeTestRule.onNodeWithTag("shelldon_last_${unsaved.key}").assertIsDisplayed()

        composeTestRule.onNodeWithTag("shelldon_search").performTextInput("prod")

        composeTestRule.onNodeWithTag("shelldon_last_${unsaved.key}").assertDoesNotExist()
    }

    @Test
    fun runningQuickConnect_isAnActiveSessionWithSaveButton() {
        var saved: Host? = null
        val temp = testHost(id = UUID(0, 10), nickname = "root@10.0.0.9").copy(isTemporary = true)

        setContent(
            uiState = HostListUiState(hosts = listOf(testHost(id = UUID(0, 11), nickname = "prod"))),
            unsavedSessions = listOf(temp),
            onSaveActiveSession = { host, _, done ->
                saved = host
                done(null)
            },
        )

        composeTestRule.onNodeWithTag("shelldon_session_${temp.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("shelldon_session_save_${temp.id}").performClick()
        assertNull(saved)
        composeTestRule.onNodeWithTag("save_session_confirm").performClick()

        assertEquals(temp, saved)
    }

    @Test
    fun saveDialog_startsOnNoFolderAndOffersTheExistingFoldersAndANewOne() {
        val work = HostGroup(id = UUID(5, 1), name = "Work")
        val prod = HostGroup(id = UUID(5, 2), parentId = work.id, name = "Prod")
        val unsaved = LastSession.Unsaved(QuickConnectTarget("root", "10.0.0.2", 22), System.currentTimeMillis())
        setContent(
            uiState = HostListUiState(hosts = listOf(testHost(id = UUID(0, 20), nickname = "web"))),
            lastSessions = listOf(unsaved),
            groupSnapshot = GroupSnapshot(listOf(work, prod), emptyList(), emptyList(), emptyList()),
        )

        composeTestRule.onNodeWithTag("shelldon_last_save_${unsaved.key}").performClick()

        composeTestRule.onNodeWithTag("save_session_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.shelldon_save_no_folder)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("save_session_folder").performClick()
        composeTestRule.onNodeWithTag("save_session_option_${work.id}").assertIsDisplayed()
        composeTestRule.onNodeWithText("Work / Prod").assertIsDisplayed()
        composeTestRule.onNodeWithTag("save_session_option_new").assertIsDisplayed()
    }

    @Test
    fun saveDialog_savesIntoAnExistingFolder() {
        val work = HostGroup(id = UUID(5, 1), name = "Work")
        var folder: SaveFolder? = null
        val temp = testHost(id = UUID(0, 21), nickname = "root@10.0.0.9").copy(isTemporary = true)
        setContent(
            uiState = HostListUiState(hosts = listOf(testHost(id = UUID(0, 22), nickname = "prod"))),
            unsavedSessions = listOf(temp),
            groupSnapshot = GroupSnapshot(listOf(work), emptyList(), emptyList(), emptyList()),
            onSaveActiveSession = { _, chosen, done ->
                folder = chosen
                done(null)
            },
        )

        composeTestRule.onNodeWithTag("shelldon_session_save_${temp.id}").performClick()
        composeTestRule.onNodeWithTag("save_session_folder").performClick()
        composeTestRule.onNodeWithTag("save_session_option_${work.id}").performClick()
        composeTestRule.onNodeWithTag("save_session_confirm").performClick()

        assertEquals(SaveFolder.Existing(work.id), folder)
    }

    @Test
    fun saveDialog_canCreateTheFolderInTheSameStep() {
        var folder: SaveFolder? = null
        val temp = testHost(id = UUID(0, 23), nickname = "root@10.0.0.9").copy(isTemporary = true)
        setContent(
            uiState = HostListUiState(hosts = listOf(testHost(id = UUID(0, 24), nickname = "prod"))),
            unsavedSessions = listOf(temp),
            onSaveActiveSession = { _, chosen, done ->
                folder = chosen
                done(null)
            },
        )

        composeTestRule.onNodeWithTag("shelldon_session_save_${temp.id}").performClick()
        composeTestRule.onNodeWithTag("save_session_folder").performClick()
        composeTestRule.onNodeWithTag("save_session_option_new").performClick()
        // A new folder needs a name before it can be saved.
        composeTestRule.onNodeWithTag("save_session_confirm").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("save_session_new_name").performTextInput("Lab")
        composeTestRule.onNodeWithTag("save_session_confirm").performClick()

        assertEquals(SaveFolder.New("Lab"), folder)
    }

    @Test
    fun saveDialog_staysOpenAndSaysWhyWhenTheFolderNameIsRefused() {
        val temp = testHost(id = UUID(0, 25), nickname = "root@10.0.0.9").copy(isTemporary = true)
        setContent(
            uiState = HostListUiState(hosts = listOf(testHost(id = UUID(0, 26), nickname = "prod"))),
            unsavedSessions = listOf(temp),
            onSaveActiveSession = { _, _, done -> done(Rejection.NAME_TAKEN) },
        )

        composeTestRule.onNodeWithTag("shelldon_session_save_${temp.id}").performClick()
        composeTestRule.onNodeWithTag("save_session_folder").performClick()
        composeTestRule.onNodeWithTag("save_session_option_new").performClick()
        composeTestRule.onNodeWithTag("save_session_new_name").performTextInput("Work")
        composeTestRule.onNodeWithTag("save_session_confirm").performClick()

        composeTestRule.onNodeWithTag("save_session_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("save_session_error").assertIsDisplayed()
    }

    @Test
    fun saveDialog_cancelStoresNothing() {
        var called = false
        val temp = testHost(id = UUID(0, 27), nickname = "root@10.0.0.9").copy(isTemporary = true)
        setContent(
            uiState = HostListUiState(hosts = listOf(testHost(id = UUID(0, 28), nickname = "prod"))),
            unsavedSessions = listOf(temp),
            onSaveActiveSession = { _, _, done ->
                called = true
                done(null)
            },
        )

        composeTestRule.onNodeWithTag("shelldon_session_save_${temp.id}").performClick()
        composeTestRule.onNodeWithTag("save_session_cancel").performClick()

        assertEquals(false, called)
        composeTestRule.onNodeWithTag("save_session_dialog").assertDoesNotExist()
    }

    @Test
    fun newButton_asksWhetherToCreateAHostOrAFolder() {
        val edits = mutableListOf<Host?>()
        setContent(
            uiState = HostListUiState(hosts = listOf(testHost(id = UUID(0, 30), nickname = "web"))),
            onNavigateToEditHost = { edits += it },
        )

        composeTestRule.onNodeWithTag("host_new").performClick()
        // Nothing opens yet: the button only offers the two choices.
        composeTestRule.onNodeWithTag("host_new_host").assertIsDisplayed()
        composeTestRule.onNodeWithTag("host_new_folder").assertIsDisplayed()
        assertTrue(edits.isEmpty())

        composeTestRule.onNodeWithTag("host_new_host").performClick()

        // A new host is the editor with no host.
        assertEquals(listOf<Host?>(null), edits)
    }

    @Test
    fun newButton_folderChoiceOpensTheFolderEditor() {
        setContent(uiState = HostListUiState(hosts = listOf(testHost(id = UUID(0, 31), nickname = "web"))))

        composeTestRule.onNodeWithTag("host_new").performClick()
        composeTestRule.onNodeWithTag("host_new_folder").performClick()

        composeTestRule.onNodeWithTag("group_name").assertIsDisplayed()
    }

    @Test
    fun folderChip_isNotBelowTheSearchFieldAnymore() {
        setContent(uiState = HostListUiState(hosts = listOf(testHost(id = UUID(0, 32), nickname = "web"))))

        composeTestRule.onNodeWithText("+ Folder").assertDoesNotExist()
    }

    @Test
    fun searchField_saysItCanAlsoQuickConnect() {
        setContent(uiState = HostListUiState(hosts = listOf(testHost(id = UUID(0, 33), nickname = "web"))))

        composeTestRule.onNodeWithText("Search, or quick connect user@host").assertIsDisplayed()
    }

    @Test
    fun quickConnect_keepsTheTextAndTheMatchingLastSessionStaysVisible() {
        val unsaved = LastSession.Unsaved(QuickConnectTarget("root", "10.0.0.2", 22), System.currentTimeMillis())

        setContent(
            uiState = HostListUiState(hosts = listOf(testHost(id = UUID(0, 12), nickname = "prod"))),
            lastSessions = listOf(unsaved),
        )

        composeTestRule.onNodeWithTag("shelldon_search").performTextInput("root@10.0.0.2")
        composeTestRule.onNodeWithTag("shelldon_quick_connect_row").performClick()

        composeTestRule.onNodeWithTag("shelldon_search").assertTextContains("root@10.0.0.2")
        composeTestRule.onNodeWithTag("shelldon_last_${unsaved.key}").assertIsDisplayed()
    }

    @Test
    fun clearButton_emptiesTheSearchField() {
        val web = testHost(id = UUID(0, 13), nickname = "web-01")
        val db = testHost(id = UUID(0, 14), nickname = "db-01")

        setContent(uiState = HostListUiState(hosts = listOf(web, db)))
        composeTestRule.onNodeWithTag("shelldon_search_clear").assertDoesNotExist()

        composeTestRule.onNodeWithTag("shelldon_search").performTextInput("web")
        composeTestRule.onNodeWithTag(HostListTestTags.itemRow(db.id)).assertDoesNotExist()

        composeTestRule.onNodeWithTag("shelldon_search_clear").performClick()

        composeTestRule.onNodeWithTag(HostListTestTags.itemRow(db.id)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("shelldon_search_clear").assertDoesNotExist()
    }

    @Test
    fun emptyState_offersTheSshImport() {
        var opened = 0
        setContent(uiState = HostListUiState(hosts = emptyList()), onImportSsh = { opened++ })

        composeTestRule.onNodeWithTag("empty_import_ssh").assertIsDisplayed().performClick()

        assertEquals(1, opened)
    }

    @Test
    fun overflowMenu_offersTheSshImport() {
        var opened = 0
        val web = testHost(id = UUID(0, 21), nickname = "web-01")
        setContent(uiState = HostListUiState(hosts = listOf(web)), onImportSsh = { opened++ })

        composeTestRule.onNodeWithContentDescription(composeTestRule.activity.getString(R.string.button_more_options)).performClick()
        composeTestRule.onNodeWithTag("host_menu_import_ssh").assertIsDisplayed().performClick()

        assertEquals(1, opened)
    }

    @Test
    fun filesAction_isOnlyAvailableOnAConnectedHost() {
        val web = testHost(id = UUID(0, 22), nickname = "web-01")
        val db = testHost(id = UUID(0, 23), nickname = "db-01")
        var filesFor: Host? = null
        setContent(
            uiState = HostListUiState(
                hosts = listOf(web, db),
                connectionStates = mapOf(web.id to ConnectionState.CONNECTED, db.id to ConnectionState.DISCONNECTED),
            ),
            onNavigateToFiles = { filesFor = it },
        )

        composeTestRule.onNodeWithTag(HostListTestTags.itemMenuButton(db.id)).performClick()
        composeTestRule.onNodeWithTag("host_files_${db.id}").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("host_files_${db.id}").performClick()
        assertNull(filesFor)
    }

    @Test
    fun filesAction_opensTheFilesOfAConnectedHost() {
        val web = testHost(id = UUID(0, 24), nickname = "web-01")
        var filesFor: Host? = null
        setContent(
            uiState = HostListUiState(hosts = listOf(web), connectionStates = mapOf(web.id to ConnectionState.CONNECTED)),
            onNavigateToFiles = { filesFor = it },
        )

        composeTestRule.onNodeWithTag(HostListTestTags.itemMenuButton(web.id)).performClick()
        composeTestRule.onNodeWithTag("host_files_${web.id}").assertIsEnabled().performClick()

        assertEquals(web.id, filesFor?.id)
    }

    private fun setContent(
        uiState: HostListUiState = HostListUiState(),
        makingShortcut: Boolean = false,
        onImportSsh: () -> Unit = {},
        onNavigateToFiles: (Host) -> Unit = {},
        onNavigateToConsole: (Host) -> Unit = {},
        onSelectShortcut: (Host) -> Unit = {},
        onNavigateToEditHost: (Host?) -> Unit = {},
        onQuickConnect: (QuickConnectTarget) -> Unit = {},
        unsavedSessions: List<Host> = emptyList(),
        lastSessions: List<LastSession> = emptyList(),
        onSaveActiveSession: (Host, SaveFolder, (Rejection?) -> Unit) -> Unit = { _, _, done -> done(null) },
        onOpenLastSession: (LastSession) -> Unit = {},
        onSaveLastSession: (LastSession.Unsaved, SaveFolder, (Rejection?) -> Unit) -> Unit = { _, _, done -> done(null) },
        groupSnapshot: GroupSnapshot = GroupSnapshot(emptyList(), emptyList(), emptyList(), emptyList()),
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
                    onImportSsh = onImportSsh,
                    onNavigateToFiles = onNavigateToFiles,
                    unsavedSessions = unsavedSessions,
                    lastSessions = lastSessions,
                    onSaveActiveSession = onSaveActiveSession,
                    onOpenLastSession = onOpenLastSession,
                    onSaveLastSession = onSaveLastSession,
                    groupSnapshot = groupSnapshot,
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
