package org.tcgscan.app.ui.catalog

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tcgscan.app.data.CollectionRepository
import org.tcgscan.app.data.db.AppDatabase
import org.tcgscan.app.data.db.CardListEntity
import org.tcgscan.app.data.db.CardListItem
import org.tcgscan.app.data.db.DbProvider
import org.tcgscan.app.data.pack.PackCard
import org.tcgscan.app.data.pack.PackSet
import org.tcgscan.app.scan.ScanPhotoStore

private const val TAG_CARD_DETAIL = "CardDetailViewModel"

/**
 * One card sheet (FR-15): visual, name, numbering, set, language, ownership and list memberships.
 *
 * The card itself is read once from the pack; ownership and list membership are Room Flows, so a
 * toggle is reflected without a refresh. When the id is not in the installed pack the sheet still
 * opens, as « carte inconnue » with the raw id: the possession and the list entries are the user's
 * data and survive a pack that dropped the card or was never installed (EC-16/EC-18).
 */
class CardDetailViewModel(
    private val lang: String,
    private val cardId: String,
    private val catalogs: PackCatalogs,
    private val db: AppDatabase,
    private val collection: CollectionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CardDetailUiState(lang = lang, cardId = cardId))
    val state: StateFlow<CardDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val content = readCard()
            _state.update {
                it.copy(loading = false, card = content?.card, set = content?.set)
            }
        }
        viewModelScope.launch {
            db.ownershipDao().observeIsOwned(lang, cardId).collect { owned ->
                _state.update { it.copy(owned = owned) }
            }
        }
        viewModelScope.launch {
            db.cardListDao().observeLists().collect { lists ->
                _state.update { it.copy(lists = lists) }
            }
        }
        viewModelScope.launch {
            db.cardListDao().observeMembership(lang, cardId).collect { listIds ->
                _state.update { it.copy(memberOf = listIds.toSet()) }
            }
        }
    }

    /** Manual ownership toggle of the sheet (FR-14). */
    fun toggleOwned() {
        val owned = _state.value.owned
        viewModelScope.launch {
            collection.setOwned(lang, cardId, owned = !owned)
        }
    }

    /** Adds the card to, or removes it from, one list (FR-15/FR-18). */
    fun setInList(listId: Long, member: Boolean) {
        viewModelScope.launch {
            val dao = db.cardListDao()
            if (member) {
                dao.addItem(CardListItem(listId = listId, lang = lang, cardId = cardId))
            } else {
                dao.removeItem(listId = listId, lang = lang, cardId = cardId)
            }
        }
    }

    /** « Ajouter à la wishlist » — the system list of FR-16. */
    fun addToWishlist() {
        setInList(CardListEntity.WISHLIST_ID, member = true)
    }

    override fun onCleared() {
        catalogs.close()
    }

    /** `null` when the pack is missing or unreadable, or when it has no such card (EC-16). */
    private suspend fun readCard(): CardContent? = withContext(Dispatchers.IO) {
        val catalog = catalogs.of(lang) ?: return@withContext null
        try {
            val card = catalog.card(cardId) ?: return@withContext null
            CardContent(card, catalog.set(card.setId))
        } catch (e: Exception) {
            Log.w(TAG_CARD_DETAIL, "cannot read card $cardId of pack $lang", e)
            null
        }
    }

    private class CardContent(val card: PackCard, val set: PackSet?)

    companion object {

        /** Ad-hoc wiring until the dependency container of T31 owns these singletons. */
        fun factory(context: Context, lang: String, cardId: String): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(CardDetailViewModel::class.java)) {
                        "CardDetailViewModel.factory asked for $modelClass"
                    }
                    val db = DbProvider.get(appContext)
                    @Suppress("UNCHECKED_CAST")
                    return CardDetailViewModel(
                        lang = lang,
                        cardId = cardId,
                        catalogs = PackCatalogs(appContext),
                        db = db,
                        collection = CollectionRepository(db, ScanPhotoStore(appContext)),
                    ) as T
                }
            }
        }
    }
}

/** Everything the card sheet draws. */
data class CardDetailUiState(
    val lang: String,
    val cardId: String,
    val loading: Boolean = true,
    /** `null` once [loading] is false means « carte inconnue » (EC-16/EC-18). */
    val card: PackCard? = null,
    /** The card's set, source of the printed denominator of « 136/201 » (D12). */
    val set: PackSet? = null,
    val owned: Boolean = false,
    /** The wishlist first, then the user lists (FR-16). */
    val lists: List<CardListEntity> = emptyList(),
    val memberOf: Set<Long> = emptySet(),
) {

    /** True when the id resolves to no card of the installed pack. */
    val unknownCard: Boolean get() = !loading && card == null

    val inWishlist: Boolean get() = CardListEntity.WISHLIST_ID in memberOf
}
