/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.keyboard

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.connectbot.data.keyboard.KeyboardLayout
import org.junit.Test

class KeySizeTest {
    private companion object {
        const val TOLERANCE = 0.001f
    }

    private fun layout(width: Float, height: Float) = KeyboardLayout(name = "test", buttonWidth = width, buttonHeight = height)

    @Test
    fun defaultSize_isTwentyPercentBigger() {
        val layout = layout(45f, 30f)

        assertThat(layout.keyWidthDp()).isCloseTo(54f, within(TOLERANCE))
        assertThat(layout.keyHeightDp()).isCloseTo(36f, within(TOLERANCE))
    }

    @Test
    fun invalidStoredSizes_fallBackToDefaultsBeforeScaling() {
        assertThat(layout(Float.NaN, 0f).keyWidthDp()).isCloseTo(54f, within(TOLERANCE))
        assertThat(layout(-5f, Float.POSITIVE_INFINITY).keyHeightDp()).isCloseTo(36f, within(TOLERANCE))
    }

    @Test
    fun hugeStoredSizes_areCappedBeforeScaling() {
        assertThat(layout(5000f, 5000f).keyWidthDp()).isCloseTo(1200f, within(TOLERANCE))
    }
}
