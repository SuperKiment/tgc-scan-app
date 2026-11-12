package org.tcgscan.app.scan.match

import org.tcgscan.app.data.pack.EmbeddingIndex
import org.tcgscan.app.data.pack.PackCard
import org.tcgscan.app.data.pack.PackCatalog
import org.tcgscan.app.data.pack.PackSet
import org.tcgscan.app.scan.ScanTuning
import org.tcgscan.app.scan.text.CardScript
import org.tcgscan.app.scan.text.ParsedCardText
import org.tcgscan.app.scan.text.TextNormalizer
import kotlin.math.max
import kotlin.math.min

/**
 * One installed pack the matcher may read: its catalogue and, when it is usable, its visual index.
 *
 * Pass `index = null` whenever the vectors must not be trusted — no `embeddings` table, or a
 * `pack_meta.embed_model` that is not the model bundled in this build
 * (`PackMeta.matchesEmbedModel`, docs/pack-format.md §2.1). The matcher then falls back to the
 * OCR-only path (FR-3) instead of comparing incomparable vectors.
 */
class MatchPack(val catalog: PackCatalog, val index: EmbeddingIndex?) {

    /** Language of this pack, `"fr"` or `"ja"` (D10). */
    val lang: String get() = catalog.lang

    init {
        require(index == null || index.lang == catalog.lang) {
            "Index language ${index?.lang} does not match catalogue language ${catalog.lang}"
        }
    }
}

/**
 * Turns a card embedding and its OCR readings into the candidates of the review screen, and decides
 * whether the detection may be pre-checked (FR-2, FR-8, D16).
 *
 * The hybrid rule, entirely local:
 *
 * 1. the visual top-[ScanTuning.TOP_K] of the chosen language comes from
 *    [EmbeddingIndex.topK] — the app's single similarity entry point — over the pack's vectors and
 *    over the vectors learned from the user's validated scans, merged per card on the best cosine;
 * 2. every neighbour is **re-scored** with its OCR agreement (printed number, the full
 *    « 136/201 », set id or set name, normalised-name Levenshtein ratio), so an identical-art
 *    reprint is separated by what is printed on it (FR-2) and not by a hair of cosine;
 * 3. cards the OCR points at but the index cannot contain — `has_embed = 0`, 70 % of the Japanese
 *    cards — are looked up by name and number through [PackCatalog] and merged in (FR-3);
 * 4. the margin of D16 is taken on the **re-scored** values for that very reason; the
 *    [ScanTuning.MIN_VISUAL_COSINE] threshold is taken on the raw cosine, so no amount of OCR
 *    agreement can manufacture a pre-check.
 *
 * Nothing here writes anything: the outcome is persisted and shown by `scan/ScanPipeline.kt`, and
 * only the review screen commits a card to the collection (FR-9).
 *
 * Every call does a handful of SQLite reads; it is blocking and expects the scan dispatcher.
 */
object Matcher {

    /** `physical_lang` of an English card filed under its French id (FR-11/AC-12). */
    const val PHYSICAL_LANG_EN = "en"

    /** The pack an English card is proposed from: FR and EN share their card ids (SPEC Context). */
    private const val FRENCH_PACK_LANG = "fr"

    /** Shortest set name compared against the card text; shorter ones match far too easily. */
    private const val MIN_SET_NAME_LENGTH = 4

    /** Best score first, then the stronger visual evidence, then the id so ties are stable. */
    private val BY_SCORE = compareByDescending<Candidate> { it.score }
        .thenByDescending { it.cosine ?: -1f }
        .thenBy { it.cardId }

