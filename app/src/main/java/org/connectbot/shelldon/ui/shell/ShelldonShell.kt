/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ui.shell

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navOptions
import org.connectbot.R
import org.connectbot.ui.navigation.NavDestinations
import org.connectbot.ui.navigation.navigateSafely

/** Top-level destinations reachable from the bottom bar. */
internal enum class ShellTab(
    val route: String,
    @param:StringRes val label: Int,
    val icon: ImageVector,
) {
    HOSTS(NavDestinations.HOST_LIST, R.string.shelldon_tab_hosts, Icons.Default.Dns),
    KEYCHAIN(NavDestinations.KEYCHAIN, R.string.shelldon_tab_keychain, Icons.Default.Key),
    SETTINGS(NavDestinations.SETTINGS, R.string.shelldon_tab_settings, Icons.Default.Settings),
    ;

    companion object {
        /** The tab whose destination is [route], or null if [route] is not a top-level destination. */
        fun forRoute(route: String?): ShellTab? = if (route == null) null else entries.firstOrNull { route.startsWith(it.route) }
    }
}

/**
 * Wraps the navigation graph with a bottom bar that is shown on the top-level destinations only.
 * The bar is hidden while picking a host for a shortcut and on every other screen.
 *
 * [content] receives a modifier carrying the bar's padding; apply it to the NavHost.
 */
@Composable
fun ShelldonShell(
    navController: NavHostController,
    makingShortcut: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selected = ShellTab.forRoute(backStackEntry?.destination?.route)

    Scaffold(
        modifier = modifier,
        // Screens inside the graph handle the system bars themselves.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (selected != null && !makingShortcut) {
                ShelldonNavigationBar(
                    selected = selected,
                    onSelect = { tab ->
                        navController.navigateSafely(
                            tab.route,
                            navOptions {
                                popUpTo(NavDestinations.HOST_LIST) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            },
                        )
                    },
                )
            }
        },
    ) { padding ->
        content(Modifier.padding(padding).consumeWindowInsets(padding))
    }
}

@Composable
private fun ShelldonNavigationBar(
    selected: ShellTab,
    onSelect: (ShellTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        ShellTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(imageVector = tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
            )
        }
    }
}
