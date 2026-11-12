package org.tcgscan.app.scan.ocr

import org.opencv.core.Point
import org.tcgscan.app.scan.ModelAsset
import org.tcgscan.app.scan.text.OcrLine
import kotlin.math.hypot

/**
 * The two recognisers shipped in the APK (D7, `docs/models.md` §2).
 *
 * A script is picked per card: the latin model reads FR and EN cards (its 185-character dictionary
 * carries the French accents), the japan model reads JP cards. When the language is unknown the
 * engine runs both and keeps the more confident reading (FR-5).
 *
 * @property recognizer the recognition model of this script.
 * @property dictionary its character dictionary.
 * @property outputClasses the class count of the recogniser's last axis, **measured** and recorded
 *   in `docs/models.md` §2: `1 blank + dictionary + 1 space`. [CtcDecoder] checks the dictionary it
 *   loads against this number, and the recogniser checks the tensor it gets back against it, so a
 *   mismatched pair of assets fails loudly instead of decoding shifted characters.
 */
enum class OcrScript(
    val recognizer: ModelAsset,
    val dictionary: ModelAsset,
    val outputClasses: Int,
) {

    /** PP-OCRv3 latin recognition: 185 dictionary characters ⇒ 187 classes. */
    LATIN(
        recognizer = ModelAsset.OCR_REC_LATIN,
        dictionary = ModelAsset.DICT_LATIN,
        outputClasses = 187,
    ),

    /** PP-OCRv3 japanese recognition: 4399 dictionary characters ⇒ 4401 classes. */
    JAPAN(
        recognizer = ModelAsset.OCR_REC_JAPAN,
        dictionary = ModelAsset.DICT_JAPAN,
        outputClasses = 4401,
    ),
    ;

    companion object {

        /**
         * The recogniser to use for a TCGdex language code (D10), or `null` when the language gives
         * no hint — in which case [OcrEngine] runs both scripts.
         */
        fun forLang(lang: String?): OcrScript? = when (lang?.lowercase()) {
            "ja" -> JAPAN
            "fr", "en" -> LATIN
            else -> null
        }
    }
}

/**
 * A text box in the coordinates of the perspective-corrected card, corners clockwise from the
 * top-left one.
 *
 * The detector returns *rotated* quads — printed card text is rarely perfectly axis-aligned once the
 * card has been warped — so the recogniser deskews each one instead of cropping its bounding box.
 */
data class OcrQuad(
    val topLeft: Point,
    val topRight: Point,
    val bottomRight: Point,
    val bottomLeft: Point,
) {

    /** The four corners, clockwise from [topLeft]. */
    val points: List<Point> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** Smallest `y` of the four corners, in card pixels. */
    val top: Float = points.minOf { it.y }.toFloat()

    /** Largest `y` of the four corners, in card pixels. */
    val bottom: Float = points.maxOf { it.y }.toFloat()

    /** Smallest `x` of the four corners, in card pixels. */
    val left: Float = points.minOf { it.x }.toFloat()

    /** Largest `x` of the four corners, in card pixels. */
    val right: Float = points.maxOf { it.x }.toFloat()

    /** Mean length of the two reading-direction edges: the width of the deskewed crop. */
    val width: Float =
        ((distance(topLeft, topRight) + distance(bottomLeft, bottomRight)) / 2.0).toFloat()

    /** Mean length of the two cross-reading edges: the height of the deskewed crop. */
    val height: Float =
        ((distance(topLeft, bottomLeft) + distance(topRight, bottomRight)) / 2.0).toFloat()

    /** Area of the axis-aligned bounding box; used to rank boxes when there are too many. */
    val boundingArea: Float get() = (right - left) * (bottom - top)

    private companion object {

        fun distance(a: Point, b: Point): Double = hypot(b.x - a.x, b.y - a.y)
    }
}

/**
 * One recognised line: the text, the mean class probability the CTC decode gave it, and where it
 * was read on the card.
 */
data class OcrTextLine(
    val text: String,
    val confidence: Float,
    val quad: OcrQuad,
)

/**
 * What the OCR read on one card: the boxes, their texts and their confidences (FR-2).
 *
 * @property script the recogniser that produced [lines], `null` when nothing was read at all.
 * @property cardWidth width in pixels of the warped card the boxes refer to.
 * @property cardHeight height in pixels of the warped card the boxes refer to.
 */
data class OcrResult(
    val script: OcrScript?,
    val lines: List<OcrTextLine>,
    val cardWidth: Int,
    val cardHeight: Int,
) {

    /** True when the OCR read nothing: only the visual path can decide (FR-8). */
    val isEmpty: Boolean = lines.isEmpty()

    /**
     * Mean confidence of the lines, `0f` when there are none. This is what arbitrates between the
     * latin and the japan reading of the same card (FR-5).
     */
    val meanConfidence: Float =
        if (lines.isEmpty()) 0f else (lines.sumOf { it.confidence.toDouble() } / lines.size).toFloat()

    /** Every reading joined top-to-bottom, as the script detector wants it. */
    val text: String = lines.joinToString(separator = " ") { it.text }

    /**
     * The readings in the form the text layer consumes, with the vertical position of each box
     * expressed as a fraction of the card height (`scan/text/CardTextParser.kt`).
     */
    fun toOcrLines(): List<OcrLine> = lines.map { line ->
        OcrLine.fromBox(
            text = line.text,
            confidence = line.confidence,
            boxTopPx = line.quad.top,
            boxBottomPx = line.quad.bottom,
            cardHeightPx = cardHeight.toFloat(),
        )
    }

    companion object {

        /** The result of a card on which no text was found. */
        fun empty(cardWidth: Int, cardHeight: Int, script: OcrScript? = null): OcrResult =
            OcrResult(
                script = script,
                lines = emptyList(),
                cardWidth = cardWidth,
                cardHeight = cardHeight,
            )
    }
}
