/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.keyboard

import org.connectbot.data.keyboard.KeyboardLayout

/**
 * Global multiplier on the stored key size of every keyboard layout. The stored defaults
 * (45 x 30 dp) were too small to hit reliably on a phone, so everything is drawn 20% bigger. The
 * stored values are left alone, so existing layouts and their editor keep working; to make keys
 * bigger or smaller still, change this one number (a setting can replace it later).
 */
const val KEY_SIZE_SCALE = 1.2f

private const val DEFAULT_KEY_WIDTH = 45f
private const val DEFAULT_KEY_HEIGHT = 30f
private const val MAX_STORED_SIZE = 1000f

/** Key width in dp as drawn: the stored width (or the default if invalid) times [KEY_SIZE_SCALE]. */
internal fun KeyboardLayout.keyWidthDp(): Float = storedSize(buttonWidth, DEFAULT_KEY_WIDTH) * KEY_SIZE_SCALE

/** Key height in dp as drawn: the stored height (or the default if invalid) times [KEY_SIZE_SCALE]. */
internal fun KeyboardLayout.keyHeightDp(): Float = storedSize(buttonHeight, DEFAULT_KEY_HEIGHT) * KEY_SIZE_SCALE

private fun storedSize(value: Float, default: Float): Float = value.takeIf { it.isFinite() && it > 0 }?.coerceAtMost(MAX_STORED_SIZE) ?: default
