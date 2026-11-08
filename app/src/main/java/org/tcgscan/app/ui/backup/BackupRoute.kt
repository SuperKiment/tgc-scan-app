package org.tcgscan.app.ui.backup

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import org.tcgscan.app.R

/** JSON and CSV export, JSON import with merge or replace. Implemented in T22. */
@Composable
fun BackupRoute(nav: NavController) {
    Text(stringResource(R.string.screen_backup_title))
}
