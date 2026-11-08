package org.tcgscan.app.ui.about

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import org.tcgscan.app.R

/** FR-29 mentions, GPL-3.0 notice, dependency and model licences. Implemented in T20. */
@Composable
fun AboutRoute(nav: NavController) {
    Text(stringResource(R.string.screen_about_title))
}
