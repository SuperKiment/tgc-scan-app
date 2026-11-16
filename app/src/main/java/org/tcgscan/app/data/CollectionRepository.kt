package org.tcgscan.app.data

import android.database.SQLException
import android.util.Log
import androidx.room.withTransaction
import org.tcgscan.app.data.db.AppDatabase
import org.tcgscan.app.data.db.LearnedEmbedding
import org.tcgscan.app.data.db.OwnedCard
import org.tcgscan.app.data.db.ScanDetectionState
import org.tcgscan.app.data.db.ScanSessionStatus
import org.tcgscan.app.data.net.describe
import org.tcgscan.app.data.pack.EmbeddingIndex
import org.tcgscan.app.data.pack.PackPaths
import org.tcgscan.app.scan.ScanPhotoStore
import org.tcgscan.app.scan.ScanTuning

/**
 * The only writer of `owned_cards` (FR-12): the manual toggle of the catalogue ([setOwned]) and the
 * validation of a scan review ([commit]).
 *
 * Becoming owned always drops the card from the wishlist in the same transaction (FR-17/AC-15); the
 * user's other lists keep it (FR-18).
 */
class CollectionRepository(
    private val db: AppDatabase,
    private val photos: ScanPhotoStore,
) {

    /**
     * Manual ownership write of the catalogue (FR-14), always for a card whose physical language is
     * its pack language. Marking a card owned also drops it from the wishlist, in the same
     * transaction (FR-17/AC-15).
     */
    suspend fun setOwned(lang: String, cardId: String, owned: Boolean) {
        db.withTransaction {
            if (owned) {
                db.ownershipDao().insert(
                    OwnedCard(
                        lang = lang,
                        cardId = cardId,
                        // Nothing says otherwise for a card added by hand from the catalogue.
                        physicalLang = lang,
                        addedAt = System.currentTimeMillis(),
                    ),
                )
                db.cardListDao().removeFromWishlist(lang, cardId)
            } else {
                db.ownershipDao().delete(lang, cardId)
            }
        }
    }

    /**
     * Validates a scan review: the only way a scanned card enters the collection (FR-9).
     *
     * Everything is written by one `withTransaction`, or nothing is: each [ReviewDecision.Commit]
     * inserts its [OwnedCard], removes the card from the wishlist only (FR-17/AC-15), marks its
     * detection `COMMITTED` and, when the detection kept an embedding, learns it as an extra visual
     * reference of the chosen card (see [LearnedEmbedding]); each [ReviewDecision.Reject] marks its detection `REJECTED`; the
     * session becomes `COMMITTED`. A detection with no decision stays untouched — an unchecked row
     * adds nothing (FR-9) — and a card already owned is left as it is (FR-10/EC-9): ownership is
     * binary, so its original `added_at` and `physical_lang` win.
     *
     * Only once the transaction has succeeded are the photos of the whole session deleted
     * (FR-26): the rejected and committed ones are no longer needed, and the session is history, so
     * the unchecked ones would only linger. A file that cannot be deleted is logged and never turns
     * a committed review into a failure.
     *
     * Nothing is written when the session is unknown or no longer open.
     *
     * @throws IllegalArgumentException when a decision names a detection outside the session or
     *   outside the three states a review can decide, names one detection twice, or carries a
     *   malformed card key. These are caller bugs, not user situations.
     */
    suspend fun commit(sessionId: Long, decisions: List<ReviewDecision>): CommitResult {
        require(sessionId > 0L) { "Invalid session id $sessionId" }
        require(decisions.map { it.detectionId }.toSet().size == decisions.size) {
            "A detection can only have one decision"
        }
        decisions.forEach { decision ->
            if (decision is ReviewDecision.Commit) {
                require(PackPaths.isValidLang(decision.lang) && decision.cardId.isNotBlank()) {
                    "Invalid card key ${decision.lang}/${decision.cardId}"
                }
                require(PackPaths.isValidLang(decision.physicalLang)) {
                    "Invalid physical language ${decision.physicalLang}"
                }
            }
        }

        val result = try {
            db.withTransaction { writeReview(sessionId, decisions) }
        } catch (e: SQLException) {
            Log.w(TAG, "Review commit of session $sessionId rolled back", e)
            return CommitResult.Failed(e.describe())
        }
        if (result is CommitResult.Success) deletePhotos(sessionId)
        return result
    }

    private suspend fun writeReview(sessionId: Long, decisions: List<ReviewDecision>): CommitResult {
        val sessions = db.scanSessionDao()
        val session = sessions.session(sessionId) ?: return CommitResult.SessionClosed
        if (session.status != ScanSessionStatus.OPEN) return CommitResult.SessionClosed

        val rows = sessions.detections(sessionId).associateBy { it.id }
        decisions.forEach { decision ->
            val row = requireNotNull(rows[decision.detectionId]) {
                "Detection ${decision.detectionId} is not in session $sessionId"
            }
            require(row.state in DECIDABLE_STATES) {
                "Detection ${row.id} is ${row.state} and cannot be decided"
            }
        }

        val now = System.currentTimeMillis()
        var committed = 0
        var rejected = 0
        decisions.forEach { decision ->
            when (decision) {
                is ReviewDecision.Commit -> {
                    db.ownershipDao().insert(
                        OwnedCard(
                            lang = decision.lang,
                            cardId = decision.cardId,
                            physicalLang = decision.physicalLang,
                            addedAt = now,
                        ),
                    )
                    db.cardListDao().removeFromWishlist(decision.lang, decision.cardId)
                    learn(rows.getValue(decision.detectionId).embedding, decision, now)
                    sessions.setDetectionDecision(
                        detectionId = decision.detectionId,
                        state = ScanDetectionState.COMMITTED,
                        chosenLang = decision.lang,
                        chosenCardId = decision.cardId,
                    )
                    committed++
                }

                is ReviewDecision.Reject -> {
                    sessions.setDetectionState(decision.detectionId, ScanDetectionState.REJECTED)
                    rejected++
                }
            }
        }
        sessions.setSessionStatus(sessionId, ScanSessionStatus.COMMITTED)
        return CommitResult.Success(committed = committed, rejected = rejected)
    }

    /**
     * Keeps the embedding of a validated scan as a visual reference of the card, under the pack
     * language of the decision (an English card filed under its French id is learned under `"fr"`),
     * then trims the card to its newest [ScanTuning.LEARNED_VECTORS_PER_CARD]. Runs in the
     * transaction of the review. A detection without an embedding teaches nothing.
     */
    private suspend fun learn(embedding: ByteArray?, decision: ReviewDecision.Commit, now: Long) {
        if (embedding == null || embedding.size != EmbeddingIndex.EXPECTED_DIM) return
        val dao = db.learnedEmbeddingDao()
        dao.insert(
            LearnedEmbedding(
                lang = decision.lang,
                cardId = decision.cardId,
                vector = embedding,
                createdAt = now,
            ),
        )
        dao.trim(decision.lang, decision.cardId, ScanTuning.LEARNED_VECTORS_PER_CARD)
    }

    private suspend fun deletePhotos(sessionId: Long) {
        val sessions = db.scanSessionDao()
        for (row in sessions.detections(sessionId)) {
            val path = row.photoPath ?: continue
            if (photos.delete(path)) {
                sessions.clearDetectionPhoto(row.id)
            } else {
                Log.w(TAG, "Cannot delete the photo of detection ${row.id}")
            }
        }
        if (!photos.deleteSession(sessionId)) {
            Log.w(TAG, "Cannot delete the photo directory of session $sessionId")
        }
    }

    private companion object {

        const val TAG = "CollectionRepository"

        /** The states a review can still decide; the others are read-only reports (FR-10/EC-9). */
        val DECIDABLE_STATES = setOf(
            ScanDetectionState.CONFIDENT,
            ScanDetectionState.REVIEW,
            ScanDetectionState.NO_CANDIDATE,
        )
    }
}

/** What the user decided for one detection of the review screen. */
sealed interface ReviewDecision {

    val detectionId: Long

    /**
     * Enters the collection as `(lang, cardId)`. [physicalLang] is the language printed on the
     * cardboard: the pack language in the normal case, `"en"` for an English card filed under its
     * French id (FR-11).
     */
    data class Commit(
        override val detectionId: Long,
        val lang: String,
        val cardId: String,
        val physicalLang: String = lang,
    ) : ReviewDecision

    /** Dismissed by the user; nothing enters the collection and the photo goes (FR-26). */
    data class Reject(override val detectionId: Long) : ReviewDecision
}

/** Outcome of [CollectionRepository.commit]. */
sealed interface CommitResult {

    /** Written; the counts only include the decisions that were applied. */
    data class Success(val committed: Int, val rejected: Int) : CommitResult

    /** The session was deleted, discarded or already validated: nothing was written. */
    data object SessionClosed : CommitResult

    /** The transaction failed and was rolled back; nothing was written. */
    data class Failed(val message: String) : CommitResult
}
