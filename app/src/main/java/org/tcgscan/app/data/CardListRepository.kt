package org.tcgscan.app.data

import android.database.SQLException
import kotlinx.coroutines.flow.Flow
import org.tcgscan.app.data.db.CardListDao
import org.tcgscan.app.data.db.CardListEntity
import org.tcgscan.app.data.db.CardListItem
import org.tcgscan.app.data.net.describe

/**
 * The single place where lists are created, renamed, deleted and filled (FR-16/FR-18).
 *
 * Two invariants are enforced here, once, for every caller:
 *  - the wishlist is a system list: it can gain and lose cards like any other, but it is never
 *    renamed nor deleted (FR-16). The guard is doubled — this layer refuses the row, and the DAO
 *    statements carry `is_system = 0` anyway;
 *  - a list only ever holds `(lang, cardId)` references, so deleting a list cascades on
 *    `card_list_items` and can never reach `owned_cards` (FR-18). This class deliberately has no
 *    access to `OwnershipDao`, which makes that property structural rather than a promise.
 *
 * Nothing throws: every mutation answers with a [ListMutationResult] the UI can turn into one
 * French sentence.
 */
class CardListRepository(private val dao: CardListDao) {

    /** Wishlist first, then the user lists by name (FR-16). */
    val lists: Flow<List<CardListEntity>> = dao.observeLists()

    /** Emits `null` once the list is gone, which is how an open detail screen closes itself. */
    fun observeList(listId: Long): Flow<CardListEntity?> = dao.observeList(listId)

    fun observeItems(listId: Long): Flow<List<CardListItem>> = dao.observeItems(listId)

    fun observeItemCount(listId: Long): Flow<Int> = dao.observeItemCount(listId)

    /** Ids of the lists a card belongs to — a card may be in several at once (FR-18). */
    fun observeMembership(lang: String, cardId: String): Flow<List<Long>> =
        dao.observeMembership(lang, cardId)

    /** Creates a user list. A created list is never a system one, whatever its name. */
    suspend fun create(name: String): ListMutationResult {
        val clean = name.trim()
        if (!isValidName(clean)) return ListMutationResult.InvalidName
        return runCatchingSql {
            if (dao.listByName(clean) != null) {
                ListMutationResult.DuplicateName
            } else {
                ListMutationResult.Success(
                    dao.insertList(
                        CardListEntity(
                            name = clean,
                            isSystem = false,
                            createdAt = System.currentTimeMillis(),
                        ),
                    ),
                )
            }
        }
    }

    /** Renames a user list; the wishlist is refused (FR-16). */
    suspend fun rename(listId: Long, name: String): ListMutationResult {
        val clean = name.trim()
        if (!isValidName(clean)) return ListMutationResult.InvalidName
        return runCatchingSql {
            val list = dao.list(listId) ?: return@runCatchingSql ListMutationResult.NotFound
            if (list.isSystem) return@runCatchingSql ListMutationResult.SystemListProtected
            val clash = dao.listByName(clean)
            if (clash != null && clash.id != listId) {
                return@runCatchingSql ListMutationResult.DuplicateName
            }
            if (dao.renameUserList(listId, clean) == 0) {
                ListMutationResult.NotFound
            } else {
                ListMutationResult.Success(listId)
            }
        }
    }

    /**
     * Deletes a user list and its membership rows only: the cards stay in the catalogue, in the
     * other lists they belong to, and in the collection (FR-18). The wishlist is refused (FR-16).
     */
    suspend fun delete(listId: Long): ListMutationResult = runCatchingSql {
        val list = dao.list(listId) ?: return@runCatchingSql ListMutationResult.NotFound
        if (list.isSystem) return@runCatchingSql ListMutationResult.SystemListProtected
        if (dao.deleteUserList(listId) == 0) {
            ListMutationResult.NotFound
        } else {
            ListMutationResult.Success(listId)
        }
    }

    /**
     * Adds a card to any list, the wishlist included (FR-16). Adding a card already in the list is
     * a no-op success, so a double tap cannot fail.
     */
    suspend fun addCard(listId: Long, lang: String, cardId: String): ListMutationResult {
        if (lang.isBlank() || cardId.isBlank()) return ListMutationResult.InvalidCard
        return runCatchingSql {
            if (dao.list(listId) == null) return@runCatchingSql ListMutationResult.NotFound
            dao.addItem(CardListItem(listId = listId, lang = lang, cardId = cardId))
            ListMutationResult.Success(listId)
        }
    }

    /** Removes a card from one list; its other lists and its ownership are untouched (FR-18). */
    suspend fun removeCard(listId: Long, lang: String, cardId: String): ListMutationResult =
        runCatchingSql {
            dao.removeItem(listId, lang, cardId)
            ListMutationResult.Success(listId)
        }

    private inline fun runCatchingSql(block: () -> ListMutationResult): ListMutationResult =
        try {
            block()
        } catch (e: SQLException) {
            ListMutationResult.Failed(e.describe())
        }

    companion object {

        /** Long enough for any sensible label, short enough to stay readable in a row. */
        const val MAX_NAME_LENGTH: Int = 60

        fun isValidName(name: String): Boolean =
            name.isNotBlank() && name.trim().length <= MAX_NAME_LENGTH
    }
}

/** Typed outcome of every [CardListRepository] mutation; failures mean « nothing changed ». */
sealed interface ListMutationResult {

    /** The mutation was applied; [listId] is the list it concerned. */
    data class Success(val listId: Long) : ListMutationResult

    /** Empty name, or one longer than [CardListRepository.MAX_NAME_LENGTH]. */
    data object InvalidName : ListMutationResult

    /** Another list already carries that name. */
    data object DuplicateName : ListMutationResult

    /** The list does not exist any more. */
    data object NotFound : ListMutationResult

    /** Rename or delete asked for the wishlist: refused (FR-16). */
    data object SystemListProtected : ListMutationResult

    /** A `(lang, cardId)` pair that cannot reference a card. */
    data object InvalidCard : ListMutationResult

    /** The write failed; the database rolled it back. */
    data class Failed(val message: String) : ListMutationResult
}
