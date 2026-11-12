package org.tcgscan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import org.tcgscan.app.data.AppPrefs
import org.tcgscan.app.ui.Routes
import org.tcgscan.app.ui.TcgScanNavHost
import org.tcgscan.app.ui.theme.TcgScanTheme

/**
 * The single activity of the app (portrait only, NFR-8). It picks the start destination from
 * [AppPrefs.onboardingDone]: first-run onboarding while it is false, the scan tab afterwards. The
 * navigation graph itself is untouched; the choice is made once, so finishing onboarding never
 * rebuilds it.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = AppPrefs(applicationContext)
        setContent {
            TcgScanTheme {
                // `null` for the instant the preferences are being read: nothing is shown yet.
                val onboardingDone by produceState<Boolean?>(initialValue = null) {
                    value = prefs.onboardingDone.first()
                }
                onboardingDone?.let { done ->
                    TcgScanNavHost(
                        startDestination = remember { if (done) Routes.SCAN else Routes.ONBOARDING },
                    )
                }
            }
        }
    }
}
