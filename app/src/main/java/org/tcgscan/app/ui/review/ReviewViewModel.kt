package org.tcgscan.app.ui.review

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tcgscan.app.data.CollectionRepository
import org.tcgscan.app.data.CommitResult
import org.tcgscan.app.data.ReviewDecision
import org.tcgscan.app.data.db.ScanDetectionEntity
import org.tcgscan.app.data.db.ScanDetectionState
import org.tcgscan.app.data.db.ScanSessionStatus
import org.tcgscan.app.data.pack.PackSet
import org.tcgscan.app.di.appContainer
import org.tcgscan.app.scan.ScanSessionRepository
import org.tcgscan.app.scan.ScanTuning
import org.tcgscan.app.scan.match.Candidate
import org.tcgscan.app.scan.match.Matcher
import org.tcgscan.app.scan.match.OcrAgreement
import org.tcgscan.app.scan.match.ReviewReason
import org.tcgscan.app.scan.text.TextNormalizer
import org.tcgscan.app.ui.catalog.PackCatalogs

private const val TAG = "ReviewViewModel"

/** Idle time before a typed query reaches the pack, same as the catalogue search. */
private const val SEARCH_DEBOUNCE_MS = 250L

/** Rows of the manual-search sheet; a correction is a precise search, not a browse. */
private const val SEARCH_LIMIT = 30

/** TCGdex code of the language an English card is read in (`ScanSessionRepository.detectedLangOf`). */
private const val ENGLISH = Matcher.PHYSICAL_LANG_EN

/** The pack an English card is proposed from (FR-11). */
private const val FRENCH_PACK_LANG = "fr"

/**
 * Drives the review screen of one scan session: the only place a scanned card is validated into the
 * collection (FR-9).
 *
 * The screen has four lists, derived from the persisted detections of the session:
 * « Reconnues » (`CONFIDENT`, pre-checked), « À vérifier » (`REVIEW` and `NO_CANDIDATE`, unchecked),
 * « Déjà possédées » (`ALREADY_OWNED`, read-only, FR-10) and the burst's duplicates (`DUPLICATE`,
 * read-only, FR-7/EC-9).
 *
 * What the user does on the screen — unchecking a recognised card, picking a candidate, correcting
 * by manual search, rejecting — is held in memory **only**. Nothing reaches Room before
 * [commit], so leaving the screen keeps the session untouched and adds nothing (EC-14).
 */
