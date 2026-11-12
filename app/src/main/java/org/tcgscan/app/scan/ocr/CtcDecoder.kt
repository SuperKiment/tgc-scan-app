package org.tcgscan.app.scan.ocr

import android.content.Context
import org.tcgscan.app.scan.ModelAssets
import java.io.IOException

/**
 * Greedy CTC decoding of a PP-OCR recognition tensor (`docs/models.md` §2).
 *
 * The recognisers output `(N, T, classes)` **probabilities** — the softmax is part of the exported
 * graph — over a class axis built the PaddleOCR way: index 0 is the CTC blank, indices `1..n` are
 * the lines of the script's dictionary, and the last class is the space that `use_space_char` adds.
 *
 * Decoding keeps, for each time step, the most probable class, then drops the blanks and the
 * repetitions of the previous step; the confidence of a reading is the mean probability of the
 * characters that survived, which is what the matcher weighs OCR agreement with (FR-2).
 *
 * One instance is immutable and belongs to one [TextRecognizer].
 */
class CtcDecoder private constructor(
    /** The script this decoder reads; its dictionary built [characters]. */
    val script: OcrScript,
    /** Class axis of the recogniser: `[""] + dictionary + [" "]`. */
    private val characters: Array<String>,
) {

    /** Size of the class axis this decoder expects, `dictionary + 2`. */
    val classCount: Int get() = characters.size

    /**
     * Decodes one sample of a recognition tensor.
     *
     * @param probabilities the flattened `(N, T, classCount)` tensor.
     * @param offset index of the sample's first value, `sample * timeSteps * classCount`.
     * @param timeSteps the `T` of the tensor.
     * @return the decoded text and its mean class probability; [DecodedText.EMPTY] when every step
     *   decoded to a blank.
     */
    fun decode(probabilities: FloatArray, offset: Int, timeSteps: Int): DecodedText {
        require(offset >= 0 && timeSteps >= 0) {
            "Invalid sample window: offset $offset, $timeSteps time steps"
        }
        require(offset + timeSteps * classCount <= probabilities.size) {
            "Sample window [$offset, ${offset + timeSteps * classCount}) exceeds the " +
                "${probabilities.size}-value tensor"
        }

        val text = StringBuilder()
        var probabilitySum = 0.0
        var kept = 0
        var previous = BLANK_INDEX

        for (step in 0 until timeSteps) {
            val base = offset + step * classCount
            var best = BLANK_INDEX
            var bestProbability = probabilities[base]
            for (classIndex in 1 until classCount) {
                val probability = probabilities[base + classIndex]
                if (probability > bestProbability) {
                    bestProbability = probability
                    best = classIndex
                }
            }
            if (best != BLANK_INDEX && best != previous) {
                text.append(characters[best])
                probabilitySum += bestProbability
                kept++
            }
            previous = best
        }

        if (kept == 0) return DecodedText.EMPTY
        return DecodedText(
            text = text.toString().trim(),
            confidence = (probabilitySum / kept).toFloat().coerceIn(0f, 1f),
        )
    }

    /** A decoded reading. */
    data class DecodedText(val text: String, val confidence: Float) {

        companion object {

            /** Nothing was read. */
            val EMPTY = DecodedText(text = "", confidence = 0f)
        }
    }

    companion object {

        /** PaddleOCR puts the CTC blank first. */
        const val BLANK_INDEX = 0

        /** The space PaddleOCR appends after the dictionary when `use_space_char` is on. */
        private const val SPACE = " "

        /**
         * Loads the dictionary of [script] and builds its class axis.
         *
         * The dictionary is read verbatim, one character per line, **without** trimming: the first
         * line of `dict_latin.txt` is itself a space and dropping it would shift every class.
         *
         * @throws IOException when the dictionary asset cannot be extracted from the APK.
         * @throws IllegalStateException when the dictionary does not match the recogniser it is
         *   paired with — the guard of `docs/models.md` §2, `dict.size + 2 == outputClasses`.
         */
        fun open(context: Context, script: OcrScript): CtcDecoder {
            val dictionary = ModelAssets.file(context, script.dictionary)
                .readLines(Charsets.UTF_8)
            check(dictionary.size + 2 == script.outputClasses) {
                "${script.dictionary.fileName} has ${dictionary.size} characters, so " +
                    "${dictionary.size + 2} classes, but ${script.recognizer.fileName} outputs " +
                    "${script.outputClasses}; re-run `bash tools/fetch_models.sh`"
            }
            val characters = Array(script.outputClasses) { index ->
                when (index) {
                    BLANK_INDEX -> ""
                    script.outputClasses - 1 -> SPACE
                    else -> dictionary[index - 1]
                }
            }
            return CtcDecoder(script = script, characters = characters)
        }
    }
}
