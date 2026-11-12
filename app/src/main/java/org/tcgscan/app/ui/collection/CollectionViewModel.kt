package org.tcgscan.app.ui.collection

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.tcgscan.app.data.db.OwnedCard
import org.tcgscan.app.data.db.OwnershipDao
import org.tcgscan.app.data.pack.InstalledPack
import org.tcgscan.app.data.pack.InstalledPacksStore
import org.tcgscan.app.data.pack.PackCard
import org.tcgscan.app.data.pack.PackCatalog
import org.tcgscan.app.data.pack.PackSet
import org.tcgscan.app.di.appContainer
import org.tcgscan.app.scan.text.TextNormalizer

/**
 * Drives the collection screen: every owned card, resolved against the installed packs, filterable
 * by language and by set and searchable by name or number (FR-13).
 *
 * The pipeline is deliberately split in two stages:
 * 1. [resolved] joins the Room ownership Flow with the packs. That is the only costly step and it
 *    re-runs solely when a possession appears or disappears, or when a pack is installed, updated
 *    or removed — so a « retirer » redraws the list with no manual refresh.
 * 2. the language filter, the set filter and the search needle are applied in memory over that
 *    join, so typing never opens a pack nor hits SQLite.
 *
 * An owned id that no pack resolves is kept in the list and rendered as « carte inconnue » with its
 * raw id: a pack update can never hide a possession (EC-16).
 */
class CollectionViewModel(
    private val appContext: Context,
    private val ownership: OwnershipDao,
    installedPacks: InstalledPacksStore,
) : ViewModel() {

    private val langFilter = MutableStateFlow<String?>(null)
    private val setFilter = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")

    /**
     * The whole collection, resolved. The on-disk packs Flow is both an input (a pack this build
     * cannot read is not opened, EC-17) and a trigger: installing a pack fills in the names of the
     * ids that were showing as unknown.
     */
    private val resolved: Flow<List<CollectionCard>> =
        combine(ownership.observeAll(), installedPacks.onDisk, ::resolve)
            .flowOn(Dispatchers.IO)

    val state: StateFlow<CollectionUiState> =
        combine(resolved, langFilter, setFilter, query, ::buildState)
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = CollectionUiState(),
            )

    /** `null` means « toutes les langues ». A set belongs to one language, so its filter is reset. */
    fun selectLang(lang: String?) {
        langFilter.value = lang
        setFilter.value = null
    }

    /** `null` means « toutes les extensions ». */
    fun selectSet(setId: String?) {
        setFilter.value = setId
    }

    fun search(text: String) {
        query.value = text
    }

    /**
     * Drops one possession (FR-13). Only the `owned_cards` row goes: list memberships and the pack
     * are untouched, and the Room Flow refreshes the screen on its own.
     */
    fun remove(card: CollectionCard) {
        viewModelScope.launch { ownership.delete(card.lang, card.cardId) }
    }

    /**
     * Joins the owned rows with the packs, preserving the `added_at DESC` order of the DAO.
     *
     * Each language's pack is opened read-only for the duration of the join and closed again; the
     * ids are resolved with [PackCatalog.cardsByIds], which chunks them itself, so no query is ever
     * built with the whole collection as bound parameters.
     */
    private fun resolve(
        owned: List<OwnedCard>,
        installed: Map<String, InstalledPack>,
    ): List<CollectionCard> {
        if (owned.isEmpty()) return emptyList()
        val views = owned.groupBy({ it.lang }, { it.cardId })
            .mapValues { (lang, ids) -> readPack(lang, ids, installed[lang]) }
        return owned.map { row ->
            val view = views[row.lang]
            val card = view?.cards?.get(row.cardId)
            val set = card?.let { view?.sets?.get(it.setId) }
            CollectionCard(
                lang = row.lang,
                cardId = row.cardId,
                physicalLang = row.physicalLang,
                addedAt = row.addedAt,
                card = card,
                setName = set?.name,
                setCardCountTotal = set?.cardCountTotal,
            )
        }
    }

    /**
     * Reads the cards of [ids] and the sets of the pack of [lang], or `null` when there is nothing
     * usable to read — no pack installed, a pack built by a newer pipeline than this build can read
     * (EC-17), or an unreadable file. In all three cases the possessions stay listed as unknown
     * cards rather than vanishing (EC-16).
     */
    private fun readPack(lang: String, ids: List<String>, installed: InstalledPack?): PackView? {
        if (installed != null && !installed.isSchemaSupported) return null
        val catalog = PackCatalog.open(appContext, lang) ?: return null
        return catalog.use { open ->
            try {
                PackView(
                    cards = open.cardsByIds(ids),
                    sets = open.sets().associateBy(PackSet::id),
                )
            } catch (e: SQLiteException) {
                Log.w(TAG, "pack $lang is unreadable; its cards stay unresolved", e)
                null
            }
        }
    }

    /** Applies the filters and the needle; also derives the filter options from what is owned. */
    private fun buildState(
        all: List<CollectionCard>,
        lang: String?,
        setId: String?,
        rawQuery: String,
    ): CollectionUiState {
        val langs = all.map { it.lang }.distinct().sorted()
        // A filter whose last card was just removed must not hide the rest of the collection.
        val activeLang = lang?.takeIf { it in langs }
        val inLang = if (activeLang == null) all else all.filter { it.lang == activeLang }

        val setCounts = LinkedHashMap<String, Int>()
        val setNames = HashMap<String, String>()
        inLang.forEach { row ->
            val set = row.card?.setId ?: return@forEach
            setCounts[set] = (setCounts[set] ?: 0) + 1
            setNames[set] = row.setName ?: set
        }
        val sets = setCounts.map { (set, count) ->
            CollectionSetFilter(setId = set, name = setNames.getValue(set), ownedCount = count)
        }.sortedBy { it.name }
        val activeSet = setId?.takeIf { setCounts.containsKey(it) }

        val nameNeedle = TextNormalizer.normalize(rawQuery)
        val rawNeedle = rawQuery.trim().lowercase()
        val visible = inLang.filter { row ->
            (activeSet == null || row.card?.setId == activeSet) && row.matches(nameNeedle, rawNeedle)
        }

        return CollectionUiState(
            loading = false,
            query = rawQuery,
            langFilter = activeLang,
            setFilter = activeSet,
            langs = langs,
            sets = sets,
            cards = visible,
            totalCount = all.size,
        )
    }

    /** What one pack answered for one language, for the duration of a single join. */
    private class PackView(
        val cards: Map<String, PackCard>,
        val sets: Map<String, PackSet>,
    )

    companion object {

        private const val TAG = "CollectionViewModel"

        /** Keeps the join alive across a tab switch or a rotation instead of redoing it at once. */
        private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            val container = context.appContainer
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(CollectionViewModel::class.java)) {
                        "CollectionViewModel.factory asked for $modelClass"
                    }
                    @Suppress("UNCHECKED_CAST")
                    return CollectionViewModel(
                        appContext = appContext,
                        ownership = container.db.ownershipDao(),
                        installedPacks = container.installedPacksStore,
                    ) as T
                }
            }
        }
    }
}

