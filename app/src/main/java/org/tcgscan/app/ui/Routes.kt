package org.tcgscan.app.ui

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.ui.graphics.vector.ImageVector
import org.tcgscan.app.R

/**
 * The complete navigation contract of the app. Route patterns and argument names are final: every
 * screen task fills its own `…Route` composable in place and navigates with the builders below, so
 * [TcgScanNavHost] is never edited again.
 */
object Routes {

    const val ARG_SESSION_ID = "sessionId"
    const val ARG_LIST_ID = "listId"
    const val ARG_LANG = "lang"
    const val ARG_SET_ID = "setId"
    const val ARG_CARD_ID = "cardId"

    // Bottom-bar destinations.
    const val SCAN = "scan"
    const val COLLECTION = "collection"
    const val CATALOG = "catalog"
    const val LISTS = "lists"
    const val SETTINGS = "settings"

    // Detail destinations.
    const val REVIEW = "review/{$ARG_SESSION_ID}"
    const val SET_BROWSE = "catalog/{$ARG_LANG}/set/{$ARG_SET_ID}"
    const val CARD_DETAIL = "catalog/{$ARG_LANG}/card/{$ARG_CARD_ID}"
    const val LIST_DETAIL = "lists/{$ARG_LIST_ID}"
    const val ABOUT = "about"
    const val BACKUP = "backup"
    const val ONBOARDING = "onboarding"

    fun review(sessionId: Long): String = "review/$sessionId"

    fun setBrowse(lang: String, setId: String): String =
        "catalog/${Uri.encode(lang)}/set/${Uri.encode(setId)}"

    fun cardDetail(lang: String, cardId: String): String =
        "catalog/${Uri.encode(lang)}/card/${Uri.encode(cardId)}"

    fun listDetail(listId: Long): String = "lists/$listId"

    /** The five tabs, in display order. */
    val bottomDestinations: List<BottomDestination> = listOf(
        BottomDestination(SCAN, R.string.nav_scan, Icons.Outlined.PhotoCamera),
        BottomDestination(COLLECTION, R.string.nav_collection, Icons.Outlined.Style),
        BottomDestination(CATALOG, R.string.nav_catalog, Icons.Outlined.Search),
        BottomDestination(LISTS, R.string.nav_lists, Icons.Outlined.Bookmarks),
        BottomDestination(SETTINGS, R.string.nav_settings, Icons.Outlined.Settings),
    )

    /** Routes that show the bottom bar; everything else is a full-screen detail. */
    val bottomBarRoutes: Set<String> = bottomDestinations.mapTo(mutableSetOf()) { it.route }
}

/** One bottom-bar tab. */
data class BottomDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)
