package org.tcgscan.app.ui.lists

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tcgscan.app.R
import org.tcgscan.app.data.CardListRepository
import org.tcgscan.app.data.ListMutationResult
import org.tcgscan.app.data.db.CardListEntity
import org.tcgscan.app.data.db.CardListItem
import org.tcgscan.app.data.db.DbProvider
import org.tcgscan.app.data.db.OwnershipDao
import org.tcgscan.app.data.pack.PackCard
import org.tcgscan.app.data.pack.PackCatalog

/**
 * State holders of the lists area: [ListsViewModel] for the wishlist + user lists index,
 * [ListDetailViewModel] for the cards of one list. They share this file because they share the
 * repository, the message type and the « nothing was changed » error mapping.
 *
 * Both only ever touch `card_list*` rows: removing a card from a list, or deleting a list, leaves
 * the collection exactly as it was (FR-18).
 */
class ListsViewModel(private val repository: CardListRepository) : ViewModel() {

    private val dialog = MutableStateFlow<ListsDialog?>(null)
    private val message = MutableStateFlow<ListsMessage?>(null)

    val state: StateFlow<ListsUiState> = combine(rows(), dialog, message) { rows, dialog, message ->
        ListsUiState(loading = false, lists = rows, dialog = dialog, message = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ListsUiState())

    fun openCreateDialog() {
        dialog.value = ListsDialog.Create
    }

    /** No-op for the wishlist: it is never renamed (FR-16). */
    fun openRenameDialog(row: ListRow) {
        if (row.isSystem) return
        dialog.value = ListsDialog.Rename(row.id, row.name)
    }

    /** No-op for the wishlist: it is never deleted (FR-16). */
    fun openDeleteDialog(row: ListRow) {
        if (row.isSystem) return
        dialog.value = ListsDialog.Delete(row.id, row.name)
    }

    fun dismissDialog() {
        dialog.value = null
    }

    fun create(name: String) = mutate { repository.create(name) }

    fun rename(listId: Long, name: String) = mutate { repository.rename(listId, name) }

    /** Deletes the list and its membership rows only; the possessions are untouched (FR-18). */
    fun delete(listId: Long) = mutate(R.string.lists_message_deleted) { repository.delete(listId) }

    fun dismissMessage() {
        message.value = null
    }

    private fun mutate(
        @StringRes successRes: Int? = null,
        block: suspend () -> ListMutationResult,
    ) {
        dialog.value = null
        viewModelScope.launch {
            val result = block()
            if (result !is ListMutationResult.Success) Log.w(TAG, "list mutation refused: $result")
            message.value = when (result) {
                is ListMutationResult.Success -> successRes?.let { ListsMessage(it) }
                else -> result.errorMessage()
            }
        }
    }

    /**
     * One row per list with its card count. The counts are separate `COUNT(*)` flows because the
     * DAO exposes membership per list; the number of lists is small and user-driven.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun rows(): Flow<List<ListRow>> = repository.lists.flatMapLatest { lists ->
        if (lists.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(lists.map { list -> repository.observeItemCount(list.id).map { list.toRow(it) } }) {
                it.toList()
            }
        }
    }

    private fun CardListEntity.toRow(cardCount: Int) = ListRow(
        id = id,
        name = name,
        isSystem = isSystem,
        cardCount = cardCount,
    )

    companion object {

        private const val TAG = "ListsViewModel"

        /** Ad-hoc wiring until the dependency container of T31 owns the database. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ListsViewModel::class.java)) {
                        "ListsViewModel.factory asked for $modelClass"
                    }
                    @Suppress("UNCHECKED_CAST")
                    return ListsViewModel(
                        CardListRepository(DbProvider.get(appContext).cardListDao()),
                    ) as T
                }
            }
        }
    }
}

/**
 * The cards of one list, each resolved through the installed pack and badged with its ownership.
 *
 * The card metadata comes from the pack and the badge from Room, as everywhere else: an id the pack
 * does not know is kept and rendered as « carte inconnue » instead of being dropped (EC-16).
 */
class ListDetailViewModel(
    private val listId: Long,
    private val repository: CardListRepository,
    private val ownershipDao: OwnershipDao,
    private val appContext: Context,
) : ViewModel() {

    private val message = MutableStateFlow<ListsMessage?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ListDetailUiState> = combine(
        repository.observeList(listId),
        repository.observeItems(listId),
    ) { list, items -> list to items }
        .flatMapLatest { (list, items) ->
            val resolved = resolve(items)
            ownedKeys(items).map { owned ->
                ListDetailUiState(
                    loading = false,
                    // Null once the list has been deleted elsewhere: the screen closes itself.
                    listName = list?.name,
                    isSystem = list?.isSystem == true,
                    exists = list != null,
                    cards = resolved.map { it.copy(owned = CardKey(it.lang, it.cardId) in owned) },
                )
            }
        }
        .combine(message) { state, message -> state.copy(message = message) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ListDetailUiState())

    /** Removes the card from this list only: its other lists and its ownership stay (FR-18). */
    fun removeCard(lang: String, cardId: String) {
        viewModelScope.launch {
            val result = repository.removeCard(listId, lang, cardId)
            if (result !is ListMutationResult.Success) {
                Log.w(TAG, "removal refused: $result")
                message.value = result.errorMessage()
            }
        }
    }

    fun dismissMessage() {
        message.value = null
    }

    /**
     * Resolves the `(lang, cardId)` references against the packs, one open pack per language of the
     * list. A missing or unreadable pack simply yields unknown cards (EC-1/EC-16).
     */
    private suspend fun resolve(items: List<CardListItem>): List<ListCardRow> =
        withContext(Dispatchers.IO) {
            val rows = ArrayList<ListCardRow>(items.size)
            items.groupBy { it.lang }.forEach { (lang, langItems) ->
                val cards = cardsOf(lang, langItems.map { it.cardId })
                langItems.forEach { item ->
                    val card = cards.cards[item.cardId]
                    rows += ListCardRow(
                        lang = lang,
                        cardId = item.cardId,
                        name = card?.name,
                        localId = card?.localId,
                        setName = card?.let { cards.setNames[it.setId] },
                        imageUrl = card?.imageUrl,
                    )
                }
            }
            rows.inDisplayOrder()
        }

    private fun cardsOf(lang: String, cardIds: List<String>): ResolvedCards {
        val catalog = runCatching { PackCatalog.open(appContext, lang) }.getOrNull()
            ?: return ResolvedCards.EMPTY
        return catalog.use { pack ->
            runCatching {
                val cards = pack.cardsByIds(cardIds)
                val setNames = cards.values.map { it.setId }.distinct()
                    .mapNotNull { setId -> pack.set(setId)?.let { setId to it.name } }
                    .toMap()
                ResolvedCards(cards, setNames)
            }.getOrDefault(ResolvedCards.EMPTY)
        }
    }

    /**
     * Ownership badges, straight from Room so the list reacts to a scan validation or a manual
     * toggle without a refresh. Queried per language and in chunks, to stay under SQLite's
     * bound-parameter limit on a long wishlist.
     */
    private fun ownedKeys(items: List<CardListItem>): Flow<Set<CardKey>> {
        val queries = items.groupBy { it.lang }.flatMap { (lang, langItems) ->
            langItems.map { it.cardId }.distinct()
                .chunked(OWNED_QUERY_CHUNK)
                .map { chunk -> lang to chunk }
        }
        if (queries.isEmpty()) return flowOf(emptySet())
        return combine(
            queries.map { (lang, chunk) ->
                ownershipDao.observeOwnedIdsIn(lang, chunk)
                    .map { owned -> owned.map { CardKey(lang, it) } }
            },
        ) { chunks -> chunks.flatMap { it }.toSet() }
    }

    /** By set, then by printed number; ids unknown to the pack come last (EC-16). */
    private fun List<ListCardRow>.inDisplayOrder(): List<ListCardRow> = sortedWith(
        compareBy<ListCardRow>(
            { it.name == null },
            { it.setName ?: "" },
            { it.localId?.toIntOrNull() ?: Int.MAX_VALUE },
            { it.localId ?: it.cardId },
        ),
    )

    private class ResolvedCards(
        val cards: Map<String, PackCard>,
        val setNames: Map<String, String>,
    ) {
        companion object {
            val EMPTY = ResolvedCards(emptyMap(), emptyMap())
        }
    }

    /** `(lang, cardId)`, the key of a card everywhere in the app. */
    private data class CardKey(val lang: String, val cardId: String)

    companion object {

        private const val TAG = "ListDetailViewModel"

        /** SQLite binds at most 999 parameters; the DAO query spends one per id. */
        private const val OWNED_QUERY_CHUNK = 500

        /** Ad-hoc wiring until the dependency container of T31 owns the database. */
        fun factory(context: Context, listId: Long): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ListDetailViewModel::class.java)) {
                        "ListDetailViewModel.factory asked for $modelClass"
                    }
                    val db = DbProvider.get(appContext)
                    @Suppress("UNCHECKED_CAST")
                    return ListDetailViewModel(
                        listId = listId,
                        repository = CardListRepository(db.cardListDao()),
                        ownershipDao = db.ownershipDao(),
                        appContext = appContext,
                    ) as T
                }
            }
        }
    }
}

