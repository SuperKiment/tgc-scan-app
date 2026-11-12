package org.tcgscan.app.scan.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tcgscan.app.scan.ModelAsset
import org.tcgscan.app.scan.OnnxEnv
import org.tcgscan.app.scan.Preprocess
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PP-OCRv4 text detection on a warped card: `ocr_det.onnx` plus the DB (Differentiable
 * Binarization) post-processing of `docs/models.md` §2.
 *
 * The model takes an `(1, 3, H, W)` tensor whose sides are multiples of 32 and returns a
 * `(1, 1, H, W)` probability map of « this pixel is text ». Turning that map into boxes is the
 * standard DB recipe, done with OpenCV: threshold at [PROB_THRESHOLD], `findContours`,
 * `minAreaRect` per contour, then *unclip* each rectangle — the map is trained to light up a shrunk
 * version of the real box, so it has to be grown back by [UNCLIP_RATIO] before the text is cropped.
 *
 * Normalisation is the ImageNet one of [Preprocess], which is exactly what PP-OCR's detector
 * expects, so there is a single implementation of it in the app.
 *
 * One instance owns one ORT session and reusable scratch buffers, so it is **not** thread-safe: it
 * belongs to the single-thread scan dispatcher, through [OcrEngine]. [close] gives the session back
 * to the [OnnxEnv] that created it.
 */
