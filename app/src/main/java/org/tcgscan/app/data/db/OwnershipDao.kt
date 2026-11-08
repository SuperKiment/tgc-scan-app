package org.tcgscan.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Ownership reads and writes. Set-scoped queries take the card ids of the set as read from the
 * pack, because no card metadata is stored in Room (FR-20, docs/pack-format.md).
 */
@Dao
interface OwnershipDao {

    @Query("SELECT * FROM owned_cards ORDER BY added_at DESC")
    fun observeAll(): Flow<List<OwnedCard>>

    @Query("SELECT * FROM owned_cards WHERE lang = :lang ORDER BY added_at DESC")
    fun observeByLang(lang: String): Flow<List<OwnedCard>>

    /** Owned ids among [cardIds], i.e. the « possédée » badges of one set page (FR-14). */
    @Query("SELECT card_id FROM owned_cards WHERE lang = :lang AND card_id IN (:cardIds)")
    fun observeOwnedIdsIn(lang: String, cardIds: Collection<String>): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM owned_cards WHERE lang = :lang AND card_id = :cardId)")
    fun observeIsOwned(lang: String, cardId: String): Flow<Boolean>

    @Query("SELECT COUNT(*) FROM owned_cards WHERE lang = :lang")
    fun observeCountByLang(lang: String): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM owned_cards WHERE lang = :lang AND card_id = :cardId)")
    suspend fun isOwned(lang: String, cardId: String): Boolean

    /** Full snapshot for the backup export, unknown ids included (FR-27/EC-16/AC-18). */
    @Query("SELECT * FROM owned_cards")
    suspend fun all(): List<OwnedCard>

    /** How many cards of [lang] are owned, for the pack-removal warning (EC-20). */
    @Query("SELECT COUNT(*) FROM owned_cards WHERE lang = :lang")
    suspend fun countByLang(lang: String): Int

    /**
     * Ownership is binary, so an existing row wins: re-validating a card already in the collection
     * keeps its original `added_at` and leaves `physical_lang` alone (FR-12/EC-9).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(card: OwnedCard)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(cards: List<OwnedCard>)

    @Query("DELETE FROM owned_cards WHERE lang = :lang AND card_id = :cardId")
    suspend fun delete(lang: String, cardId: String)

    /** Only used by a confirmed « remplacement » import, inside the import transaction (FR-28). */
    @Query("DELETE FROM owned_cards")
    suspend fun deleteAll()
}
