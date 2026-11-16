package org.tcgscan.app.scan

import org.tcgscan.app.data.pack.EmbeddingIndex

/**
 * The confidence policy of the scanner (D16), in numbers — and the only place they are written.
 *
 * The rule these constants encode: a detection is **pre-checked** (« Reconnue », FR-9) only when the
 * visual cosine is at least [MIN_VISUAL_COSINE], the runner-up is at least [MIN_MARGIN] behind, the
 * OCR name or number agrees, and the card has its own visual reference (`has_embed = 1`). Anything
 * else goes to « à vérifier » with the [MAX_CANDIDATES] best candidates (FR-8).
 *
 * `scan/match/Matcher.kt` is the only reader of the decision thresholds; tuning the scanner means
 * editing this file and nothing else.
 */
object ScanTuning {

    /** Minimum int8 cosine (docs/pack-format.md §3.3) for a visual match to be trusted alone. */
    const val MIN_VISUAL_COSINE = 0.72f

    /** Minimum score distance to the runner-up; below it the detection is ambiguous (EC-11). */
    const val MIN_MARGIN = 0.06f

    /** Learned references kept per card (newest first); older ones are trimmed at validation. */
    const val LEARNED_VECTORS_PER_CARD = 3

    /** How many visual neighbours are re-scored with the OCR readings. */
    const val TOP_K = 30

    /** How many candidates the review screen is given (FR-8). */
    const val MAX_CANDIDATES = 3

    /** Score added when the printed `local_id` agrees with the card's. */
    const val NUMBER_AGREEMENT_BONUS = 0.08f

    /** Extra score when the printed denominator also agrees — the full « 136/201 » (docs §5). */
    const val FRACTION_AGREEMENT_BONUS = 0.04f

    /** Score added when a read name agrees, scaled by the similarity itself. */
    const val NAME_AGREEMENT_BONUS = 0.10f

    /** Score added when the set id or the set name was read on the card (docs §5). */
    const val SET_AGREEMENT_BONUS = 0.03f

    /**
     * Base score of a candidate found by OCR alone, i.e. with no comparable vector in the pack
     * (FR-3). It sits below [MIN_VISUAL_COSINE] on purpose: such a candidate can be ranked first in
     * « à vérifier », never pre-checked.
     */
    const val OCR_ONLY_BASE_SCORE = 0.50f

    /**
     * Penalty applied to a card whose embedding comes from the EN image of the same id
     * (`has_embed = 2`, D15/FR-4): usable as a hint, down-weighted against a native reference.
     */
    const val EN_FALLBACK_PENALTY = 0.04f

    /** Minimum Levenshtein ratio between a read name and `cards.name_norm` for the name to agree. */
    const val MIN_NAME_SIMILARITY = 0.80f

    /** Absolute edit-distance ceiling for a name agreement, so long names stay tightly matched. */
    const val MAX_NAME_DISTANCE = 3

    /** Similarity above which a read name counts as the *exact* name (FR-3 pre-selection). */
    const val EXACT_NAME_SIMILARITY = 0.95f

    /** Rows read per printed-number lookup of the OCR-only path. */
    const val OCR_NUMBER_LOOKUP_LIMIT = 60

    /** Rows read per name lookup of the OCR-only path. */
    const val OCR_NAME_LOOKUP_LIMIT = 20

    /** Hard ceiling on the rows the OCR-only path pulls out of one pack for one detection. */
    const val MAX_OCR_LOOKUP_ROWS = 200

    init {
        // EmbeddingIndex.topK rejects a k above its preallocated buffers; fail at startup, not mid-scan.
        require(TOP_K in MAX_CANDIDATES..EmbeddingIndex.MAX_TOP_K) {
            "TOP_K=$TOP_K must be within $MAX_CANDIDATES..${EmbeddingIndex.MAX_TOP_K}"
        }
    }
}
