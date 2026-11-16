/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ssh

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.entity.HostCryptoConfig
import org.connectbot.sshlib.AlgorithmCategory
import org.junit.Test

/**
 * What a host gets when nobody touched its crypto settings. These are properties of the policy the
 * library resolves for the app's stored preset names, so a library update that weakens a default
 * (or drops post-quantum key exchange) fails here instead of silently shipping. The container
 * tests then check that servers really negotiate them.
 */
class CryptoDefaultsTest {
    private fun postQuantum(name: String) = name.startsWith("mlkem") || name.startsWith("sntrup")

    private fun HostCryptoConfig.names(category: AlgorithmCategory) = resolve().algorithms.getValue(category)

    @Test
    fun aNewHostPrefersPostQuantumKeyExchange() {
        val kex = HostCryptoConfig().names(AlgorithmCategory.KEX)
        assertThat(kex).isNotEmpty()
        assertThat(postQuantum(kex.first())).describedAs("first key exchange offered: %s", kex.first()).isTrue()
        // The classical fallback stays, so servers without ML-KEM or sntrup remain reachable.
        assertThat(kex.any { !postQuantum(it) }).isTrue()
    }

    @Test
    fun theDefaultOffersNothingWeak() {
        val config = HostCryptoConfig()
        val policy = config.resolve()
        assertThat(config.names(AlgorithmCategory.KEX)).noneMatch { it.endsWith("sha1") }
        assertThat(config.names(AlgorithmCategory.CIPHER)).noneMatch { "cbc" in it || "3des" in it || "arcfour" in it || "blowfish" in it }
        assertThat(config.names(AlgorithmCategory.MAC)).noneMatch { "sha1" in it || "md5" in it }
        assertThat(config.names(AlgorithmCategory.HOST_KEY)).noneMatch { it.startsWith("ssh-rsa") || it.startsWith("ssh-dss") }
        assertThat(config.names(AlgorithmCategory.PUBKEY)).noneMatch { it == "ssh-rsa" || it.startsWith("ssh-dss") }
        assertThat(policy.minimumRsaBits).isGreaterThanOrEqualTo(2048)
        assertThat(policy.minimumDhBits).isGreaterThanOrEqualTo(2048)
    }

    @Test
    fun theLegacyPresetIsWeakerButOnlyForTheHostThatAsksForIt() {
        val legacy = HostCryptoConfig(preset = "LEGACY")
        assertThat(legacy.names(AlgorithmCategory.KEX)).anyMatch { it.endsWith("sha1") }
        assertThat(legacy.resolve().minimumRsaBits).isEqualTo(1024)
        // Resolving it changed nothing for the next host.
        assertThat(HostCryptoConfig().names(AlgorithmCategory.KEX)).noneMatch { it.endsWith("sha1") }
        assertThat(HostCryptoConfig().resolve().minimumRsaBits).isGreaterThanOrEqualTo(2048)
    }

    @Test
    fun theFuturePresetOffersOnlyPostQuantumKeyExchange() {
        val kex = HostCryptoConfig(preset = "FUTURE").names(AlgorithmCategory.KEX)
        assertThat(kex).isNotEmpty().allMatch { postQuantum(it) }
    }
}
