package org.tcgscan.app.ui.catalog

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tcgscan.app.data.CollectionRepository
import org.tcgscan.app.data.db.AppDatabase
import org.tcgscan.app.data.db.DbProvider
import org.tcgscan.app.data.pack.InstalledPacksStore
import org.tcgscan.app.data.pack.PackCard
import org.tcgscan.app.data.pack.PackCatalog
import org.tcgscan.app.data.pack.PackPaths
import org.tcgscan.app.data.pack.PackSet
import org.tcgscan.app.scan.ScanPhotoStore
import org.tcgscan.app.scan.text.TextNormalizer
import java.io.Closeable

/*
 * The catalogue browse layer (FR-14): the root screen — language tabs, search, set list — and the
 * set page it opens.
 *
 * Card metadata always comes from the installed pack through `PackCatalog`; Room only answers
 * « est-elle possédée ? », as a Flow, so a pack install or removal can never change the collection
 * and the collection is never a copy of the pack (FR-20/FR-23).
 */

/** Cards per search page (the plan's paging unit for the catalogue list). */
private const val PAGE_SIZE = 50

/** Idle time before a typed query reaches the pack. */
private const val SEARCH_DEBOUNCE_MS = 250L

private const val TAG_CATALOG = "CatalogViewModel"

private const val TAG_SET_BROWSE = "SetBrowseViewModel"

/**
 * Catalogue root.
 *
 * Search covers both a name and a number (FR-14): the normalised `name_norm` `LIKE` query is paged,
 * and the `local_id`/`ocr_tokens` lookup — which is exhaustive and short by nature — rides the first
 * page only, ahead of the name matches.
 */