/**
 * One owned card as the screen draws it. [card] is `null` for an id that no installed pack resolves:
 * the possession is kept and rendered as « carte inconnue » with its raw [cardId] (EC-16).
 */
data class CollectionCard(
    val lang: String,
    val cardId: String,
    val physicalLang: String,
    val addedAt: Long,
    val card: PackCard?,
    val setName: String?,
    val setCardCountTotal: Int?,
) {

    /** Stable identity of the row across list updates: ownership is keyed by `(lang, cardId)`. */
    val key: String get() = "$lang/$cardId"

    val isUnknown: Boolean get() = card == null

    private val nameNorm: String = card?.nameNorm ?: ""
    private val localIdLower: String = card?.localId?.lowercase() ?: ""
    private val cardIdLower: String = cardId.lowercase()

    /**
     * Search by name or by number (FR-13): [nameNeedle] is the canonical normalisation of the query
     * compared against `cards.name_norm`, [rawNeedle] its trimmed lowercase form compared against
     * the printed number and the raw id — the only thing an unknown card can be found by.
     */
    fun matches(nameNeedle: String, rawNeedle: String): Boolean {
        if (nameNeedle.isEmpty() && rawNeedle.isEmpty()) return true
        if (nameNeedle.isNotEmpty() && nameNorm.contains(nameNeedle)) return true
        if (rawNeedle.isEmpty()) return false
        return localIdLower.contains(rawNeedle) || cardIdLower.contains(rawNeedle)
    }
}

/** One entry of the set filter: a set the user owns at least one card of. */
data class CollectionSetFilter(
    val setId: String,
    val name: String,
    val ownedCount: Int,
)

/** Everything the collection screen draws. */
data class CollectionUiState(
    /** True until the first ownership emission: neither the list nor the empty state is known yet. */
    val loading: Boolean = true,
    val query: String = "",
    /** `null` = « toutes les langues »; always a language the collection actually contains. */
    val langFilter: String? = null,
    /** `null` = « toutes les extensions »; always a set the filtered collection contains. */
    val setFilter: String? = null,
    val langs: List<String> = emptyList(),
    val sets: List<CollectionSetFilter> = emptyList(),
    val cards: List<CollectionCard> = emptyList(),
    /** Size of the whole collection, filters ignored: the « %d sur %d » of the count header. */
    val totalCount: Int = 0,
) {

    val isFiltered: Boolean
        get() = langFilter != null || setFilter != null || query.isNotBlank()

    /** Nothing is owned at all: the screen points at the scan tab. */
    val isEmptyCollection: Boolean get() = !loading && totalCount == 0

    /** Something is owned but the filters and the needle leave nothing to show. */
    val isEmptyResult: Boolean get() = !loading && totalCount > 0 && cards.isEmpty()
}
