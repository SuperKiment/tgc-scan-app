package org.tcgscan.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.tcgscan.app.ui.about.AboutRoute
import org.tcgscan.app.ui.backup.BackupRoute
import org.tcgscan.app.ui.catalog.CardDetailRoute
import org.tcgscan.app.ui.catalog.CatalogRoute
import org.tcgscan.app.ui.catalog.SetBrowseRoute
import org.tcgscan.app.ui.collection.CollectionRoute
import org.tcgscan.app.ui.lists.ListDetailRoute
import org.tcgscan.app.ui.lists.ListsRoute
import org.tcgscan.app.ui.onboarding.OnboardingRoute
import org.tcgscan.app.ui.review.ReviewRoute
import org.tcgscan.app.ui.scan.ScanRoute
import org.tcgscan.app.ui.settings.SettingsRoute

/**
 * The one and only navigation graph. It is written final in T2: later tasks implement the screens
 * behind the composables referenced here and must not change this file.
 */
@Composable
fun TcgScanNavHost(
    nav: NavHostController = rememberNavController(),
    startDestination: String = Routes.SCAN,
) {
    val currentEntry by nav.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute in Routes.bottomBarRoutes) {
                NavigationBar {
                    Routes.bottomDestinations.forEach { destination ->
                        val label = stringResource(destination.labelRes)
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { nav.navigateToTab(destination.route) },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(Routes.SCAN) { ScanRoute(nav) }

            composable(
                route = Routes.REVIEW,
                arguments = listOf(navArgument(Routes.ARG_SESSION_ID) { type = NavType.LongType }),
            ) { entry ->
                ReviewRoute(nav, entry.requireLong(Routes.ARG_SESSION_ID))
            }

            composable(Routes.COLLECTION) { CollectionRoute(nav) }

            composable(Routes.CATALOG) { CatalogRoute(nav) }

            composable(
                route = Routes.SET_BROWSE,
                arguments = listOf(
                    navArgument(Routes.ARG_LANG) { type = NavType.StringType },
                    navArgument(Routes.ARG_SET_ID) { type = NavType.StringType },
                ),
            ) { entry ->
                SetBrowseRoute(
                    nav = nav,
                    lang = entry.requireString(Routes.ARG_LANG),
                    setId = entry.requireString(Routes.ARG_SET_ID),
                )
            }

            composable(
                route = Routes.CARD_DETAIL,
                arguments = listOf(
                    navArgument(Routes.ARG_LANG) { type = NavType.StringType },
                    navArgument(Routes.ARG_CARD_ID) { type = NavType.StringType },
                ),
            ) { entry ->
                CardDetailRoute(
                    nav = nav,
                    lang = entry.requireString(Routes.ARG_LANG),
                    cardId = entry.requireString(Routes.ARG_CARD_ID),
                )
            }

            composable(Routes.LISTS) { ListsRoute(nav) }

            composable(
                route = Routes.LIST_DETAIL,
                arguments = listOf(navArgument(Routes.ARG_LIST_ID) { type = NavType.LongType }),
            ) { entry ->
                ListDetailRoute(nav, entry.requireLong(Routes.ARG_LIST_ID))
            }

            composable(Routes.SETTINGS) { SettingsRoute(nav) }

            composable(Routes.ABOUT) { AboutRoute(nav) }

            composable(Routes.BACKUP) { BackupRoute(nav) }

            composable(Routes.ONBOARDING) { OnboardingRoute(nav) }
        }
    }
}

/** Tab switch: single instance per tab, state preserved, no back stack growth. */
private fun NavHostController.navigateToTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavBackStackEntry.requireString(key: String): String =
    checkNotNull(arguments?.getString(key)) { "missing route argument: $key" }

private fun NavBackStackEntry.requireLong(key: String): Long =
    checkNotNull(arguments) { "missing route arguments" }.getLong(key)