/** Everything the lists index draws. */
data class ListsUiState(
    val loading: Boolean = true,
    val lists: List<ListRow> = emptyList(),
    val dialog: ListsDialog? = null,
    val message: ListsMessage? = null,
) {

    /** True once the user has no list of their own: the wishlist alone is not an « empty » state. */
    val hasNoUserList: Boolean get() = !loading && lists.none { !it.isSystem }
}

/** One row of the lists index. [isSystem] rows offer neither rename nor delete (FR-16). */
data class ListRow(
    val id: Long,
    val name: String,
    val isSystem: Boolean,
    val cardCount: Int,
)

/** The three dialogs of the index; only one is open at a time. */
sealed interface ListsDialog {

    data object Create : ListsDialog

    data class Rename(val listId: Long, val currentName: String) : ListsDialog

    data class Delete(val listId: Long, val name: String) : ListsDialog
}

/** Everything the list detail draws. */
data class ListDetailUiState(
    val loading: Boolean = true,
    val listName: String? = null,
    val isSystem: Boolean = false,
    /** False once the list has been deleted while the screen was open. */
    val exists: Boolean = true,
    val cards: List<ListCardRow> = emptyList(),
    val message: ListsMessage? = null,
)

/**
 * One card of a list. [name] and [localId] are `null` for an id the installed pack does not know,
 * which the screen renders as « carte inconnue » with the raw id (EC-16).
 */
