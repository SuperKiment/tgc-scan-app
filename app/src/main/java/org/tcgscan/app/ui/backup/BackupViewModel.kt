package org.tcgscan.app.ui.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tcgscan.app.R
import org.tcgscan.app.data.backup.BackupExporter
import org.tcgscan.app.data.backup.BackupFormat
import org.tcgscan.app.data.backup.BackupImporter
import org.tcgscan.app.data.backup.ExportResult
import org.tcgscan.app.data.backup.ImportMode
import org.tcgscan.app.data.backup.ImportPreview
import org.tcgscan.app.data.backup.ImportPreviewResult
import org.tcgscan.app.data.backup.ImportResult
import org.tcgscan.app.data.db.DbProvider

/**
 * Drives the backup screen: export to the document the user created, and a two-step import —
 * [prepareImport] parses and summarises, [confirmImport] writes (FR-27/FR-28).
 *
 * Technical failure details are logged, never shown: the user gets one French sentence per outcome,
 * and every failing outcome says that nothing was changed or kept (EC-18/EC-19).
 */
class BackupViewModel(
    private val exporter: BackupExporter,
    private val importer: BackupImporter,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun export(format: BackupFormat, target: Uri) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = exporter.export(format, target)
            if (result is ExportResult.Failure) Log.w(TAG, "export failed: $result")
            _state.update { it.copy(busy = false, message = result.toMessage()) }
        }
    }

    /** Reads and summarises the file; the confirmation dialog opens, nothing is written yet. */
    fun prepareImport(source: Uri) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null, pendingImport = null) }
        viewModelScope.launch {
            when (val result = importer.read(source)) {
                is ImportPreviewResult.Success ->
                    _state.update { it.copy(busy = false, pendingImport = result.preview) }

                is ImportPreviewResult.Failure -> {
                    Log.w(TAG, "import refused: $result")
                    _state.update { it.copy(busy = false, message = result.toMessage()) }
                }
            }
        }
    }

    /** Dismisses the confirmation dialog: the file is dropped, the collection never touched. */
    fun cancelImport() {
        _state.update { it.copy(pendingImport = null) }
    }

    fun confirmImport(mode: ImportMode) {
        val preview = _state.value.pendingImport ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, pendingImport = null, message = null) }
        viewModelScope.launch {
            val result = importer.apply(preview, mode)
            if (result is ImportResult.WriteFailed) Log.w(TAG, "import failed: $result")
            _state.update { it.copy(busy = false, message = result.toMessage()) }
        }
    }

    /** Called once the message has been shown, so it is not shown again on recomposition. */
    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun ExportResult.toMessage(): BackupMessage = when (this) {
        is ExportResult.Success ->
            BackupMessage(R.string.backup_export_done, listOf(ownershipCount))

        // Snapshot, target and write failures share one sentence: in all three cases no file was
        // produced or kept, and the technical detail is in the log (EC-19).
        is ExportResult.Failure -> BackupMessage(R.string.backup_export_failed)
    }

    private fun ImportPreviewResult.Failure.toMessage(): BackupMessage = when (this) {
        is ImportPreviewResult.Unreadable -> BackupMessage(R.string.backup_import_unreadable)
        is ImportPreviewResult.Corrupt -> BackupMessage(R.string.backup_import_corrupt)
        is ImportPreviewResult.UnsupportedSchema ->
            BackupMessage(R.string.backup_import_unsupported)

        is ImportPreviewResult.CollectionUnreadable -> BackupMessage(R.string.backup_import_failed)
    }

    private fun ImportResult.toMessage(): BackupMessage = when (this) {
        is ImportResult.Success -> BackupMessage(
            R.string.backup_import_done,
            listOf(summary.ownershipsAdded, summary.ownershipsRemoved),
        )

        is ImportResult.WriteFailed -> BackupMessage(R.string.backup_import_failed)
    }

    companion object {

        private const val TAG = "BackupViewModel"

        /**
         * Ad-hoc wiring until the dependency container of T31 owns the database; [context] is only
         * used to reach the application-scoped [DbProvider] and the content resolver.
         */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(BackupViewModel::class.java)) {
                        "BackupViewModel.factory asked for $modelClass"
                    }
                    val db = DbProvider.get(appContext)
                    @Suppress("UNCHECKED_CAST")
                    return BackupViewModel(
                        exporter = BackupExporter(appContext, db),
                        importer = BackupImporter(appContext, db),
                    ) as T
                }
            }
        }
    }
}

/** Everything the backup screen draws. */
data class BackupUiState(
    /** An export or an import is running: the actions are disabled and a progress bar shows. */
    val busy: Boolean = false,
    /** Non-null while the confirmation dialog of FR-28 is open; nothing has been written. */
    val pendingImport: ImportPreview? = null,
    val message: BackupMessage? = null,
)

/** A localised one-liner for the snackbar. [args] are positional format arguments. */
data class BackupMessage(
    @StringRes val textRes: Int,
    val args: List<Any> = emptyList(),
)