class ReviewViewModel(
    private val sessionId: Long,
    private val sessions: ScanSessionRepository,
    private val collection: CollectionRepository,
    private val catalogs: PackCatalogs,
) : ViewModel() {

    /** In-memory decision on one detection; absent means « untouched ». */
    private data class Choice(
        /** `null` = default: a recognised row is checked, a row to check is not. */
        val checked: Boolean? = null,
        val picked: Candidate? = null,
        val rejected: Boolean = false,
    )

    /** What the screen needs once and for all to explain its rows. */
    private data class SessionContext(
        val open: Boolean,
        val defaultLang: String,
        val installedLangs: List<String>,
    )

    /** The parts of the state that do not come from the detections. */
    private data class Overlay(
        val search: ReviewSearch? = null,
        val committing: Boolean = false,
        val error: ReviewError? = null,
        val finished: Boolean = false,
    )

    private val choices = MutableStateFlow<Map<Long, Choice>>(emptyMap())
    private val context = MutableStateFlow<SessionContext?>(null)
    private val overlay = MutableStateFlow(Overlay())
    private var searchJob: Job? = null

    private val rows = combine(
        context.filterNotNull(),
        sessions.observeDetections(sessionId),
        choices,
        ::buildRows,
    ).flowOn(Dispatchers.Default)

    val state: StateFlow<ReviewUiState> = combine(rows, overlay) { base, extra ->
        base.copy(
            search = extra.search,
            committing = extra.committing,
            error = extra.error,
            finished = extra.finished,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = ReviewUiState(),
    )

    init {
        viewModelScope.launch {
            val session = sessions.session(sessionId)
            context.value = SessionContext(
                open = session?.status == ScanSessionStatus.OPEN,
                defaultLang = session?.defaultLang.orEmpty(),
                installedLangs = catalogs.installedLangs(),
            )
        }
    }

    /** Checks or unchecks a row; a row to check needs a card picked first (FR-9). */
    fun toggleChecked(detectionId: Long) {
        val ui = state.value
        val current = ui.recognised.firstOrNull { it.detectionId == detectionId }?.checked
            ?: ui.toCheck.firstOrNull { it.detectionId == detectionId && it.picked != null }?.checked
            ?: return
        updateChoice(detectionId) { it.copy(checked = !current) }
    }

    /** Settles a row on [card], a candidate or a manual pick, which also checks it. */
    fun pick(detectionId: Long, card: Candidate) {
        updateChoice(detectionId) { it.copy(checked = true, picked = card, rejected = false) }
        closeSearch()
    }

    /** Dismisses a detection: it enters nothing and its photo is deleted on validation (FR-26). */
    fun reject(detectionId: Long) {
        updateChoice(detectionId) { it.copy(checked = false, rejected = true) }
    }

    /** Takes back a [reject] before validating. */
    fun undoReject(detectionId: Long) {
        updateChoice(detectionId) { it.copy(rejected = false) }
    }

    /** Opens the manual-search sheet on one row to check (FR-8). */
    fun openSearch(detectionId: Long) {
        val row = state.value.toCheck.firstOrNull { it.detectionId == detectionId } ?: return
        val langs = context.value?.installedLangs.orEmpty()
        val preferred = row.candidates.firstOrNull()?.lang ?: row.searchLang
        overlay.update {
            it.copy(
                search = ReviewSearch(
                    detectionId = detectionId,
                    langs = langs,
                    lang = preferred.takeIf { lang -> lang in langs } ?: langs.firstOrNull(),
                    english = row.english,
                ),
            )
        }
    }

    fun closeSearch() {
        searchJob?.cancel()
        overlay.update { it.copy(search = null) }
    }

    fun searchLang(lang: String) {
        overlay.update { it.copy(search = it.search?.copy(lang = lang)) }
        runSearch(debounce = false)
    }

    fun searchQuery(query: String) {
        overlay.update { it.copy(search = it.search?.copy(query = query)) }
        runSearch(debounce = true)
    }

    /**
     * Validates the session: every checked row enters the collection and every rejected row is
     * dismissed, in one transaction (FR-9). The ViewModel never writes a card itself.
     */
    fun commit() {
        val ui = state.value
        if (overlay.value.committing || ui.unavailable || ui.commitCount + ui.rejectedCount == 0) return
        val decisions = buildList {
            ui.recognised.filter { it.checked }.forEach { add(commitOf(it.detectionId, it.card)) }
            ui.toCheck.forEach { row ->
                val picked = row.picked
                when {
                    row.rejected -> add(ReviewDecision.Reject(row.detectionId))
                    row.checked && picked != null -> add(commitOf(row.detectionId, picked))
                }
            }
        }
        overlay.update { it.copy(committing = true, error = null) }
        viewModelScope.launch {
            val result = try {
                collection.commit(sessionId, decisions)
            } catch (e: IllegalArgumentException) {
                // A malformed decision is a bug: nothing was written, say so instead of crashing.
                Log.e(TAG, "Review commit refused", e)
                CommitResult.Failed(e.message.orEmpty())
            }
            overlay.update {
                when (result) {
                    is CommitResult.Success -> it.copy(committing = false, finished = true)
                    CommitResult.SessionClosed ->
                        it.copy(committing = false, error = ReviewError.SESSION_CLOSED)
                    is CommitResult.Failed ->
                        it.copy(committing = false, error = ReviewError.COMMIT_FAILED)
                }
            }
        }
    }

    fun dismissError() {
        overlay.update { it.copy(error = null) }
    }

    override fun onCleared() {
        catalogs.close()
    }

    private fun commitOf(detectionId: Long, card: Candidate) =
        ReviewDecision.Commit(detectionId, card.lang, card.cardId, card.physicalLang)

    private fun updateChoice(detectionId: Long, change: (Choice) -> Choice) {
        choices.update { it + (detectionId to change(it[detectionId] ?: Choice())) }
    }

    private fun runSearch(debounce: Boolean) {
        searchJob?.cancel()
        val search = overlay.value.search ?: return
        val lang = search.lang
        val readable = TextNormalizer.normalize(search.query).isNotEmpty() || search.query.any(Char::isDigit)
        if (lang == null || !readable) {
            overlay.update { it.copy(search = it.search?.copy(results = emptyList(), searching = false)) }
            return
        }
        overlay.update { it.copy(search = it.search?.copy(searching = true)) }
        searchJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val results = findCards(lang, search.query, search.english)
            overlay.update {
                it.copy(search = it.search?.copy(results = results, searching = false))
            }
        }
    }

    /**
     * Name search over `cards.name_norm`, or number search when the query holds a digit — the two
     * things a user can read on the photo. Results carry no visual score: they are manual picks.
     */
    private suspend fun findCards(lang: String, query: String, english: Boolean): List<Candidate> =
        withContext(Dispatchers.IO) {
            val catalog = catalogs.of(lang) ?: return@withContext emptyList()
            try {
                val found = if (query.any(Char::isDigit)) {
                    catalog.searchByNumber(query, SEARCH_LIMIT)
                } else {
                    catalog.searchByName(TextNormalizer.normalize(query), SEARCH_LIMIT)
                }
                val sets = catalog.sets().associateBy(PackSet::id)
                // An English card filed under its French id keeps `physical_lang = en` (FR-11).
                val physicalLang = if (english) ENGLISH else lang
                found.map { card ->
                    Candidate.of(
                        card = card,
                        set = sets[card.setId],
                        lang = lang,
                        physicalLang = physicalLang,
                        cosine = null,
                        agreement = OcrAgreement.NONE,
                    )
                }
            } catch (e: SQLiteException) {
                Log.w(TAG, "Manual search in pack $lang failed", e)
                emptyList()
            }
        }

    private fun buildRows(
        ctx: SessionContext,
        detections: List<ScanDetectionEntity>,
        choices: Map<Long, Choice>,
    ): ReviewUiState {
        if (!ctx.open) return ReviewUiState(loading = false, unavailable = true)

        val decoded = detections.associate { it.id to ScanSessionRepository.decodeCandidates(it.candidatesJson) }
        // A duplicate carries no candidates: it names the card of the capture it repeats.
        val known = decoded.values.flatten().associateBy { "${it.lang}/${it.cardId}" }

        val recognised = ArrayList<RecognisedRow>()
        val toCheck = ArrayList<ToCheckRow>()
        val owned = ArrayList<OwnedRow>()
        val duplicates = ArrayList<OwnedRow>()

        for (row in detections) {
            val candidates = decoded.getValue(row.id)
            val choice = choices[row.id] ?: Choice()
            val chosen = chosenCard(row, candidates, known)
            when (row.state) {
                ScanDetectionState.CONFIDENT -> if (chosen != null) {
                    recognised += RecognisedRow(
                        detectionId = row.id,
                        photoPath = row.photoPath,
                        card = chosen,
                        checked = choice.checked ?: true,
                    )
                }

                ScanDetectionState.REVIEW, ScanDetectionState.NO_CANDIDATE -> {
                    val english = row.detectedLang == ENGLISH
                    toCheck += ToCheckRow(
                        detectionId = row.id,
                        photoPath = row.photoPath,
                        candidates = candidates,
                        suggested = chosen,
                        picked = choice.picked,
                        checked = choice.picked != null && choice.checked == true,
                        rejected = choice.rejected,
                        reason = reasonOf(row, candidates, ctx),
                        english = english,
                        searchLang = if (english) FRENCH_PACK_LANG else row.detectedLang ?: ctx.defaultLang,
                    )
                }

                ScanDetectionState.ALREADY_OWNED ->
                    owned += OwnedRow(row.id, row.photoPath, chosen)

                ScanDetectionState.DUPLICATE ->
                    duplicates += OwnedRow(row.id, row.photoPath, chosen)

                // Written by `CollectionRepository.commit`, which closes the session.
                ScanDetectionState.REJECTED, ScanDetectionState.COMMITTED -> Unit
            }
        }
        return ReviewUiState(
            loading = false,
            recognised = recognised,
            toCheck = toCheck,
            owned = owned,
            duplicates = duplicates,
        )
    }

    /**
     * The card a row opens on, from its candidates when the payload is readable. An id with no
     * readable candidate is still shown, by its raw id, so a row never loses the card it names
     * (EC-13/EC-16).
     */
    private fun chosenCard(
        row: ScanDetectionEntity,
        candidates: List<Candidate>,
        known: Map<String, Candidate>,
    ): Candidate? {
        val lang = row.chosenLang ?: return null
        val cardId = row.chosenCardId ?: return null
        return candidates.firstOrNull { it.lang == lang && it.cardId == cardId }
            ?: known["$lang/$cardId"]
            ?: unresolved(lang, cardId)
    }

    private fun unresolved(lang: String, cardId: String) = Candidate(
        lang = lang,
        cardId = cardId,
        name = cardId,
        localId = "",
        setId = "",
        setName = "",
        setCardCountTotal = 0,
        imageUrl = null,
        hasEmbed = 0,
        physicalLang = lang,
        cosine = null,
        score = 0f,
        agreement = OcrAgreement.NONE,
    )

    /**
     * Why a row is in « à vérifier ». The matcher's verdict is not stored with the detection, so it
     * is re-derived here from what is: the language read, the persisted candidates and the packs
     * installed now. It follows the order of `Matcher.match`, so it agrees with it unless a pack was
     * installed or removed since the scan.
     */
    private fun reasonOf(
        row: ScanDetectionEntity,
        candidates: List<Candidate>,
        ctx: SessionContext,
    ): ReviewReason {
        val english = row.detectedLang == ENGLISH
        val top = candidates.firstOrNull()
        if (top == null) {
            val lang = if (english) FRENCH_PACK_LANG else row.detectedLang ?: ctx.defaultLang
            return when {
                lang !in ctx.installedLangs -> ReviewReason.PACK_MISSING
                english -> ReviewReason.ENGLISH_UNSUPPORTED
                else -> ReviewReason.NO_MATCH
            }
        }
        val cosine = top.cosine
        val margin = top.score - (candidates.getOrNull(1)?.score ?: 0f)
        return when {
            english -> ReviewReason.ENGLISH_CARD
            row.detectedLang == null -> ReviewReason.LANGUAGE_UNKNOWN
            cosine == null || !top.hasOwnVisualReference -> ReviewReason.NO_VISUAL_REFERENCE
            cosine < ScanTuning.MIN_VISUAL_COSINE -> ReviewReason.LOW_CONFIDENCE
            margin < ScanTuning.MIN_MARGIN -> ReviewReason.AMBIGUOUS
            !top.agreement.confirmsIdentity -> ReviewReason.OCR_UNCONFIRMED
            else -> ReviewReason.LOW_CONFIDENCE
        }
    }

    companion object {

        /** Keeps the rows alive across a rotation instead of rebuilding them at once. */
        private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

        fun factory(context: Context, sessionId: Long): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            val container = context.appContainer
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ReviewViewModel::class.java)) {
                        "ReviewViewModel.factory asked for $modelClass"
                    }
                    @Suppress("UNCHECKED_CAST")
                    return ReviewViewModel(
                        sessionId = sessionId,
                        sessions = container.scanSessionRepository,
                        collection = container.collectionRepository,
                        catalogs = PackCatalogs(appContext),
                    ) as T
                }
            }
        }
    }
}

