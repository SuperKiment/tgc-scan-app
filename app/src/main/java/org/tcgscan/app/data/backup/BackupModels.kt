package org.tcgscan.app.data.backup

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tcgscan.app.AppConfig
import org.tcgscan.app.data.db.CardListEntity
import org.tcgscan.app.data.db.CardListItem
import org.tcgscan.app.data.db.OwnedCard

/**
 * The backup envelope: everything the user created and nothing else (FR-27).
 *
 * It carries the ownerships (with their `physical_lang`, FR-11), the wishlist, the custom lists and
 * the schema version. It carries **no card metadata** — a card is a `(lang, cardId)` pair resolved
 * through the installed pack — and **no scan photo**, which is why an export is a few hundred
 * kilobytes and stays readable.
 *
 * Because no pack is consulted, an id absent from the installed pack survives a round trip and is
 * rendered « carte inconnue » until the right pack is installed (EC-16/EC-18).
 *
 * [schemaVersion] is [AppConfig.DB_SCHEMA]: a file written by a newer build is refused rather than
 * guessed at (EC-18).
 */
@Serializable
data class BackupEnvelope(
    val schemaVersion: Int,
    val exportedAt: String = "",
    val ownerships: List<BackupOwnership> = emptyList(),
    val lists: List<BackupList> = emptyList(),
) {

    /**
     * Null when this envelope is usable, otherwise a short reason. Checked once, right after
     * parsing, so the import planner and the transaction can trust every field (EC-18).
     */
    fun validationError(): String? {
        if (schemaVersion <= 0) return "non-positive schemaVersion: $schemaVersion"
        if (exportedAt.length > MAX_TIMESTAMP_LENGTH) return "exportedAt too long"
        for (ownership in ownerships) {
            ownership.validationError()?.let { return "ownership: $it" }
        }
        for (list in lists) {
            list.validationError()?.let { return "list: $it" }
        }
        return null
    }

    companion object {

        /**
         * Hard ceiling on the size of an imported file. An export of the whole French and Japanese
         * catalogues is far below a megabyte, so anything past this is not a backup of this app and
         * must not be parsed into memory.
         */
        const val MAX_IMPORT_BYTES: Long = 4L * 1024 * 1024

        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        /** Snapshot of the user's data, in a stable order so two exports of it are identical. */
        fun of(
            ownerships: List<OwnedCard>,
            lists: List<CardListEntity>,
            items: List<CardListItem>,
            exportedAt: String,
        ): BackupEnvelope {
            val itemsByList = items.groupBy { it.listId }
            return BackupEnvelope(
                schemaVersion = AppConfig.DB_SCHEMA,
                exportedAt = exportedAt,
                ownerships = ownerships
                    .sortedWith(compareBy({ it.lang }, { it.cardId }))
                    .map { BackupOwnership(it.lang, it.cardId, it.physicalLang, it.addedAt) },
                lists = lists
                    .sortedWith(compareBy({ !it.isSystem }, { it.name }))
                    .map { list ->
                        BackupList(
                            name = list.name,
                            isSystem = list.isSystem,
                            cards = itemsByList[list.id]
                                .orEmpty()
                                .sortedWith(compareBy({ it.lang }, { it.cardId }))
                                .map { BackupCardRef(it.lang, it.cardId) },
                        )
                    },
            )
        }

        fun encode(envelope: BackupEnvelope): String =
            json.encodeToString(serializer(), envelope)

        /**
         * @throws SerializationException when [body] is not a backup envelope at all: not JSON, or
         *   JSON without the required fields (the corrupt-file case of EC-18).
         */
        fun parse(body: String): BackupEnvelope = json.decodeFromString(serializer(), body)
    }
}

/** One owned card, exactly as [OwnedCard] stores it. */
@Serializable
data class BackupOwnership(
    val lang: String,
    val cardId: String,
    val physicalLang: String,
    val addedAt: Long,
) {

    fun validationError(): String? = when {
        lang.length > MAX_LANG_LENGTH || !LANG_PATTERN.matches(lang) -> "invalid lang: ${lang.safe()}"
        physicalLang.length > MAX_LANG_LENGTH || !LANG_PATTERN.matches(physicalLang) ->
            "invalid physicalLang: ${physicalLang.safe()}"
        cardId.isBlank() -> "empty cardId"
        cardId.length > MAX_CARD_ID_LENGTH -> "cardId too long"
        addedAt < 0L -> "negative addedAt: $addedAt"
        else -> null
    }

    fun toEntity(): OwnedCard = OwnedCard(
        lang = lang,
        cardId = cardId,
        physicalLang = physicalLang,
        addedAt = addedAt,
    )
}

/**
 * One list and its members. [isSystem] marks the wishlist, which is matched to the local system row
 * on import instead of being recreated: the wishlist is never duplicated nor renamed (FR-16).
 */
@Serializable
data class BackupList(
    val name: String,
    val isSystem: Boolean = false,
    val cards: List<BackupCardRef> = emptyList(),
) {

    fun validationError(): String? = when {
        name.isBlank() -> "empty name"
        name.length > MAX_LIST_NAME_LENGTH -> "name too long"
        else -> cards.firstNotNullOfOrNull { it.validationError() }
    }
}

/** A card referenced by a list: the same `(lang, cardId)` key as everywhere else. */
@Serializable
data class BackupCardRef(val lang: String, val cardId: String) {

    fun validationError(): String? = when {
        lang.length > MAX_LANG_LENGTH || !LANG_PATTERN.matches(lang) -> "invalid lang: ${lang.safe()}"
        cardId.isBlank() -> "empty cardId"
        cardId.length > MAX_CARD_ID_LENGTH -> "cardId too long"
        else -> null
    }
}

/**
 * The two export formats of FR-27. JSON is the restorable one — it is what the importer reads; the
 * CSV is a flat ownership dump for spreadsheets and carries no list.
 */
enum class BackupFormat(val mimeType: String, val extension: String) {
    JSON("application/json", "json"),
    CSV("text/csv", "csv"),
    ;

    /** Name proposed to `ACTION_CREATE_DOCUMENT`; the user remains free to change it. */
    fun suggestedFileName(at: LocalDateTime = LocalDateTime.now()): String =
        "tcgscan-backup-${FILE_NAME_STAMP.format(at)}.$extension"

    private companion object {
        val FILE_NAME_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    }
}

/** Header of the CSV export, and the column order of its rows (FR-27). */
internal const val CSV_HEADER = "lang,card_id,physical_lang,added_at"

private val LANG_PATTERN = Regex("[a-z]{2,3}")

private const val MAX_LANG_LENGTH = 8
private const val MAX_CARD_ID_LENGTH = 64
private const val MAX_LIST_NAME_LENGTH = 200
private const val MAX_TIMESTAMP_LENGTH = 64

/** Untrusted text made safe for a failure message: truncated, control characters (newlines) dropped. */
internal fun String.safe(max: Int = 40): String = take(max).filterNot { it.isISOControl() }
