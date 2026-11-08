package org.tcgscan.app.ui.collection

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import org.tcgscan.app.R

/** Owned cards, filterable by language and set. Implemented in T18. */
@Composable
fun CollectionRoute(nav: NavController) {
    Text(stringResource(R.string.screen_collection_title))
}
