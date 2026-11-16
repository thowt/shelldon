/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package org.connectbot.shelldon.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Shelldon colour schemes. Green on near-black, with a restrained set of status colours.
 * Text and container pairs are chosen for at least 4.5:1 contrast.
 */
val ShelldonDarkColorScheme = darkColorScheme(
    primary = Color(0xFF5FD38D),
    onPrimary = Color(0xFF06210F),
    primaryContainer = Color(0xFF1B6B45),
    onPrimaryContainer = Color(0xFFBDEFD0),
    secondary = Color(0xFF9FD8B5),
    onSecondary = Color(0xFF06210F),
    secondaryContainer = Color(0xFF24352B),
    onSecondaryContainer = Color(0xFF5FD38D),
    tertiary = Color(0xFF7CD4E8),
    onTertiary = Color(0xFF00313B),
    tertiaryContainer = Color(0xFF10303A),
    onTertiaryContainer = Color(0xFF7CD4E8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1411),
    onBackground = Color(0xFFE8F0EA),
    surface = Color(0xFF0E1411),
    onSurface = Color(0xFFE8F0EA),
    surfaceVariant = Color(0xFF1E2822),
    onSurfaceVariant = Color(0xFF9FB0A5),
    surfaceContainerLowest = Color(0xFF0A100D),
    surfaceContainerLow = Color(0xFF121A16),
    surfaceContainer = Color(0xFF161E19),
    surfaceContainerHigh = Color(0xFF1E2822),
    surfaceContainerHighest = Color(0xFF24352B),
    outline = Color(0xFF6B7D72),
    outlineVariant = Color(0xFF243229),
    inverseSurface = Color(0xFFE8F0EA),
    inverseOnSurface = Color(0xFF0E1411),
    inversePrimary = Color(0xFF1B6B45),
)

val ShelldonLightColorScheme = lightColorScheme(
    primary = Color(0xFF1B6B45),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBDEFD0),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF3F6B52),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE9D6),
    onSecondaryContainer = Color(0xFF0F4A2D),
    tertiary = Color(0xFF1F6B7D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCDEAF2),
    onTertiaryContainer = Color(0xFF0B3A47),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5F8F6),
    onBackground = Color(0xFF101713),
    surface = Color(0xFFF5F8F6),
    onSurface = Color(0xFF101713),
    surfaceVariant = Color(0xFFE1EAE4),
    onSurfaceVariant = Color(0xFF3F4B44),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5F2),
    surfaceContainer = Color(0xFFE9F0EB),
    surfaceContainerHigh = Color(0xFFE1EAE4),
    surfaceContainerHighest = Color(0xFFD9E4DD),
    outline = Color(0xFF6E7C73),
    outlineVariant = Color(0xFFC2CEC6),
    inverseSurface = Color(0xFF26362D),
    inverseOnSurface = Color(0xFFE8F0EA),
    inversePrimary = Color(0xFF5FD38D),
)

/** A badge's container and text colour. */
@Immutable
data class BadgeColors(
    val container: Color,
    val content: Color,
)

/** Colours that Material's scheme has no slot for: warning badges and session status. */
@Immutable
data class ShelldonExtraColors(
    /** Host uses the LEGACY crypto preset. */
    val legacy: BadgeColors,
    /** Host uses Mosh. */
    val mosh: BadgeColors,
    /** Host uses a protocol that sends data unencrypted (Telnet). */
    val plaintext: BadgeColors,
    /** Neutral informational badge. */
    val neutral: BadgeColors,
    /** Background of an active-session card. */
    val sessionCard: Color,
    /** Connected status. */
    val connected: Color,
    /** Needs-attention status (unread output). */
    val attention: Color,
)

val ShelldonDarkExtraColors = ShelldonExtraColors(
    legacy = BadgeColors(Color(0xFF3A2D10), Color(0xFFF2B84B)),
    mosh = BadgeColors(Color(0xFF2A2540), Color(0xFFC6B8FF)),
    plaintext = BadgeColors(Color(0xFF3D1E1E), Color(0xFFFFB4AB)),
    neutral = BadgeColors(Color(0xFF1E2822), Color(0xFFB7C6BC)),
    sessionCard = Color(0xFF1A2A21),
    connected = Color(0xFF5FD38D),
    attention = Color(0xFFF2B84B),
)

val ShelldonLightExtraColors = ShelldonExtraColors(
    legacy = BadgeColors(Color(0xFFFFE9B8), Color(0xFF5C3D00)),
    mosh = BadgeColors(Color(0xFFE6DEFF), Color(0xFF3B2D80)),
    plaintext = BadgeColors(Color(0xFFFFDAD6), Color(0xFF8C1D18)),
    neutral = BadgeColors(Color(0xFFE1EAE4), Color(0xFF3F4B44)),
    sessionCard = Color(0xFFD7EBDD),
    connected = Color(0xFF1B6B45),
    attention = Color(0xFF8A5A00),
)

val LocalShelldonExtraColors = staticCompositionLocalOf { ShelldonDarkExtraColors }
