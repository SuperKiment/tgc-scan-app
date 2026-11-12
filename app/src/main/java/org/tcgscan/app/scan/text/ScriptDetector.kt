package org.tcgscan.app.scan.text

/**
 * Language of the physical card as read by the OCR (FR-5).
 *
 * [UNKNOWN] is not a failure to be hidden: it means the session's default language applies and the
 * card goes to « à vérifier » (FR-5), which is what `scan/match/Matcher.kt` does with it.
 */
enum class CardScript {

    /** French markers were read (`PV`, `Dégâts`, `Faiblesse`). */
    FR,

    /** English markers were read (`HP`, `Weakness`, `Resistance`). */
    EN,

    /** The reading is mostly kana/CJK. */
    JA,

    /** Nothing conclusive: the session default applies and the card is reviewed. */
    UNKNOWN,
    ;

    /** True for a language the OCR actually established. */
    val isKnown: Boolean get() = this != UNKNOWN

    /**
     * TCGdex language code of this script (D10), or `null` for [UNKNOWN] and for [EN], which has no
     * pack — an English card is proposed as the FR card of the same id instead (FR-11/EC-10).
     */
    val packLang: String?
        get() = when (this) {
            FR -> "fr"
            JA -> "ja"
            EN, UNKNOWN -> null
        }
}

/**
 * Decides the language of a scanned card from its OCR text (FR-5).
 *
 * Japanese is decided on the share of kana/CJK code points, French and English on the markers
 * printed on every card. `Résistance` normalises to `resistance`, exactly like the English
 * `Resistance`, so the markers are **counted** rather than tested in order: the language with the
 * most hits wins, and an equal non-zero count is reported as [CardScript.UNKNOWN] rather than
 * guessed.
 *
 * Detection runs on the canonical normalisation of [TextNormalizer], so accents, case and
 * punctuation never change the outcome.
 */
object ScriptDetector {

    /** Share of kana/CJK code points above which the card is read as Japanese (FR-5). */
    const val JA_RATIO_THRESHOLD = 0.15f

    /** Short French markers, matched as whole tokens so they cannot hide inside a word. */
    private val FR_TOKEN_MARKERS = setOf("pv")

    /** Long French markers, matched anywhere because the OCR glues neighbouring words. */
    private val FR_TEXT_MARKERS = listOf("degats", "faiblesse")

    /** Short English markers, matched as whole tokens. */
    private val EN_TOKEN_MARKERS = setOf("hp")

    /** Long English markers, matched anywhere. */
    private val EN_TEXT_MARKERS = listOf("weakness", "resistance")

    /** Detects the language of one card's readings. */
    fun detect(lines: List<OcrLine>): CardScript {
        if (lines.isEmpty()) return CardScript.UNKNOWN
        return detect(lines.joinToString(separator = " ") { it.text })
    }

    /** Detects the language of an already-assembled card text. */
    fun detect(text: String): CardScript {
        val normalized = TextNormalizer.normalize(text)
        if (normalized.isEmpty()) return CardScript.UNKNOWN
        if (ratioOf(normalized) >= JA_RATIO_THRESHOLD) return CardScript.JA

        val tokens = normalized.split(' ').toHashSet()
        val frenchHits = markerHits(normalized, tokens, FR_TOKEN_MARKERS, FR_TEXT_MARKERS)
        val englishHits = markerHits(normalized, tokens, EN_TOKEN_MARKERS, EN_TEXT_MARKERS)
        return when {
            frenchHits > englishHits -> CardScript.FR
            englishHits > frenchHits -> CardScript.EN
            else -> CardScript.UNKNOWN
        }
    }

    /**
     * Share of kana/CJK among the code points kept by [TextNormalizer] — spaces excluded, so the
     * ratio does not depend on how the OCR split the lines.
     *
     * [text] may be raw or already normalised: it is normalised here either way.
     */
    fun kanaCjkRatio(text: String): Float = ratioOf(TextNormalizer.normalize(text))

    private fun ratioOf(normalized: String): Float {
        var kept = 0
        var kanaCjk = 0
        var index = 0
        while (index < normalized.length) {
            val codePoint = normalized.codePointAt(index)
            index += Character.charCount(codePoint)
            if (codePoint == ' '.code) continue
            kept++
            if (TextNormalizer.isKanaOrCjk(codePoint)) kanaCjk++
        }
        return if (kept == 0) 0f else kanaCjk.toFloat() / kept
    }

    private fun markerHits(
        normalized: String,
        tokens: Set<String>,
        tokenMarkers: Set<String>,
        textMarkers: List<String>,
    ): Int {
        var hits = tokenMarkers.count { it in tokens }
        for (marker in textMarkers) {
            if (normalized.contains(marker)) hits++
        }
        return hits
    }
}
