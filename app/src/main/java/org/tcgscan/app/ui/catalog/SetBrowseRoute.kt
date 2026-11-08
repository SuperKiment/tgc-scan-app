package org.tcgscan.app.ui.catalog

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import org.tcgscan.app.R

/** Grid of the cards of one set, with the owned badge. Implemented in T17. */
@Composable
fun SetBrowseRoute(nav: NavController, lang: String, setId: String) {
    Text(stringResource(R.string.screen_set_browse_title))
}
