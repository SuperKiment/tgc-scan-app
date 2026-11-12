package org.tcgscan.app.scan.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tcgscan.app.scan.OnnxEnv
import org.tcgscan.app.scan.Preprocess
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PP-OCRv3 text recognition of one script: quads in, text and confidence out
 * (`docs/models.md` §2).
 *
 * Each quad is deskewed into its own `48`-pixel-high crop by a perspective warp — the printed line
 * is rarely axis-aligned and cropping its bounding box would drag in the neighbouring artwork — then
 * normalised to `[-1, 1]`, the range PP-OCR's recognisers were trained on. Crops are run in batches
 * of [MAX_BATCH]; a batch shares one tensor width, so the narrower crops are zero-padded on the
 * right, exactly as PaddleOCR pads them.
 *
 * One instance owns one ORT session (9–10 MB of weights), its [CtcDecoder] and reusable buffers, so
 * it is **not** thread-safe: it belongs to the single-thread scan dispatcher, through [OcrEngine].
 * [close] gives the session back to the [OnnxEnv] that created it.
 */
class TextRecognizer private constructor(
    private val env: OnnxEnv,
    private val session: OrtSession,
    private val decoder: CtcDecoder,
    /** The script this recogniser reads. */
    val script: OcrScript,
) : Closeable {

    /** `x` per `docs/models.md`; read from the model so a changed export fails loudly here. */
    private val inputName: String = session.inputNames.single()

    /** The batch tensor, allocated once at its maximum size (1.4 MB). */
    private val input: FloatBuffer = Preprocess.newInputBuffer(
        MAX_BATCH * Preprocess.CHANNELS * HEIGHT * MAX_WIDTH,
    )

    /** Pixels of the crop being written into [input]; grown on demand. */
    private var cropPixels: ByteArray = ByteArray(0)

    /** The output tensor copied out of ORT before the result is closed; grown on demand. */
    private var probabilities: FloatArray = FloatArray(0)

    /**
     * Reads the text of every quad of one card.
     *
     * @param cardRgb the perspective-corrected card, 8-bit RGB (`CV_8UC3`); it is not modified.
     * @param quads the boxes of [TextDetector], in the coordinates of [cardRgb].
     * @return one line per quad that decoded to something, in the order of [quads]; a quad that
     *   decoded to nothing (a holographic glint rather than text) is dropped rather than returned
     *   as an empty reading, so it cannot dilute [OcrResult.meanConfidence].
     * @throws ai.onnxruntime.OrtException when inference fails.
     */
    fun recognize(cardRgb: Mat, quads: List<OcrQuad>): List<OcrTextLine> {
        require(!cardRgb.empty()) { "Cannot run text recognition on an empty Mat" }
        require(cardRgb.type() == CvType.CV_8UC3) {
            "Expected an 8-bit RGB Mat (CV_8UC3), got ${CvType.typeToString(cardRgb.type())}"
        }
        if (quads.isEmpty()) return emptyList()

        val lines = ArrayList<OcrTextLine>(quads.size)
        var from = 0
        while (from < quads.size) {
            val until = min(from + MAX_BATCH, quads.size)
            recognizeBatch(cardRgb, quads, from, until, lines)
            from = until
        }
        return lines
    }

    /** Returns the session to [OnnxEnv]; the instance must not be used afterwards. */
    override fun close() {
        env.closeSession(session)
    }

    /** Runs the quads `[from, until)` of [quads] in one batch and appends their readings. */
    private fun recognizeBatch(
        cardRgb: Mat,
        quads: List<OcrQuad>,
        from: Int,
        until: Int,
        into: MutableList<OcrTextLine>,
    ) {
        val crops = ArrayList<Mat>(until - from)
        try {
            var batchWidth = MIN_WIDTH
            for (index in from until until) {
                val crop = deskew(cardRgb, quads[index])
                crops += crop
                batchWidth = max(batchWidth, crop.cols())
            }
            writeBatch(crops, batchWidth)

            val samples = crops.size
            val shape = longArrayOf(
                samples.toLong(),
                Preprocess.CHANNELS.toLong(),
                HEIGHT.toLong(),
                batchWidth.toLong(),
            )
            val timeSteps = OnnxTensor.createTensor(env.environment, input, shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val output = result[0] as? OnnxTensor
                        ?: error("Recognition model returned ${result[0].type}, expected a tensor")
                    val outputShape = output.info.shape
                    check(
                        outputShape.size == OUTPUT_RANK &&
                            outputShape[0] == samples.toLong() &&
                            outputShape[2] == decoder.classCount.toLong(),
                    ) {
                        "Expected ($samples, T, ${decoder.classCount}) from " +
                            "${script.recognizer.fileName}, got ${outputShape.contentToString()}"
                    }
                    val steps = outputShape[1].toInt()
                    val values = output.floatBuffer
                        ?: error("The recognition output is not a float32 tensor")
                    val count = samples * steps * decoder.classCount
                    if (probabilities.size < count) probabilities = FloatArray(count)
                    values.get(probabilities, 0, count)
                    steps
                }
            }

            val sampleStride = timeSteps * decoder.classCount
            for (sample in 0 until samples) {
                val decoded = decoder.decode(probabilities, sample * sampleStride, timeSteps)
                if (decoded.text.isEmpty()) continue
                into += OcrTextLine(
                    text = decoded.text,
                    confidence = decoded.confidence,
                    quad = quads[from + sample],
                )
            }
        } finally {
            crops.forEach(Mat::release)
        }
    }

    /**
     * Crops and straightens one quad into a [HEIGHT]-high, 8-bit RGB `Mat`.
     *
     * The width keeps the quad's aspect ratio, is padded up to a multiple of [WIDTH_STRIDE] and
     * capped at [MAX_WIDTH] (a very wide line is squashed rather than truncated — losing the end of
     * a card name would be worse). A quad at least [VERTICAL_ASPECT] times taller than wide holds
     * text printed sideways, which Japanese cards do: its corners are fed in rotated order so the
     * warp itself turns the crop a quarter turn anticlockwise, the way PaddleOCR does.
     */
    private fun deskew(cardRgb: Mat, quad: OcrQuad): Mat {
        val vertical = quad.height >= quad.width * VERTICAL_ASPECT
        val lineWidth = if (vertical) quad.height else quad.width
        val lineHeight = if (vertical) quad.width else quad.height
        val aspect = if (lineHeight > 0f) lineWidth / lineHeight else 1f
        val width = alignToStride((HEIGHT * aspect).roundToInt())
        val source = if (vertical) {
            arrayOf(quad.topRight, quad.bottomRight, quad.bottomLeft, quad.topLeft)
        } else {
            arrayOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        }

        val sourceCorners = MatOfPoint2f(*source)
        val targetCorners = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(width.toDouble(), 0.0),
            Point(width.toDouble(), HEIGHT.toDouble()),
            Point(0.0, HEIGHT.toDouble()),
        )
        val transform = Imgproc.getPerspectiveTransform(sourceCorners, targetCorners)
        val crop = Mat()
        try {
            Imgproc.warpPerspective(
                cardRgb,
                crop,
                transform,
                Size(width.toDouble(), HEIGHT.toDouble()),
                Imgproc.INTER_LINEAR,
            )
            return crop
        } catch (e: Throwable) {
            crop.release()
            throw e
        } finally {
            sourceCorners.release()
            targetCorners.release()
            transform.release()
        }
    }

    /**
     * Writes [crops] into [input] as an `(N, 3, HEIGHT, batchWidth)` tensor normalised to
     * `[-1, 1]`, right-padding the narrow ones with zeros, and leaves the buffer ready for
     * `OnnxTensor.createTensor`.
     */
    private fun writeBatch(crops: List<Mat>, batchWidth: Int) {
        val plane = HEIGHT * batchWidth
        val sampleStride = Preprocess.CHANNELS * plane
        input.clear()
        for ((sample, crop) in crops.withIndex()) {
            check(crop.isContinuous) { "A deskewed crop is not continuous" }
            val cropWidth = crop.cols()
            val pixelCount = Preprocess.CHANNELS * HEIGHT * cropWidth
            if (cropPixels.size < pixelCount) cropPixels = ByteArray(pixelCount)
            crop.get(0, 0, cropPixels)

            val sampleBase = sample * sampleStride
            for (channel in 0 until Preprocess.CHANNELS) {
                val channelBase = sampleBase + channel * plane
                for (row in 0 until HEIGHT) {
                    val rowBase = channelBase + row * batchWidth
                    var source = row * cropWidth * Preprocess.CHANNELS + channel
                    for (column in 0 until cropWidth) {
                        val value = (cropPixels[source].toInt() and 0xFF) / MAX_BYTE_VALUE
                        input.put(rowBase + column, (value - MEAN) / STD)
                        source += Preprocess.CHANNELS
                    }
                    for (column in cropWidth until batchWidth) {
                        input.put(rowBase + column, 0f)
                    }
                }
            }
        }
        input.position(0)
        input.limit(crops.size * sampleStride)
    }

    companion object {

        /** Input height of both recognisers (`(N, 3, 48, W)`). */
        const val HEIGHT = 48

        /** Crop widths are padded up to a multiple of this. */
        const val WIDTH_STRIDE = 32

        /** Narrowest crop handed to the model. */
        const val MIN_WIDTH = WIDTH_STRIDE

        /** Widest crop handed to the model; beyond it the line is squashed. */
        const val MAX_WIDTH = 320

        /** Crops per inference call. */
        const val MAX_BATCH = 8

        /** Above this height-to-width ratio the line is printed sideways. */
        const val VERTICAL_ASPECT = 1.5f

        /** `(N, T, classes)`. */
        private const val OUTPUT_RANK = 3

        private const val MAX_BYTE_VALUE = 255f

        /** PP-OCR recognition normalisation: `(v / 255 - 0.5) / 0.5`, i.e. `[-1, 1]`. */
        private const val MEAN = 0.5f

        private const val STD = 0.5f

        /**
         * Opens the recogniser of [script] and loads its dictionary.
         *
         * @throws java.io.IOException when a model or dictionary asset cannot be extracted.
         * @throws IllegalStateException when the dictionary does not match the model
         *   (`dict.size + 2 == outputClasses`, `docs/models.md` §2).
         */
        fun open(context: Context, env: OnnxEnv, script: OcrScript): TextRecognizer {
            val decoder = CtcDecoder.open(context, script)
            val session = env.createSession(script.recognizer)
            return TextRecognizer(
                env = env,
                session = session,
                decoder = decoder,
                script = script,
            )
        }

        /** The smallest multiple of [WIDTH_STRIDE] holding [value], within the model's bounds. */
        fun alignToStride(value: Int): Int {
            val bounded = value.coerceIn(1, MAX_WIDTH)
            return ((bounded + WIDTH_STRIDE - 1) / WIDTH_STRIDE * WIDTH_STRIDE)
                .coerceIn(MIN_WIDTH, MAX_WIDTH)
        }
    }
}
