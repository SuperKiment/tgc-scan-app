package org.tcgscan.app.scan.match

import kotlinx.serialization.Serializable
import org.tcgscan.app.data.pack.HasEmbed
import org.tcgscan.app.data.pack.PackCard
import org.tcgscan.app.data.pack.PackSet
import org.tcgscan.app.scan.ScanTuning

/**
 * What the matcher proposes for one detection, and why the user has to look at it.
 *
 * [Candidate] is a flat, [Serializable] snapshot on purpose: a detection is persisted with its
 * candidates as JSON before it is shown (EC-13), and the review screen must still render the row
 * — visual, name and printed number — when the pack has meanwhile been updated or removed (EC-16).
 */

/** Why a detection goes to « à vérifier » instead of being pre-checked (FR-8). */
enum class ReviewReason {

    /** The best visual cosine is below [ScanTuning.MIN_VISUAL_COSINE]. */
    LOW_CONFIDENCE,

    /** Two candidates are within [ScanTuning.MIN_MARGIN]: an identical-art reprint, never a silent pick (EC-11). */
    AMBIGUOUS,

    /** The best candidate has no own visual reference — `has_embed` 0 or 2 (FR-3, D15). */
    NO_VISUAL_REFERENCE,

    /** The visual match is strong but neither the name nor the number could confirm it (EC-7). */
    OCR_UNCONFIRMED,

    /** The OCR could not establish the language, so the session default was used (FR-5). */
    LANGUAGE_UNKNOWN,

    /** An English card: the French card of the same id is proposed, `physical_lang = en` (FR-11). */
    ENGLISH_CARD,

    /** An English card with no French equivalent: « anglais non pris en charge » (EC-10). */
    ENGLISH_UNSUPPORTED,

    /** No pack is installed for the detected (or default) language (EC-1/EC-12). */
    PACK_MISSING,

    /** Nothing plausible at all: another game, a TCG Pocket print, an object (EC-6). */
    NO_MATCH,
}

/**
 * How much the OCR readings of the scan agree with one candidate card (FR-2).
 *
 * Numbers are compared on the printed forms of docs/pack-format.md §5 (`local_id`, the fraction
 * `local_id/card_count_total`, the set id and the set name); names are compared on the canonical
 * normalisation of `cards.name_norm` (docs §5.1).
 */
@Serializable
data class OcrAgreement(
    /** A read printed number equals the candidate's `local_id`. */
    val localIdMatch: Boolean = false,
    /** The read number *and* its denominator match — the full « 136/201 ». */
    val totalMatch: Boolean = false,
    /** The set id or the set name was read on the card. */
    val setMatch: Boolean = false,
    /** Best Levenshtein ratio between a read name and `cards.name_norm`, in `0f..1f`. */
    val nameSimilarity: Float = 0f,
    /** Edit distance of the reading that produced [nameSimilarity]. */
    val nameDistance: Int = Int.MAX_VALUE,
) {

    /** True when a read name is close enough to the card's to count as the same name. */
    val nameMatch: Boolean
        get() = nameSimilarity >= ScanTuning.MIN_NAME_SIMILARITY &&
            nameDistance <= ScanTuning.MAX_NAME_DISTANCE

    /** The OCR agreement D16 demands before a visual match may be pre-checked: name **or** number. */
    val confirmsIdentity: Boolean get() = nameMatch || localIdMatch

    /**
     * True for an *exact* OCR match: the printed number plus a second printed fact (the
     * denominator, the set, or the very name). Used for the FR-3 pre-selection, which also requires
     * uniqueness.
     */
    val isExact: Boolean
        get() = localIdMatch &&
            (totalMatch || setMatch || nameSimilarity >= ScanTuning.EXACT_NAME_SIMILARITY)

    /**
     * True when the OCR alone is specific enough to propose this card (FR-3). A bare number match is
     * deliberately not enough: the same `local_id` is printed in two hundred sets.
     */
    val supportsOcrOnlyCandidate: Boolean
        get() = nameMatch || (localIdMatch && (totalMatch || setMatch))

    /** Score added to the visual cosine, or to [ScanTuning.OCR_ONLY_BASE_SCORE]. */
    val bonus: Float
        get() {
            var bonus = 0f
            if (localIdMatch) bonus += ScanTuning.NUMBER_AGREEMENT_BONUS
            if (totalMatch) bonus += ScanTuning.FRACTION_AGREEMENT_BONUS
            if (setMatch) bonus += ScanTuning.SET_AGREEMENT_BONUS
            if (nameMatch) bonus += ScanTuning.NAME_AGREEMENT_BONUS * nameSimilarity
            return bonus
        }

    companion object {

        /** Nothing in the OCR relates to the card — or nothing was read at all. */
        val NONE = OcrAgreement()
    }
}

/**
 * One card proposed for a detection, with the evidence that put it there.
 *
 * [cosine] is the int8 cosine of the visual index (docs/pack-format.md §3.3) and is `null` for a
 * candidate found by OCR alone. [score] is the ordering value: [cosine] (or
 * [ScanTuning.OCR_ONLY_BASE_SCORE]) plus [OcrAgreement.bonus], minus the EN-fallback penalty of
 * D15. Only [cosine] is ever compared to a threshold.
 */
