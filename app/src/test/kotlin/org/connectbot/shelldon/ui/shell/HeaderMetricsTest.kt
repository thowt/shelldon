/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.shell

import androidx.compose.ui.unit.dp
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class HeaderMetricsTest {
    @Test
    fun aPhoneAndTheFoldedCoverScreenGetTheCompactBar() {
        // 360 dp: a small phone. 412 dp: the Pixel Fold's cover screen and most current phones.
        assertThat(headerBarHeight(360)).isEqualTo(56.dp)
        assertThat(headerBarHeight(412)).isEqualTo(56.dp)
        assertThat(headerBarHeight(599)).isEqualTo(56.dp)
    }

    @Test
    fun anUnfoldedFoldAndATabletGetTheLowestBar() {
        assertThat(headerBarHeight(600)).isEqualTo(48.dp)
        // About 840 dp: the Pixel Fold unfolded.
        assertThat(headerBarHeight(841)).isEqualTo(48.dp)
        assertThat(headerBarHeight(1280)).isEqualTo(48.dp)
    }

    @Test
    fun theBarIsAlwaysLowerThanMaterialsDefaultButKeepsATouchTarget() {
        listOf(320, 412, 599, 600, 841, 1280).forEach { width ->
            val height = headerBarHeight(width)
            assertThat(height).describedAs("width $width").isLessThan(64.dp)
            assertThat(height).describedAs("width $width").isGreaterThanOrEqualTo(48.dp)
        }
    }

    @Test
    fun theTitleBarHidesByDefaultOnlyOnAWideWindow() {
        assertThat(titleBarHideDefault(412)).isFalse()
        assertThat(titleBarHideDefault(599)).isFalse()
        assertThat(titleBarHideDefault(600)).isTrue()
        assertThat(titleBarHideDefault(841)).isTrue()
    }
}
