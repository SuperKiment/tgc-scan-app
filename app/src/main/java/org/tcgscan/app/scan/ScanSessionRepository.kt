package org.tcgscan.app.scan

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.opencv.core.Mat
import org.tcgscan.app.data.db.OwnershipDao
import org.tcgscan.app.data.db.ScanDetectionEntity
import org.tcgscan.app.data.db.ScanDetectionState
import org.tcgscan.app.data.db.ScanSessionDao
import org.tcgscan.app.data.db.ScanSessionEntity
import org.tcgscan.app.data.db.ScanSessionStatus
import org.tcgscan.app.scan.match.Candidate
import org.tcgscan.app.scan.match.MatchOutcome
import org.tcgscan.app.scan.text.CardScript

/**
 * Persistence of a scan session and of every capture of its burst (EC-13).
 *
 * A detection is written as soon as it is produced, photo included: an incoming call or a killed
 * process in the middle of a burst must cost nothing, and the session is offered for resume at the
 * next launch (EC-13/EC-14).
 *
 * **Nothing here enters the collection.** [ownership] is read — and only read — to flag a card the
 * user already owns (FR-10); `owned_cards` is written by the review screen alone, which is the only
 * validation the spec allows (FR-9).
 */
class ScanSessionRepository(
    private val sessions: ScanSessionDao,
    private val ownership: OwnershipDao,
    private val photos: ScanPhotoStore,
) {

    /** Opens a new burst and returns its id. */
    suspend fun startSession(defaultLang: String): Long {
        require(defaultLang.isNotBlank()) { "A scan session needs a default language" }
        return sessions.insertSession(
            ScanSessionEntity(
                startedAt = System.currentTimeMillis(),
                defaultLang = defaultLang,
                status = ScanSessionStatus.OPEN,
            ),
        )
    }

    /**
     * The unfinished session to offer for resume, or `null` when there is none (EC-13/EC-14).
     *
     * A session stays open until the review screen validates or the user discards it, so leaving
     * the review without validating keeps everything and writes nothing (EC-14).
     */
    suspend fun activeSession(): ScanSessionEntity? =
        sessions.latestSessionWithStatus(ScanSessionStatus.OPEN)

    /** [activeSession] as a Flow, for the resume banner of the scan screen. */
    fun observeActiveSession(): Flow<ScanSessionEntity?> =
        sessions.observeLatestSessionWithStatus(ScanSessionStatus.OPEN)

    /** One session by id, `null` when it was deleted. */
    suspend fun session(sessionId: Long): ScanSessionEntity? = sessions.session(sessionId)

    /** The captures of a session, oldest first; the review screen's source (FR-8). */
    fun observeDetections(sessionId: Long): Flow<List<ScanDetectionEntity>> =
        sessions.observeDetections(sessionId)

    /** Live row count of the burst; the scan screen filters the states it does not count (FR-6). */
    fun observeDetectionCount(sessionId: Long): Flow<Int> = sessions.observeDetectionCount(sessionId)

    /** The captures of a session as a snapshot. */
    suspend fun detections(sessionId: Long): List<ScanDetectionEntity> = sessions.detections(sessionId)

    /**
     * Persists one capture with its candidates and its photo, and returns the row id.
     *
     * The state comes from the matcher's verdict: [MatchOutcome.Confident] is pre-checked
     * (« Reconnue », FR-9) unless the card is already owned, in which case it is reported and
     * nothing else happens to it (FR-10); everything else lands in « à vérifier » (FR-8).
     *
     * @param outcome what `Matcher.match` concluded.
     * @param script the language the OCR established, stored as `detected_lang` (FR-5).
     * @param cardRgb the perspective-corrected card to keep as the review photo (FR-26), or `null`
     *   to store no photo.
     */
    suspend fun recordDetection(
        sessionId: Long,
        outcome: MatchOutcome,
        script: CardScript,
        cardRgb: Mat?,
    ): Long {
        require(sessionId > 0L) { "Invalid session id $sessionId" }
        val chosen = chosenCandidate(outcome)
        val row = ScanDetectionEntity(
            sessionId = sessionId,
            photoPath = null,
            state = stateOf(outcome, chosen),
            detectedLang = detectedLangOf(script),
            chosenLang = chosen?.lang,
            chosenCardId = chosen?.cardId,
            candidatesJson = encodeCandidates(outcome.candidates),
            // T4 documents `score` as the leading candidate's score and as null when that candidate
            // has no visual evidence at all, which is the OCR-only path of FR-3.
            score = outcome.candidates.firstOrNull()?.takeIf { it.isVisual }?.score,
            createdAt = System.currentTimeMillis(),
        )
        val detectionId = sessions.insertDetection(row)
        // The photo is named after its row, so it can only be written once the row exists; its path
        // is then stored by a second write. Both happen before the capture is reported, so a
        // detection is never visible without its persisted photo (EC-13).
        val photoPath = cardRgb?.let { photos.save(sessionId, detectionId, it) }
        if (photoPath != null) {
            sessions.updateDetection(row.copy(id = detectionId, photoPath = photoPath))
        }
        return detectionId
    }

    /**
     * Persists a capture the [DedupTracker] recognised as a second take of [ofDetectionId]
     * (FR-7/EC-9).
     *
     * The row carries no candidate and no photo: the card is already in the session with both, and
     * this one only records that the burst saw it again, so the review screen can say so instead of
     * silently swallowing a capture the user watched happen.
     */
    suspend fun recordDuplicate(sessionId: Long, ofDetectionId: Long, script: CardScript): Long {
        require(sessionId > 0L) { "Invalid session id $sessionId" }
        val original = sessions.detection(ofDetectionId)
        return sessions.insertDetection(
            ScanDetectionEntity(
                sessionId = sessionId,
                photoPath = null,
                state = ScanDetectionState.DUPLICATE,
                detectedLang = detectedLangOf(script),
                // Carried over so the row names the card it repeats; it stays unchecked and the
                // review screen commits nothing from it (FR-9).
                chosenLang = original?.chosenLang,
                chosenCardId = original?.chosenCardId,
                score = null,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Drops a session: it stops being offered for resume and its photos are deleted (FR-26).
     *
     * The status is written first, so a process killed in the middle of the deletion cannot leave
     * an open session pointing at photos that are already gone.
     */
    suspend fun discard(sessionId: Long) {
        sessions.setSessionStatus(sessionId, ScanSessionStatus.DISCARDED)
        for (detection in sessions.detections(sessionId)) {
            val path = detection.photoPath ?: continue
            if (photos.delete(path)) {
                sessions.clearDetectionPhoto(detection.id)
            } else {
                Log.w(TAG, "Cannot delete the photo of detection ${detection.id}")
            }
        }
        if (!photos.deleteSession(sessionId)) {
            Log.w(TAG, "Cannot delete the photo directory of session $sessionId")
        }
    }

    /**
     * The card the row opens on: the pre-checked card of a confident match, or the cursor the
     * matcher placed on a review row (FR-3/FR-8). For a review row it is **not** a decision —
     * `state` says so, and only the review screen validates (FR-9).
     */
    private fun chosenCandidate(outcome: MatchOutcome): Candidate? = when (outcome) {
        is MatchOutcome.Confident -> outcome.card
        is MatchOutcome.Review -> outcome.preSelected
        is MatchOutcome.NoCandidate -> null
    }

    private suspend fun stateOf(outcome: MatchOutcome, chosen: Candidate?): ScanDetectionState =
        when (outcome) {
            is MatchOutcome.Confident ->
                // Already in the collection: reported, never written again (FR-10/EC-9).
                if (chosen != null && ownership.isOwned(chosen.lang, chosen.cardId)) {
                    ScanDetectionState.ALREADY_OWNED
                } else {
                    ScanDetectionState.CONFIDENT
                }

            is MatchOutcome.Review -> ScanDetectionState.REVIEW
            is MatchOutcome.NoCandidate -> ScanDetectionState.NO_CANDIDATE
        }

    /**
     * `detected_lang` of a reading: the TCGdex code of the script the OCR established, `"en"` for
     * an English card filed under its French id (FR-11), and `null` when nothing could be decided —
     * the session default then applies (FR-5).
     */
    private fun detectedLangOf(script: CardScript): String? =
        if (script.isKnown) script.name.lowercase() else null

    companion object {

        private const val TAG = "ScanSessionRepository"

        private val json = Json { ignoreUnknownKeys = true }

        private val candidatesSerializer = ListSerializer(Candidate.serializer())

        /** Serialises the candidates of a detection into `scan_detections.candidates_json`. */
        fun encodeCandidates(candidates: List<Candidate>): String =
            json.encodeToString(candidatesSerializer, candidates)

        /**
         * Reads back the candidates of a detection row.
         *
         * A payload this build cannot parse — a row written before an app update — yields an empty
         * list rather than an exception: the review row still has its photo and its manual search,
         * which is what « à vérifier » needs (FR-8/EC-13).
         */
        fun decodeCandidates(candidatesJson: String): List<Candidate> {
            if (candidatesJson.isBlank()) return emptyList()
            return try {
                json.decodeFromString(candidatesSerializer, candidatesJson)
            } catch (e: SerializationException) {
                Log.w(TAG, "Unreadable candidates payload, falling back to manual search", e)
                emptyList()
            }
        }
    }
}
