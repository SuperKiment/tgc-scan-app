package org.tcgscan.app.data.pack

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import java.io.Closeable
import java.io.File

/**
 * Read-only reader of one installed pack (docs/pack-format.md §1).
 *
 * The pack is produced externally and versioned by `pack_meta.pack_schema`, independently of the
 * app's own Room database, so it is opened directly with [SQLiteDatabase.openDatabase] in
 * [SQLiteDatabase.OPEN_READONLY] mode and never through Room.
 *
 * Searches use `LIKE` over the normalised columns: FTS5 is not guaranteed in the SQLite of
 * API 26 (docs §1).
 *
 * Instances are not thread-confined — `SQLiteDatabase` serialises access itself — but they own a
 * native handle and must be [close]d.
 */
class PackCatalog private constructor(
    val lang: String,
    private val db: SQLiteDatabase,
) : Closeable {

    /** The `pack_meta` table. Cheap enough to re-read; callers usually cache the result. */
    fun meta(): PackMeta {
        val values = LinkedHashMap<String, String>()
        db.query(TABLE_META, arrayOf(COL_KEY, COL_VALUE), null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                values[c.getString(0)] = c.getString(1) ?: ""
            }
        }
        return PackMeta.from(values)
    }

    /**
     * Every set of the pack, grouped by series then ordered by release date (newest first). Sets
     * without a `release_date` come last inside their series instead of first, which is what
     * `DESC` alone would do with SQLite's NULL ordering.
     */
    fun sets(): List<PackSet> = db.rawQuery(
        """
        SELECT $SET_COLUMNS FROM $TABLE_SETS
        ORDER BY series_id, release_date IS NULL, release_date DESC, name
        """.trimIndent(),
        null,
    ).use { it.mapAll(::readSet) }

    /** One set, or `null` when [setId] is not in this pack (EC-16). */
    fun set(setId: String): PackSet? = db.rawQuery(
        "SELECT $SET_COLUMNS FROM $TABLE_SETS WHERE id = ? LIMIT 1",
        arrayOf(setId),
    ).use { if (it.moveToFirst()) readSet(it) else null }

    /** The cards of one set, numerically ordered on `local_id` so promos land after the numbers. */
    fun cardsInSet(setId: String): List<PackCard> = db.rawQuery(
        """
        SELECT $CARD_COLUMNS FROM $TABLE_CARDS
        WHERE set_id = ?
        ORDER BY CAST(local_id AS INTEGER), local_id
        """.trimIndent(),
        arrayOf(setId),
    ).use { it.mapAll(::readCard) }

    /**
     * Name search over `cards.name_norm` (docs §5.1). [query] must already be normalised by the
     * canonical rule (`scan/text/TextNormalizer`); a blank query returns nothing rather than
     * everything. Prefix matches come first. [offset] supports the paged catalogue list.
     */
    fun searchByName(query: String, limit: Int, offset: Int = 0): List<PackCard> {
        val needle = query.trim()
        if (needle.isEmpty() || limit <= 0) return emptyList()
        val escaped = escapeLike(needle)
        return db.rawQuery(
            """
            SELECT $CARD_COLUMNS FROM $TABLE_CARDS
            WHERE name_norm LIKE '%' || ? || '%' ESCAPE '$LIKE_ESCAPE'
            ORDER BY (CASE WHEN name_norm LIKE ? || '%' ESCAPE '$LIKE_ESCAPE' THEN 0 ELSE 1 END),
                     LENGTH(name_norm), name_norm, card_id
            LIMIT ? OFFSET ?
            """.trimIndent(),
            arrayOf(escaped, escaped, limit.toString(), offset.coerceAtLeast(0).toString()),
        ).use { it.mapAll(::readCard) }
    }

    /**
     * Number search for the OCR path: matches every `ocr_tokens.token`, so « 136 », « 136/201 »,
     * « SV043 » and a set id all resolve (docs §2.3 and §5).
     *
     * `cards.local_id` is deliberately *not* matched directly: `ocr_tokens` already holds the bare
     * lowercased `local_id` of every card (docs §2.3), and a `LOWER(local_id) = ?` branch would
     * only add a full table scan — `LOWER()` makes `idx_cards_local_id` unusable.
     */
    fun searchByNumber(localId: String, limit: Int = DEFAULT_NUMBER_LIMIT): List<PackCard> {
        val token = localId.trim().lowercase()
        if (token.isEmpty() || limit <= 0) return emptyList()
        return db.rawQuery(
            """
            SELECT $CARD_COLUMNS FROM $TABLE_CARDS
            WHERE card_id IN (SELECT card_id FROM $TABLE_TOKENS WHERE token = ?)
            ORDER BY set_id, CAST(local_id AS INTEGER), local_id
            LIMIT ?
            """.trimIndent(),
            arrayOf(token, limit.toString()),
        ).use { it.mapAll(::readCard) }
    }

    /** One card, or `null` when the id is not in this pack — a possession kept as « carte inconnue » (EC-16). */
    fun card(cardId: String): PackCard? = db.rawQuery(
        "SELECT $CARD_COLUMNS FROM $TABLE_CARDS WHERE card_id = ? LIMIT 1",
        arrayOf(cardId),
    ).use { if (it.moveToFirst()) readCard(it) else null }

    /**
     * Bulk resolution of card ids, keyed by `card_id`. Ids absent from the pack are simply absent
     * from the result so the caller can render them as « carte inconnue » (EC-16/EC-18).
     *
     * The ids are queried in chunks to stay below SQLite's bound-parameter limit.
     */
    fun cardsByIds(ids: Collection<String>): Map<String, PackCard> {
        if (ids.isEmpty()) return emptyMap()
        val distinct = ids.toSet()
        val result = LinkedHashMap<String, PackCard>(distinct.size)
        distinct.chunked(MAX_BOUND_ARGS).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT $CARD_COLUMNS FROM $TABLE_CARDS WHERE card_id IN ($placeholders)",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    val card = readCard(c)
                    result[card.cardId] = card
                }
            }
        }
        return result
    }

    /** The OCR search tokens of one card (docs §2.3). */
    fun tokensFor(cardId: String): List<String> = db.rawQuery(
        "SELECT token FROM $TABLE_TOKENS WHERE card_id = ? ORDER BY token",
        arrayOf(cardId),
    ).use { c ->
        ArrayList<String>(c.count).apply {
            while (c.moveToNext()) add(c.getString(0))
        }
    }

    /** Number of rows in `embeddings`; used by [EmbeddingIndex] to size its buffer once. */
    internal fun embeddingCount(): Int =
        db.rawQuery("SELECT COUNT(*) FROM $TABLE_EMBEDDINGS", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /**
     * Streams `embeddings` in one pass, one row at a time, so [EmbeddingIndex] can copy the int8
     * vectors straight into its direct buffer without holding the whole table as objects.
     */
    internal fun forEachEmbedding(onRow: (cardId: String, vec: ByteArray) -> Unit) {
        db.rawQuery("SELECT card_id, vec FROM $TABLE_EMBEDDINGS", null).use { c ->
            while (c.moveToNext()) {
                val vec = c.getBlob(1) ?: continue
                onRow(c.getString(0), vec)
            }
        }
    }

    override fun close() {
        db.close()
    }

    private fun readSet(c: Cursor) = PackSet(
        id = c.getString(0),
        name = c.getString(1),
        seriesId = c.getString(2),
        cardCountTotal = c.getInt(3),
        cardCountOfficial = c.getInt(4),
        releaseDate = c.getStringOrNull(5),
        symbolUrl = c.getStringOrNull(6),
        logoUrl = c.getStringOrNull(7),
    )

    private fun readCard(c: Cursor) = PackCard(
        cardId = c.getString(0),
        localId = c.getString(1),
        name = c.getString(2),
        nameNorm = c.getString(3),
        setId = c.getString(4),
        imageUrl = c.getStringOrNull(5),
        hasEmbed = c.getInt(6),
    )

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private inline fun <T> Cursor.mapAll(read: (Cursor) -> T): List<T> =
        ArrayList<T>(count).also { out ->
            while (moveToNext()) out.add(read(this))
        }

    companion object {

        private const val TAG = "PackCatalog"

        private const val TABLE_META = "pack_meta"
        private const val TABLE_SETS = "sets"
        private const val TABLE_CARDS = "cards"
        private const val TABLE_EMBEDDINGS = "embeddings"
        private const val TABLE_TOKENS = "ocr_tokens"
        private const val COL_KEY = "key"
        private const val COL_VALUE = "value"

        private const val SET_COLUMNS =
            "id, name, series_id, card_count_total, card_count_official, release_date, " +
                "symbol_url, logo_url"
        private const val CARD_COLUMNS =
            "card_id, local_id, name, name_norm, set_id, image_url, has_embed"

        private const val LIKE_ESCAPE = "\\"

        /** SQLite's default `SQLITE_MAX_VARIABLE_NUMBER` is 999; stay well under it. */
        private const val MAX_BOUND_ARGS = 500

        private const val DEFAULT_NUMBER_LIMIT = 50

        /**
         * Opens the installed pack of [lang], or returns `null` when no pack is installed (EC-1) or
         * when the file is not a readable pack.
         *
         * A corrupt pack is still a `null` — callers treat « pas de pack » and « pack illisible »
         * the same way — but it is logged, because a silent `null` makes a broken `pack.db`
         * impossible to diagnose from a bug report.
         */
        fun open(context: Context, lang: String): PackCatalog? {
            val file = PackPaths.packDb(context, lang)
            if (!file.isFile) return null
            return try {
                open(file, lang)
            } catch (e: PackFormatException) {
                Log.w(TAG, "Pack $lang at ${file.absolutePath} is not readable", e)
                null
            }
        }

        /**
         * Opens an arbitrary pack file read-only — also used by `PackInstaller` to inspect a freshly
         * gunzipped `pack.db.tmp` before it is renamed into place (EC-17).
         *
         * [SQLiteDatabase.NO_LOCALIZED_COLLATORS] is mandatory: the pack is built by the Python
         * pipeline, it carries no `android_metadata` row, and a read-only database cannot be
         * localised at open time.
         *
         * @throws PackFormatException when the file cannot be opened as SQLite.
         */
        fun open(file: File, lang: String): PackCatalog {
            val db = try {
                SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                )
            } catch (e: SQLiteException) {
                throw PackFormatException("Cannot open pack ${file.absolutePath}", e)
            }
            return PackCatalog(lang, db)
        }

        /** Escapes the `LIKE` wildcards of a user- or OCR-provided needle. */
        internal fun escapeLike(value: String): String = buildString(value.length) {
            value.forEach { ch ->
                if (ch == '%' || ch == '_' || ch == '\\') append('\\')
                append(ch)
            }
        }
    }
}
