package org.tcgscan.app.data.backup

import android.content.Context
import android.database.SQLException
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tcgscan.app.data.db.AppDatabase
import org.tcgscan.app.data.net.describe

/**
 * Writes the collection to a document the user chose through `ACTION_CREATE_DOCUMENT` (FR-27).
 *
 * Two guarantees shape the code:
 * * **No partial file is ever presented as valid (EC-19).** The whole payload is built in memory
 *   from a single database snapshot *before* the target is opened, so the only remaining failure is
 *   the write itself; when that fails the document is deleted and a typed failure is returned.
 * * **No scan photo leaves the app.** This class never looks at the scan photo directory: a backup
 *   is the user's own data, not the images (FR-26/FR-27).
 */
class BackupExporter(
    private val context: Context,
    private val db: AppDatabase,
) {

    /** Never throws: every outcome is an [ExportResult]. Safe to call from a ViewModel scope. */
    suspend fun export(format: BackupFormat, target: Uri): ExportResult =
        withContext(Dispatchers.IO) {
            val envelope = try {
                snapshot()
            } catch (e: SQLException) {
                return@withContext ExportResult.SnapshotFailed(e.describe())
            }
            val payload = when (format) {
                BackupFormat.JSON -> BackupEnvelope.encode(envelope)
                BackupFormat.CSV -> csv(envelope)
            }.toByteArray(StandardCharsets.UTF_8)

            write(target, payload)?.let { return@withContext it }
            ExportResult.Success(
                format = format,
                ownershipCount = envelope.ownerships.size,
                listCount = envelope.lists.size,
                bytes = payload.size.toLong(),
            )
        }

    /** One consistent read of everything the envelope holds, unknown ids included (EC-16). */
    private suspend fun snapshot(): BackupEnvelope {
        val ownershipDao = db.ownershipDao()
        val cardListDao = db.cardListDao()
        return BackupEnvelope.of(
            ownerships = ownershipDao.all(),
            lists = cardListDao.allLists(),
            items = cardListDao.allItems(),
            exportedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
        )
    }

    /** Returns null on success, or the failure to report; the target is discarded on failure. */
    private fun write(target: Uri, payload: ByteArray): ExportResult.Failure? = try {
        val stream = context.contentResolver.openOutputStream(target, WRITE_TRUNCATE)
        if (stream == null) {
            ExportResult.TargetUnwritable("no output stream for $target")
        } else {
            stream.use { out ->
                out.write(payload)
                out.flush()
            }
            null
        }
    } catch (e: IOException) {
        // Includes FileNotFoundException (document gone, folder unreachable) and a full volume.
        discard(target)
        ExportResult.WriteFailed(e.describe())
    } catch (e: SecurityException) {
        // The grant on the document expired before the write; nothing was written.
        discard(target)
        ExportResult.TargetUnwritable(e.describe())
    } catch (e: IllegalArgumentException) {
        // Provider that rejects the "wt" mode or a non-document Uri.
        discard(target)
        ExportResult.TargetUnwritable(e.describe())
    }

    /**
     * Best-effort removal of a half-written document (EC-19). A provider may refuse the deletion;
     * the export is reported as failed either way, so the file is never offered as a valid backup.
     */
    private fun discard(target: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, target)
        } catch (e: IOException) {
            Log.w(TAG, "could not delete the partial export", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "not allowed to delete the partial export", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "not a document Uri, partial export left in place", e)
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "provider does not support deletion, partial export left in place", e)
        }
    }

    /**
     * Flat ownership dump: one row per owned card, lists excluded (FR-27). Fields are quoted only
     * when they must be, so the usual output stays diff-friendly.
     */
    private fun csv(envelope: BackupEnvelope): String = buildString {
        append(CSV_HEADER).append('\n')
        for (ownership in envelope.ownerships) {
            append(csvField(ownership.lang)).append(',')
            append(csvField(ownership.cardId)).append(',')
            append(csvField(ownership.physicalLang)).append(',')
            append(ownership.addedAt).append('\n')
        }
    }

    /** Prefixes `'` to a leading formula trigger (CSV injection), then applies RFC 4180 quoting. */
    private fun csvField(raw: String): String {
        val value = if (raw.isNotEmpty() && raw[0] in CSV_FORMULA_TRIGGERS) "'$raw" else raw
        return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private companion object {
        const val TAG = "BackupExporter"

        val CSV_FORMULA_TRIGGERS = setOf('=', '+', '-', '@', '\t', '\r')

        /** Truncating write: the document the user picked may already exist and be longer. */
        const val WRITE_TRUNCATE = "wt"
    }
}

/** Typed outcome of [BackupExporter.export]; the UI maps each case to its own message. */
sealed interface ExportResult {

    data class Success(
        val format: BackupFormat,
        val ownershipCount: Int,
        val listCount: Int,
        val bytes: Long,
    ) : ExportResult

    /** Nothing valid was produced; see each case for the matching criterion (EC-19). */
    sealed interface Failure : ExportResult

    /** The collection could not be read, so the document was never opened. */
    data class SnapshotFailed(val message: String) : Failure

    /** The chosen document cannot be written to at all. */
    data class TargetUnwritable(val message: String) : Failure

    /** The write started and failed; the document has been deleted if the provider allowed it. */
    data class WriteFailed(val message: String) : Failure
}
