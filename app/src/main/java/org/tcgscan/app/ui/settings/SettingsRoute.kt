package org.tcgscan.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import org.tcgscan.app.AppConfig
import org.tcgscan.app.R
import org.tcgscan.app.ui.Routes

/**
 * Settings: the default scan language (FR-5), the language packs with their size, update, install
 * and removal actions (FR-23, FR-24), the bounded image cache (FR-25) and the entries to Backup and
 * About (FR-29).
 *
 * Nothing here writes to the collection: removing a pack keeps every possession (EC-20).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(nav: NavController) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(
        factory = remember(context) { SettingsViewModel.factory(context) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val messageText = state.message?.let { messageText(it) }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.screen_settings_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!state.repoConfigured) {
                WarningCard(
                    stringResource(
                        R.string.settings_repo_placeholder_warning,
                        AppConfig.GITHUB_REPO_PLACEHOLDER,
                    ),
                )
            }
            if (!state.hasInstalledPack) {
                WarningCard(stringResource(R.string.settings_no_pack_banner))
            }

            SectionHeader(stringResource(R.string.settings_section_scan))
            DefaultLangSection(
                defaultScanLang = state.defaultScanLang,
                installedLangs = state.installedLangs,
                onSelect = viewModel::setDefaultScanLang,
            )

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_section_packs))
            ManifestStatus(state.manifest, onRetry = viewModel::refreshManifest)
            state.packs.forEach { pack ->
                PackRow(
                    state = pack,
                    onInstall = { viewModel.installPack(pack.lang) },
                    onUpdate = { viewModel.installPack(pack.lang) },
                    onRemove = { viewModel.removePack(pack.lang) },
                    onCancel = { viewModel.cancelInstall(pack.lang) },
                    onDismissFailure = { viewModel.dismissInstallFailure(pack.lang) },
                )
            }

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_section_data))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_clear_image_cache)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_clear_image_cache_summary))
                },
                modifier = Modifier.clickable(onClick = viewModel::clearImageCache),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_entry_backup)) },
                modifier = Modifier.clickable { nav.navigate(Routes.BACKUP) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_entry_about)) },
                modifier = Modifier.clickable { nav.navigate(Routes.ABOUT) },
            )
        }
    }

    // EC-20: the warning is shown before anything is deleted, and the possessions are kept either
    // way — the pack layer refuses to remove an owned language until this dialog is accepted.
    state.pendingRemoval?.let { pending ->
        val lang = langLabel(pending.lang)
        AlertDialog(
            onDismissRequest = viewModel::dismissRemoval,
            title = { Text(stringResource(R.string.settings_remove_title, lang)) },
            text = {
                Text(stringResource(R.string.settings_remove_message, pending.ownedCardCount))
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRemoval) {
                    Text(stringResource(R.string.settings_remove_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRemoval) {
                    Text(stringResource(R.string.settings_remove_cancel))
                }
            },
        )
    }
}

@Composable
private fun DefaultLangSection(
    defaultScanLang: String,
    installedLangs: List<String>,
    onSelect: (String) -> Unit,
) {
    Text(
        text = stringResource(R.string.settings_default_lang_title),
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        text = stringResource(R.string.settings_default_lang_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (installedLangs.isEmpty()) {
        Text(
            text = stringResource(R.string.settings_default_lang_none),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    installedLangs.forEach { lang ->
        val selected = lang == defaultScanLang
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = { onSelect(lang) },
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Text(text = langLabel(lang), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The manifest read, including the EC-1 « Réessayer » affordance when the device is offline. */
@Composable
private fun ManifestStatus(state: ManifestUiState, onRetry: () -> Unit) {
    val message = when (state) {
        ManifestUiState.Idle -> null
        ManifestUiState.Loading -> stringResource(R.string.settings_packs_checking)
        is ManifestUiState.Loaded ->
            stringResource(R.string.settings_packs_version, state.version)

        ManifestUiState.Offline -> stringResource(R.string.settings_packs_offline_banner)
        ManifestUiState.RepoNotConfigured ->
            stringResource(R.string.settings_packs_failed_banner, stringResource(R.string.settings_manifest_repo))

        is ManifestUiState.Failed -> stringResource(
            R.string.settings_packs_failed_banner,
            manifestFailureText(state.reason),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = message.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f, fill = false),
        )
        TextButton(onClick = onRetry, enabled = state !is ManifestUiState.Loading) {
            Text(stringResource(R.string.settings_packs_check))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun WarningCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun messageText(message: SettingsMessage): String = when (message) {
    is SettingsMessage.PackInstalled ->
        stringResource(R.string.settings_message_pack_installed, langLabel(message.lang))

    is SettingsMessage.PackRemoved -> stringResource(
        R.string.settings_message_pack_removed,
        langLabel(message.lang),
        message.ownedCardsKept,
    )

    is SettingsMessage.PackNotInstalled ->
        stringResource(R.string.settings_message_pack_absent, langLabel(message.lang))

    is SettingsMessage.RemovalFailed ->
        stringResource(R.string.settings_message_removal_failed, message.message)

    SettingsMessage.ImageCacheCleared ->
        stringResource(R.string.settings_message_image_cache_cleared)
}
