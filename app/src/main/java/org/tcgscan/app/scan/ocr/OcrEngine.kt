package org.tcgscan.app.scan.ocr

import android.content.Context
import org.opencv.core.Mat
import org.tcgscan.app.scan.OnnxEnv
import java.io.Closeable
import java.util.EnumMap

/**
 * The on-device OCR of a scanned card: detection then recognition, entirely local (FR-2).
 *
 * [read] takes a perspective-corrected card and returns its text boxes with their readings. When
 * the script is not known yet — the common case, since the language itself is read off the card
 * (FR-5) — **both** recognisers run over the same boxes and the more confident reading wins; the
 * detection, which is script-agnostic and the most expensive step, runs only once.
 *
 * Sessions are opened lazily and kept: a card whose language is known never pays for the other
 * recogniser, and a burst of scans pays for neither detector nor recogniser twice. One instance
 * owns up to three ORT sessions (~24 MB of weights), so it is **not** thread-safe: it belongs to the
 * single-thread scan dispatcher of `ScanPipeline` (T14) and [close] must be called when the scan
 * screen goes away.
 */
class OcrEngine private constructor(
    private val context: Context,
    private val env: OnnxEnv,
) : Closeable {

    private var detector: TextDetector? = null

    private val recognizers = EnumMap<OcrScript, TextRecognizer>(OcrScript::class.java)

    private var closed = false

    /**
     * Reads the text of one card.
     *
     * @param cardRgb the perspective-corrected card, 8-bit RGB (`CV_8UC3`); it is not modified.
     * @param script the recogniser to use, or `null` when the card's language is unknown — then
     *   both are tried and the one with the higher [OcrResult.meanConfidence] is returned (a tie
     *   goes to [OcrScript.LATIN], the script of the FR pack).
     * @throws IllegalArgumentException when [cardRgb] does not honour the contract above.
     * @throws java.io.IOException when a model or dictionary asset cannot be extracted.
     * @throws ai.onnxruntime.OrtException when inference fails.
     */
    fun read(cardRgb: Mat, script: OcrScript? = null): OcrResult {
        check(!closed) { "OcrEngine is closed" }
        val cardWidth = cardRgb.cols()
        val cardHeight = cardRgb.rows()
        val quads = detector().detect(cardRgb)
        if (quads.isEmpty()) return OcrResult.empty(cardWidth, cardHeight, script)

        if (script != null) return readWith(script, cardRgb, quads, cardWidth, cardHeight)

        val latin = readWith(OcrScript.LATIN, cardRgb, quads, cardWidth, cardHeight)
        val japan = readWith(OcrScript.JAPAN, cardRgb, quads, cardWidth, cardHeight)
        return if (japan.meanConfidence > latin.meanConfidence) japan else latin
    }

    /** Closes every session this engine opened. The [OnnxEnv] itself survives. */
    override fun close() {
        closed = true
        detector?.close()
        detector = null
        recognizers.values.forEach(TextRecognizer::close)
        recognizers.clear()
    }

    private fun readWith(
        script: OcrScript,
        cardRgb: Mat,
        quads: List<OcrQuad>,
        cardWidth: Int,
        cardHeight: Int,
    ): OcrResult = OcrResult(
        script = script,
        lines = recognizer(script).recognize(cardRgb, quads),
        cardWidth = cardWidth,
        cardHeight = cardHeight,
    )

    private fun detector(): TextDetector =
        detector ?: TextDetector.open(env).also { detector = it }

    private fun recognizer(script: OcrScript): TextRecognizer =
        recognizers.getOrPut(script) { TextRecognizer.open(context, env, script) }

    companion object {

        /** Opens an engine; no session is created until the first [read]. */
        fun open(context: Context, env: OnnxEnv): OcrEngine =
            OcrEngine(context.applicationContext, env)
    }
}
