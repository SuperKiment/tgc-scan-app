package org.tcgscan.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.tcgscan.app.R
import org.tcgscan.app.data.net.ManifestResult
import org.tcgscan.app.data.pack.PackInstallState

/**
 * One language pack in the Settings list: what is installed, what the manifest publishes — with the
 * download size shown **before** any transfer (FR-23) — the live progress of an install, its typed
 * failure, and the install / update / remove actions (FR-24).
 *
 * The row is a pure function of [state]: it decides nothing, it only renders what
 * [SettingsViewModel] derived from `PackStatus` and `PackInstallState`.
 */
@Composable
fun PackRow(
    state: PackUiState,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
    onDismissFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = langLabel(state.lang), style = MaterialTheme.typography.titleMedium)

            val installed = state.installed
            if (installed == null) {
                Text(
                    text = stringResource(R.string.settings_pack_not_installed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.settings_pack_installed_summary,
                        installed.version,
                        installed.cardCount,
                        formatBytes(installed.bytes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // FR-23: the size is announced while the pack can still only be *offered*.
            val downloadBytes = state.downloadBytes
            if (downloadBytes != null && (state.canInstall || state.canUpdate)) {
                Text(
                    text = stringResource(
                        R.string.settings_pack_download_size,
                        formatBytes(downloadBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                state.requiredBytes?.let { required ->
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

            if (state.requiresAppUpdate) {
                Text(
                    text = stringResource(R.string.settings_pack_requires_app_update),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.isWorking) {
                InstallProgress(state.install)
            }

            state.failure?.let { failure ->
                Text(
                    text = installFailureText(failure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Actions(
                state = state,
                onInstall = onInstall,
                onUpdate = onUpdate,
                onRemove = onRemove,
                onCancel = onCancel,
                onDismissFailure = onDismissFailure,
            )
        }
    }
}

@Composable
private fun InstallProgress(install: PackInstallState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = installProgressText(install), style = MaterialTheme.typography.bodySmall)
        if (install is PackInstallState.Downloading && install.totalBytes > 0L) {
            LinearProgressIndicator(
                progress = { install.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Actions(
    state: PackUiState,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
    onDismissFailure: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.isWorking) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.settings_pack_action_cancel))
            }
        } else {
            val failed = state.failure != null
            val retry = R.string.settings_pack_action_retry
            if (state.canUpdate) {
                Button(onClick = onUpdate) {
                    Text(stringResource(if (failed) retry else R.string.settings_pack_action_update))
                }
            } else if (state.canInstall) {
                Button(onClick = onInstall) {
                    Text(
                        stringResource(if (failed) retry else R.string.settings_pack_action_install),
                    )
                }
            }
            if (state.canRemove) {
                OutlinedButton(onClick = onRemove) {
                    Text(stringResource(R.string.settings_pack_action_remove))
                }
            }
            if (failed) {
                TextButton(onClick = onDismissFailure) {
                    Text(stringResource(R.string.settings_pack_action_dismiss))
                }
            }
        }
    }
}

/** « Français » / « Japonais » (D10); an unexpected code is shown as-is rather than hidden. */
@Composable
internal fun langLabel(lang: String): String = when (lang) {
    "fr" -> stringResource(R.string.lang_fr)
    "ja" -> stringResource(R.string.lang_ja)
    else -> lang.uppercase(Locale.ROOT)
}

/** Label of a non-terminal [PackInstallState]; the resumed byte count is part of it (EC-2). */
@Composable
internal fun installProgressText(install: PackInstallState): String = when (install) {
    is PackInstallState.Downloading -> stringResource(
        R.string.settings_pack_state_downloading,
        formatBytes(install.bytesWritten),
        formatBytes(install.totalBytes),
    )

    is PackInstallState.Verifying -> stringResource(R.string.settings_pack_state_verifying)
    is PackInstallState.Installing -> stringResource(R.string.settings_pack_state_installing)
    else -> stringResource(R.string.settings_pack_state_preparing)
}

/** One message per [PackInstallState.Failure]; every case keeps the installed pack usable. */
@Composable
internal fun installFailureText(failure: PackInstallState.Failure): String = when (failure) {
    is PackInstallState.RepoNotConfigured ->
        stringResource(R.string.settings_pack_error_repo)

    is PackInstallState.ManifestFailure ->
        stringResource(R.string.settings_pack_error_manifest, manifestFailureText(failure.reason))

    is PackInstallState.NotPublished ->
        stringResource(R.string.settings_pack_error_not_published)

    is PackInstallState.MalformedEntry ->
        stringResource(R.string.settings_pack_error_malformed, failure.message)

    is PackInstallState.Offline ->
        stringResource(R.string.settings_pack_error_offline)

    is PackInstallState.HttpError ->
        stringResource(R.string.settings_pack_error_http, failure.code)

    is PackInstallState.NotEnoughSpace -> stringResource(
        R.string.settings_pack_error_space,
        formatBytes(failure.requiredBytes),
        formatBytes(failure.availableBytes),
    )

    is PackInstallState.SizeMismatch -> stringResource(
        R.string.settings_pack_error_size,
        formatBytes(failure.expectedBytes),
        formatBytes(failure.actualBytes),
    )

    is PackInstallState.ChecksumMismatch ->
        stringResource(R.string.settings_pack_error_checksum)

    is PackInstallState.UnsupportedPackSchema -> stringResource(
        R.string.settings_pack_error_schema,
        failure.packSchema,
        failure.supportedPackSchema,
    )

    is PackInstallState.CorruptArchive ->
        stringResource(R.string.settings_pack_error_corrupt, failure.message)

    is PackInstallState.StorageError ->
        stringResource(R.string.settings_pack_error_storage, failure.message)
}

/** Shared by the manifest banner and the install failures, so a cause reads the same everywhere. */
@Composable
internal fun manifestFailureText(reason: ManifestResult.Failure): String = when (reason) {
    is ManifestResult.RepoNotConfigured -> stringResource(R.string.settings_manifest_repo)
    is ManifestResult.Offline -> stringResource(R.string.settings_manifest_offline)
    is ManifestResult.Unavailable ->
        stringResource(R.string.settings_manifest_unavailable, reason.message)

    is ManifestResult.HttpError -> stringResource(R.string.settings_manifest_http, reason.code)
    is ManifestResult.UnsupportedSchema ->
        stringResource(R.string.settings_manifest_schema, reason.schema)

    is ManifestResult.Malformed ->
        stringResource(R.string.settings_manifest_malformed, reason.message)
}

/**
 * Human-readable size, 1024-based, with the units of `R.array.byte_units` (`o`, `Ko`, `Mo`, `Go` in
 * French). Pack sizes are the only byte counts the UI shows, and they must be readable before a
 * download starts (FR-23).
 */
@Composable
internal fun formatBytes(bytes: Long): String {
    val units = stringArrayResource(R.array.byte_units)
    if (bytes < BYTES_PER_UNIT) return "$bytes ${units[0]}"
    var value = bytes.toDouble() / BYTES_PER_UNIT
    var unit = 1
    while (value >= BYTES_PER_UNIT && unit < units.lastIndex) {
        value /= BYTES_PER_UNIT
        unit++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

private const val BYTES_PER_UNIT = 1024