/** A recognised card, pre-checked: « Reconnues » (FR-9). */
data class RecognisedRow(
    val detectionId: Long,
    val photoPath: String?,
    val card: Candidate,
    val checked: Boolean,
)

/**
 * A detection the user must look at: « À vérifier » (FR-8).
 *
 * [suggested] is the cursor the matcher placed, if any; it is a hint and never a decision. [picked]
 * is what the user settled on — a candidate or a manual search result — and a row is only [checked]
 * once it has one.
 */
data class ToCheckRow(
    val detectionId: Long,
    val photoPath: String?,
    val candidates: List<Candidate>,
    val suggested: Candidate?,
    val picked: Candidate?,
    val checked: Boolean,
    val rejected: Boolean,
    val reason: ReviewReason,
    /** The capture was read as an English card (FR-11). */
    val english: Boolean,
    /** Pack language the manual search opens on when there is no candidate to take it from. */
    val searchLang: String,
)

/** A read-only report: a card already owned (FR-10) or a repeat of the burst (EC-9). */
data class OwnedRow(
    val detectionId: Long,
    val photoPath: String?,
    /** `null` when the capture named no card at all. */
    val card: Candidate?,
)

/** The manual-search sheet of one row to check. */
data class ReviewSearch(
    val detectionId: Long,
    /** Languages with an installed pack; empty means nothing can be searched. */
    val langs: List<String>,
    val lang: String?,
    val english: Boolean,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<Candidate> = emptyList(),
)

