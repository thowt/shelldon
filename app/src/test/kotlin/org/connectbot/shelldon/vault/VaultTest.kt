/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.vault

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var file: File

    // The smallest legal cost, to keep the suite quick. Production uses KdfParams.DEFAULT.
    private val fast = KdfParams(memoryKib = 8 * 1024, iterations = 1, parallelism = 1)

    private val password = "correct horse battery staple"
    private val secret = "-----BEGIN OPENSSH PRIVATE KEY-----\nnot a real key\n".toByteArray()

    @Before
    fun setUp() {
        file = File(folder.root, "vault.hdr")
    }

    private fun newVault() = Vault(file)

    private fun createdVault(): Vault = newVault().also { it.create(password, fast) }

    private fun editHeader(edit: (ByteArray) -> Unit) {
        val bytes = file.readBytes()
        edit(bytes)
        file.writeBytes(bytes)
    }

    // --- key derivation -------------------------------------------------------------------------

    @Test
    fun argon2idMatchesAnIndependentImplementation() {
        // Expected values come from argon2-cffi (reference C implementation), Argon2id v1.3.
        val salt = ByteArray(16) { it.toByte() }
        val one = VaultCrypto.deriveKey("correct horse battery staple", salt, KdfParams(8192, 2, 1))
        assertThat(one.toHex()).isEqualTo("a891dd91a3d123add6ea78ceedcc94a7f1dc1e4ca480e57e6f240a113662ea2f")
        val two = VaultCrypto.deriveKey("pässwörd✓", salt, KdfParams(8192, 1, 2))
        assertThat(two.toHex()).isEqualTo("68538e3ca5e198b16fa1367f0926866947bd4d288dce950eac4e450fbc2b48e2")
    }

    @Test
    fun passwordIsNormalisedSoDecomposedInputOpensTheSameVault() {
        val salt = ByteArray(16) { it.toByte() }
        val composed = VaultCrypto.deriveKey("pässwörd✓", salt, fast)
        val decomposed = VaultCrypto.deriveKey("pässwörd✓", salt, fast)
        assertThat(decomposed).isEqualTo(composed)
    }

    @Test
    fun kdfParametersAreRangeChecked() {
        assertThatThrownBy { KdfParams(memoryKib = 1_000_000, iterations = 3, parallelism = 1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { KdfParams(memoryKib = 1024, iterations = 3, parallelism = 1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { KdfParams(memoryKib = 65536, iterations = 0, parallelism = 1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { KdfParams(memoryKib = 65536, iterations = 3, parallelism = 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(KdfParams.DEFAULT.memoryKib).isEqualTo(65536)
    }

    // --- lifecycle ------------------------------------------------------------------------------

    @Test
    fun newVaultStartsWithNoVaultAndRejectsEverything() {
        val vault = newVault()
        assertThat(vault.state.value).isEqualTo(VaultState.NO_VAULT)
        assertThat(vault.unlock(password)).isEqualTo(UnlockResult.NO_VAULT)
        assertThatThrownBy { vault.seal(secret, "k") }.isInstanceOf(VaultLockedException::class.java)
    }

    @Test
    fun createLeavesTheVaultUnlockedAndWritesOneHeaderFile() {
        val vault = createdVault()
        assertThat(vault.state.value).isEqualTo(VaultState.UNLOCKED)
        assertThat(file.length()).isEqualTo(VaultHeader.SIZE.toLong())
        assertThat(VaultHeader.SIZE).isEqualTo(109)
        assertThat(folder.root.list()!!.toList()).containsExactly("vault.hdr")
    }

    @Test
    fun lockedVaultCannotSealOrOpen() {
        val vault = createdVault()
        val blob = vault.seal(secret, "pubkey/1")
        vault.lock()
        assertThat(vault.state.value).isEqualTo(VaultState.LOCKED)
        assertThatThrownBy { vault.open(blob, "pubkey/1") }.isInstanceOf(VaultLockedException::class.java)
        assertThatThrownBy { vault.seal(secret, "pubkey/1") }.isInstanceOf(VaultLockedException::class.java)
    }

    @Test
    fun wrongPasswordDoesNotUnlockAndRightOneDoes() {
        val vault = createdVault()
        val blob = vault.seal(secret, "pubkey/1")
        vault.lock()
        assertThat(vault.unlock("not the password")).isEqualTo(UnlockResult.WRONG_PASSWORD)
        assertThat(vault.state.value).isEqualTo(VaultState.LOCKED)
        assertThat(vault.unlock(password)).isEqualTo(UnlockResult.OK)
        assertThat(vault.open(blob, "pubkey/1")).isEqualTo(secret)
    }

    @Test
    fun aRestartedAppUnlocksTheSameVault() {
        val blob = createdVault().seal(secret, "pubkey/1")
        val restarted = newVault()
        assertThat(restarted.state.value).isEqualTo(VaultState.LOCKED)
        assertThat(restarted.unlock(password)).isEqualTo(UnlockResult.OK)
        assertThat(restarted.open(blob, "pubkey/1")).isEqualTo(secret)
    }

    @Test
    fun lockZeroesTheKeyInMemory() {
        val vault = createdVault()
        val leaked = vault.useKey { it }
        assertThat(leaked.any { it != 0.toByte() }).isTrue()
        vault.lock()
        assertThat(leaked.all { it == 0.toByte() }).isTrue()
    }

    @Test
    fun createRefusesToOverwriteAndEnforcesAMinimumPasswordLength() {
        val vault = createdVault()
        assertThatThrownBy { vault.create("another password", fast) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { Vault(File(folder.root, "other.hdr")).create("short", fast) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // --- sealing --------------------------------------------------------------------------------

    @Test
    fun sealedBlobsAreRandomisedAndMarked() {
        val vault = createdVault()
        val a = vault.seal(secret, "pubkey/1")
        val b = vault.seal(secret, "pubkey/1")
        assertThat(a).isNotEqualTo(b)
        assertThat(Vault.isSealed(a)).isTrue()
        assertThat(String(a, Charsets.ISO_8859_1)).doesNotContain("OPENSSH")
    }

    @Test
    fun emptyPlaintextRoundTrips() {
        val vault = createdVault()
        assertThat(vault.open(vault.seal(ByteArray(0), "x"), "x")).isEmpty()
    }

    @Test
    fun aBlobOnlyOpensForItsOwnContext() {
        val vault = createdVault()
        val blob = vault.seal(secret, "pubkey/1")
        assertThatThrownBy { vault.open(blob, "pubkey/2") }.isInstanceOf(VaultDataException::class.java)
        assertThatThrownBy { vault.open(blob, "password/1") }.isInstanceOf(VaultDataException::class.java)
    }

    @Test
    fun anyChangedByteInASealedBlobIsDetected() {
        val vault = createdVault()
        val blob = vault.seal(secret, "pubkey/1")
        for (index in listOf(4, 15, 16, blob.size / 2, blob.size - 1)) {
            val damaged = blob.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertThatThrownBy { vault.open(damaged, "pubkey/1") }.isInstanceOf(VaultDataException::class.java)
        }
        assertThatThrownBy { vault.open(blob.copyOf(blob.size - 1), "pubkey/1") }.isInstanceOf(VaultDataException::class.java)
    }

    @Test
    fun legacyPlaintextIsNeverTakenForSealedData() {
        assertThat(Vault.isSealed(null)).isFalse()
        assertThat(Vault.isSealed(ByteArray(0))).isFalse()
        // DER starts with 0x30, OpenSSH/PEM with '-'.
        assertThat(Vault.isSealed(ByteArray(80) { if (it == 0) 0x30 else 1 })).isFalse()
        assertThat(Vault.isSealed("-----BEGIN".toByteArray())).isFalse()
        assertThatThrownBy { createdVault().open("-----BEGIN OPENSSH PRIVATE KEY----- plus some more padding".toByteArray(), "k") }
            .isInstanceOf(VaultDataException::class.java)
    }

    @Test
    fun textFormRoundTripsAndIsRecognisable() {
        val vault = createdVault()
        val sealed = vault.sealText("hunter2 ä✓", "password/abc")
        assertThat(sealed).startsWith("sh1:")
        assertThat(Vault.isSealedText(sealed)).isTrue()
        assertThat(Vault.isSealedText("hunter2")).isFalse()
        assertThat(Vault.isSealedText(null)).isFalse()
        assertThat(vault.openText(sealed, "password/abc")).isEqualTo("hunter2 ä✓")
        assertThatThrownBy { vault.openText(sealed, "password/other") }.isInstanceOf(VaultDataException::class.java)
        assertThatThrownBy { vault.openText("sh1:!!!not base64!!!", "password/abc") }.isInstanceOf(VaultDataException::class.java)
    }

    // --- password change ------------------------------------------------------------------------

    @Test
    fun changingThePasswordKeepsSealedDataReadable() {
        val vault = createdVault()
        val blob = vault.seal(secret, "pubkey/1")
        val idBefore = vault.vaultId!!

        assertThat(vault.changePassword(password, "a brand new passphrase", fast)).isTrue()
        assertThat(vault.vaultId).isEqualTo(idBefore)
        vault.lock()
        assertThat(vault.unlock(password)).isEqualTo(UnlockResult.WRONG_PASSWORD)
        assertThat(vault.unlock("a brand new passphrase")).isEqualTo(UnlockResult.OK)
        assertThat(vault.open(blob, "pubkey/1")).isEqualTo(secret)
    }

    @Test
    fun changingThePasswordNeedsTheOldOne() {
        val vault = createdVault()
        assertThat(vault.changePassword("wrong old password", "a brand new passphrase", fast)).isFalse()
        vault.lock()
        assertThat(vault.unlock(password)).isEqualTo(UnlockResult.OK)
    }

    @Test
    fun changingThePasswordUsesAFreshSaltAndNonce() {
        val vault = createdVault()
        val before = file.readBytes()
        vault.changePassword(password, password, fast)
        val after = file.readBytes()
        assertThat(after).isNotEqualTo(before)
        assertThat(after.copyOfRange(33, 61)).isNotEqualTo(before.copyOfRange(33, 61))
    }

    // --- damaged or hostile header --------------------------------------------------------------

    @Test
    fun editingAnyAuthenticatedHeaderFieldBreaksUnlock() {
        createdVault()
        // iterations, vault id, salt, nonce, wrapped key
        for (index in listOf(12, 17, 33, 49, 61, 108)) {
            val original = file.readBytes()
            editHeader { it[index] = (it[index].toInt() xor 2).toByte() }
            assertThat(newVault().unlock(password)).describedAs("byte $index").isEqualTo(UnlockResult.WRONG_PASSWORD)
            file.writeBytes(original)
        }
        assertThat(newVault().unlock(password)).isEqualTo(UnlockResult.OK)
    }

    @Test
    fun malformedHeadersAreReportedAsDamagedWithoutRunningTheKdf() {
        createdVault()
        val original = file.readBytes()

        editHeader { it[0] = 'X'.code.toByte() }
        assertThat(newVault().unlock(password)).isEqualTo(UnlockResult.DAMAGED)
        file.writeBytes(original)

        editHeader { it[4] = 9 }
        assertThat(newVault().unlock(password)).isEqualTo(UnlockResult.DAMAGED)
        file.writeBytes(original)

        // memory = 0x7fffffff KiB: must be refused, not allocated.
        editHeader {
            it[5] = 0x7f
            it[6] = 0xff.toByte()
            it[7] = 0xff.toByte()
            it[8] = 0xff.toByte()
        }
        assertThat(newVault().unlock(password)).isEqualTo(UnlockResult.DAMAGED)
        file.writeBytes(original)

        file.writeBytes(original.copyOf(50))
        assertThat(newVault().unlock(password)).isEqualTo(UnlockResult.DAMAGED)
        file.writeBytes(original + byteArrayOf(0))
        assertThat(newVault().unlock(password)).isEqualTo(UnlockResult.DAMAGED)
    }

    // --- reset and key hand-over ----------------------------------------------------------------

    @Test
    fun resetRemovesTheVaultAndOldBlobsStayUnreadable() {
        val vault = createdVault()
        val blob = vault.seal(secret, "pubkey/1")
        vault.reset()
        assertThat(file.exists()).isFalse()
        assertThat(vault.state.value).isEqualTo(VaultState.NO_VAULT)

        vault.create(password, fast)
        assertThatThrownBy { vault.open(blob, "pubkey/1") }.isInstanceOf(VaultDataException::class.java)
    }

    @Test
    fun unlockWithKeyOpensTheSameData() {
        val vault = createdVault()
        val blob = vault.seal(secret, "pubkey/1")
        val key = vault.useKey { it.copyOf() }
        vault.lock()

        assertThat(vault.unlockWithKey(key.copyOf(16))).isFalse()
        assertThat(vault.state.value).isEqualTo(VaultState.LOCKED)
        assertThat(vault.unlockWithKey(key)).isTrue()
        assertThat(vault.state.value).isEqualTo(VaultState.UNLOCKED)
        assertThat(vault.open(blob, "pubkey/1")).isEqualTo(secret)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
