package org.tcgscan.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import org.tcgscan.app.AppConfig
import org.tcgscan.app.R

/**
 * « À propos »: the FR-29 mentions (fan project unaffiliated with Nintendo, The Pokémon Company or
 * Creatures; card data by TCGdex), the GPL-3.0 notice, the licences of the dependencies and of the
 * ML models, and the configured `AppConfig.GITHUB_REPO` with a warning while it is the placeholder
 * shipped by the plan (D13).
 *
 * `docs/models.md` §4 is authoritative for [LICENCES]; this screen is its user-facing copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutRoute(nav: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_about_title)) },
                navigationIcon = {
                    IconButton(onClick = { nav.navigateUp() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
            )

            // FR-29, first and foremost.
            Text(
                text = stringResource(R.string.about_fan_notice),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.about_data_source),
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()

            Text(
                text = stringResource(R.string.about_licence_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.about_licence_body),
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = stringResource(R.string.about_licences_title),
                style = MaterialTheme.typography.titleMedium,
            )
            LICENCES.forEach { (component, licence) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = component,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = licence, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                text = stringResource(R.string.about_licences_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text(
                text = stringResource(R.string.about_repo_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = AppConfig.GITHUB_REPO, style = MaterialTheme.typography.bodyMedium)
            if (!AppConfig.isGithubRepoConfigured()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(
                        text = stringResource(
                            R.string.about_repo_placeholder_warning,
                            AppConfig.GITHUB_REPO_PLACEHOLDER,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * Component / licence pairs, copied from `docs/models.md` §4 (FR-29, NFR-1). Component names are
 * proper nouns and licence identifiers are SPDX ids: neither is translated.
 */
private val LICENCES: List<Pair<String, String>> = listOf(
    "TCG Scan" to "GPL-3.0-only",
    "facebook/dinov2-small (export ONNX int8)" to "Apache-2.0",
    "PP-OCRv4 / PP-OCRv3 + dictionnaires PaddleOCR" to "Apache-2.0",
    "ONNX Runtime Android 1.31.1" to "MIT",
    "OpenCV 4.12.0" to "Apache-2.0",
    "AndroidX (Compose, CameraX, Room, DataStore, Lifecycle, Navigation)" to "Apache-2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache-2.0",
    "OkHttp 4.12.0" to "Apache-2.0",
    "Coil 2.7.0" to "Apache-2.0",
    "Données des cartes : TCGdex (tcgdex.dev)" to "TCGdex",
)
