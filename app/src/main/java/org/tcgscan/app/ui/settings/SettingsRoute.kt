package org.tcgscan.app.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import org.tcgscan.app.R

/** Default scan language, pack management, cache and backup entries. Implemented in T20. */
@Composable
fun SettingsRoute(nav: NavController) {
    Text(stringResource(R.string.screen_settings_title))
}