/** Failures the screen reports with a message; nothing was written in either case. */
enum class ReviewError { SESSION_CLOSED, COMMIT_FAILED }

/** Everything the review screen draws. */
data class ReviewUiState(
    val loading: Boolean = true,
    /** The session is unknown or already closed: there is nothing to review. */
    val unavailable: Boolean = false,
    val recognised: List<RecognisedRow> = emptyList(),
    val toCheck: List<ToCheckRow> = emptyList(),
    val owned: List<OwnedRow> = emptyList(),
    val duplicates: List<OwnedRow> = emptyList(),
    val search: ReviewSearch? = null,
    val committing: Boolean = false,
    val error: ReviewError? = null,
    /** Validated: the screen leaves for the collection. */
    val finished: Boolean = false,
) {

    /** Cards « Tout valider » adds to the collection. */
    val commitCount: Int
        get() = recognised.count { it.checked } + toCheck.count { it.checked && !it.rejected }

    val rejectedCount: Int get() = toCheck.count { it.rejected }

    /** Rows to check left undecided: they are dropped with the session, so the user is warned. */
    val unresolvedCount: Int get() = toCheck.count { !it.checked && !it.rejected }

    val isEmpty: Boolean
        get() = !loading && !unavailable && recognised.isEmpty() && toCheck.isEmpty() &&
            owned.isEmpty() && duplicates.isEmpty()
}