@Serializable
data class Candidate(
    /** Pack language the [cardId] belongs to, `"fr"` or `"ja"` (D10). */
    val lang: String,
    val cardId: String,
    val name: String,
    val localId: String,
    val setId: String,
    /** `sets.name`, or an empty string when the set row is missing from the pack. */
    val setName: String,
    /** `sets.card_count_total`, the printed denominator; `0` when unknown. */
    val setCardCountTotal: Int,
    /** TCGdex image *stem*; the asset is `$imageUrl/low.webp` (docs §4.5). */
    val imageUrl: String?,
    /** `cards.has_embed` (docs §2.2). */
    val hasEmbed: Int,
    /** Language printed on the cardboard, `"en"` for the FR-11 case; stored as `physical_lang`. */
    val physicalLang: String,
    /** Visual cosine, `null` when this candidate comes from the OCR-only path (FR-3). */
    val cosine: Float?,
    val score: Float,
    val agreement: OcrAgreement,
) {

    /** True only for `has_embed = 1`: the sole case a detection may be pre-checked (D16/FR-3). */
    val hasOwnVisualReference: Boolean get() = hasEmbed == HasEmbed.OWN_IMAGE

    /** True when the candidate was found in the visual index. */
    val isVisual: Boolean get() = cosine != null

    /** « 136/201 » when the set is known, « 136 » otherwise. */
    val printedNumber: String
        get() = if (setCardCountTotal > 0) "$localId/$setCardCountTotal" else localId

    companion object {

        /** Builds a candidate from a pack row; [set] may be `null` for a malformed pack (EC-16). */
        fun of(
            card: PackCard,
            set: PackSet?,
            lang: String,
            physicalLang: String,
            cosine: Float?,
            agreement: OcrAgreement,
        ): Candidate {
            val base = cosine ?: ScanTuning.OCR_ONLY_BASE_SCORE
            val penalty =
                if (card.hasEmbed == HasEmbed.EN_IMAGE_FALLBACK) ScanTuning.EN_FALLBACK_PENALTY else 0f
            return Candidate(
                lang = lang,
                cardId = card.cardId,
                name = card.name,
                localId = card.localId,
                setId = card.setId,
                setName = set?.name ?: "",
                setCardCountTotal = set?.cardCountTotal ?: 0,
                imageUrl = card.imageUrl,
                hasEmbed = card.hasEmbed,
                physicalLang = physicalLang,
                cosine = cosine,
                score = base + agreement.bonus - penalty,
                agreement = agreement,
            )
        }
    }
}

/**
 * What `Matcher.match` concluded. The three cases map one-to-one onto the review screen sections
 * and onto `ScanDetectionState`: [Confident] is pre-checked in « Reconnues », [Review] and
 * [NoCandidate] land in « à vérifier » (FR-8/FR-9).
 */
sealed interface MatchOutcome {

    /** Pack language the outcome is about — for [NoCandidate] the language whose pack was needed. */
    val lang: String

    /** The best candidates, at most [ScanTuning.MAX_CANDIDATES], best first. */
    val candidates: List<Candidate>

    /** Why the user must decide, `null` only for [Confident]. */
    val reason: ReviewReason?

    /**
     * Every threshold of D16 is met: the card is pre-checked and a single « Tout valider » commits
     * it (FR-9).
     *
     * The invariants of the policy are enforced here, in the only type that can express a
     * pre-checked detection: own visual reference, cosine above [ScanTuning.MIN_VISUAL_COSINE],
     * runner-up at least [ScanTuning.MIN_MARGIN] behind, and an OCR confirmation.
     */
    data class Confident(
        val card: Candidate,
        /** Score distance to the runner-up, or the score itself when [card] is the only candidate. */
        val margin: Float,
        override val candidates: List<Candidate>,
    ) : MatchOutcome {

        init {
            require(card.hasOwnVisualReference) {
                "Confident requires has_embed=${HasEmbed.OWN_IMAGE}, got ${card.hasEmbed}"
            }
            val cosine = card.cosine
            require(cosine != null && cosine >= ScanTuning.MIN_VISUAL_COSINE) {
                "Confident requires a cosine >= ${ScanTuning.MIN_VISUAL_COSINE}, got $cosine"
            }
            require(margin >= ScanTuning.MIN_MARGIN) {
                "Confident requires a margin >= ${ScanTuning.MIN_MARGIN}, got $margin"
            }
            require(card.agreement.confirmsIdentity) {
                "Confident requires an OCR name or number agreement"
            }
            require(candidates.firstOrNull() == card) { "Confident card must head the candidates" }
        }

        override val lang: String get() = card.lang

        override val reason: ReviewReason? get() = null
    }

    /**
     * « À vérifier » with its candidates (FR-8). [preSelected], when set, is one of [candidates] and
     * is only a cursor placed under the user's thumb — it is never a validation (FR-9).
     */
    data class Review(
        override val lang: String,
        override val candidates: List<Candidate>,
        override val reason: ReviewReason,
        val preSelected: Candidate? = null,
    ) : MatchOutcome {

        init {
            require(preSelected == null || preSelected in candidates) {
                "preSelected must be one of the candidates"
            }
        }
    }

    /** « À vérifier » with no candidate: manual search or rejection only (EC-6/EC-10/EC-12). */
    data class NoCandidate(
        override val lang: String,
        override val reason: ReviewReason,
    ) : MatchOutcome {

        override val candidates: List<Candidate> get() = emptyList()
    }
}
