package org.tcgscan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.tcgscan.app.ui.TcgScanNavHost
import org.tcgscan.app.ui.theme.TcgScanTheme

/**
 * The single activity of the app (portrait only, NFR-8). The start destination becomes conditional
 * on first-run onboarding in T21, which edits this file and never the navigation graph.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TcgScanTheme {
                TcgScanNavHost()
            }
        }
    }
}
