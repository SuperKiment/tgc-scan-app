package org.tcgscan.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** The visual references learned from validated scans; see [LearnedEmbedding]. */
@Dao
interface LearnedEmbeddingDao {

    @Insert
    suspend fun insert(embedding: LearnedEmbedding): Long

    /** Keeps the [keep] newest vectors of `(lang, cardId)` and deletes the older ones. */
    @Query(
        "DELETE FROM learned_embeddings WHERE lang = :lang AND card_id = :cardId AND id NOT IN (" +
            "SELECT id FROM learned_embeddings WHERE lang = :lang AND card_id = :cardId " +
            "ORDER BY id DESC LIMIT :keep)",
    )
    suspend fun trim(lang: String, cardId: String, keep: Int)

    /** Every vector of [lang], the matcher's source. */
    @Query("SELECT * FROM learned_embeddings WHERE lang = :lang")
    suspend fun allForLang(lang: String): List<LearnedEmbedding>

    /** Live number of learned vectors, shown in Settings. */
    @Query("SELECT COUNT(*) FROM learned_embeddings")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM learned_embeddings")
    suspend fun deleteAll()
}
