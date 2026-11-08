package org.tcgscan.app.ui.catalog

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import org.tcgscan.app.R

/** Catalogue root: language tabs, search, set list. Implemented in T17. */
@Composable
fun CatalogRoute(nav: NavController) {
    Text(stringResource(R.string.screen_catalog_title))
}
