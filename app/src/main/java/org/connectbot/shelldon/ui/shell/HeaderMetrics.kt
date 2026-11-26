/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The window width from which a screen counts as wide: a Pixel Fold unfolded (about 840 dp), a
 * tablet, or a large split-screen window. A phone, and the folded Fold's cover screen (about 410
 * dp), stay below it.
 */
const val WIDE_WINDOW_DP = 600

/** Height of the top bar on a phone or a folded cover screen. Material's default is 64 dp. */
val CompactBarHeight: Dp = 56.dp

/**
 * Height of the top bar on a wide window. 48 dp is as low as it can go while an icon button still
 * gets its full 48 dp touch target.
 */
val WideBarHeight: Dp = 48.dp

fun isWideWindow(screenWidthDp: Int): Boolean = screenWidthDp >= WIDE_WINDOW_DP

fun headerBarHeight(screenWidthDp: Int): Dp = if (isWideWindow(screenWidthDp)) WideBarHeight else CompactBarHeight

/**
 * Whether the terminal's title bar hides itself when the person has not chosen. Wide windows have
 * room for the terminal to use, so it hides there; a phone keeps it visible.
 */
fun titleBarHideDefault(screenWidthDp: Int): Boolean = isWideWindow(screenWidthDp)

/**
 * The height to give every top app bar (`TopAppBar(expandedHeight = shelldonBarHeight())`), so
 * all screens shrink by the same amount and the bar follows the window as it is folded and
 * unfolded.
 */
@Composable
fun shelldonBarHeight(): Dp = headerBarHeight(LocalConfiguration.current.screenWidthDp)
