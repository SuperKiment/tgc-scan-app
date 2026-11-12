package org.tcgscan.app.data.backup

import android.content.Context
import android.database.SQLException
import android.net.Uri
import androidx.room.withTransaction
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.tcgscan.app.AppConfig
import org.tcgscan.app.data.db.AppDatabase
import org.tcgscan.app.data.db.CardListEntity
import org.tcgscan.app.data.db.CardListItem
import org.tcgscan.app.data.net.describe

/**
 * Reads a JSON backup chosen through `ACTION_OPEN_DOCUMENT` and applies it as a merge or as a
 * replacement (FR-28).
 *
 * The two steps are separate on purpose: [read] parses the whole file, validates it and computes
 * the diff **without writing anything**, and [apply] only accepts an [ImportPreview] that [read]
 * produced. A corrupt body or a `schemaVersion` newer than [AppConfig.DB_SCHEMA] therefore cannot
 * leave the database half-changed — it never reaches a write at all (EC-18).
 *
 * No pack is consulted: an imported id that the installed pack does not know is stored as it comes
 * and shows up as « carte inconnue » until the matching pack is installed (EC-16/EC-18).
 */
class BackupImporter(
    private val context: Context,
    private val db: AppDatabase,
) {

    /** Never throws: every outcome is an [ImportPreviewResult]. No write happens here. */
    suspend fun read(source: Uri): ImportPreviewResult = withContext(Dispatchers.IO) {
        val body: String? = try {
            readText(source)
        } catch (e: IOException) {
            return@withContext ImportPreviewResult.Unreadable(e.describe())
        } catch (e: SecurityException) {
            return@withContext ImportPreviewResult.Unreadable(e.describe())
        } catch (e: IllegalArgumentException) {
            return@withContext ImportPreviewResult.Unreadable(e.describe())
        }
        if (body == null) {
            return@withContext ImportPreviewResult.Corrupt(
                "file larger than ${BackupEnvelope.MAX_IMPORT_BYTES} bytes",
            )
        }

        val envelope = try {
            BackupEnvelope.parse(body)
        } catch (e: SerializationException) {
            return@withContext ImportPreviewResult.Corrupt(e.describe().safe(MAX_ERROR_CHARS))
        } catch (e: IllegalArgumentException) {
            // Thrown by the JSON reader on a structurally impossible document.
            return@withContext ImportPreviewResult.Corrupt(e.describe().safe(MAX_ERROR_CHARS))
        }
        if (envelope.schemaVersion > AppConfig.DB_SCHEMA) {
            return@withContext ImportPreviewResult.UnsupportedSchema(envelope.schemaVersion)
        }
        envelope.validationError()?.let { return@withContext ImportPreviewResult.Corrupt(it) }

        try {
            ImportPreviewResult.Success(plan(envelope))
        } catch (e: SQLException) {
            ImportPreviewResult.CollectionUnreadable(e.describe())
        }
    }

    /**
     * Applies the previewed import in **one** transaction, so an interrupted import leaves the
     * collection exactly as it was (FR-28). Never throws: the outcome is an [ImportResult].
     */
    suspend fun apply(preview: ImportPreview, mode: ImportMode): ImportResult =
        withContext(Dispatchers.IO) {
            try {
                db.withTransaction {
                    if (mode == ImportMode.REPLACE) clearUserData()
                    insert(preview.envelope)
                }
                ImportResult.Success(mode, preview.summary(mode))
            } catch (e: SQLException) {
                ImportResult.WriteFailed(e.describe())
            }
        }

    /**
     * Reads at most [BackupEnvelope.MAX_IMPORT_BYTES] of [source], or null when the document is
     * bigger than that — a file that size is not an export of this app and is not parsed (EC-18).
     */
    private fun readText(source: Uri): String? {
        val stream: InputStream = context.contentResolver.openInputStream(source)
            ?: throw IOException("no input stream for $source")
        return stream.use { input ->
            val limit = BackupEnvelope.MAX_IMPORT_BYTES
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            val collected = ByteArrayOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (collected.size() + read > limit) return null
                collected.write(buffer, 0, read)
            }
            collected.toByteArray().toString(StandardCharsets.UTF_8)
        }
    }

    /** Diffs the envelope against the current collection for both modes, reading only (EC-18). */
    private suspend fun plan(envelope: BackupEnvelope): ImportPreview {
        val ownershipDao = db.ownershipDao()
        val cardListDao = db.cardListDao()
        val currentOwnerships = ownershipDao.all()
            .mapTo(mutableSetOf()) { CardKey(it.lang, it.cardId) }
        val importedOwnerships = envelope.ownerships
            .mapTo(mutableSetOf()) { CardKey(it.lang, it.cardId) }

        val itemsByList = cardListDao.allItems().groupBy { it.listId }
        val currentLists = mutableMapOf<String, MutableSet<CardKey>>()
        for (list in cardListDao.allLists()) {
            val members = currentLists.getOrPut(listKey(list.name, list.isSystem)) { mutableSetOf() }
            itemsByList[list.id].orEmpty().mapTo(members) { CardKey(it.lang, it.cardId) }
        }
        val importedLists = mutableMapOf<String, MutableSet<CardKey>>()
        for (list in envelope.lists) {
            val members = importedLists.getOrPut(listKey(list.name, list.isSystem)) { mutableSetOf() }
            list.cards.mapTo(members) { CardKey(it.lang, it.cardId) }
        }

        val ownershipsAdded = importedOwnerships.count { it !in currentOwnerships }
        val itemsAdded = importedLists.entries.sumOf { (key, members) ->
            val current = currentLists[key].orEmpty()
            members.count { it !in current }
        }
        val itemsRemoved = currentLists.entries.sumOf { (key, members) ->
            val imported = importedLists[key].orEmpty()
            members.count { it !in imported }
        }

        return ImportPreview(
            envelope = envelope,
            // Fusion is a pure union: it adds and never takes anything away.
            merge = ImportSummary(
                ownershipsAdded = ownershipsAdded,
                ownershipsRemoved = 0,
                listsAdded = importedLists.keys.count { it !in currentLists },
                listsRemoved = 0,
                listItemsAdded = itemsAdded,
                listItemsRemoved = 0,
            ),
            // Replacement drops whatever the file does not contain, except the wishlist row
            // itself, which is a system list and only loses its members (FR-16).
            replace = ImportSummary(
                ownershipsAdded = ownershipsAdded,
                ownershipsRemoved = currentOwnerships.count { it !in importedOwnerships },
                listsAdded = importedLists.keys.count { it !in currentLists },
                listsRemoved = currentLists.keys.count { it != SYSTEM_LIST_KEY && it !in importedLists },
                listItemsAdded = itemsAdded,
                listItemsRemoved = itemsRemoved,
            ),
        )
    }

    /**
     * Empties the collection before a « remplacement ». The wishlist row survives because it is the
     * system list (FR-16); its members do not, which is why they are removed one by one — the items
     * of the user lists go with their list through the foreign key.
     */
    private suspend fun clearUserData() {
        val cardListDao = db.cardListDao()
        db.ownershipDao().deleteAll()
        cardListDao.deleteAllUserLists()
        for (item in cardListDao.items(CardListEntity.WISHLIST_ID)) {
            cardListDao.removeItem(item.listId, item.lang, item.cardId)
        }
    }

    /**
     * Inserts the envelope. Both modes share this step: conflicting rows are ignored, so a merge
     * keeps the ownership already in place with its original `added_at` (FR-12), and a replacement
     * starts from an empty table anyway.
     *
     * The file is applied as it is: a card that is both owned and wishlisted in the backup stays in
     * both, because pruning the wishlist is a rule of the scan validation (FR-17), not of an import.
     */
    private suspend fun insert(envelope: BackupEnvelope) {
        val cardListDao = db.cardListDao()
        db.ownershipDao().insertAll(envelope.ownerships.map { it.toEntity() })
        for (list in envelope.lists) {
            val listId = resolveListId(list)
            cardListDao.addItems(
                list.cards.map { CardListItem(listId = listId, lang = it.lang, cardId = it.cardId) },
            )
        }
    }

    /**
     * The local list an imported one maps to: the system wishlist for the exported system list, an
     * existing list of the same name, or a new user list. A system entry whose local wishlist row is
     * somehow missing falls back to the name match so no member is dropped.
     */
    private suspend fun resolveListId(list: BackupList): Long {
        val cardListDao = db.cardListDao()
        if (list.isSystem) {
            cardListDao.list(CardListEntity.WISHLIST_ID)?.let { return it.id }
        }
        cardListDao.listByName(list.name)?.let { return it.id }
        return cardListDao.insertList(
            CardListEntity(
                name = list.name,
                isSystem = false,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private companion object {

        const val COPY_BUFFER_BYTES = 8 * 1024

        /** Parser messages can echo the file content: clamp them before they reach the UI. */
        const val MAX_ERROR_CHARS = 200

        /**
         * Key the wishlist is diffed under. Lists are matched by name, case-insensitively, but the
         * wishlist is matched by its system flag: the local row keeps its own label (the device
         * language may differ from the one that produced the file), so its name cannot be the key.
         */
        const val SYSTEM_LIST_KEY = "\u0000system"

        fun listKey(name: String, isSystem: Boolean): String =
            if (isSystem) SYSTEM_LIST_KEY else name.lowercase()
    }

    /** `(lang, cardId)`, the key of a card everywhere in the app. */
    private data class CardKey(val lang: String, val cardId: String)
}

/** The two ways to apply a backup (FR-28). */
enum class ImportMode {
    /** Union: nothing the user already has is removed. */
    MERGE,

    /** The file becomes the collection; what it does not contain is dropped. */
    REPLACE,
}

/**
 * A parsed, validated backup plus the diff it would produce in either mode. Only [BackupImporter]
 * can build one, which is what makes « parsed and summarised before any write » a type-level
 * guarantee (EC-18).
 */
class ImportPreview internal constructor(
    val envelope: BackupEnvelope,
    val merge: ImportSummary,
    val replace: ImportSummary,
) {

    val exportedAt: String get() = envelope.exportedAt

    fun summary(mode: ImportMode): ImportSummary = when (mode) {
        ImportMode.MERGE -> merge
        ImportMode.REPLACE -> replace
    }
}

/** What the confirmation dialog shows before anything is written (FR-28). */
data class ImportSummary(
    val ownershipsAdded: Int,
    val ownershipsRemoved: Int,
    val listsAdded: Int,
    val listsRemoved: Int,
    val listItemsAdded: Int,
    val listItemsRemoved: Int,
)

/** Typed outcome of [BackupImporter.read]. Every failure means « nothing changed » (EC-18). */
sealed interface ImportPreviewResult {

    data class Success(val preview: ImportPreview) : ImportPreviewResult

    sealed interface Failure : ImportPreviewResult

    /** The document could not be opened or read. */
    data class Unreadable(val message: String) : Failure

    /** Not a backup of this app: not JSON, missing fields, implausible values, or far too big. */
    data class Corrupt(val message: String) : Failure

    /** A file written by a newer build; this one must not guess what it means (EC-18). */
    data class UnsupportedSchema(val schemaVersion: Int) : Failure

    /** The file is fine but the current collection could not be read, so no diff is trustworthy. */
    data class CollectionUnreadable(val message: String) : Failure
}

/** Typed outcome of [BackupImporter.apply]. */
sealed interface ImportResult {

    data class Success(val mode: ImportMode, val summary: ImportSummary) : ImportResult

    /** The transaction rolled back: the collection is untouched. */
    data class WriteFailed(val message: String) : ImportResult
}
