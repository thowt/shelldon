/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.tunnels

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.connectbot.data.EntityIds
import org.connectbot.data.HostRepository
import org.connectbot.data.entity.PortForward
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.ui.screens.portforwardlist.PortForwardEditorViewModel
import org.connectbot.ui.screens.portforwardlist.SourceAddressOption
import org.connectbot.util.HostConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

/**
 * Where a local (`-L`) or dynamic (`-D`) forward listens: this phone's loopback unless the person
 * picks something else and confirms it for that forward.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortForwardLanBindTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val dispatchers = CoroutineDispatchers(dispatcher, dispatcher, dispatcher)
    private lateinit var repository: HostRepository
    private val hostId = UUID(0, 5)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mock(HostRepository::class.java)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newEditor(type: String = HostConstants.PORTFORWARD_LOCAL): PortForwardEditorViewModel {
        val viewModel = PortForwardEditorViewModel(
            SavedStateHandle(mapOf("hostId" to hostId.toString(), "forwardId" to EntityIds.NONE.toString())),
            repository,
            dispatchers,
        )
        viewModel.updateNickname("web")
        viewModel.updateType(type)
        viewModel.updateSourcePort("8080")
        viewModel.updateDestination("db.internal:5432")
        return viewModel
    }

    private fun saveAndCapture(viewModel: PortForwardEditorViewModel): PortForward = runBlocking {
        val placeholder = PortForward(hostId = hostId, nickname = "", type = HostConstants.PORTFORWARD_LOCAL, sourcePort = 1, destAddr = "x", destPort = 1)
        `when`(repository.savePortForward(any(PortForward::class.java) ?: placeholder)).thenAnswer { it.getArgument<PortForward>(0) }
        viewModel.save {}
        val captured = ArgumentCaptor.forClass(PortForward::class.java)
        verify(repository).savePortForward(captured.capture() ?: placeholder)
        captured.value
    }

    @Test
    fun aNewLocalForwardListensOnLoopbackAndNeedsNoConfirmation() {
        val viewModel = newEditor()
        val state = viewModel.uiState.value
        assertFalse(state.exposesLan)
        assertTrue(state.canSave)
        assertEquals("localhost", saveAndCapture(viewModel).sourceAddr)
    }

    @Test
    fun theLoopbackChoicesAreNeverTreatedAsExposed() {
        val viewModel = newEditor()
        for (option in listOf(SourceAddressOption.LOCALHOST, SourceAddressOption.LOCALHOST_IPV4, SourceAddressOption.LOCALHOST_IPV6)) {
            viewModel.updateSourceAddressOption(option)
            assertFalse(option.name, viewModel.uiState.value.exposesLan)
            assertTrue(option.name, viewModel.uiState.value.canSave)
        }
    }

    @Test
    fun everyNetworkChoiceIsExposedAndCannotBeSavedUntilConfirmed() {
        for (option in listOf(SourceAddressOption.ALL, SourceAddressOption.ALL_IPV4, SourceAddressOption.ALL_IPV6)) {
            for (type in listOf(HostConstants.PORTFORWARD_LOCAL, HostConstants.PORTFORWARD_DYNAMIC5)) {
                val viewModel = newEditor(type)
                viewModel.updateSourceAddressOption(option)
                assertTrue("$option $type", viewModel.uiState.value.exposesLan)
                assertFalse("$option $type", viewModel.uiState.value.canSave)
                viewModel.updateLanAcknowledged(true)
                assertTrue("$option $type", viewModel.uiState.value.canSave)
            }
        }
    }

    @Test
    fun aConfirmedLocalForwardIsSavedWithTheAddressAsOpenSshWritesIt() {
        val all = newEditor()
        all.updateSourceAddressOption(SourceAddressOption.ALL)
        all.updateLanAcknowledged(true)
        assertEquals("", saveAndCapture(all).sourceAddr)
    }

    @Test
    fun aConfirmedDynamicForwardOnOneInterfaceIsSavedWithThatAddress() {
        val viewModel = newEditor(HostConstants.PORTFORWARD_DYNAMIC5)
        viewModel.updateSourceAddressOption(SourceAddressOption.SPECIFIC)
        viewModel.updateSpecificAddress(" 192.168.1.20 ")
        assertFalse(viewModel.uiState.value.canSave)
        viewModel.updateLanAcknowledged(true)
        assertTrue(viewModel.uiState.value.canSave)
        val saved = saveAndCapture(viewModel)
        assertEquals("192.168.1.20", saved.sourceAddr)
        assertEquals(HostConstants.PORTFORWARD_DYNAMIC5, saved.type)
    }

    @Test
    fun changingTheAddressOrTheTypeAsksAgain() {
        val viewModel = newEditor()
        viewModel.updateSourceAddressOption(SourceAddressOption.ALL)
        viewModel.updateLanAcknowledged(true)
        viewModel.updateSourceAddressOption(SourceAddressOption.ALL_IPV4)
        assertFalse(viewModel.uiState.value.lanAcknowledged)

        viewModel.updateLanAcknowledged(true)
        viewModel.updateType(HostConstants.PORTFORWARD_DYNAMIC5)
        assertFalse(viewModel.uiState.value.lanAcknowledged)

        viewModel.updateLanAcknowledged(true)
        viewModel.updateSourceAddressOption(SourceAddressOption.SPECIFIC)
        viewModel.updateSpecificAddress("10.0.0.5")
        assertFalse(viewModel.uiState.value.lanAcknowledged)
    }

    @Test
    fun anEmptySpecificAddressIsNeverMeantAsEveryInterface() {
        val viewModel = newEditor()
        viewModel.updateSourceAddressOption(SourceAddressOption.SPECIFIC)
        viewModel.updateLanAcknowledged(true)
        assertFalse(viewModel.uiState.value.isListenAddressValid)
        assertFalse(viewModel.uiState.value.canSave)
        viewModel.updateSpecificAddress("   ")
        viewModel.updateLanAcknowledged(true)
        assertFalse(viewModel.uiState.value.canSave)
    }

    @Test
    fun aHostNameIsNotAcceptedAsAListenAddress() {
        val viewModel = newEditor()
        viewModel.updateSourceAddressOption(SourceAddressOption.SPECIFIC)
        viewModel.updateSpecificAddress("my-laptop.local")
        viewModel.updateLanAcknowledged(true)
        assertFalse(viewModel.uiState.value.isListenAddressValid)
        assertFalse(viewModel.uiState.value.canSave)
    }

    @Test
    fun aLoopbackAddressTypedByHandIsNotExposed() {
        val viewModel = newEditor()
        viewModel.updateSourceAddressOption(SourceAddressOption.SPECIFIC)
        viewModel.updateSpecificAddress("127.0.0.2")
        assertFalse(viewModel.uiState.value.exposesLan)
        assertTrue(viewModel.uiState.value.canSave)
    }

    @Test
    fun remoteForwardsKeepTheirOwnRules() {
        val viewModel = newEditor(HostConstants.PORTFORWARD_REMOTE)
        viewModel.updateSourceAddressOption(SourceAddressOption.ALL)
        assertFalse(viewModel.uiState.value.exposesLan)
        assertTrue(viewModel.uiState.value.canSave)
        assertEquals("", saveAndCapture(viewModel).sourceAddr)
    }

    @Test
    fun editingAnExistingExposedForwardKeepsItsConfirmationUntilTheAddressChanges() = runTest {
        val existing = PortForward(
            id = UUID(0, 9),
            hostId = hostId,
            nickname = "lan",
            type = HostConstants.PORTFORWARD_LOCAL,
            sourceAddr = "192.168.1.20",
            sourcePort = 8080,
            destAddr = "db.internal",
            destPort = 5432,
        )
        `when`(repository.getPortForwardById(existing.id)).thenReturn(existing)
        val viewModel = PortForwardEditorViewModel(
            SavedStateHandle(mapOf("hostId" to hostId.toString(), "forwardId" to existing.id.toString())),
            repository,
            dispatchers,
        )
        viewModel.updateNickname("lan2")
        assertTrue(viewModel.uiState.value.exposesLan)
        assertTrue(viewModel.uiState.value.canSave)
        viewModel.updateSpecificAddress("192.168.1.21")
        assertFalse(viewModel.uiState.value.canSave)
    }
}
