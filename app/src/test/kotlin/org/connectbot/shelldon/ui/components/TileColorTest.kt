/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.components

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.Random
import java.util.UUID

class TileColorTest {
    @Test
    fun anItemKeepsItsColour() {
        val id = UUID.fromString("5f0e5c3a-7a43-4d0e-9d57-0a1b2c3d4e5f")
        assertThat(tileColorFor(id)).isEqualTo(tileColorFor(UUID.fromString(id.toString())))
    }

    @Test
    fun idsWithANegativeHashStillGetAColour() {
        // UUID.hashCode() is negative for about half of all ids; a plain remainder would index out of range.
        val ids = randomIds(200)
        assertThat(ids.any { it.hashCode() < 0 }).isTrue()
        ids.forEach { tileColorFor(it) }
    }

    @Test
    fun theWholePaletteIsUsed() {
        assertThat(randomIds(400).map { tileColorFor(it) }.toSet()).hasSize(4)
    }

    private fun randomIds(count: Int): List<UUID> {
        val random = Random(1)
        return List(count) { UUID(random.nextLong(), random.nextLong()) }
    }
}
