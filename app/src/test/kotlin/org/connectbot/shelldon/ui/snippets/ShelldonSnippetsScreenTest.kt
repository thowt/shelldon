/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.snippets

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.flowOf
import org.connectbot.R
import org.connectbot.shelldon.snippets.Snippet
import org.connectbot.shelldon.snippets.SnippetBody
import org.connectbot.shelldon.snippets.SnippetRejection
import org.connectbot.shelldon.snippets.SnippetRepository
import org.connectbot.shelldon.snippets.SnippetResult
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyBlocking
import java.util.UUID

/** The Snippets tab, driven through its real view model over a stand-in repository. */
@RunWith(AndroidJUnit4::class)
class ShelldonSnippetsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(id: Int) = context.getString(id)

    private val restart = Snippet(UUID(0, 1), "web-restart", SnippetBody("sudo systemctl restart nginx", pressEnter = true))
    private val backup = Snippet(UUID(0, 2), "db-backup", SnippetBody("pg_dumpall > backup.sql", pressEnter = true))

    private fun setContent(snippets: List<Snippet>, saveResult: SnippetResult<UUID>? = null): SnippetRepository {
        val repository = mock<SnippetRepository> {
            on { observeSnippets() } doReturn flowOf(snippets)
            if (saveResult != null) {
                onBlocking { save(anyOrNull(), any(), any()) } doReturn saveResult
            }
        }
        composeTestRule.setContent {
            ConnectBotTheme {
                ShelldonSnippetsScreen(viewModel = SnippetsViewModel(repository))
            }
        }
        return repository
    }

    @Test
    fun noSnippets_saysSoAndOffersToCreateOne() {
        setContent(emptyList())

        composeTestRule.onNodeWithTag("snippets_empty").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.shelldon_snippets_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("snippets_search").assertDoesNotExist()
    }

    @Test
    fun snippets_areListedWithASearchField() {
        setContent(listOf(restart, backup))

        composeTestRule.onNodeWithTag("snippet_card_web-restart").assertIsDisplayed()
        composeTestRule.onNodeWithTag("snippet_card_db-backup").assertIsDisplayed()
        composeTestRule.onNodeWithTag("snippets_search").assertIsDisplayed()
    }

    @Test
    fun search_narrowsTheListByNameOrText() {
        setContent(listOf(restart, backup))

        composeTestRule.onNodeWithTag("snippets_search").performTextInput("nginx")

        composeTestRule.onNodeWithTag("snippet_card_web-restart").assertIsDisplayed()
        composeTestRule.onNodeWithTag("snippet_card_db-backup").assertDoesNotExist()
    }

    @Test
    fun search_withoutAMatchSaysSo() {
        setContent(listOf(restart, backup))

        composeTestRule.onNodeWithTag("snippets_search").performTextInput("zzz")

        composeTestRule.onNodeWithText(context.getString(R.string.shelldon_snippets_no_match, "zzz")).assertIsDisplayed()
        composeTestRule.onNodeWithTag("snippet_card_web-restart").assertDoesNotExist()
    }

    @Test
    fun sending_withoutAnOpenSessionTellsYouToConnectFirst() {
        setContent(listOf(restart))

        composeTestRule.onNodeWithTag("snippet_send_web-restart").performClick()

        composeTestRule.onNodeWithText(string(R.string.shelldon_snippet_no_session)).assertIsDisplayed()
    }

    @Test
    fun newSnippet_isSavedWithTheTextAndEnterOn() {
        val repository = setContent(emptyList(), saveResult = SnippetResult.Ok(UUID(0, 3)))

        composeTestRule.onNodeWithTag("snippet_new").performClick()
        composeTestRule.onNodeWithTag("snippet_name").performTextInput("uptime")
        composeTestRule.onNodeWithTag("snippet_text").performTextInput("uptime -p")
        composeTestRule.onNodeWithTag("snippet_save").performClick()

        verifyBlocking(repository) { save(isNull(), eq("uptime"), eq(SnippetBody("uptime -p", pressEnter = true))) }
        composeTestRule.onNodeWithTag("snippet_name").assertDoesNotExist()
    }

    @Test
    fun aRejectedSnippet_keepsTheEditorOpenWithTheReason() {
        setContent(emptyList(), saveResult = SnippetResult.Rejected(SnippetRejection.NAME_TAKEN))

        composeTestRule.onNodeWithTag("snippet_new").performClick()
        composeTestRule.onNodeWithTag("snippet_name").performTextInput("uptime")
        composeTestRule.onNodeWithTag("snippet_text").performTextInput("uptime -p")
        composeTestRule.onNodeWithTag("snippet_save").performClick()

        composeTestRule.onNodeWithTag("snippet_error").assertIsDisplayed()
        composeTestRule.onNodeWithTag("snippet_name").assertIsDisplayed()
    }

    @Test
    fun tappingASnippet_opensItInTheEditor() {
        setContent(listOf(restart))

        composeTestRule.onNodeWithTag("snippet_card_web-restart").performClick()

        composeTestRule.onNodeWithTag("snippet_name").assertIsDisplayed()
        composeTestRule.onNodeWithTag("snippet_delete").assertIsDisplayed()
    }
}
