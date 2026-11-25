/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.vault

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException

/**
 * The Keystore itself cannot be reached from a unit test. What can be pinned down here is the
 * policy that went wrong before: which errors may throw the biometric setup away.
 */
@RunWith(AndroidJUnit4::class)
class VaultBiometricTest {
    @Test
    fun aKeyTheSystemInvalidatedSwitchesBiometricUnlockOff() {
        assertThat(startFailure(KeyPermanentlyInvalidatedException())).isEqualTo(BiometricStart.Invalidated)
    }

    @Test
    fun aDeviceThatCountsAsLockedKeepsTheSetup() {
        assertThat(startFailure(UserNotAuthenticatedException())).isEqualTo(BiometricStart.Unavailable)
    }

    @Test
    fun aBusyKeystoreKeepsTheSetup() {
        assertThat(startFailure(KeyStoreException("Keystore operation failed"))).isEqualTo(BiometricStart.Unavailable)
        assertThat(startFailure(UnrecoverableKeyException("could not get the key"))).isEqualTo(BiometricStart.Unavailable)
    }

    @Test
    fun otherKeyAndIoErrorsKeepTheSetup() {
        assertThat(startFailure(InvalidKeyException("not usable now"))).isEqualTo(BiometricStart.Unavailable)
        assertThat(startFailure(IOException("could not read the wrap"))).isEqualTo(BiometricStart.Unavailable)
    }

    @Test
    fun runtimeErrorsFromTheKeystoreProviderKeepTheSetup() {
        assertThat(startFailure(ProviderException("vendor glitch"))).isEqualTo(BiometricStart.Unavailable)
        assertThat(startFailure(IllegalStateException("oops"))).isEqualTo(BiometricStart.Unavailable)
    }
}
