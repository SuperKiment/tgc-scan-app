package org.tcgscan.app.data

import kotlinx.coroutines.flow.Flow
import org.tcgscan.app.data.db.LearnedEmbeddingDao
import org.tcgscan.app.data.pack.EmbeddingIndex

/**
 * Read side and reset of the visual references learned on the device (`learned_embeddings`).
 * They are written by `CollectionRepository.commit`, in the transaction of the validation.
 *
 * Nothing is cached: the scan pipeline asks for the indexes on every capture, so a validation or a
 * [forget] is taken into account by the very next one with no invalidation to get wrong. The cost
 * is one small query (3 vectors of 384 bytes per validated card) next to an embedding and an OCR
 * pass.
 */
class LearnedEmbeddingRepository(private val dao: LearnedEmbeddingDao) {

    /** Number of learned vectors, live, for Settings. */
    fun observeCount(): Flow<Int> = dao.observeCount()

    /** Deletes every learned vector; the cards go back to their pack references only. */
    suspend fun forget() = dao.deleteAll()

    /**
     * The learned index of each language of [langs] that has at least one vector. Cheap enough to
     * rebuild per capture (see the class comment); the indexes are heap-backed.
     */
    suspend fun indexesFor(langs: Collection<String>): Map<String, EmbeddingIndex> {
        val indexes = HashMap<String, EmbeddingIndex>()
        for (lang in langs) {
            val rows = dao.allForLang(lang)
            if (rows.isEmpty()) continue
            indexes[lang] = EmbeddingIndex.fromVectors(lang, rows.map { it.cardId to it.vector })
        }
        return indexes
    }
}
