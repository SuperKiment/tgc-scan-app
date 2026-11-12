package org.tcgscan.app.scan.text

/**
 * One OCR reading handed over to the text layer, reduced to what interpretation needs.
 *
 * [yCenter] is the vertical centre of the recognised box expressed as a fraction of the
 * perspective-corrected card height — `0f` at the top edge, `1f` at the bottom edge — which is what
 * tells a name apart from the printed number (FR-2). Use [fromBox] to build it from pixels.
 */
data class OcrLine(
    val text: String,
    val confidence: Float,
    val yCenter: Float,
) {

    companion object {

        /**
         * Builds a line from a recognised box in card pixel coordinates.
         *
         * @param cardHeightPx height of the warped card; a non-positive value yields `yCenter = 0f`
         *   rather than an arithmetic error, so a degenerate crop cannot crash the scan loop.
         */
        fun fromBox(
            text: String,
            confidence: Float,
            boxTopPx: Float,
            boxBottomPx: Float,
            cardHeightPx: Float,
        ): OcrLine {
            val center = if (cardHeightPx > 0f) {
                ((boxTopPx + boxBottomPx) / 2f / cardHeightPx).coerceIn(0f, 1f)
            } else {
                0f
            }
            return OcrLine(text = text, confidence = confidence, yCenter = center)
        }
    }
}

/**
 * What the OCR of one card yielded, in the forms the matcher looks up (FR-2/FR-3).
 *
 * Every text member is in the canonical normalisation of [TextNormalizer] / docs/pack-format.md
 * §5.1, and every number member is lowercase, so they can be compared to `cards.name_norm` and to
 * `ocr_tokens.token` without further processing.
 */
data class ParsedCardText(
    /** Plausible card names, best first: the cleaned readings of the top third of the card. */
    val candidateNames: List<String>,
    /** The most plausible printed number (`136`, `sv043`), `null` when none was read. */
    val localId: String?,
    /** Printed denominator of « 136/201 », `null` when no fraction was read. */
    val total: Int?,
    /** Every plausible printed number, best first; [localId] is its first element. */
    val localIdCandidates: List<String>,
    /** Set-id-like readings (`swsh3`), which often match the small printed code (docs §5). */
    val setIdTokens: List<String>,
    /** The whole card text, normalised — used for the set-name comparison of docs §5. */
    val normalizedText: String,
) {

    /** The printed fraction token of `ocr_tokens` (docs §2.3), when both parts were read. */
    val printedFraction: String? =
        if (localId != null && total != null) "$localId/$total" else null

    /**
     * Keys to look up in `ocr_tokens`, most selective first: the printed fraction, then every
     * number candidate, then the set-id-like readings.
     */
    val ocrTokens: List<String> = buildList {
        printedFraction?.let(::add)
        addAll(localIdCandidates)
        addAll(setIdTokens)
    }.distinct()

    /** True when the OCR gave nothing to match on, so only the visual path can decide (FR-8). */
    val isEmpty: Boolean = candidateNames.isEmpty() && localIdCandidates.isEmpty()

    companion object {

        /** The result of an OCR that read nothing usable. */
        val EMPTY = ParsedCardText(
            candidateNames = emptyList(),
            localId = null,
            total = null,
            localIdCandidates = emptyList(),
            setIdTokens = emptyList(),
            normalizedText = "",
        )
    }
}

/**
 * Turns the OCR readings of one card into the name, number and set-code candidates of FR-2/FR-3.
 *
 * Positions matter and are read from [OcrLine.yCenter]: names are taken from the top third of the
 * card, the printed number from the bottom strip. Numbers are matched on the **raw** readings
 * because the canonical normalisation collapses the `/` of « 136/201 »; names are matched on the
 * normalised form so they can be compared to `cards.name_norm` (docs/pack-format.md §5.1).
 *
 * This class decides nothing: it never ranks a card, it only extracts what `scan/match/Matcher.kt`
 * needs to confirm or separate the visual candidates.
 */
object CardTextParser {

    /** End of the « top third » of the card, where the name is printed. */
    const val NAME_BAND_END = 0.34f

    /** Start of the bottom strip, where the printed number and set code live. */
    const val NUMBER_BAND_START = 0.75f

    /** How many name candidates are kept at most; the matcher scores all of them. */
    const val MAX_CANDIDATE_NAMES = 4

    /** How many number candidates are kept at most. */
    const val MAX_LOCAL_ID_CANDIDATES = 4

    /** How many set-id-like tokens are kept at most. */
    const val MAX_SET_ID_TOKENS = 4

    private const val MIN_NAME_LENGTH = 3
    private const val MIN_SET_ID_LENGTH = 3
    private const val MAX_SET_ID_LENGTH = 10

    /** « 136 / 201 » — the printed fraction, read on the raw text (docs §5). */
    private val FRACTION = Regex("""(\d{1,3})\s*/\s*(\d{1,3})""")

    /** Promo and alphanumeric `local_id` forms: `SV043`, `XY12`, `H7` (docs §2.3). */
    private val PROMO_LOCAL_ID = Regex(
        """(?<![A-Za-z0-9])(SV|XY|H)-?(\d{1,3})(?![0-9])""",
        RegexOption.IGNORE_CASE,
    )

    /** A bare number, never a part of a fraction (those are handled by [FRACTION]). */
    private val BARE_NUMBER = Regex("""(?<![\d/])(\d{1,3})(?![\d/])""")