data class ListCardRow(
    val lang: String,
    val cardId: String,
    val name: String?,
    val localId: String?,
    val setName: String?,
    val imageUrl: String?,
    val owned: Boolean = false,
)

/** A localised one-liner for the snackbar. [args] are positional format arguments. */
data class ListsMessage(
    @StringRes val textRes: Int,
    val args: List<Any> = emptyList(),
)

/**
 * The sentence shown when a mutation was refused, `null` for a success. Every failure says that
 * nothing changed; the technical detail stays in the log.
 */
private fun ListMutationResult.errorMessage(): ListsMessage? = when (this) {
    is ListMutationResult.Success -> null
    ListMutationResult.InvalidName -> ListsMessage(R.string.lists_error_name_invalid)
    ListMutationResult.DuplicateName -> ListsMessage(R.string.lists_error_name_duplicate)
    ListMutationResult.NotFound -> ListsMessage(R.string.lists_error_not_found)
    ListMutationResult.SystemListProtected -> ListsMessage(R.string.lists_error_system)
    ListMutationResult.InvalidCard -> ListsMessage(R.string.lists_error_card)
    is ListMutationResult.Failed -> ListsMessage(R.string.lists_error_write)
}

/** Flows stay collected across a configuration change, then stop. */
private const val STOP_TIMEOUT_MS = 5_000L