    /**
     * Matches one detection.
     *
     * @param embedding L2-normalised 384-float embedding of the warped card (docs §3.2), or `null`
     *   when no usable crop could be embedded — the detection then takes the OCR-only path (FR-3).
     * @param parsedText what `scan/text/CardTextParser.kt` read on the card.
     * @param script the language the OCR established; [CardScript.UNKNOWN] makes [sessionLang] apply
     *   and forces a review (FR-5).
     * @param sessionLang default language of the scan session, a TCGdex code (`fr`, `ja`).
     * @param installedPacks the readable packs, keyed by language code.
     * @param learnedIndexes the references learned on the device, keyed by pack language; a language
     *   without one is simply absent.
     */
    fun match(
        embedding: FloatArray?,
        parsedText: ParsedCardText,
        script: CardScript,
        sessionLang: String,
        installedPacks: Map<String, MatchPack>,
        learnedIndexes: Map<String, EmbeddingIndex> = emptyMap(),
    ): MatchOutcome {
        require(sessionLang.isNotBlank()) { "The scan session has no default language" }

        val isEnglish = script == CardScript.EN
        val lang = if (isEnglish) FRENCH_PACK_LANG else script.packLang ?: sessionLang
        val pack = installedPacks[lang]
            ?: return MatchOutcome.NoCandidate(lang, ReviewReason.PACK_MISSING)

        val physicalLang = if (isEnglish) PHYSICAL_LANG_EN else lang
        val ranked = collect(pack, learnedIndexes[lang], embedding, parsedText, physicalLang)
            .sortedWith(BY_SCORE)
        val top = ranked.firstOrNull() ?: return MatchOutcome.NoCandidate(
            lang,
            if (isEnglish) ReviewReason.ENGLISH_UNSUPPORTED else ReviewReason.NO_MATCH,
        )

        val candidates = ranked.take(ScanTuning.MAX_CANDIDATES)
        // With a single candidate there is no runner-up to be close to: its own score is the margin.
        val margin = top.score - (ranked.getOrNull(1)?.score ?: 0f)
        val cosine = top.cosine

        val blocker = when {
            isEnglish -> ReviewReason.ENGLISH_CARD
            !script.isKnown -> ReviewReason.LANGUAGE_UNKNOWN
            cosine == null || !top.hasOwnVisualReference -> ReviewReason.NO_VISUAL_REFERENCE
            cosine < ScanTuning.MIN_VISUAL_COSINE -> ReviewReason.LOW_CONFIDENCE
            margin < ScanTuning.MIN_MARGIN -> ReviewReason.AMBIGUOUS
            !top.agreement.confirmsIdentity -> ReviewReason.OCR_UNCONFIRMED
            else -> null
        }
        return if (blocker == null) {
            MatchOutcome.Confident(card = top, margin = margin, candidates = candidates)
        } else {
            MatchOutcome.Review(
                lang = lang,
                candidates = candidates,
                reason = blocker,
                preSelected = preSelected(top, ranked, margin),
            )
        }
    }

    /**
     * The candidate the review row opens on, or `null` when the user must choose unaided.
     *
     * Two cases deserve a cursor: an OCR match that is **exact and unique** (FR-3), and a visual
     * match that cleared both thresholds and was only held back by the language rule or by a silent
     * OCR (FR-11/FR-5/EC-7). An ambiguous pair (EC-11) clears neither and is never pre-selected.
     */
    private fun preSelected(top: Candidate, ranked: List<Candidate>, margin: Float): Candidate? {
        val cosine = top.cosine
        return when {
            top.agreement.isExact && ranked.count { it.agreement.isExact } == 1 -> top
            cosine != null && top.hasOwnVisualReference &&
                cosine >= ScanTuning.MIN_VISUAL_COSINE && margin >= ScanTuning.MIN_MARGIN -> top
            else -> null
        }
    }

    /**
     * The scored candidates of one detection: the re-scored visual neighbours, plus the cards only
     * the OCR can reach (FR-3). A card found by both keeps its visual score.
     */
    private fun collect(
        pack: MatchPack,
        learned: EmbeddingIndex?,
        embedding: FloatArray?,
        parsedText: ParsedCardText,
        physicalLang: String,
    ): List<Candidate> {
        val byCardId = LinkedHashMap<String, Candidate>()
        // `sets` is read once per set and reused: a top-K spans a handful of sets at most.
        val sets = HashMap<String, PackSet?>()

        val hits = visualHits(pack.index, learned, embedding)
        if (hits.isNotEmpty()) {
            val cards = pack.catalog.cardsByIds(hits.keys)
            for ((cardId, hit) in hits) {
                // A vector without its `cards` row cannot happen in a valid pack (docs §2.2), but a
                // learned one can outlive its card when a newer pack drops it (EC-16); skip it.
                val card = cards[cardId] ?: continue
                byCardId[cardId] = candidate(
                    pack, card, sets, parsedText, physicalLang, hit.cosine, hit.fromLearned,
                )
            }
        }

        if (!parsedText.isEmpty) {
            for (card in ocrLookup(pack.catalog, parsedText)) {
                if (byCardId.containsKey(card.cardId)) continue
                val candidate = candidate(pack, card, sets, parsedText, physicalLang, cosine = null)
                if (candidate.agreement.supportsOcrOnlyCandidate) byCardId[card.cardId] = candidate
            }
        }
        return ArrayList(byCardId.values)
    }

    /** Best visual evidence for one card; [fromLearned] says which vector produced [cosine]. */
    private class VisualHit(val cosine: Float, val fromLearned: Boolean)

    /**
     * The visual neighbours of [embedding] among the pack's vectors and the [learned] ones, one hit
     * per card: the highest cosine wins, and a tie goes to the learned vector. [VisualHit.fromLearned]
     * follows the winner, so a card is only treated as learned (own reference, no EN-fallback
     * penalty) when its learned vector is really what matched.
     */
    private fun visualHits(
        index: EmbeddingIndex?,
        learned: EmbeddingIndex?,
        embedding: FloatArray?,
    ): Map<String, VisualHit> {
        val hits = LinkedHashMap<String, VisualHit>()
        if (embedding == null) return hits
        if (index != null && index.size > 0 && embedding.size == index.dim) {
            for ((cardId, cosine) in index.topK(embedding, ScanTuning.TOP_K)) {
                hits[cardId] = VisualHit(cosine, fromLearned = false)
            }
        }
        if (learned != null && learned.size > 0 && embedding.size == learned.dim) {
            for ((cardId, cosine) in learned.topK(embedding, ScanTuning.TOP_K)) {
                val known = hits[cardId]
                if (known == null || cosine >= known.cosine) {
                    hits[cardId] = VisualHit(cosine, fromLearned = true)
                }
            }
        }
        return hits
    }

