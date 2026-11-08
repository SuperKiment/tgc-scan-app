package org.tcgscan.app.ui.lists

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import org.tcgscan.app.R

/** Wishlist plus user lists, with create, rename and delete. Implemented in T19. */
@Composable
fun ListsRoute(nav: NavController) {
    Text(stringResource(R.string.screen_lists_title))
}
