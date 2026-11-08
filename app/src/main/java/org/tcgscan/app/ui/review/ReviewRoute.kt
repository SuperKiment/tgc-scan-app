package org.tcgscan.app.ui.review

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import org.tcgscan.app.R

/** Review screen of a scan session, the only path into the collection. Implemented in T16. */
@Composable
fun ReviewRoute(nav: NavController, sessionId: Long) {
    Text(stringResource(R.string.screen_review_title))
}
