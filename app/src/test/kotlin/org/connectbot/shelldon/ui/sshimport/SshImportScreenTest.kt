/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("NewApi")

package org.connectbot.shelldon.ui.sshimport

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.connectbot.shelldon.sshconfig.ED1
import org.connectbot.shelldon.sshconfig.ED2
import org.connectbot.shelldon.sshconfig.ExistingHost
import org.connectbot.shelldon.sshconfig.KeyDecodeResult
import org.connectbot.shelldon.sshconfig.KeyFileDecoder
import org.connectbot.shelldon.sshconfig.PickedFile
import org.connectbot.shelldon.sshconfig.SshImportInput
import org.connectbot.shelldon.sshconfig.SshImportOptions
import org.connectbot.shelldon.sshconfig.SshImportPlan
import org.connectbot.shelldon.sshconfig.SshImportPlanner
import org.connectbot.shelldon.sshconfig.SshImportResult
import org.connectbot.shelldon.sshconfig.TestPem
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** The preview of an `.ssh` import, driven by plans built from real file text. */
@RunWith(AndroidJUnit4::class)
class SshImportScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun file(name: String, text: String) = PickedFile(name, text.toByteArray())

    private val decoder = KeyFileDecoder { text, passphrase ->
        if ("ENCRYPTED" in text && passphrase != "right") KeyDecodeResult.NeedsPassphrase else KeyDecodeResult.Decoded("ssh-ed25519", byteArrayOf(1), byteArrayOf(1))
    }

    private val config = """
        Host old
            HostName old.example.com
            KexAlgorithms +diffie-hellman-group1-sha1
            StrictHostKeyChecking no
        Host new
            HostName new.example.com
        Host *
            Ciphers aes128-cbc
    """.trimIndent()

    private fun plan(vararg files: PickedFile) = SshImportPlanner.plan(SshImportInput(files.toList(), emptyList(), emptyList(), emptyMap()), decoder) { true }

    /** The node with [tag], after scrolling the preview list to it. */
    private fun scrolled(tag: String): SemanticsNodeInteraction {
        composeTestRule.onNodeWithTag("sshimport_preview").performScrollToNode(hasTestTag(tag))
        return composeTestRule.onNodeWithTag(tag)
    }

    private val options = mutableListOf<SshImportOptions>()
    private val unlocked = mutableListOf<Pair<String, String>>()
    private var imports = 0
    private var startedOver = 0

    private fun show(state: MutableStateFlow<SshImportUiState>) {
        composeTestRule.setContent {
            ConnectBotTheme {
                SshImportContent(
                    uiState = state,
                    onNavigateBack = {},
                    onFilesPick = {},
                    onUnlock = { name, passphrase -> unlocked.add(name to passphrase) },
                    onOptionsChange = { options.add(it) },
                    onImport = { imports++ },
                    onStartOver = { startedOver++ },
                )
            }
        }
    }

    private fun preview(plan: SshImportPlan, opts: SshImportOptions = SshImportOptions()) = MutableStateFlow(SshImportUiState(phase = SshImportPhase.PREVIEW, plan = plan, options = opts))

    @Test
    fun theFirstStepOffersToChooseFiles() {
        show(MutableStateFlow(SshImportUiState()))
        composeTestRule.onNodeWithTag("sshimport_pick").assertIsDisplayed()
    }

    @Test
    fun anErrorIsShownOnTheFirstStep() {
        show(MutableStateFlow(SshImportUiState(error = SshImportError.NOTHING_FOUND)))
        composeTestRule.onNodeWithTag("sshimport_error").assertIsDisplayed()
    }

    @Test
    fun thePreviewCountsHostsAndOffersTheAlgorithmSwitches() {
        show(preview(plan(file("config", config))))

        composeTestRule.onNodeWithTag("sshimport_hosts_new").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sshimport_option_algorithms").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sshimport_option_wildcard").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sshimport_import").assertIsEnabled()
    }

    @Test
    fun theWildcardSwitchStartsOffAndReportsTheChange() {
        show(preview(plan(file("config", config))))

        composeTestRule.onNodeWithTag("sshimport_option_wildcard").performClick()

        assertEquals(listOf(SshImportOptions(wildcardAlgorithms = true)), options)
    }

    @Test
    fun hostKeyCheckingTurnedOffIsShownAsNotImported() {
        show(preview(plan(file("config", config))))
        scrolled("sshimport_refused").assertIsDisplayed()
    }

    @Test
    fun hostKeysShowWhatWillBeAddedAndWhatIsNotSavedHere() {
        val known = file("known_hosts", "old.example.com ssh-ed25519 $ED1\nnew.example.com ssh-ed25519 $ED2\nelsewhere.example.com ssh-ed25519 $ED2")
        show(preview(plan(file("config", config), known)))

        scrolled("sshimport_option_known_hosts").assertIsDisplayed()
        scrolled("sshimport_known_unmatched").assertIsDisplayed()
    }

    @Test
    fun anEncryptedKeyAsksForItsPassphraseAndReportsIt() {
        val key = file("id_enc", TestPem.openSsh("ENCRYPTED") + "\n")
        show(preview(plan(file("config", "Host a"), key)))

        scrolled("sshimport_key_unlock_id_enc").assertIsNotEnabled()
        scrolled("sshimport_key_passphrase_id_enc").performTextInput("right")
        composeTestRule.onNodeWithTag("sshimport_key_unlock_id_enc").assertIsEnabled().performClick()

        assertEquals(listOf("id_enc" to "right"), unlocked)
    }

    @Test
    fun importIsOnlyOfferedWhenSomethingWouldBeWritten() {
        // Every host is already saved and there are no keys or host keys: nothing to do.
        val onlyExisting = SshImportPlanner.plan(
            SshImportInput(
                listOf(file("config", "Host a")),
                listOf(ExistingHost(UUID.randomUUID(), "a", "a", 22, true)),
                emptyList(),
                emptyMap(),
            ),
            decoder,
        ) { true }
        show(preview(onlyExisting))

        composeTestRule.onNodeWithTag("sshimport_import").assertIsNotEnabled()
    }

    @Test
    fun importAndStartOverReportToTheCaller() {
        show(preview(plan(file("config", config))))

        composeTestRule.onNodeWithTag("sshimport_import").performClick()
        composeTestRule.onNodeWithTag("sshimport_start_over").performClick()

        assertEquals(1, imports)
        assertEquals(1, startedOver)
    }

    @Test
    fun theResultIsShownWhenDone() {
        val result = SshImportResult(3, 1, 1, 1, 1, 2, 1, emptyList())
        show(MutableStateFlow(SshImportUiState(phase = SshImportPhase.DONE, result = result)))
        composeTestRule.onNodeWithTag("sshimport_done").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sshimport_done_close").assertIsDisplayed()
    }
}
