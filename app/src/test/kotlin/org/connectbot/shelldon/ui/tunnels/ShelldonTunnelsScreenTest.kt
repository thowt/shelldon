/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.tunnels

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.connectbot.R
import org.connectbot.data.HostRepository
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.PortForward
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.util.HostConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.util.UUID

/** The Tunnels tab, driven through its real view model over a stand-in repository. */
@RunWith(AndroidJUnit4::class)
class ShelldonTunnelsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(id: Int) = context.getString(id)

    private val host = Host(
        id = UUID(0, 1),
        nickname = "bastion",
        protocol = "ssh",
        username = "ops",
        hostname = "bastion.example.com",
        port = 22,
        color = "#2196F3",
    )

    private val forward = PortForward(
        id = UUID(0, 2),
        hostId = host.id,
        nickname = "postgres",
        type = HostConstants.PORTFORWARD_LOCAL,
        sourcePort = 5432,
        destAddr = "db.internal",
        destPort = 5432,
    )

    private class Calls {
        var connected: Host? = null
        var managedHost: UUID? = null
        var editedHost: UUID? = null
        var editedForward: UUID? = null
        var editCalls = 0
        var manageCalls = 0
    }

    private val calls = Calls()

    private fun setContent(hosts: List<Host>, forwards: List<PortForward>) {
        val repository = mock<HostRepository> {
            on { observeHosts() } doReturn flowOf(hosts)
            on { observeAllPortForwards() } doReturn flowOf(forwards)
        }
        val dispatchers = CoroutineDispatchers(Dispatchers.Default, Dispatchers.IO, Dispatchers.Main)
        val viewModel = TunnelsViewModel(repository, dispatchers)
        composeTestRule.setContent {
            ConnectBotTheme {
                ShelldonTunnelsScreen(
                    onConnect = { calls.connected = it },
                    onEditForward = { hostId, forwardId ->
                        calls.editCalls++
                        calls.editedHost = hostId
                        calls.editedForward = forwardId
                    },
                    onManageHost = {
                        calls.manageCalls++
                        calls.managedHost = it
                    },
                    viewModel = viewModel,
                )
            }
        }
    }

    @Test
    fun noHosts_saysSoAndHasNoNewButton() {
        setContent(emptyList(), emptyList())

        composeTestRule.onNodeWithTag("tunnels_empty").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.shelldon_tunnels_no_hosts_title)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("tunnel_new").assertDoesNotExist()
    }

    @Test
    fun aHostWithoutTunnels_offersToAddOneAndAsksWhichHost() {
        setContent(listOf(host), emptyList())

        composeTestRule.onNodeWithText(string(R.string.shelldon_tunnels_empty_title)).assertIsDisplayed()

        composeTestRule.onNodeWithTag("tunnel_new").performClick()
        composeTestRule.onNodeWithText(string(R.string.shelldon_tunnel_pick_host)).assertIsDisplayed()
        composeTestRule.onNodeWithText("bastion").performClick()

        assertEquals(1, calls.editCalls)
        assertEquals(host.id, calls.editedHost)
        assertNull(calls.editedForward)
    }

    @Test
    fun aTunnelOfAnUnconnectedHost_isListedButItsSwitchIsOff() {
        setContent(listOf(host), listOf(forward))

        composeTestRule.onNodeWithTag("tunnel_row_postgres").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.shelldon_tunnels_host_offline)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("tunnel_switch_postgres").assertIsNotEnabled()
    }

    @Test
    fun aTunnelOfAnUnconnectedHost_canBeConnectedManagedAndOpened() {
        setContent(listOf(host), listOf(forward))

        composeTestRule.onNodeWithTag("tunnel_connect_bastion").performClick()
        assertEquals(host.id, calls.connected?.id)

        composeTestRule.onNodeWithTag("tunnel_manage_bastion").performClick()
        assertEquals(host.id, calls.managedHost)

        composeTestRule.onNodeWithTag("tunnel_row_postgres").performClick()
        assertEquals(host.id, calls.editedHost)
        assertEquals(forward.id, calls.editedForward)
    }

    @Test
    fun aTemporaryQuickConnectHost_isNotOfferedForTunnels() {
        val temporary = host.copy(id = UUID(0, 9), nickname = "root@10.0.0.9", isTemporary = true)
        setContent(listOf(temporary), emptyList())

        composeTestRule.onNodeWithText(string(R.string.shelldon_tunnels_no_hosts_title)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("tunnel_new").assertDoesNotExist()
    }
}
