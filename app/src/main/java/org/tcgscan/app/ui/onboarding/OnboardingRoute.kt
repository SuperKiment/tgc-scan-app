package org.tcgscan.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import org.tcgscan.app.R
import org.tcgscan.app.data.net.ManifestResult
import org.tcgscan.app.data.pack.PackInstallState
import org.tcgscan.app.ui.Routes
import org.tcgscan.app.ui.settings.ManifestUiState
import org.tcgscan.app.ui.settings.PackUiState
import org.tcgscan.app.ui.settings.formatBytes
import org.tcgscan.app.ui.settings.installFailureText
import org.tcgscan.app.ui.settings.installProgressText
import org.tcgscan.app.ui.settings.langLabel
import org.tcgscan.app.ui.settings.manifestFailureText

/**
 * First run (EC-1): the fan-project notice, the pack choice with sizes read from the manifest, then
 * the download with its progress and a done state. Without network the choice step becomes an
 * explanation with « Réessayer » and a « continuer sans pack » exit; scanning is then disabled by
 * the scan screen itself, which sees no installed pack.
 *
 * Onboarding is left for the scan tab and never returned to: [OnboardingViewModel.finish] records it
 * as done before navigating.
 */
@Composable
fun OnboardingRoute(nav: NavController) {
    val context = LocalContext.current
    val viewModel: OnboardingViewModel = viewModel(
        factory = remember(context) { OnboardingViewModel.factory(context) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val finish = {
        viewModel.finish {
            nav.navigate(Routes.SCAN) {
                popUpTo(Routes.ONBOARDING) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    BackHandler(enabled = state.step != OnboardingStep.Notice, onBack = viewModel::back)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state.step) {
            OnboardingStep.Notice -> NoticeStep(onContinue = viewModel::acceptNotice)

            OnboardingStep.PackChoice -> PackChoiceStep(
                state = state,
                onToggle = viewModel::toggleLang,
                onDownload = viewModel::startDownload,
                onRetry = viewModel::refreshManifest,
                onSkip = { finish() },
            )

            OnboardingStep.Download -> DownloadStep(
                state = state,
                onRetry = viewModel::startDownload,
                onFinish = { finish() },
            )
        }
    }
}

@Composable
private fun ColumnScope.NoticeStep(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.screen_onboarding_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.onboarding_notice_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.onboarding_notice_body),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.onboarding_notice_privacy),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_action_continue))
    }
}

@Composable
private fun ColumnScope.PackChoiceStep(
    state: OnboardingUiState,
    onToggle: (String) -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
) {
    when (val manifest = state.manifest) {
        is ManifestUiState.Idle, is ManifestUiState.Loading -> {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.onboarding_choice_loading))
            }
        }

        is ManifestUiState.Loaded -> PackChoiceList(state, onToggle, onDownload, onSkip)

        is ManifestUiState.Offline -> UnreachableCatalog(
            title = stringResource(R.string.onboarding_offline_title),
            body = stringResource(R.string.onboarding_offline_body),
            onRetry = onRetry,
            onSkip = onSkip,
        )

        is ManifestUiState.RepoNotConfigured -> UnreachableCatalog(
            title = stringResource(R.string.onboarding_error_title),
            body = stringResource(
                R.string.onboarding_error_body,
                manifestFailureText(ManifestResult.RepoNotConfigured),
            ),
            onRetry = onRetry,
            onSkip = onSkip,
        )

        is ManifestUiState.Failed -> UnreachableCatalog(
            title = stringResource(R.string.onboarding_error_title),
            body = stringResource(
                R.string.onboarding_error_body,
                manifestFailureText(manifest.reason),
            ),
            onRetry = onRetry,
            onSkip = onSkip,
        )
    }
}

@Composable
private fun ColumnScope.PackChoiceList(
    state: OnboardingUiState,
    onToggle: (String) -> Unit,
    onDownload: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_choice_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.onboarding_choice_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.packs.forEach { pack ->
            PackChoiceCard(
                pack = pack,
                checked = pack.lang in state.selected,
                onToggle = { onToggle(pack.lang) },
            )
        }
    }
    // Packs already on disk (a reinstall that kept them) leave nothing to choose: just go on.
    if (!state.canStartDownload && state.hasInstalledPack) {
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_action_continue))
        }
        return
    }
    if (!state.canStartDownload) {
        Text(
            text = stringResource(R.string.onboarding_choice_pick_one),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(
        onClick = onDownload,
        enabled = state.canStartDownload,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.onboarding_action_download))
    }
}

/** One language: its size is read before anything is downloaded (FR-23). */
@Composable
private fun PackChoiceCard(pack: PackUiState, checked: Boolean, onToggle: () -> Unit) {
    val selectable = pack.status?.canInstall == true
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = selectable,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() },
                )
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = selectable)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = langLabel(pack.lang), style = MaterialTheme.typography.titleMedium)
                val downloadBytes = pack.downloadBytes
                when {
                    pack.installed != null -> Text(
                        text = stringResource(R.string.onboarding_choice_installed),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    pack.requiresAppUpdate -> Text(
                        text = stringResource(R.string.settings_pack_requires_app_update),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    downloadBytes == null -> Text(
                        text = stringResource(R.string.onboarding_choice_not_published),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    else -> {
                        Text(
                            text = stringResource(
                                R.string.settings_pack_download_size,
                                formatBytes(downloadBytes),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        pack.requiredBytes?.let { required ->
                            Text(
                                text = stringResource(
                                    R.string.settings_pack_required_space,
                                    formatBytes(required),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** EC-1: the catalogue cannot be read; retry, or leave without a pack. */
@Composable
private fun ColumnScope.UnreachableCatalog(
    title: String,
    body: String,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Text(text = body, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(R.string.onboarding_skip_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_pack_action_retry))
    }
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_action_skip))
    }
}

@Composable
private fun ColumnScope.DownloadStep(
    state: OnboardingUiState,
    onRetry: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(
                if (state.downloadDone) {
                    R.string.onboarding_download_done_title
                } else {
                    R.string.onboarding_download_title
                },
            ),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (state.downloadDone) {
            Text(
                text = stringResource(R.string.onboarding_download_done_body),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        state.chosenPacks.forEach { pack -> DownloadRow(pack) }
    }

    when {
        state.downloadDone -> Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_action_start))
        }

        state.downloadFailed -> {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_pack_action_retry))
            }
            TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (state.hasInstalledPack) {
                            R.string.onboarding_action_continue
                        } else {
                            R.string.onboarding_action_skip
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(pack: PackUiState) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = langLabel(pack.lang), style = MaterialTheme.typography.titleMedium)
            val failure = pack.failure
            when {
                pack.isWorking -> {
                    Text(
                        text = installProgressText(pack.install),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val install = pack.install
                    if (install is PackInstallState.Downloading && install.totalBytes > 0L) {
                        LinearProgressIndicator(
                            progress = { install.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                failure != null -> Text(
                    text = installFailureText(failure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                pack.installed != null -> Text(
                    text = stringResource(R.string.onboarding_download_installed),
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> Text(
                    text = stringResource(R.string.onboarding_download_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
