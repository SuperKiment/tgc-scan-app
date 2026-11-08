package org.tcgscan.app.ui.onboarding

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import org.tcgscan.app.R

/** First run: fan-project notice, pack choice, download. Implemented in T21. */
@Composable
fun OnboardingRoute(nav: NavController) {
    Text(stringResource(R.string.screen_onboarding_title))
}
