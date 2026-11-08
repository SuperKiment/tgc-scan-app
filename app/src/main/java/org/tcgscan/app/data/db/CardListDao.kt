package org.tcgscan.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Wishlist and custom lists: list CRUD plus membership. Every write that must not touch the system
 * list carries `is_system = 0` in its `WHERE` clause, so the guard holds even if a caller forgets
 * it; the repository of T19 turns the resulting « 0 rows » into an explicit error (FR-16/FR-18).
 */
@Dao
interface CardListDao {

    /** Wishlist first, then user lists by name (FR-16). */
    @Query("SELECT * FROM card_lists ORDER BY is_system DESC, name COLLATE NOCASE ASC")
    fun observeLists(): Flow<List<CardListEntity>>

    @Query("SELECT * FROM card_lists WHERE id = :listId")
    fun observeList(listId: Long): Flow<CardListEntity?>

    @Query("SELECT * FROM card_lists WHERE id = :listId")
    suspend fun list(listId: Long): CardListEntity?

    /** Used by a « fusion » import to reuse an existing list of the same name (FR-28). */
    @Query("SELECT * FROM card_lists WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun listByName(name: String): CardListEntity?

    @Query("SELECT * FROM card_lists")
    suspend fun allLists(): List<CardListEntity>

    @Insert
    suspend fun insertList(list: CardListEntity): Long

    /** Returns the number of rows renamed: 0 for the system list or an unknown id. */
    @Query("UPDATE card_lists SET name = :name WHERE id = :listId AND is_system = 0")
    suspend fun renameUserList(listId: Long, name: String): Int

    /**
     * Returns the number of rows deleted: 0 for the system list or an unknown id. Items of the list
     * go with it through the foreign key; ownership rows live in another table and are untouched
     * (FR-18).
     */
    @Query("DELETE FROM card_lists WHERE id = :listId AND is_system = 0")
    suspend fun deleteUserList(listId: Long): Int

    /** Only used by a confirmed « remplacement » import; the wishlist survives it (FR-28/FR-16). */
    @Query("DELETE FROM card_lists WHERE is_system = 0")
    suspend fun deleteAllUserLists()

    @Query("SELECT * FROM card_list_items WHERE list_id = :listId")
    fun observeItems(listId: Long): Flow<List<CardListItem>>

    @Query("SELECT * FROM card_list_items WHERE list_id = :listId")
    suspend fun items(listId: Long): List<CardListItem>

    @Query("SELECT * FROM card_list_items")
    suspend fun allItems(): List<CardListItem>

    @Query("SELECT COUNT(*) FROM card_list_items WHERE list_id = :listId")
    fun observeItemCount(listId: Long): Flow<Int>

    /** Lists a card belongs to, for the card detail screen (FR-15). */
    @Query("SELECT list_id FROM card_list_items WHERE lang = :lang AND card_id = :cardId")
    fun observeMembership(lang: String, cardId: String): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItem(item: CardListItem)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItems(items: List<CardListItem>)

    @Query(
        "DELETE FROM card_list_items WHERE list_id = :listId AND lang = :lang AND card_id = :cardId",
    )
    suspend fun removeItem(listId: Long, lang: String, cardId: String)

    /**
     * Removal from the wishlist only, applied when a card becomes owned: its other lists keep it
     * (FR-17/AC-15).
     */
    @Query(
        "DELETE FROM card_list_items " +
            "WHERE list_id = ${CardListEntity.WISHLIST_ID} AND lang = :lang AND card_id = :cardId",
    )
    suspend fun removeFromWishlist(lang: String, cardId: String)
}
