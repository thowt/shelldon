/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.shell

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The bar follows the width of the window it is drawn in, as it does when a Fold is opened. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class ShelldonBarHeightTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun showBar() {
        composeTestRule.setContent {
            MaterialTheme {
                TopAppBar(
                    title = { Text("Shelldon") },
                    modifier = Modifier.testTag("bar"),
                    expandedHeight = shelldonBarHeight(),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
            }
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w412dp-h900dp")
    fun aNarrowWindowDrawsTheCompactBar() {
        showBar()
        composeTestRule.onNodeWithTag("bar").assertHeightIsEqualTo(56.dp)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w840dp-h900dp")
    fun aWideWindowDrawsTheLowestBar() {
        showBar()
        composeTestRule.onNodeWithTag("bar").assertHeightIsEqualTo(48.dp)
    }
}
