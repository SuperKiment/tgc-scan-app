package org.tcgscan.app.ui.lists

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import org.tcgscan.app.R

/** Cards of one list, resolved through the pack. Implemented in T19. */
@Composable
fun ListDetailRoute(nav: NavController, listId: Long) {
    Text(stringResource(R.string.screen_list_detail_title))
}
