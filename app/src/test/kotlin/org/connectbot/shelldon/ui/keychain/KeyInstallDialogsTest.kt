/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("NewApi")

package org.connectbot.shelldon.ui.keychain

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.connectbot.R
import org.connectbot.data.HostRepository
import org.connectbot.data.PubkeyRepository
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.Pubkey
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalBridge
import org.connectbot.service.TerminalManager
import org.connectbot.shelldon.exec.ExecResult
import org.connectbot.shelldon.keyinstall.KeyInstallScript
import org.connectbot.shelldon.keyinstall.PendingKeyInstall
import org.connectbot.shelldon.keyinstall.toAuthorizedKeyLine
import org.connectbot.transport.AbsTransport
import org.connectbot.ui.LocalTerminalManager
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The "install this key on a server" dialogs, driven through their real view model over stand-in
 * repositories and a stand-in SSH transport that records the command it is asked to run.
 */
@RunWith(AndroidJUnit4::class)
class KeyInstallDialogsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private val bastion = Host(
        id = UUID(0, 1),
        nickname = "bastion",
        protocol = "ssh",
        username = "ops",
        hostname = "bastion.example.com",
        port = 22,
        color = "#2196F3",
    )

    private val legacy = Host(
        id = UUID(0, 2),
        nickname = "legacy",
        protocol = "ssh",
        username = "root",
        hostname = "old.example.com",
        port = 22,
        color = "#2196F3",
    )

    private val key = ecKey("ops-key")

    /** The same holder the view model uses, so a test can see and set what is waiting for a login. */
    private val pendingInstall = PendingKeyInstall()

    private class Calls {
        var connected: Host? = null
        var dismissed = 0
    }

    private val calls = Calls()

    /** Every command the stand-in transport was asked to run. */
    private val commands = CopyOnWriteArrayList<String>()

    private fun ecKey(nickname: String): Pubkey {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return Pubkey(
            nickname = nickname,
            type = "EC",
            privateKey = null,
            publicKey = pair.public.encoded,
            encrypted = false,
            startup = false,
            confirmation = false,
            createdDate = 0,
        )
    }

    private fun unusableKey(nickname: String) = Pubkey(
        nickname = nickname,
        type = "IMPORTED",
        privateKey = null,
        publicKey = byteArrayOf(1, 2, 3),
        encrypted = false,
        startup = false,
        confirmation = false,
        createdDate = 0,
    )

    private fun reply(stdout: String, stderr: String = "", exitCode: Int? = 0) = ExecResult(
        stdout = stdout,
        stderr = stderr,
        exitCode = exitCode,
        signal = null,
        stdoutTruncated = false,
        stderrTruncated = false,
        durationMillis = 1,
    )

    /** A live SSH connection to [host] whose server answers every command with [answer]. */
    private fun connection(host: Host, answer: ExecResult): TerminalBridge {
        val transport = mock<AbsTransport> {
            on { canExecCommand() } doReturn true
            onBlocking { execCommand(any(), any()) } doAnswer { invocation ->
                commands += invocation.getArgument<String>(0)
                answer
            }
        }
        return mock<TerminalBridge> {
            on { this.host } doReturn host
            on { this.transport } doReturn transport
        }
    }

    private fun setDialog(
        hosts: List<Host>,
        keys: List<Pubkey>,
        bridges: List<TerminalBridge>,
        shown: Pubkey,
        picker: Boolean = false,
        afterLogin: Boolean = false,
    ) {
        val hostRepository = mock<HostRepository> {
            on { observeHosts() } doReturn flowOf(hosts)
        }
        val pubkeyRepository = mock<PubkeyRepository> {
            on { observeAll() } doReturn flowOf(keys)
        }
        val manager = mock<TerminalManager> {
            on { bridgesFlow } doReturn MutableStateFlow(bridges)
            on { hostStatusChangedFlow } doReturn MutableSharedFlow<Unit>()
        }
        val dispatchers = CoroutineDispatchers(Dispatchers.Default, Dispatchers.IO, Dispatchers.Main)
        val viewModel = KeyInstallViewModel(hostRepository, pubkeyRepository, dispatchers, pendingInstall)
        composeTestRule.setContent {
            ConnectBotTheme {
                CompositionLocalProvider(LocalTerminalManager provides manager) {
                    if (afterLogin) {
                        AfterLoginKeyInstall(
                            hostId = bastion.id,
                            hostName = "bastion",
                            viewModel = viewModel,
                        )
                    } else if (picker) {
                        KeyPickerInstallDialog(
                            hostId = bastion.id,
                            hostName = "bastion",
                            onDismiss = { calls.dismissed++ },
                            viewModel = viewModel,
                        )
                    } else {
                        KeyInstallDialog(
                            key = shown,
                            onConnect = { calls.connected = it },
                            onDismiss = { calls.dismissed++ },
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
    }

    private fun waitForTag(tag: String) {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aConnectedServer_isInstalledOnlyAfterTheFingerprintIsConfirmed() {
        val line = key.toAuthorizedKeyLine()
        assertNotNull(line)
        setDialog(
            hosts = listOf(bastion, legacy),
            keys = listOf(key),
            bridges = listOf(connection(bastion, reply(KeyInstallScript.ADDED_MARKER))),
            shown = key,
        )
        waitForTag("key_install_run_bastion")

        composeTestRule.onNodeWithTag("key_install_run_bastion").performClick()
        composeTestRule.onNodeWithText(string(R.string.shelldon_key_install_confirm_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            string(R.string.shelldon_key_install_confirm_body, "ops-key", line!!.fingerprint, "bastion"),
        ).assertIsDisplayed()
        assertTrue("nothing runs before the confirmation", commands.isEmpty())

        composeTestRule.onNodeWithTag("key_install_confirm").performClick()
        waitForTag("key_install_result_bastion")

        composeTestRule.onNodeWithText(string(R.string.shelldon_key_install_added)).assertIsDisplayed()
        assertEquals(1, commands.size)
        assertTrue("the command carries the key", commands.single().contains(line.keyText))
    }

    @Test
    fun cancellingTheConfirmation_runsNothing() {
        setDialog(
            hosts = listOf(bastion),
            keys = listOf(key),
            bridges = listOf(connection(bastion, reply(KeyInstallScript.ADDED_MARKER))),
            shown = key,
        )
        waitForTag("key_install_run_bastion")

        composeTestRule.onNodeWithTag("key_install_run_bastion").performClick()
        composeTestRule.onNodeWithText(string(R.string.shelldon_cancel)).performClick()
        composeTestRule.waitForIdle()

        assertTrue(commands.isEmpty())
        composeTestRule.onAllNodesWithTag("key_install_result_bastion").assertCountEquals(0)
        composeTestRule.onNodeWithTag("key_install_run_bastion").assertIsEnabled()
    }

    @Test
    fun aServerThatIsNotConnected_offersConnectInsteadOfInstall() {
        setDialog(
            hosts = listOf(bastion, legacy),
            keys = listOf(key),
            bridges = listOf(connection(bastion, reply(KeyInstallScript.ADDED_MARKER))),
            shown = key,
        )
        waitForTag("key_install_connect_legacy")

        composeTestRule.onAllNodesWithTag("key_install_run_legacy").assertCountEquals(0)
        composeTestRule.onNodeWithTag("key_install_connect_legacy").performClick()

        assertEquals(legacy, calls.connected)
    }

    @Test
    fun connectOnAnUnconnectedServer_remembersWhichKeyToInstallAfterTheLogin() {
        setDialog(
            hosts = listOf(bastion, legacy),
            keys = listOf(key),
            bridges = listOf(connection(bastion, reply(KeyInstallScript.ADDED_MARKER))),
            shown = key,
        )
        waitForTag("key_install_connect_legacy")
        assertNull(pendingInstall.request.value)

        composeTestRule.onNodeWithTag("key_install_connect_legacy").performClick()

        val request = pendingInstall.request.value
        assertNotNull(request)
        assertEquals(legacy.id, request!!.hostId)
        assertEquals(key.id, request.keyId)
        assertEquals(legacy, calls.connected)
    }

    @Test
    fun afterTheLogin_theConsoleAsksToInstallTheRememberedKeyAndReportsTheResult() {
        val line = key.toAuthorizedKeyLine()
        assertNotNull(line)
        pendingInstall.set(bastion.id, key.id)
        setDialog(
            hosts = listOf(bastion),
            keys = listOf(key),
            bridges = listOf(connection(bastion, reply(KeyInstallScript.ADDED_MARKER))),
            shown = key,
            afterLogin = true,
        )

        waitForTag("key_install_confirm")
        composeTestRule.onNodeWithText(
            string(R.string.shelldon_key_install_confirm_body, "ops-key", line!!.fingerprint, "bastion"),
        ).assertIsDisplayed()
        assertTrue("nothing runs before the confirmation", commands.isEmpty())

        composeTestRule.onNodeWithTag("key_install_confirm").performClick()
        waitForTag("key_after_login_result")

        composeTestRule.onNodeWithText(string(R.string.shelldon_key_install_added)).assertIsDisplayed()
        assertEquals(1, commands.size)
        assertTrue(commands.single().contains(line.keyText))

        composeTestRule.onNodeWithTag("key_after_login_done").performClick()
        composeTestRule.waitForIdle()
        assertNull(pendingInstall.request.value)
        composeTestRule.onAllNodesWithTag("key_after_login_result").assertCountEquals(0)
    }

    @Test
    fun afterTheLogin_cancellingEndsTheRequestWithoutInstalling() {
        pendingInstall.set(bastion.id, key.id)
        setDialog(
            hosts = listOf(bastion),
            keys = listOf(key),
            bridges = listOf(connection(bastion, reply(KeyInstallScript.ADDED_MARKER))),
            shown = key,
            afterLogin = true,
        )
        waitForTag("key_install_confirm")

        composeTestRule.onNodeWithText(string(R.string.shelldon_cancel)).performClick()
        composeTestRule.waitForIdle()

        assertTrue(commands.isEmpty())
        assertNull(pendingInstall.request.value)
    }

    @Test
    fun beforeTheLoginIsThrough_theConsoleAsksNothing() {
        pendingInstall.set(bastion.id, key.id)
        // No open connection to the server yet: the console is still at its password prompt.
        setDialog(hosts = listOf(bastion), keys = listOf(key), bridges = emptyList(), shown = key, afterLogin = true)

        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag("key_install_confirm").assertCountEquals(0)
        assertNotNull("the request waits", pendingInstall.request.value)
    }

    @Test
    fun aRequestForAnotherServerOrAnExpiredOne_isIgnored() {
        setDialog(
            hosts = listOf(bastion, legacy),
            keys = listOf(key),
            bridges = listOf(connection(bastion, reply(KeyInstallScript.ADDED_MARKER))),
            shown = key,
            afterLogin = true,
        )
        pendingInstall.set(legacy.id, key.id)
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag("key_install_confirm").assertCountEquals(0)

        pendingInstall.set(bastion.id, key.id, nowMillis = System.currentTimeMillis() - 2 * PendingKeyInstall.MAX_AGE_MILLIS)
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag("key_install_confirm").assertCountEquals(0)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun aRememberedKeyThatWasDeletedEndsTheRequestQuietly() {
        pendingInstall.set(bastion.id, UUID(7, 7))
        setDialog(
            hosts = listOf(bastion),
            keys = listOf(key),
            bridges = listOf(connection(bastion, reply(KeyInstallScript.ADDED_MARKER))),
            shown = key,
            afterLogin = true,
        )

        composeTestRule.waitUntil(timeoutMillis = 10_000) { pendingInstall.request.value == null }

        composeTestRule.onAllNodesWithTag("key_install_confirm").assertCountEquals(0)
    }

    @Test
    fun aKeyThatIsAlreadyThere_isReportedAsSuch() {
        setDialog(
            hosts = listOf(bastion),
            keys = listOf(key),
            bridges = listOf(connection(bastion, reply(KeyInstallScript.PRESENT_MARKER))),
            shown = key,
        )
        waitForTag("key_install_run_bastion")

        composeTestRule.onNodeWithTag("key_install_run_bastion").performClick()
        composeTestRule.onNodeWithTag("key_install_confirm").performClick()
        waitForTag("key_install_result_bastion")

        composeTestRule.onNodeWithText(string(R.string.shelldon_key_install_present)).assertIsDisplayed()
    }

    @Test
    fun aServerThatDoesNotConfirm_isReportedAsAFailureAndCanBeTriedAgain() {
        setDialog(
            hosts = listOf(bastion),
            keys = listOf(key),
            bridges = listOf(connection(bastion, reply(stdout = "", stderr = "Permission denied", exitCode = 1))),
            shown = key,
        )
        waitForTag("key_install_run_bastion")

        composeTestRule.onNodeWithTag("key_install_run_bastion").performClick()
        composeTestRule.onNodeWithTag("key_install_confirm").performClick()
        waitForTag("key_install_result_bastion")

        composeTestRule.onNodeWithText(
            string(R.string.shelldon_key_install_not_confirmed, "Permission denied"),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithTag("key_install_run_bastion").assertIsEnabled()
    }

    @Test
    fun aKeyWithoutAPublicPart_saysSoAndListsNoServers() {
        val bad = unusableKey("old-import")
        setDialog(
            hosts = listOf(bastion),
            keys = listOf(bad),
            bridges = listOf(connection(bastion, reply(KeyInstallScript.ADDED_MARKER))),
            shown = bad,
        )

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("key_install_unusable").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("key_install_run_bastion").assertCountEquals(0)
    }

    @Test
    fun withoutAnySshServer_theDialogSaysSo() {
        setDialog(hosts = emptyList(), keys = listOf(key), bridges = emptyList(), shown = key)

        waitForTag("key_install_no_hosts")
        composeTestRule.onNodeWithText(string(R.string.shelldon_key_install_no_hosts)).assertIsDisplayed()
    }

    @Test
    fun doneClosesTheDialog() {
        setDialog(hosts = emptyList(), keys = listOf(key), bridges = emptyList(), shown = key)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("key_install_close").performClick()

        assertEquals(1, calls.dismissed)
    }

    @Test
    fun theConsolePicker_installsTheChosenKeyOnThisServer() {
        val other = ecKey("laptop")
        val line = other.toAuthorizedKeyLine()
        assertNotNull(line)
        setDialog(
            hosts = listOf(bastion),
            keys = listOf(key, other),
            bridges = listOf(connection(bastion, reply(KeyInstallScript.ADDED_MARKER))),
            shown = key,
            picker = true,
        )
        waitForTag("key_pick_run_laptop")

        composeTestRule.onNodeWithTag("key_pick_run_laptop").performClick()
        composeTestRule.onNodeWithText(
            string(R.string.shelldon_key_install_confirm_body, "laptop", line!!.fingerprint, "bastion"),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithTag("key_install_confirm").performClick()
        waitForTag("key_pick_result_laptop")

        composeTestRule.onNodeWithText(string(R.string.shelldon_key_install_added)).assertIsDisplayed()
        assertEquals(1, commands.size)
        assertTrue(commands.single().contains(line.keyText))
        composeTestRule.onAllNodesWithTag("key_pick_result_ops-key").assertCountEquals(0)
    }

    @Test
    fun theConsolePicker_cannotInstallAKeyWithoutAPublicPart() {
        setDialog(
            hosts = listOf(bastion),
            keys = listOf(unusableKey("old-import"), key),
            bridges = listOf(connection(bastion, reply(KeyInstallScript.ADDED_MARKER))),
            shown = key,
            picker = true,
        )
        waitForTag("key_pick_run_ops-key")

        composeTestRule.onNodeWithTag("key_pick_run_old-import").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("key_pick_run_ops-key").assertIsEnabled()
    }

    @Test
    fun theConsolePicker_saysSoWhenThereAreNoKeys() {
        setDialog(
            hosts = listOf(bastion),
            keys = emptyList(),
            bridges = listOf(connection(bastion, reply(KeyInstallScript.ADDED_MARKER))),
            shown = key,
            picker = true,
        )

        waitForTag("key_pick_empty")
        composeTestRule.onNodeWithText(string(R.string.shelldon_key_pick_empty)).assertIsDisplayed()
    }
}
