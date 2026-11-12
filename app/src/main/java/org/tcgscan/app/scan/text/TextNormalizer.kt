package org.tcgscan.app.scan.text

import java.text.Normalizer

/**
 * The canonical name normalisation of docs/pack-format.md §5.1.
 *
 * `cards.name_norm` is written by the pipeline with exactly this rule
 * (`pipeline/tcgscan_pipeline/catalog.py`, `normalize_name`) and every name comparison or
 * `LIKE '%' || :q || '%'` query on the device must pass its query through [normalize] first,
 * otherwise an accented or punctuated OCR reading can never match the pack.
 *
 * The rule, verbatim from docs §5.1:
 *
 * 1. Unicode **NFD** decomposition.
 * 2. Remove every combining mark (Unicode category `Mn`) — accents are stripped, and so are the
 *    Japanese dakuten/handakuten by design, on both sides.
 * 3. Lowercase.
 * 4. Replace every run of characters that is neither `[a-z0-9]` nor kana/CJK by a single space,
 *    then trim.
 *
 * Parity notes with `catalog.py`, which must be preserved when either side is touched:
 * - the NFD form is lowercased *before* the marks are dropped, so a lowercase mapping that itself
 *   produces a combining mark (`İ` → `i` + U+0307) collapses the same way;
 * - the text is walked by **code point**, not by UTF-16 `Char`, because the kept CJK ranges reach
 *   into the supplementary plane (U+20000..U+2FA1F);
 * - a separator run never produces a leading space, and the single possible trailing space is
 *   removed at the end.
 */
object TextNormalizer {

    /**
     * Kana and CJK code points kept by [normalize] — the exact ranges of
     * `catalog.py:_KANA_CJK_RANGES`, in the same order.
     */
    private val KANA_CJK_RANGES: Array<IntRange> = arrayOf(
        0x3005..0x3006, // 々 〆, used inside Japanese names
        0x3041..0x3096, // hiragana letters
        0x309D..0x309F, // ゝ ゞ ゟ
        0x30A1..0x30FA, // katakana letters
        0x30FC..0x30FF, // ー ヽ ヾ ヿ (the prolonged sound mark matters, ・ at 30FB does not)
        0x31F0..0x31FF, // katakana phonetic extensions
        0x3400..0x4DBF, // CJK unified ideographs extension A
        0x4E00..0x9FFF, // CJK unified ideographs
        0xF900..0xFAFF, // CJK compatibility ideographs
        0xFF66..0xFF9D, // halfwidth katakana
        0x20000..0x2FA1F, // CJK unified ideographs extensions B..F and compatibility supplement
    )

    private const val SPACE = ' '

    /**
     * Returns the canonical `name_norm` form of [raw]: lowercase `[a-z0-9]` and kana/CJK code
     * points, single spaces in between, no leading or trailing space.
     *
     * A text made only of separators normalises to the empty string.
     */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD).lowercase()
        val out = StringBuilder(decomposed.length)
        var index = 0
        while (index < decomposed.length) {
            val codePoint = decomposed.codePointAt(index)
            index += Character.charCount(codePoint)
            if (Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt()) continue
            if (isKept(codePoint)) {
                out.appendCodePoint(codePoint)
            } else if (out.isNotEmpty() && out[out.length - 1] != SPACE) {
                out.append(SPACE)
            }
        }
        if (out.isNotEmpty() && out[out.length - 1] == SPACE) out.setLength(out.length - 1)
        return out.toString()
    }

    /** [normalize] split on its single spaces: the words of [raw], never empty strings. */
    fun tokenize(raw: String): List<String> {
        val normalized = normalize(raw)
        return if (normalized.isEmpty()) emptyList() else normalized.split(SPACE)
    }

    /**
     * True for a kana or CJK code point kept by [normalize].
     *
     * Exposed for [ScriptDetector], which needs the kana+CJK ratio of an OCR reading (FR-5).
     */
    fun isKanaOrCjk(codePoint: Int): Boolean {
        for (range in KANA_CJK_RANGES) {
            if (codePoint in range) return true
        }
        return false
    }

    /** True for the ASCII alphanumerics kept by [normalize] (the text is already lowercase here). */
    fun isAsciiAlphanumeric(codePoint: Int): Boolean =
        codePoint in 'a'.code..'z'.code || codePoint in '0'.code..'9'.code

    private fun isKept(codePoint: Int): Boolean =
        isAsciiAlphanumeric(codePoint) || isKanaOrCjk(codePoint)
}