class CatalogViewModel(
    private val catalogs: PackCatalogs,
    private val installedPacks: InstalledPacksStore,
    private val db: AppDatabase,
) : ViewModel() {

    private val _state = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    private var setsJob: Job? = null
    private var searchJob: Job? = null
    private var ownershipJob: Job? = null

    /**
     * How many `name_norm` rows have been consumed so far: the next page's `OFFSET`. It is not
     * `results.size`, which also counts the number matches prepended to the first page.
     */
    private var nameOffset = 0

    init {
        // Emits once at subscription and then on every install, update and removal, which is also
        // when a cached pack handle may point at a replaced file: reopen from scratch each time.
        viewModelScope.launch {
            installedPacks.installed.collect { onPacksChanged() }
        }
    }

    /** Language tab tapped. */
    fun onLangSelected(lang: String) {
        if (lang == _state.value.selectedLang || lang !in _state.value.langs) return
        selectLang(lang)
    }

    /** Called on every keystroke; the pack is only queried [SEARCH_DEBOUNCE_MS] later. */
    fun onQueryChange(raw: String) {
        if (raw == _state.value.query) return
        _state.update { it.copy(query = raw) }
        restartSearch(SEARCH_DEBOUNCE_MS)
    }

    fun onQueryCleared() {
        onQueryChange("")
    }

    /** Appends the next [PAGE_SIZE] name matches. */
    fun loadMore() {
        val current = _state.value
        val lang = current.selectedLang ?: return
        val query = current.query
        if (!current.canLoadMore || current.searchLoading || current.loadingMore) return
        if (query.isBlank()) return
        _state.update { it.copy(loadingMore = true) }
        searchJob = viewModelScope.launch {
            val page = search(lang, query, nameOffset)
            nameOffset += page.nameCount
            _state.update { shown ->
                // The language or the query may have changed while the page was loading.
                if (shown.selectedLang != lang || shown.query != query) {
                    shown.copy(loadingMore = false)
                } else {
                    val known = shown.results.mapTo(HashSet(shown.results.size)) { it.cardId }
                    shown.copy(
                        loadingMore = false,
                        results = shown.results + page.cards.filterNot { it.cardId in known },
                        canLoadMore = page.hasMore,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        catalogs.close()
    }

    private suspend fun onPacksChanged() {
        catalogs.invalidate()
        val langs = catalogs.installedLangs()
        _state.update { it.copy(langs = langs) }
        val selected = _state.value.selectedLang
        selectLang(selected?.takeIf { it in langs } ?: langs.firstOrNull())
    }

    private fun selectLang(lang: String?) {
        setsJob?.cancel()
        ownershipJob?.cancel()
        _state.update {
            it.copy(
                selectedLang = lang,
                series = emptyList(),
                ownedIds = emptySet(),
                setsLoading = lang != null,
                packUnreadable = false,
            )
        }
        if (lang == null) {
            restartSearch(0L)
            return
        }

        setsJob = viewModelScope.launch {
            val series = readSeries(lang)
            _state.update {
                if (it.selectedLang != lang) {
                    it
                } else {
                    it.copy(
                        setsLoading = false,
                        series = series.orEmpty(),
                        packUnreadable = series == null,
                    )
                }
            }
        }

        ownershipJob = viewModelScope.launch {
            db.ownershipDao().observeByLang(lang).collect { owned ->
                val ids = owned.mapTo(HashSet(owned.size)) { it.cardId }
                _state.update { if (it.selectedLang == lang) it.copy(ownedIds = ids) else it }
            }
        }

        restartSearch(0L)
    }

    private fun restartSearch(debounceMs: Long) {
        searchJob?.cancel()
        nameOffset = 0
        val lang = _state.value.selectedLang
        val query = _state.value.query
        if (lang == null || query.isBlank()) {
            _state.update {
                it.copy(
                    results = emptyList(),
                    canLoadMore = false,
                    searchLoading = false,
                    loadingMore = false,
                )
            }
            return
        }
        _state.update { it.copy(searchLoading = true, loadingMore = false) }
        searchJob = viewModelScope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            val page = search(lang, query, offset = 0)
            nameOffset = page.nameCount
            _state.update {
                if (it.selectedLang != lang || it.query != query) {
                    it
                } else {
                    it.copy(
                        searchLoading = false,
                        results = page.cards,
                        canLoadMore = page.hasMore,
                    )
                }
            }
        }
    }

    private suspend fun search(lang: String, rawQuery: String, offset: Int): SearchPage =
        withContext(Dispatchers.IO) {
            val catalog = catalogs.of(lang) ?: return@withContext SearchPage.EMPTY
            try {
                val byName = catalog.searchByName(
                    query = TextNormalizer.normalize(rawQuery),
                    limit = PAGE_SIZE,
                    offset = offset,
                )
                if (offset > 0) {
                    SearchPage(byName, byName.size)
                } else {
                    val byNumber = catalog.searchByNumber(rawQuery.trim())
                    val numberIds = byNumber.mapTo(HashSet(byNumber.size)) { it.cardId }
                    SearchPage(
                        cards = byNumber + byName.filterNot { it.cardId in numberIds },
                        nameCount = byName.size,
                    )
                }
            } catch (e: Exception) {
                // A pack that stops answering mid-session must not take the screen down: the user
                // keeps an empty result and the message of a reinstall.
                Log.w(TAG_CATALOG, "search failed in pack $lang", e)
                SearchPage.EMPTY
            }
        }

    /** `null` when the pack cannot be read at all, which the UI reports as « pack illisible ». */
    private suspend fun readSeries(lang: String): List<CatalogSeries>? =
        withContext(Dispatchers.IO) {
            val catalog = catalogs.of(lang) ?: return@withContext null
            try {
                // `sets()` is already ordered by series, so grouping keeps the pack's order.
                catalog.sets().groupBy { it.seriesId }.map { (seriesId, sets) ->
                    CatalogSeries(seriesId, sets)
                }
            } catch (e: Exception) {
                Log.w(TAG_CATALOG, "cannot read the sets of pack $lang", e)
                null
            }
        }

    companion object {

        /** Ad-hoc wiring until the dependency container of T31 owns these singletons. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(CatalogViewModel::class.java)) {
                        "CatalogViewModel.factory asked for $modelClass"
                    }
                    @Suppress("UNCHECKED_CAST")
                    return CatalogViewModel(
                        catalogs = PackCatalogs(appContext),
                        installedPacks = InstalledPacksStore(appContext),
                        db = DbProvider.get(appContext),
                    ) as T
                }
            }
        }
    }
}

/**
 * One set of one language: its cards as a grid, each with the « possédée » badge and an inline
 * ownership toggle (FR-14).
 */
class SetBrowseViewModel(
    private val lang: String,
    private val setId: String,
    private val catalogs: PackCatalogs,
    private val db: AppDatabase,
    private val collection: CollectionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SetBrowseUiState(lang = lang, setId = setId))
    val state: StateFlow<SetBrowseUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val content = readSet()
            if (content == null) {
                _state.update { it.copy(loading = false, unavailable = true) }
                return@launch
            }
            _state.update {
                it.copy(loading = false, set = content.set, cards = content.cards)
            }
            observeOwnership(content.cards.map { card -> card.cardId })
        }
    }

    /** Inline toggle of the grid (FR-14). */
    fun setOwned(cardId: String, owned: Boolean) {
        viewModelScope.launch {
            collection.setOwned(lang, cardId, owned)
        }
    }

    override fun onCleared() {
        catalogs.close()
    }

    private suspend fun observeOwnership(cardIds: List<String>) {
        if (cardIds.isEmpty()) return
        db.ownershipDao().observeOwnedIdsIn(lang, cardIds).collect { owned ->
            _state.update { it.copy(ownedIds = owned.toSet()) }
        }
    }

    /** `null` when the pack is missing or unreadable, or when the set is not in it (EC-16/EC-17). */
    private suspend fun readSet(): SetContent? = withContext(Dispatchers.IO) {
        val catalog = catalogs.of(lang) ?: return@withContext null
        try {
            val set = catalog.set(setId) ?: return@withContext null
            SetContent(set, catalog.cardsInSet(setId))
        } catch (e: Exception) {
            Log.w(TAG_SET_BROWSE, "cannot read set $setId of pack $lang", e)
            null
        }
    }

    private class SetContent(val set: PackSet, val cards: List<PackCard>)

    companion object {

        /** Ad-hoc wiring until the dependency container of T31 owns these singletons. */
        fun factory(context: Context, lang: String, setId: String): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(SetBrowseViewModel::class.java)) {
                        "SetBrowseViewModel.factory asked for $modelClass"
                    }
                    val db = DbProvider.get(appContext)
                    @Suppress("UNCHECKED_CAST")
                    return SetBrowseViewModel(
                        lang = lang,
                        setId = setId,
                        catalogs = PackCatalogs(appContext),
                        db = db,
                        collection = CollectionRepository(db, ScanPhotoStore(appContext)),
                    ) as T
                }
            }
        }
    }
}

