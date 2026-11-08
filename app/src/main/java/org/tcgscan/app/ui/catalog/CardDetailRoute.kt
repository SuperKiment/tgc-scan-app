package org.tcgscan.app.ui.catalog

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import org.tcgscan.app.R

/** Single card sheet: visual, numbering, ownership, list membership. Implemented in T17. */
@Composable
fun CardDetailRoute(nav: NavController, lang: String, cardId: String) {
    Text(stringResource(R.string.screen_card_detail_title))
}
