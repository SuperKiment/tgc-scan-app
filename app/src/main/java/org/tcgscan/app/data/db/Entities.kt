package org.tcgscan.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * The local, user-owned data — the only data the app itself writes.
 *
 * Card metadata is never duplicated here: a row references a card by its `(lang, cardId)` key only
 * and is resolved through the installed pack (docs/pack-format.md). Installing or removing a pack
 * therefore cannot change the collection (FR-23), and an id that disappeared from a newer pack is
 * still owned, just unresolvable (EC-16).
 */

/**
 * One owned card. Ownership is binary for each `(lang, cardId)` pair: no quantity, no variant, no
 * condition (FR-12). Owning a card in French says nothing about the Japanese one, hence the
 * language in the key.
 */
@Entity(
    tableName = "owned_cards",
    primaryKeys = ["lang", "card_id"],
)
data class OwnedCard(
    /** Pack language the id belongs to, `"fr"` or `"ja"` (D10). */
    @ColumnInfo(name = "lang") val lang: String,
    /** TCGdex card id, unique within [lang]. */
    @ColumnInfo(name = "card_id") val cardId: String,
    /**
     * Language actually printed on the cardboard. Equal to [lang] in the normal case, `"en"` for an
     * English card filed under the French id so it can be migrated once the EN pack exists (FR-11).
     */
    @ColumnInfo(name = "physical_lang") val physicalLang: String,
    /** Epoch millis of the validation that created the ownership. */
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/**
 * A card list. The wishlist is the single system list: it cannot be renamed nor deleted (FR-16),
 * and it is created with the database by `DbProvider`.
 */
@Entity(
    tableName = "card_lists",
    indices = [Index(value = ["name"])],
)
data class CardListEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "is_system") val isSystem: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    companion object {
        /** Row id of the system wishlist, seeded by `DbProvider` (FR-16/FR-17). */
        const val WISHLIST_ID: Long = 1L
    }
}

/**
 * Membership of a card in a list. A card may belong to several lists at once, whether it is owned
 * or not (FR-18); deleting a list cascades here and never reaches [OwnedCard].
 */
@Entity(
    tableName = "card_list_items",
    primaryKeys = ["list_id", "lang", "card_id"],
    foreignKeys = [
        ForeignKey(
            entity = CardListEntity::class,
            parentColumns = ["id"],
            childColumns = ["list_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["lang", "card_id"])],
)
data class CardListItem(
    @ColumnInfo(name = "list_id") val listId: Long,
    @ColumnInfo(name = "lang") val lang: String,
    @ColumnInfo(name = "card_id") val cardId: String,
)

/** Lifecycle of a scan session. Nothing enters the collection before [COMMITTED] (FR-9). */
enum class ScanSessionStatus {
    /** Burst in progress or review not validated yet; offered for resume at launch (EC-13/EC-14). */
    OPEN,

    /** The review screen was validated: the session is history. */
    COMMITTED,

    /** The user dropped the session; its photos are deleted with it. */
    DISCARDED,
}

/** What the matcher concluded for one detection, and what the review screen does with it. */
enum class ScanDetectionState {
    /** Above every threshold of `ScanTuning`: shown in « Reconnues », pre-checked (FR-9). */
    CONFIDENT,

    /** Ambiguous, low confidence, no visual reference or unsupported language: « à vérifier » (FR-8). */
    REVIEW,

    /** Nothing plausible was found; « à vérifier » with the photo and manual search only (EC-6). */
    NO_CANDIDATE,

    /** The identified card is already owned: reported, never written again (FR-10/EC-9). */
    ALREADY_OWNED,

    /** Second capture of a card already detected in this session (FR-7/EC-9). */
    DUPLICATE,

    /** Dismissed by the user in the review screen; its photo is deleted (FR-26). */
    REJECTED,

    /** Validated in the review screen and written to the collection. */
    COMMITTED,
}

/**
 * A scan session: one burst plus its review. Created when the camera opens, kept until the user
 * validates or discards it.
 */
@Entity(
    tableName = "scan_sessions",
    indices = [Index(value = ["status"])],
)
data class ScanSessionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    /** Language assumed when OCR cannot decide the card's own language (FR-5). */
    @ColumnInfo(name = "default_lang") val defaultLang: String,
    @ColumnInfo(name = "status") val status: ScanSessionStatus = ScanSessionStatus.OPEN,
)

/**
 * One capture of the burst, persisted as soon as it is produced so that a kill or an incoming call
 * cannot lose it (EC-13). The candidate list is stored as JSON because it is pure UI payload: the
 * review screen resolves each candidate through the pack.
 */
@Entity(
    tableName = "scan_detections",
    foreignKeys = [
        ForeignKey(
            entity = ScanSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["session_id"])],
)
data class ScanDetectionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    /** App-private JPEG of the warped card, `null` once the photo has been deleted (FR-26). */
    @ColumnInfo(name = "photo_path") val photoPath: String?,
    @ColumnInfo(name = "state") val state: ScanDetectionState,
    /** Language read by OCR, `null` when the script could not be decided (FR-5). */
    @ColumnInfo(name = "detected_lang") val detectedLang: String?,
    /** Pack language of the card the user settled on; `null` while undecided. */
    @ColumnInfo(name = "chosen_lang") val chosenLang: String?,
    @ColumnInfo(name = "chosen_card_id") val chosenCardId: String?,
    /** Serialised top candidates kept for the review screen (3 at most, FR-8). */
    @ColumnInfo(name = "candidates_json") val candidatesJson: String = "[]",
    /** Score of the leading candidate, `null` on the OCR-only path which has no visual score (FR-3). */
    @ColumnInfo(name = "score") val score: Float?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