/** Everything the catalogue root draws. */
data class CatalogUiState(
    /** Installed languages, in tab order; empty means « aucun pack installé » (EC-1). */
    val langs: List<String> = emptyList(),
    val selectedLang: String? = null,
    /** The raw text of the field; it is normalised on its way to the pack. */
    val query: String = "",
    val series: List<CatalogSeries> = emptyList(),
    val results: List<PackCard> = emptyList(),
    /** Owned ids of [selectedLang], straight from a Room Flow (FR-14). */
    val ownedIds: Set<String> = emptySet(),
    val setsLoading: Boolean = false,
    val searchLoading: Boolean = false,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    /** The pack file is there but unreadable, or built by a newer pipeline (EC-17). */
    val packUnreadable: Boolean = false,
) {

    /** True while the field holds something: the results replace the set list. */
    val searching: Boolean get() = query.isNotBlank()

    val noPackInstalled: Boolean get() = langs.isEmpty()
}

/** A series header and its sets, in the pack's own order. */
data class CatalogSeries(
    val seriesId: String,
    val sets: List<PackSet>,
)

/** Everything the set page draws. */
data class SetBrowseUiState(
    val lang: String,
    val setId: String,
    val loading: Boolean = true,
    val set: PackSet? = null,
    val cards: List<PackCard> = emptyList(),
    val ownedIds: Set<String> = emptySet(),
    /** No pack, unreadable pack, or a set id absent from it. */
    val unavailable: Boolean = false,
)

/** One page of search results. */
private class SearchPage(
    val cards: List<PackCard>,
    /** Rows the `name_norm` query returned, which is what the next `OFFSET` is counted in. */
    val nameCount: Int,
) {

    /** A full name page means there may be more; a short one is the end of the matches. */
    val hasMore: Boolean get() = nameCount >= PAGE_SIZE

    companion object {
        val EMPTY = SearchPage(emptyList(), 0)
    }
}

/**
 * Keeps one open [PackCatalog] per language for the lifetime of a screen.
 *
 * A pack is a read-only SQLite file opened through a native handle, so it is opened once and
 * [close]d with the ViewModel that owns it. A pack whose `pack_schema` is newer than this build is
 * refused here rather than half-read (EC-17).
 */
class PackCatalogs(context: Context) : Closeable {

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val opened = HashMap<String, PackCatalog>()

    /** The open catalogue of [lang], or `null` when there is no usable pack for it. */
    suspend fun of(lang: String): PackCatalog? = mutex.withLock {
        opened[lang] ?: withContext(Dispatchers.IO) { openSupported(lang) }?.also {
            opened[lang] = it
        }
    }

    /**
     * Languages with a pack file on disk, whatever [InstalledPacksStore] recorded: a pack pushed by
     * hand (docs/manual-debug.md) has no record, and a record whose file vanished must not show up.
     */
    suspend fun installedLangs(): List<String> = withContext(Dispatchers.IO) {
        PackPaths.packsRoot(appContext).list().orEmpty()
            .filter { PackPaths.isValidLang(it) && PackPaths.isInstalled(appContext, it) }
            .sorted()
    }

    /** Closes every cached handle, so the next [of] reopens the file an install may have replaced. */
    suspend fun invalidate() {
        mutex.withLock {
            opened.values.forEach { it.closeQuietly() }
            opened.clear()
        }
    }

    override fun close() {
        // Called from `onCleared`, which cannot suspend: the scope that could still be querying has
        // just been cancelled, and a query racing this close fails into the caller's catch.
        opened.values.forEach { it.closeQuietly() }
        opened.clear()
    }

    private fun openSupported(lang: String): PackCatalog? {
        val catalog = PackCatalog.open(appContext, lang) ?: return null
        return try {
            val meta = catalog.meta()
            if (meta.isSchemaSupported) {
                catalog
            } else {
                // EC-17: a pack from a newer pipeline is refused; the UI invites an app update.
                Log.w(TAG_CATALOG, "pack $lang has an unsupported schema ${meta.packSchema}")
                catalog.closeQuietly()
                null
            }
        } catch (e: Exception) {
            // Missing table, corrupt file, unreadable `pack_meta`: treat as « pas de pack ».
            Log.w(TAG_CATALOG, "pack $lang is unreadable", e)
            catalog.closeQuietly()
            null
        }
    }

    private fun PackCatalog.closeQuietly() {
        try {
            close()
        } catch (e: Exception) {
            Log.w(TAG_CATALOG, "cannot close pack $lang", e)
        }
    }
}
