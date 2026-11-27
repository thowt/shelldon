/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("NewApi")

package org.connectbot.shelldon.ui.hostimport

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.connectbot.shelldon.hostimport.CsvField
import org.connectbot.shelldon.hostimport.CsvImport
import org.connectbot.shelldon.hostimport.CsvSource
import org.connectbot.shelldon.hostimport.HostImportOptions
import org.connectbot.shelldon.hostimport.HostImportPlan
import org.connectbot.shelldon.hostimport.HostImportPlanner
import org.connectbot.shelldon.hostimport.HostImportResult
import org.connectbot.shelldon.hostimport.ImportProtocol
import org.connectbot.shelldon.hostimport.ImportedHost
import org.connectbot.shelldon.hostimport.SourceFormat
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The pages of the import from another app, driven by plans built from real file text. */
@RunWith(AndroidJUnit4::class)
class HostImportScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val options = mutableListOf<HostImportOptions>()
    private val columns = mutableListOf<Pair<CsvField, Int?>>()
    private val headers = mutableListOf<Boolean>()
    private var imports = 0
    private var startedOver = 0
    private var closed = 0

    private fun csv(text: String): CsvSource = checkNotNull(CsvImport.open(text))

    private fun planOf(hosts: List<ImportedHost>, options: HostImportOptions = HostImportOptions()): HostImportPlan = HostImportPlanner.plan(hosts, emptyList(), options, maxFolderDepth = 16)

    private fun previewOf(source: CsvSource, plan: HostImportPlan = planOf(source.hosts)) = HostImportUiState(
        phase = HostImportPhase.PREVIEW,
        format = SourceFormat.CSV,
        plan = plan,
        csv = CsvView(CsvImport.columnNames(source.table, source.settings.hasHeader), source.settings.hasHeader, source.settings.columns),
    )

    private fun show(state: MutableStateFlow<HostImportUiState>) {
        composeTestRule.setContent {
            ConnectBotTheme {
                HostImportContent(
                    uiState = state,
                    onNavigateBack = { closed++ },
                    onFilePick = {},
                    onOptionsChange = { options.add(it) },
                    onColumnChange = { field, column -> columns.add(field to column) },
                    onHeaderChange = { headers.add(it) },
                    onImport = { imports++ },
                    onStartOver = { startedOver++ },
                )
            }
        }
    }

    private fun scrolled(tag: String): SemanticsNodeInteraction {
        composeTestRule.onNodeWithTag("hostimport_preview").performScrollToNode(hasTestTag(tag))
        return composeTestRule.onNodeWithTag(tag)
    }

    @Test
    fun theStartPageSaysPasswordsAreNeverImported() {
        show(MutableStateFlow(HostImportUiState()))
        composeTestRule.onNodeWithTag("hostimport_pick").assertIsDisplayed()
        composeTestRule.onNodeWithText("Passwords in these files are never imported", substring = true).assertIsDisplayed()
    }

    @Test
    fun anErrorIsShownOnTheStartPage() {
        show(MutableStateFlow(HostImportUiState(error = HostImportError.UNSAFE_XML)))
        composeTestRule.onNodeWithTag("hostimport_error").assertIsDisplayed()
    }

    @Test
    fun thePreviewCountsHostsAndNamesTheFormat() {
        val source = csv("Label,Address,Notes,Folder\nweb,10.0.0.1,x,Prod\nrouter,192.168.1.1,y,\nbad,,z,\n")
        show(MutableStateFlow(previewOf(source)))
        composeTestRule.onNodeWithTag("hostimport_format").assertIsDisplayed()
        scrolled("hostimport_hosts_new").assertIsDisplayed()
        composeTestRule.onNodeWithText("New hosts: 2").assertIsDisplayed()
        scrolled("hostimport_invalid").assertIsDisplayed()
        composeTestRule.onNodeWithTag("hostimport_import").assertIsEnabled().performClick()
        assertEquals(1, imports)
    }

    @Test
    fun importIsOffWhenNothingIsNew() {
        val source = csv("Label,Address\n")
        show(MutableStateFlow(previewOf(source)))
        composeTestRule.onNodeWithTag("hostimport_import").assertIsNotEnabled()
    }

    @Test
    fun withoutAnAddressColumnTheScreenSaysWhatToChoose() {
        val source = csv("Label,Notes\nweb,x\n")
        show(MutableStateFlow(previewOf(source)))
        scrolled("hostimport_need_host").assertIsDisplayed()
        composeTestRule.onNodeWithTag("hostimport_import").assertIsNotEnabled()
    }

    @Test
    fun choosingAColumnReportsTheFieldAndTheColumn() {
        val source = csv("Label,Address,Notes\nweb,10.0.0.1,x\n")
        show(MutableStateFlow(previewOf(source)))
        scrolled("hostimport_column_FOLDER").performClick()
        composeTestRule.onNodeWithText("Notes").performClick()
        assertEquals(listOf<Pair<CsvField, Int?>>(CsvField.FOLDER to 2), columns)
    }

    @Test
    fun theHeaderSwitchReportsItsNewValue() {
        val source = csv("Label,Address\nweb,10.0.0.1\n")
        show(MutableStateFlow(previewOf(source)))
        scrolled("hostimport_header").performClick()
        assertEquals(listOf(false), headers)
    }

    @Test
    fun telnetEntriesAreOffUntilTheSwitchIsTurnedOn() {
        val hosts = listOf(
            ImportedHost("web", "10.0.0.1", null, "root", ImportProtocol.SSH),
            ImportedHost("router", "192.168.1.1", null, "", ImportProtocol.TELNET),
        )
        val state = HostImportUiState(phase = HostImportPhase.PREVIEW, format = SourceFormat.MOBAXTERM, plan = planOf(hosts))
        show(MutableStateFlow(state))
        scrolled("hostimport_telnet").performClick()
        assertEquals(listOf(HostImportOptions(includeTelnet = true)), options)
    }

    @Test
    fun leftBehindPasswordsAreMentioned() {
        val hosts = listOf(ImportedHost("web", "10.0.0.1", null, "root", ImportProtocol.SSH, hadPassword = true))
        val state = HostImportUiState(phase = HostImportPhase.PREVIEW, format = SourceFormat.MREMOTENG, plan = planOf(hosts))
        show(MutableStateFlow(state))
        scrolled("hostimport_passwords").assertIsDisplayed()
    }

    @Test
    fun startingOverIsPossibleFromThePreview() {
        val source = csv("Label,Address\nweb,10.0.0.1\n")
        show(MutableStateFlow(previewOf(source)))
        composeTestRule.onNodeWithTag("hostimport_start_over").performClick()
        assertEquals(1, startedOver)
    }

    @Test
    fun theDonePageShowsWhatWasSaved() {
        val state = HostImportUiState(phase = HostImportPhase.DONE, result = HostImportResult(hostsAdded = 3, hostsSkipped = 1, foldersCreated = 2, tagsCreated = 0))
        show(MutableStateFlow(state))
        composeTestRule.onNodeWithText("Hosts added: 3 (already saved: 1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Folders created: 2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("hostimport_done_close").performClick()
        assertEquals(1, closed)
    }
}
