package org.tcgscan.app.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import org.tcgscan.app.R
import org.tcgscan.app.data.backup.BackupFormat
import org.tcgscan.app.data.backup.ImportMode
import org.tcgscan.app.data.backup.ImportPreview
import org.tcgscan.app.data.backup.ImportSummary

/**
 * Export and import screen (FR-27/FR-28).
 *
 * The document pickers are the system ones: `ACTION_CREATE_DOCUMENT` for an export, so the user
 * owns the destination, and `ACTION_OPEN_DOCUMENT` for an import. An import always goes through the
 * confirmation dialog, which shows what each mode would change *before* anything is written
 * (EC-18).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRoute(nav: NavController) {
    val context = LocalContext.current
    val viewModel: BackupViewModel = viewModel(factory = BackupViewModel.factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val createJson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFormat.JSON.mimeType),
    ) { target -> target?.let { viewModel.export(BackupFormat.JSON, it) } }

    val createCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFormat.CSV.mimeType),
    ) { target -> target?.let { viewModel.export(BackupFormat.CSV, it) } }

    val openJson = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source -> source?.let(viewModel::prepareImport) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            context.getString(message.textRes, *message.args.toTypedArray()),
        )
        viewModel.dismissMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_backup_title)) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.backup_intro),
                style = MaterialTheme.typography.bodyMedium,
            )

            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Button(
                onClick = { createJson.launch(BackupFormat.JSON.suggestedFileName()) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.backup_export_json))
            }

            OutlinedButton(
                onClick = { createCsv.launch(BackupFormat.CSV.suggestedFileName()) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.backup_export_csv))
            }

            HorizontalDivider()

            OutlinedButton(
                onClick = { openJson.launch(IMPORT_MIME_TYPES) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.backup_import_json))
            }
        }
    }

    state.pendingImport?.let { preview ->
        ImportConfirmationDialog(
            preview = preview,
            onConfirm = viewModel::confirmImport,
            onDismiss = viewModel::cancelImport,
        )
    }
}

/** The diff summary and the choice between fusion and remplacement, before any write (FR-28). */
@Composable
private fun ImportConfirmationDialog(
    preview: ImportPreview,
    onConfirm: (ImportMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (preview.exportedAt.isNotBlank()) {
                    Text(
                        text = stringResource(
                            R.string.backup_dialog_exported_at,
                            preview.exportedAt.take(EXPORTED_AT_MAX_CHARS),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SummaryBlock(R.string.backup_dialog_mode_merge, preview.merge)
                SummaryBlock(R.string.backup_dialog_mode_replace, preview.replace)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(ImportMode.MERGE) }) {
                Text(stringResource(R.string.backup_action_merge))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onConfirm(ImportMode.REPLACE) }) {
                    Text(stringResource(R.string.backup_action_replace))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.backup_action_cancel))
                }
            }
        },
    )
}

@Composable
private fun SummaryBlock(@StringRes titleRes: Int, summary: ImportSummary) {
    Column {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(
                R.string.backup_summary_ownerships,
                summary.ownershipsAdded,
                summary.ownershipsRemoved,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = stringResource(
                R.string.backup_summary_lists,
                summary.listsAdded,
                summary.listsRemoved,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = stringResource(
                R.string.backup_summary_items,
                summary.listItemsAdded,
                summary.listItemsRemoved,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Types offered by the import picker. A backup is JSON, but providers label a `.json` file
 * inconsistently, so the two usual mislabels are accepted too; the content is validated either way
 * (EC-18).
 */
private val IMPORT_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")
private const val EXPORTED_AT_MAX_CHARS = 40