class TextDetector private constructor(
    private val env: OnnxEnv,
    private val session: OrtSession,
) : Closeable {

    /** `x` per `docs/models.md`; read from the model so a changed export fails loudly here. */
    private val inputName: String = session.inputNames.single()

    /** Grown on demand: one 480×672 card needs 4.9 MB at most, and the size never changes. */
    private var input: FloatBuffer? = null

    /** The thresholded probability map, reused across frames. */
    private var mask: ByteArray = ByteArray(0)

    /**
     * Detects the text boxes of one card.
     *
     * @param cardRgb the perspective-corrected card, 8-bit RGB (`CV_8UC3`); it is not modified.
     * @return rotated quads in the coordinates of [cardRgb], sorted top-to-bottom then
     *   left-to-right, at most [MAX_BOXES] of them.
     * @throws IllegalArgumentException when [cardRgb] does not honour [Preprocess]'s contract.
     * @throws ai.onnxruntime.OrtException when inference fails.
     */
    fun detect(cardRgb: Mat): List<OcrQuad> {
        require(!cardRgb.empty()) { "Cannot run text detection on an empty Mat" }
        require(cardRgb.type() == CvType.CV_8UC3) {
            "Expected an 8-bit RGB Mat (CV_8UC3), got ${CvType.typeToString(cardRgb.type())}"
        }

        val cardWidth = cardRgb.cols()
        val cardHeight = cardRgb.rows()
        val longestSide = max(cardWidth, cardHeight)
        val scale = if (longestSide > MAX_SIDE) MAX_SIDE.toDouble() / longestSide else 1.0
        val inputWidth = alignToStride((cardWidth * scale).roundToInt())
        val inputHeight = alignToStride((cardHeight * scale).roundToInt())

        val elements = Preprocess.elementCount(inputWidth, inputHeight)
        val buffer = inputBuffer(elements)
        Preprocess.writeNormalizedNchw(cardRgb, inputWidth, inputHeight, buffer)

        val shape = longArrayOf(
            1L,
            Preprocess.CHANNELS.toLong(),
            inputHeight.toLong(),
            inputWidth.toLong(),
        )
        OnnxTensor.createTensor(env.environment, buffer, shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val output = result[0] as? OnnxTensor
                    ?: error("Detection model returned ${result[0].type}, expected a tensor")
                val outputShape = output.info.shape
                check(
                    outputShape.size == OUTPUT_RANK &&
                        outputShape[0] == 1L &&
                        outputShape[1] == 1L,
                ) {
                    "Expected a probability map (1, 1, H, W), got ${outputShape.contentToString()}"
                }
                val probabilities = output.floatBuffer
                    ?: error("The probability map is not a float32 tensor")
                return boxes(
                    probabilities = probabilities,
                    mapWidth = outputShape[3].toInt(),
                    mapHeight = outputShape[2].toInt(),
                    cardWidth = cardWidth,
                    cardHeight = cardHeight,
                )
            }
        }
    }

    /** Returns the session to [OnnxEnv]; the instance must not be used afterwards. */
    override fun close() {
        env.closeSession(session)
    }

    /**
     * The DB post-processing: probability map in, quads in card coordinates out.
     *
     * The map is the size of the model input, not of the card, so every corner is scaled back by
     * the ratio of the two and clamped to the card — an unclipped box may well stick out.
     */
    private fun boxes(
        probabilities: FloatBuffer,
        mapWidth: Int,
        mapHeight: Int,
        cardWidth: Int,
        cardHeight: Int,
    ): List<OcrQuad> {
        val binary = binarize(probabilities, mapWidth, mapHeight)
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(
                binary,
                contours,
                hierarchy,
                Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )
            val scaleX = cardWidth.toDouble() / mapWidth
            val scaleY = cardHeight.toDouble() / mapHeight
            val quads = ArrayList<OcrQuad>(contours.size)
            for (contour in contours) {
                val rect = minAreaRectOf(contour) ?: continue
                val quad = quadOf(
                    rect = unclip(rect),
                    scaleX = scaleX,
                    scaleY = scaleY,
                    maxX = (cardWidth - 1).toDouble(),
                    maxY = (cardHeight - 1).toDouble(),
                )
                // Clamping an unclipped box to the card can flatten one lying on its edge; such a
                // quad has no direction to deskew along, so it is dropped here rather than warped.
                if (quad.width < MIN_QUAD_SIDE || quad.height < MIN_QUAD_SIDE) continue
                quads += quad
            }
            return sortReadingOrder(quads)
        } finally {
            contours.forEach(MatOfPoint::release)
            hierarchy.release()
            binary.release()
        }
    }

    /** The probability map as a `CV_8UC1` mask, `255` where text is more likely than not. */
    private fun binarize(probabilities: FloatBuffer, mapWidth: Int, mapHeight: Int): Mat {
        val count = mapWidth * mapHeight
        check(probabilities.remaining() >= count) {
            "The probability map holds ${probabilities.remaining()} values, $count expected"
        }
        if (mask.size != count) mask = ByteArray(count)
        for (index in 0 until count) {
            mask[index] = if (probabilities.get(index) > PROB_THRESHOLD) FOREGROUND else BACKGROUND
        }
        val binary = Mat(mapHeight, mapWidth, CvType.CV_8UC1)
        binary.put(0, 0, mask)
        return binary
    }

    /** The minimal rectangle around [contour], or `null` when it is too small to hold text. */
    private fun minAreaRectOf(contour: MatOfPoint): RotatedRect? {
        val points = MatOfPoint2f(*contour.toArray())
        try {
            val rect = Imgproc.minAreaRect(points)
            val shortestSide = minOf(rect.size.width, rect.size.height)
            return if (shortestSide < MIN_BOX_SIDE) null else rect
        } finally {
            points.release()
        }
    }

    /**
     * Grows [rect] back to the size of the printed text (the DB map covers a shrunk box).
     *
     * PP-OCR offsets the contour polygon by `area × ratio / perimeter`; on a rectangle that offset
     * is exactly an inflation of both sides by twice that distance, so no polygon clipper is needed.
     */
    private fun unclip(rect: RotatedRect): RotatedRect {
        val width = rect.size.width
        val height = rect.size.height
        val perimeter = 2.0 * (width + height)
        if (perimeter <= 0.0) return rect
        val distance = width * height * UNCLIP_RATIO / perimeter
        return RotatedRect(
            rect.center,
            Size(width + 2.0 * distance, height + 2.0 * distance),
            rect.angle,
        )
    }

    /**
     * Maps a rotated rectangle of the probability map to a quad of the card, corners clockwise from
     * the top-left one.
     *
     * `RotatedRect.points` already returns a cyclic order; it is only rotated to start at the
     * top-leftmost corner, and reversed when it runs counter-clockwise, which keeps the orientation
     * usable by [TextRecognizer]'s deskewing even for a strongly tilted box.
     */
    private fun quadOf(
        rect: RotatedRect,
        scaleX: Double,
        scaleY: Double,
        maxX: Double,
        maxY: Double,
    ): OcrQuad {
        val corners = Array(QUAD_CORNERS) { Point() }
        rect.points(corners)
        val mapped = Array(QUAD_CORNERS) { index ->
            Point(
                (corners[index].x * scaleX).coerceIn(0.0, maxX),
                (corners[index].y * scaleY).coerceIn(0.0, maxY),
            )
        }
        if (signedArea(mapped) < 0.0) mapped.reverse()

        var start = 0
        for (index in 1 until QUAD_CORNERS) {
            if (mapped[index].x + mapped[index].y < mapped[start].x + mapped[start].y) {
                start = index
            }
        }
        return OcrQuad(
            topLeft = mapped[start],
            topRight = mapped[(start + 1) % QUAD_CORNERS],
            bottomRight = mapped[(start + 2) % QUAD_CORNERS],
            bottomLeft = mapped[(start + 3) % QUAD_CORNERS],
        )
    }

    /**
     * Reading order: top-to-bottom, then left-to-right.
     *
     * A card can only hold so much text; when the detector returns more boxes than
     * [MAX_BOXES] — a noisy holographic background does that — the largest ones are kept, because
     * dropping by position would throw away the bottom strip where the printed number lives.
     */
    private fun sortReadingOrder(quads: List<OcrQuad>): List<OcrQuad> {
        val kept = if (quads.size <= MAX_BOXES) {
            quads
        } else {
            quads.sortedByDescending { it.boundingArea }.take(MAX_BOXES)
        }
        return kept.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun inputBuffer(elements: Int): FloatBuffer {
        val existing = input
        if (existing != null && existing.capacity() >= elements) return existing
        return Preprocess.newInputBuffer(elements).also { input = it }
    }

    companion object {

        /** Longest side of the detection input; PP-OCR's own default limit. */
        const val MAX_SIDE = 640

        /** Both input sides must be multiples of this. */
        const val STRIDE = 32

        /** A pixel of the probability map above this is text. */
        const val PROB_THRESHOLD = 0.3f

        /** How much a box of the (shrunk) probability map is grown back. */
        const val UNCLIP_RATIO = 1.6

        /** Boxes thinner than this, in map pixels, cannot hold a readable character. */
        const val MIN_BOX_SIDE = 3.0

        /** Quads thinner than this, in card pixels, cannot be deskewed. */
        const val MIN_QUAD_SIDE = 2f

        /** Upper bound on the boxes handed to recognition, for the per-card budget (NFR-6). */
        const val MAX_BOXES = 48

        /** `(1, 1, H, W)`. */
        private const val OUTPUT_RANK = 4

        private const val QUAD_CORNERS = 4

        private const val FOREGROUND: Byte = -1 // 0xFF

        private const val BACKGROUND: Byte = 0

        /** Opens the detector over [ModelAsset.OCR_DET], the only model it ever reads. */
        fun open(env: OnnxEnv): TextDetector =
            TextDetector(env, env.createSession(ModelAsset.OCR_DET))

        /** The smallest multiple of [STRIDE] that is not smaller than [value] (and not zero). */
        fun alignToStride(value: Int): Int =
            ((value + STRIDE - 1) / STRIDE * STRIDE).coerceAtLeast(STRIDE)

        /**
         * Twice the signed area of a quad, positive when its corners run clockwise in image
         * coordinates (`y` pointing down).
         */
        private fun signedArea(points: Array<Point>): Double {
            var sum = 0.0
            for (index in points.indices) {
                val current = points[index]
                val next = points[(index + 1) % points.size]
                sum += current.x * next.y - next.x * current.y
            }
            return sum
        }
    }
}
