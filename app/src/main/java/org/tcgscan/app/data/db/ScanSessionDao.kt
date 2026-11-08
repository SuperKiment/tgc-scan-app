package org.tcgscan.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Scan sessions and their detections. Writes here never add anything to the collection: the review
 * screen is the only path into `owned_cards` (FR-9).
 */
@Dao
interface ScanSessionDao {

    @Insert
    suspend fun insertSession(session: ScanSessionEntity): Long

    @Query("SELECT * FROM scan_sessions WHERE id = :sessionId")
    suspend fun session(sessionId: Long): ScanSessionEntity?

    @Query("SELECT * FROM scan_sessions WHERE id = :sessionId")
    fun observeSession(sessionId: Long): Flow<ScanSessionEntity?>

    /** The session to offer for resume after a kill or an exit without validating (EC-13/EC-14). */
    @Query(
        "SELECT * FROM scan_sessions WHERE status = :status ORDER BY started_at DESC LIMIT 1",
    )
    suspend fun latestSessionWithStatus(status: ScanSessionStatus): ScanSessionEntity?

    @Query(
        "SELECT * FROM scan_sessions WHERE status = :status ORDER BY started_at DESC LIMIT 1",
    )
    fun observeLatestSessionWithStatus(status: ScanSessionStatus): Flow<ScanSessionEntity?>

    @Query("UPDATE scan_sessions SET status = :status WHERE id = :sessionId")
    suspend fun setSessionStatus(sessionId: Long, status: ScanSessionStatus)

    /** Deletes the session and, through the foreign key, its detection rows. Photos are files and
     * are deleted by the caller (FR-26). */
    @Query("DELETE FROM scan_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Insert
    suspend fun insertDetection(detection: ScanDetectionEntity): Long

    @Update
    suspend fun updateDetection(detection: ScanDetectionEntity)

    @Query("SELECT * FROM scan_detections WHERE id = :detectionId")
    suspend fun detection(detectionId: Long): ScanDetectionEntity?

    @Query("SELECT * FROM scan_detections WHERE session_id = :sessionId ORDER BY created_at ASC")
    fun observeDetections(sessionId: Long): Flow<List<ScanDetectionEntity>>

    @Query("SELECT * FROM scan_detections WHERE session_id = :sessionId ORDER BY created_at ASC")
    suspend fun detections(sessionId: Long): List<ScanDetectionEntity>

    /** Live counter of the burst, states excluded by the caller (FR-6). */
    @Query("SELECT COUNT(*) FROM scan_detections WHERE session_id = :sessionId")
    fun observeDetectionCount(sessionId: Long): Flow<Int>

    @Query(
        "UPDATE scan_detections SET state = :state, chosen_lang = :chosenLang, " +
            "chosen_card_id = :chosenCardId WHERE id = :detectionId",
    )
    suspend fun setDetectionDecision(
        detectionId: Long,
        state: ScanDetectionState,
        chosenLang: String?,
        chosenCardId: String?,
    )

    @Query("UPDATE scan_detections SET state = :state WHERE id = :detectionId")
    suspend fun setDetectionState(detectionId: Long, state: ScanDetectionState)

    /** Called once the JPEG of a rejected or committed detection has been deleted (FR-26). */
    @Query("UPDATE scan_detections SET photo_path = NULL WHERE id = :detectionId")
    suspend fun clearDetectionPhoto(detectionId: Long)

    @Query("DELETE FROM scan_detections WHERE id = :detectionId")
    suspend fun deleteDetection(detectionId: Long)
}