    private fun candidate(
        pack: MatchPack,
        card: PackCard,
        sets: HashMap<String, PackSet?>,
        parsedText: ParsedCardText,
        physicalLang: String,
        cosine: Float?,
        fromLearned: Boolean = false,
    ): Candidate {
        val set = if (sets.containsKey(card.setId)) {
            sets[card.setId]
        } else {
            pack.catalog.set(card.setId).also { sets[card.setId] = it }
        }
        return Candidate.of(
            card = card,
            set = set,
            lang = pack.lang,
            physicalLang = physicalLang,
            cosine = cosine,
            agreement = agreementOf(card, set, parsedText),
            fromLearnedReference = fromLearned,
        )
    }

    /**
     * Rows the OCR points at, by printed number then by name (docs §5).
     *
     * Only the number candidates are used as *lookup* keys, never the set-id-like readings: a set id
     * is an `ocr_tokens` row of **every** card of its set, so searching it would pull two hundred
     * rows that nothing distinguishes. The set is used as a scoring agreement instead.
     */
    private fun ocrLookup(catalog: PackCatalog, parsedText: ParsedCardText): List<PackCard> {
        val found = LinkedHashMap<String, PackCard>()

        parsedText.printedFraction?.let { fraction ->
            addRows(found, catalog.searchByNumber(fraction, ScanTuning.OCR_NUMBER_LOOKUP_LIMIT))
        }
        for (number in parsedText.localIdCandidates) {
            if (found.size >= ScanTuning.MAX_OCR_LOOKUP_ROWS) break
            addRows(found, catalog.searchByNumber(number, ScanTuning.OCR_NUMBER_LOOKUP_LIMIT))
        }
        for (name in parsedText.candidateNames) {
            if (found.size >= ScanTuning.MAX_OCR_LOOKUP_ROWS) break
            addRows(found, catalog.searchByName(name, ScanTuning.OCR_NAME_LOOKUP_LIMIT))
        }
        return ArrayList(found.values)
    }

    private fun addRows(into: LinkedHashMap<String, PackCard>, rows: List<PackCard>) {
        for (row in rows) {
            if (into.size >= ScanTuning.MAX_OCR_LOOKUP_ROWS) return
            if (!into.containsKey(row.cardId)) into[row.cardId] = row
        }
    }

    /** How much the readings agree with one card, on the printed facts of docs §5. */
    private fun agreementOf(
        card: PackCard,
        set: PackSet?,
        parsedText: ParsedCardText,
    ): OcrAgreement {
        val localIdMatch = parsedText.localIdCandidates.any { it.equals(card.localId, ignoreCase = true) }
        val total = parsedText.total
        val totalMatch = localIdMatch && total != null && set != null && set.cardCountTotal == total

        var bestSimilarity = 0f
        var bestDistance = Int.MAX_VALUE
        for (name in parsedText.candidateNames) {
            val distance = editDistance(name, card.nameNorm)
            val similarity = similarityOf(name, card.nameNorm, distance)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestDistance = distance
            }
        }

        return OcrAgreement(
            localIdMatch = localIdMatch,
            totalMatch = totalMatch,
            setMatch = matchesSet(card, set, parsedText),
            nameSimilarity = bestSimilarity,
            nameDistance = bestDistance,
        )
    }

    /** True when the set id was read as a token, or the set name appears in the card text (docs §5). */
    private fun matchesSet(card: PackCard, set: PackSet?, parsedText: ParsedCardText): Boolean {
        if (parsedText.setIdTokens.any { it.equals(card.setId, ignoreCase = true) }) return true
        val setName = set?.name?.let(TextNormalizer::normalize) ?: return false
        return setName.length >= MIN_SET_NAME_LENGTH && parsedText.normalizedText.contains(setName)
    }

    /** `1 - distance / length` of the longer string, in `0f..1f`. */
    private fun similarityOf(a: String, b: String, distance: Int): Float {
        val longest = max(a.length, b.length)
        if (longest == 0) return 0f
        return (1f - distance.toFloat() / longest).coerceIn(0f, 1f)
    }

    /**
     * Levenshtein distance between two already-normalised names (docs §5.1), two rows at a time.
     *
     * Names are a few dozen characters and a handful of readings are compared against
     * [ScanTuning.TOP_K] cards, so the quadratic cost is negligible next to the brute-force cosine
     * pass that produced those cards.
     */
    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            val left = a[i - 1]
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (left == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
