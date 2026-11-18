/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.keychain

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.R
import org.connectbot.data.entity.KnownHost
import org.connectbot.data.entity.Pubkey
import org.connectbot.shelldon.data.groups.Identity
import org.connectbot.shelldon.vault.VaultState
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** The Keychain screen with plain data: what each tab shows and which callback each control calls. */
@RunWith(AndroidJUnit4::class)
class ShelldonKeychainContentTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Calls {
        var newIdentity = 0
        var addKey = 0
        var manageKeys = 0
        var lockVault = 0
        var openedKey: UUID? = null
        var editedIdentity: Identity? = null
        var deletedIdentity: IdentityRow? = null
        var forgottenHost: KnownHostRow? = null
        var installedKey: Pubkey? = null
    }

    private val calls = Calls()

    private fun string(id: Int) = context.getString(id)

    private fun identityRow(name: String, keyId: UUID? = null, keyMissing: Boolean = false) = IdentityRow(
        identity = Identity(id = UUID(0, name.hashCode().toLong()), name = name, username = "root", keyId = keyId),
        keyName = null,
        keyMissing = keyMissing,
        keyStorage = null,
        hostCount = 2,
        groupCount = 0,
    )

    private fun key(nickname: String) = Pubkey(
        id = UUID(1, nickname.hashCode().toLong()),
        nickname = nickname,
        type = "Ed25519",
        privateKey = null,
        publicKey = ByteArray(32),
        encrypted = false,
        startup = false,
        confirmation = false,
        createdDate = 0,
    )

    private fun knownHostRow(
        hostname: String = "files.example.com",
        fingerprint: String = "SHA256:abcdef",
        n: Long = 1,
    ) = KnownHostRow(
        knownHost = KnownHost(
            id = UUID(2, n),
            hostId = null,
            hostname = hostname,
            port = 22,
            hostKeyAlgo = "ssh-ed25519",
            hostKey = ByteArray(32),
        ),
        fingerprint = fingerprint,
        endpoint = "$hostname:22",
    )

    private fun setContent(
        state: KeychainUiState,
        knownHosts: List<KnownHostRow>? = emptyList(),
        startTab: Int = 0,
    ) {
        val tab = mutableIntStateOf(startTab)
        composeTestRule.setContent {
            ConnectBotTheme {
                ShelldonKeychainContent(
                    state = state,
                    knownHosts = knownHosts,
                    tab = tab.intValue,
                    onTabChange = { tab.intValue = it },
                    onNewIdentity = { calls.newIdentity++ },
                    onEditIdentity = { calls.editedIdentity = it },
                    onDeleteIdentity = { calls.deletedIdentity = it },
                    onAddKey = { calls.addKey++ },
                    onManageKeys = { calls.manageKeys++ },
                    onNavigateToEditKey = { calls.openedKey = it },
                    onInstallKey = { calls.installedKey = it },
                    onForgetKnownHost = { calls.forgottenHost = it },
                    onLockVault = { calls.lockVault++ },
                )
            }
        }
    }

    @Test
    fun whileLoading_noTabsAreShown() {
        setContent(KeychainUiState(isLoading = true))

        composeTestRule.onNodeWithTag("keychain_tab_0").assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.shelldon_identities_empty_title)).assertDoesNotExist()
    }

    @Test
    fun noIdentities_saysSoAndTheNewButtonCreatesOne() {
        setContent(KeychainUiState(isLoading = false))

        composeTestRule.onNodeWithText(string(R.string.shelldon_identities_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("identity_new").performClick()

        assertEquals(1, calls.newIdentity)
    }

    @Test
    fun identity_isListedAndTappingItOpensTheEditor() {
        val row = identityRow("deploy")
        setContent(KeychainUiState(identities = listOf(row), isLoading = false))

        composeTestRule.onNodeWithText("deploy").assertIsDisplayed()
        composeTestRule.onNodeWithTag("identity_${row.identity.id}").performClick()

        assertEquals(row.identity, calls.editedIdentity)
    }

    @Test
    fun identity_canBeDeletedFromItsMenu() {
        val row = identityRow("deploy")
        setContent(KeychainUiState(identities = listOf(row), isLoading = false))

        composeTestRule.onNodeWithContentDescription(string(R.string.button_more_options)).performClick()
        composeTestRule.onNodeWithText(string(R.string.shelldon_delete)).performClick()

        assertEquals(row, calls.deletedIdentity)
    }

    @Test
    fun tabs_switchBetweenIdentitiesKeysAndKnownHosts() {
        setContent(KeychainUiState(isLoading = false))
        composeTestRule.onNodeWithText(string(R.string.shelldon_identities_empty_title)).assertIsDisplayed()

        composeTestRule.onNodeWithTag("keychain_tab_1").performClick()
        composeTestRule.onNodeWithText(string(R.string.shelldon_keys_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.shelldon_identities_empty_title)).assertDoesNotExist()

        composeTestRule.onNodeWithTag("keychain_tab_2").performClick()
        composeTestRule.onNodeWithText(string(R.string.shelldon_known_hosts_empty_title)).assertIsDisplayed()

        composeTestRule.onNodeWithTag("keychain_tab_0").performClick()
        composeTestRule.onNodeWithText(string(R.string.shelldon_identities_empty_title)).assertIsDisplayed()
    }

    @Test
    fun keys_newButtonAddsAKeyFromTheSheetNotTheOldList() {
        setContent(KeychainUiState(isLoading = false), startTab = 1)

        composeTestRule.onNodeWithTag("key_new").performClick()

        assertEquals(1, calls.addKey)
        assertEquals(0, calls.manageKeys)
    }

    @Test
    fun keys_areListedOpenOnTapAndHaveAManageLink() {
        val deploy = key("deploy-key")
        setContent(KeychainUiState(keys = listOf(deploy), isLoading = false), startTab = 1)

        composeTestRule.onNodeWithText("deploy-key").assertIsDisplayed()
        composeTestRule.onNodeWithTag("key_${deploy.id}").performClick()
        assertEquals(deploy.id, calls.openedKey)

        composeTestRule.onNodeWithTag("keys_manage").performClick()
        assertEquals(1, calls.manageKeys)
    }

    @Test
    fun keys_canBeInstalledOnAServerFromTheirMenu() {
        val deploy = key("deploy-key")
        setContent(KeychainUiState(keys = listOf(deploy), isLoading = false), startTab = 1)

        composeTestRule.onNodeWithContentDescription(string(R.string.button_more_options)).performClick()
        composeTestRule.onNodeWithTag("key_install_${deploy.id}").performClick()

        assertEquals(deploy, calls.installedKey)
        assertNull(calls.openedKey)
    }

    @Test
    fun identity_withAKey_canInstallThatKeyFromItsMenu() {
        val deploy = key("deploy-key")
        val row = identityRow("deploy", keyId = deploy.id)
        setContent(KeychainUiState(identities = listOf(row), keys = listOf(deploy), isLoading = false))

        composeTestRule.onNodeWithContentDescription(string(R.string.button_more_options)).performClick()
        composeTestRule.onNodeWithTag("identity_install_${row.identity.id}").performClick()

        assertEquals(deploy, calls.installedKey)
    }

    @Test
    fun identity_withoutAKey_hasNothingToInstall() {
        val row = identityRow("password-only")
        setContent(KeychainUiState(identities = listOf(row), isLoading = false))

        composeTestRule.onNodeWithContentDescription(string(R.string.button_more_options)).performClick()

        composeTestRule.onNodeWithText(string(R.string.shelldon_delete)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("identity_install_${row.identity.id}").assertDoesNotExist()
    }

    @Test
    fun identity_whoseKeyIsGone_hasNothingToInstall() {
        val gone = UUID(9, 9)
        val row = identityRow("orphan", keyId = gone, keyMissing = true)
        setContent(KeychainUiState(identities = listOf(row), keys = listOf(key("other")), isLoading = false))

        composeTestRule.onNodeWithContentDescription(string(R.string.button_more_options)).performClick()

        composeTestRule.onNodeWithText(string(R.string.shelldon_delete)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("identity_install_${row.identity.id}").assertDoesNotExist()
    }

    @Test
    fun keys_manageLinkIsOnlyThereOnceThereIsAKey() {
        setContent(KeychainUiState(isLoading = false), startTab = 1)

        composeTestRule.onNodeWithTag("keys_manage").assertDoesNotExist()
    }

    @Test
    fun knownHosts_whileLoadingShowNoEmptyMessage() {
        setContent(KeychainUiState(isLoading = false), knownHosts = null, startTab = 2)

        composeTestRule.onNodeWithText(string(R.string.shelldon_known_hosts_empty_title)).assertDoesNotExist()
    }

    @Test
    fun knownHosts_areListedAndCanBeForgotten() {
        val row = knownHostRow()
        setContent(KeychainUiState(isLoading = false), knownHosts = listOf(row), startTab = 2)

        composeTestRule.onNodeWithTag("known_host_${row.knownHost.id}").assertIsDisplayed()
        composeTestRule.onNodeWithText("files.example.com:22").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.shelldon_known_hosts_forget)).performClick()

        assertEquals(row, calls.forgottenHost)
    }

    @Test
    fun knownHosts_searchNarrowsTheListAndTheCrossClearsIt() {
        val files = knownHostRow("files.example.com", n = 1)
        val db = knownHostRow("db.internal", n = 2)
        val web = knownHostRow("web.example.com", n = 3)
        setContent(KeychainUiState(isLoading = false), knownHosts = listOf(files, db, web), startTab = 2)
        composeTestRule.onNodeWithTag("known_hosts_search_clear").assertDoesNotExist()

        composeTestRule.onNodeWithTag("known_hosts_search").performTextInput("db.")

        composeTestRule.onNodeWithTag("known_host_${db.knownHost.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("known_host_${files.knownHost.id}").assertDoesNotExist()
        composeTestRule.onNodeWithTag("known_host_${web.knownHost.id}").assertDoesNotExist()

        composeTestRule.onNodeWithTag("known_hosts_search_clear").performClick()

        composeTestRule.onNodeWithTag("known_host_${files.knownHost.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("known_host_${web.knownHost.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("known_hosts_search_clear").assertDoesNotExist()
    }

    @Test
    fun knownHosts_searchFindsTheHostThatPresentedAPastedFingerprint() {
        val files = knownHostRow("files.example.com", fingerprint = "SHA256:AAAA1111", n = 1)
        val db = knownHostRow("db.internal", fingerprint = "SHA256:BBBB2222", n = 2)
        setContent(KeychainUiState(isLoading = false), knownHosts = listOf(files, db), startTab = 2)

        composeTestRule.onNodeWithTag("known_hosts_search").performTextInput("sha256:bbbb2222")

        composeTestRule.onNodeWithTag("known_host_${db.knownHost.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("known_host_${files.knownHost.id}").assertDoesNotExist()
    }

    @Test
    fun knownHosts_searchWithNoMatchSaysSo() {
        setContent(KeychainUiState(isLoading = false), knownHosts = listOf(knownHostRow()), startTab = 2)

        composeTestRule.onNodeWithTag("known_hosts_search").performTextInput("nothing-like-this")

        composeTestRule.onNodeWithTag("known_hosts_no_matches").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.shelldon_known_hosts_no_matches, "nothing-like-this")).assertIsDisplayed()
    }

    @Test
    fun knownHosts_noSearchFieldWhenThereIsNothingToSearch() {
        setContent(KeychainUiState(isLoading = false), knownHosts = emptyList(), startTab = 2)

        composeTestRule.onNodeWithTag("known_hosts_search").assertDoesNotExist()
    }

    @Test
    fun knownHosts_aThousandEntriesCanBeNarrowedToOne() {
        // A real known_hosts file is easily this long.
        val rows = (1L..1056L).map { n -> knownHostRow("host-$n.example.com", fingerprint = "SHA256:fp$n", n = n) }
        setContent(KeychainUiState(isLoading = false), knownHosts = rows, startTab = 2)

        composeTestRule.onNodeWithTag("known_hosts_search").performTextInput("host-777.example")

        composeTestRule.onNodeWithTag("known_host_${rows[776].knownHost.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("known_host_${rows[0].knownHost.id}").assertDoesNotExist()
    }

    @Test
    fun vault_unlockedShowsACardWithALockButton() {
        setContent(KeychainUiState(vault = VaultStatus(VaultState.UNLOCKED, autoLockSeconds = 300, biometricEnabled = false), isLoading = false))

        composeTestRule.onNodeWithTag("vault_card").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.shelldon_keychain_vault_unlocked)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("vault_lock").performClick()

        assertEquals(1, calls.lockVault)
    }

    @Test
    fun vault_lockedShowsTheCardButNoLockButton() {
        setContent(KeychainUiState(vault = VaultStatus(VaultState.LOCKED, autoLockSeconds = 300, biometricEnabled = false), isLoading = false))

        composeTestRule.onNodeWithText(string(R.string.shelldon_keychain_vault_locked)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("vault_lock").assertDoesNotExist()
    }

    @Test
    fun vault_notCreatedYetShowsNoCard() {
        setContent(KeychainUiState(vault = VaultStatus(VaultState.NO_VAULT, autoLockSeconds = 300, biometricEnabled = false), isLoading = false))

        composeTestRule.onNodeWithTag("vault_card").assertDoesNotExist()
        assertNull(calls.openedKey)
    }
}
