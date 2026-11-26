/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.vault

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.connectbot.data.ConnectBotDatabase
import org.connectbot.data.PubkeyRepository
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.util.SecurePasswordStorage
import timber.log.Timber
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything around the [Vault] that needs Android: the slow key derivation off the main thread,
 * moving secrets stored by earlier versions under the vault, auto-lock, biometric unlock and reset.
 */
@Singleton
class VaultController @Inject constructor(
    @ApplicationContext context: Context,
    val vault: Vault,
    val biometric: VaultBiometric,
    private val passwords: SecurePasswordStorage,
    private val pubkeys: PubkeyRepository,
    private val database: ConnectBotDatabase,
    private val dispatchers: CoroutineDispatchers,
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val lockRunnable = Runnable { lock() }
    private var backgroundedAt = 0L

    val state: StateFlow<VaultState> get() = vault.state

    /** Seconds in the background before the vault locks itself; 0 locks as soon as the app is left. */
    var autoLockSeconds: Int
        get() = prefs.getInt(KEY_AUTO_LOCK_SECONDS, DEFAULT_AUTO_LOCK_SECONDS).takeIf { it in AUTO_LOCK_OPTIONS } ?: DEFAULT_AUTO_LOCK_SECONDS
        set(value) {
            require(value in AUTO_LOCK_OPTIONS) { "Unsupported auto-lock delay: $value" }
            prefs.edit().putInt(KEY_AUTO_LOCK_SECONDS, value).apply()
        }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            backgroundedAt = SystemClock.elapsedRealtime()
            handler.removeCallbacks(lockRunnable)
            if (vault.isUnlocked) handler.postDelayed(lockRunnable, autoLockSeconds * 1000L)
        }

        override fun onStart(owner: LifecycleOwner) {
            handler.removeCallbacks(lockRunnable)
            // The timer may not have run if the process was frozen while in the background.
            val awayFor = SystemClock.elapsedRealtime() - backgroundedAt
            if (backgroundedAt != 0L && vault.isUnlocked && awayFor >= autoLockSeconds * 1000L) lock()
            backgroundedAt = 0L
        }
    }

    init {
        handler.post { ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver) }
    }

    /** Creates the vault with [password], seals whatever earlier versions stored in the clear, and leaves it unlocked. */
    suspend fun create(password: String): Boolean {
        val created = withContext(dispatchers.default) {
            try {
                vault.create(password)
                true
            } catch (e: Exception) {
                Timber.e(e, "Could not create the vault")
                false
            }
        }
        if (created) {
            mutableLastReset.value = null
            sealLegacySecrets()
        }
        return created
    }

    suspend fun unlock(password: String): UnlockResult {
        val result = withContext(dispatchers.default) { vault.unlock(password) }
        if (result == UnlockResult.OK) sealLegacySecrets()
        return result
    }

    /** Finishes a biometric unlock with the cipher the system prompt authenticated. */
    suspend fun finishBiometricUnlock(cipher: Cipher): Boolean {
        val unlocked = withContext(dispatchers.default) { biometric.finishUnlock(cipher) }
        if (unlocked) sealLegacySecrets()
        return unlocked
    }

    /** Wipes the data key from memory. Sessions that are already open keep running. */
    fun lock() {
        handler.removeCallbacks(lockRunnable)
        vault.lock()
    }

    suspend fun changePassword(oldPassword: String, newPassword: String): Boolean = withContext(dispatchers.default) {
        vault.changePassword(oldPassword, newPassword)
    }

    /** What [reset] would delete right now. Reads counts only, nothing is opened. */
    suspend fun resetImpact(): ResetImpact = withContext(dispatchers.io) {
        ResetImpact(
            passwords = passwords.count(),
            keys = pubkeys.countSealedKeys(),
            biometric = biometric.isEnabled(),
        )
    }

    /** What the last [reset] removed, until a new vault is created. Lets the setup screen say so. */
    val lastReset: StateFlow<ResetImpact?> get() = mutableLastReset
    private val mutableLastReset = MutableStateFlow<ResetImpact?>(null)

    /**
     * For a forgotten master password: removes the vault, biometric unlock, saved passwords and
     * every private key that was sealed in it, since none of that can be opened again. Hosts,
     * folders, tags and identities stay.
     *
     * @return what was removed
     */
    suspend fun reset(): ResetImpact = withContext(dispatchers.io) {
        val hadBiometric = biometric.isEnabled()
        biometric.disable()
        val removed = ResetImpact(passwords.deleteAll(), pubkeys.deleteSealedKeys(), hadBiometric)
        // Before the vault flips to "no vault", so the setup screen never shows without its note.
        mutableLastReset.value = removed
        vault.reset()
        removed
    }

    private suspend fun sealLegacySecrets() {
        withContext(dispatchers.io) {
            try {
                val converted = passwords.sealLegacyEntries() + pubkeys.sealLegacyKeys()
                if (converted > 0) {
                    Timber.i("Sealed %d secrets stored by an earlier version", converted)
                    // Push the replaced rows out of the write-ahead log.
                    database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                }
            } catch (e: Exception) {
                Timber.e(e, "Could not seal every secret stored by an earlier version")
            }
        }
    }

    companion object {
        private const val PREFS_FILE_NAME = "shelldon_vault"
        private const val KEY_AUTO_LOCK_SECONDS = "auto_lock_seconds"
        const val DEFAULT_AUTO_LOCK_SECONDS = 300

        /** Immediately, 1 minute, 5 minutes, 15 minutes, 1 hour. There is deliberately no "never". */
        val AUTO_LOCK_OPTIONS = listOf(0, 60, 300, 900, 3600)
    }
}
