/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.keyboard

import org.assertj.core.api.Assertions.assertThat
import org.connectbot.data.keyboard.KeyboardLayout
import org.junit.Test

class KeySizeTest {
    private fun layout(width: Float, height: Float) = KeyboardLayout(name = "test", buttonWidth = width, buttonHeight = height)

    @Test
    fun defaultSize_isTwentyPercentBigger() {
        val layout = layout(45f, 30f)

        assertThat(layout.keyWidthDp()).isEqualTo(54f)
        assertThat(layout.keyHeightDp()).isEqualTo(36f)
    }

    @Test
    fun invalidStoredSizes_fallBackToDefaultsBeforeScaling() {
        assertThat(layout(Float.NaN, 0f).keyWidthDp()).isEqualTo(54f)
        assertThat(layout(-5f, Float.POSITIVE_INFINITY).keyHeightDp()).isEqualTo(36f)
    }

    @Test
    fun hugeStoredSizes_areCappedBeforeScaling() {
        assertThat(layout(5000f, 5000f).keyWidthDp()).isEqualTo(1200f)
    }
}