    /** Letters followed by digits, the shape of a TCGdex set id (`swsh3`, `sv03`). */
    private val SET_ID_TOKEN = Regex("""[a-z]{2,6}\d{1,3}[a-z]?""")

    /** Tokens printed next to the name that are never part of it (`180 PV`, `70 HP`). */
    private val NAME_STOP_TOKENS = setOf("pv", "hp")

    /** Parses one card's OCR readings; an empty input yields [ParsedCardText.EMPTY]. */
    fun parse(lines: List<OcrLine>): ParsedCardText {
        if (lines.isEmpty()) return ParsedCardText.EMPTY

        val normalizedText = lines
            .sortedBy { it.yCenter }
            .map { TextNormalizer.normalize(it.text) }
            .filter { it.isNotEmpty() }
            .joinToString(separator = " ")

        val numbers = parseNumbers(lines)
        return ParsedCardText(
            candidateNames = candidateNames(lines),
            localId = numbers.localIds.firstOrNull(),
            total = numbers.total,
            localIdCandidates = numbers.localIds,
            setIdTokens = setIdTokens(normalizedText, numbers.localIds),
            normalizedText = normalizedText,
        )
    }

    /** [parse] of a single reading, for the OCR-only path where positions are unknown. */
    fun parse(line: OcrLine): ParsedCardText = parse(listOf(line))

    /**
     * Cleaned name candidates from the top third: each reading minus its number and `PV`/`HP`
     * tokens, longest first and, at equal length, most confident first.
     */
    private fun candidateNames(lines: List<OcrLine>): List<String> {
        val scored = ArrayList<Pair<String, Float>>(lines.size)
        for (line in lines) {
            if (line.yCenter > NAME_BAND_END) continue
            val name = TextNormalizer.tokenize(line.text)
                .filter { token -> token !in NAME_STOP_TOKENS && !token.isAsciiDigits() }
                .joinToString(separator = " ")
            if (name.length < MIN_NAME_LENGTH) continue
            scored += name to line.confidence
        }
        return scored
            .sortedWith(
                compareByDescending<Pair<String, Float>> { it.first.length }
                    .thenByDescending { it.second },
            )
            .map { it.first }
            .distinct()
            .take(MAX_CANDIDATE_NAMES)
    }

    /**
     * Number candidates, best first: a fraction of the bottom strip beats a promo form, which beats
     * the same readings found anywhere on the card, which beat a bare number of the bottom strip.
     *
     * [Numbers.total] comes from the fraction that produced the best candidate, so a « 136/201 »
     * read in the bottom strip always wins over an attack cost read elsewhere.
     */
    private fun parseNumbers(lines: List<OcrLine>): Numbers {
        val bottomFractions = ArrayList<Pair<String, Int>>()
        val otherFractions = ArrayList<Pair<String, Int>>()
        val bottomPromos = ArrayList<String>()
        val otherPromos = ArrayList<String>()
        val bottomBareNumbers = ArrayList<String>()

        for (line in lines) {
            val inBottomStrip = line.yCenter >= NUMBER_BAND_START
            for (match in FRACTION.findAll(line.text)) {
                val localId = match.groupValues[1]
                val total = match.groupValues[2].toIntOrNull() ?: continue
                val found = localId to total
                if (inBottomStrip) bottomFractions += found else otherFractions += found
            }
            for (match in PROMO_LOCAL_ID.findAll(line.text)) {
                val promo = (match.groupValues[1] + match.groupValues[2]).lowercase()
                if (inBottomStrip) bottomPromos += promo else otherPromos += promo
            }
            if (inBottomStrip) {
                for (match in BARE_NUMBER.findAll(line.text)) {
                    bottomBareNumbers += match.groupValues[1]
                }
            }
        }

        val fractions = bottomFractions + otherFractions
        val ordered = buildList {
            bottomFractions.forEach { add(it.first) }
            addAll(bottomPromos)
            otherFractions.forEach { add(it.first) }
            addAll(otherPromos)
            addAll(bottomBareNumbers)
        }

        val localIds = ArrayList<String>(MAX_LOCAL_ID_CANDIDATES)
        for (candidate in ordered) {
            addNumberCandidate(localIds, candidate)
            // The printed number may carry leading zeros that TCGdex does not (`007` vs `7`).
            addNumberCandidate(localIds, candidate.withoutLeadingZeros())
            if (localIds.size >= MAX_LOCAL_ID_CANDIDATES) break
        }

        val total = fractions.firstOrNull { it.first in localIds }?.second
            ?: fractions.firstOrNull()?.second
        return Numbers(localIds = localIds.take(MAX_LOCAL_ID_CANDIDATES), total = total)
    }

    private fun addNumberCandidate(into: MutableList<String>, candidate: String) {
        if (candidate.isNotEmpty() && candidate !in into) into += candidate
    }

    /** Set-id-like tokens of the whole normalised text, minus the readings already kept as numbers. */
    private fun setIdTokens(normalizedText: String, localIds: List<String>): List<String> =
        normalizedText
            .split(' ')
            .filter { token ->
                token.length in MIN_SET_ID_LENGTH..MAX_SET_ID_LENGTH &&
                    token !in localIds &&
                    SET_ID_TOKEN.matches(token)
            }
            .distinct()
            .take(MAX_SET_ID_TOKENS)

    private fun String.withoutLeadingZeros(): String {
        val trimmed = trimStart('0')
        return if (trimmed.isEmpty() && isNotEmpty()) "0" else trimmed
    }

    private fun String.isAsciiDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }

    private data class Numbers(val localIds: List<String>, val total: Int?)
}
