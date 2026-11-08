package org.tcgscan.app.ui.scan

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import org.tcgscan.app.R

/** Burst scan screen (CameraX preview, counter, torch). Implemented in T15. */
@Composable
fun ScanRoute(nav: NavController) {
    Text(stringResource(R.string.screen_scan_title))
}
