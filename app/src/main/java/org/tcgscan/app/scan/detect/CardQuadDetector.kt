package org.tcgscan.app.scan.detect

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The four corners of a detected card, ordered clockwise from the top-left one and expressed in
 * full-frame coordinates (the [CameraFrame] the quad was detected on, display orientation).
 *
 * Treated as an immutable value: nothing mutates the [Point]s after construction.
 */
data class CardQuad(
    val topLeft: Point,
    val topRight: Point,
    val bottomRight: Point,
    val bottomLeft: Point,
) {

    /** The corners in [Warper]'s expected order: top-left, top-right, bottom-right, bottom-left. */
    val corners: List<Point> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** Mean length of the top and bottom edges. */
    val width: Double = (distance(topLeft, topRight) + distance(bottomLeft, bottomRight)) / 2.0

    /** Mean length of the left and right edges. */
    val height: Double = (distance(topLeft, bottomLeft) + distance(topRight, bottomRight)) / 2.0

    /**
     * Portrait aspect ratio of the quad. A Pokémon card measures 88×63 mm, so an upright card is
     * around 1.40; a landscape quad scores below 1 and is rejected by [CardQuadDetector] rather
     * than silently warped sideways.
     */
    val aspectRatio: Double = if (width > 0.0) height / width else Double.NaN

    /** Centre of the quad, used to tell two detections apart. */
    val center: Point = Point(
        corners.sumOf { it.x } / corners.size,
        corners.sumOf { it.y } / corners.size,
    )

    /** Largest distance between two matching corners of this quad and [other], in pixels. */
    fun maxCornerShift(other: CardQuad): Double {
        var shift = 0.0
        for (index in corners.indices) {
            shift = max(shift, distance(corners[index], other.corners[index]))
        }
        return shift
    }

    /** Distance between the centres of this quad and [other], in pixels. */
    fun centerDistance(other: CardQuad): Double = distance(center, other.center)

    private companion object {

        fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)
    }
}

/**
 * How many card-like quads the current frame holds. `Multiple` is the EC-5 case: several cards in
 * the frame means no capture at all and a « une seule carte » hint.
 */
sealed interface QuadOutcome {

    /** Qualifying quads, largest first. */
    val quads: List<CardQuad>

    /** Nothing card-like in the frame. */
    data object None : QuadOutcome {
        override val quads: List<CardQuad> = emptyList()
    }

    /** Exactly one card-like quad: the only outcome that can lead to a capture (FR-1). */
    data class Single(val quad: CardQuad) : QuadOutcome {
        override val quads: List<CardQuad> = listOf(quad)
    }

    /** Two or more card-like quads (EC-5). */
    data class Multiple(override val quads: List<CardQuad>) : QuadOutcome {
        init {
            require(quads.size >= 2) { "Multiple needs at least 2 quads, got ${quads.size}" }
        }
    }
}

/**
 * Result of analysing one frame: the quad [outcome] plus the two image-quality measures the scan
 * screen turns into hints — [blurScore] for « trop flou » and [meanLuma] for « activez la lampe »
 * (EC-7).
 */
data class FrameDetection(
    val outcome: QuadOutcome,
    val blurScore: Double,
    val meanLuma: Double,
    val frameWidth: Int,
    val frameHeight: Int,
) {

    /** Frame diagonal in pixels; [Stabilizer] expresses its tolerances as a fraction of it. */
    val frameDiagonal: Double = hypot(frameWidth.toDouble(), frameHeight.toDouble())

    /** The single quad of this frame, or `null` when there is none or more than one. */
    val singleQuad: CardQuad? = (outcome as? QuadOutcome.Single)?.quad

    /** True when the frame is too soft to OCR; only meaningful once a quad is found. */
    val isBlurry: Boolean get() = blurScore < CardQuadDetector.MIN_BLUR_SCORE

    /** True when the frame is dark enough to warrant the torch suggestion of EC-7. */
    val isLowLight: Boolean get() = meanLuma < CardQuadDetector.MIN_MEAN_LUMA
}

/**
 * Finds card-shaped quads in a greyscale frame with plain OpenCV contour analysis (D4): downscale,
 * blur, Canny, external contours, keep the big convex four-point ones whose aspect ratio is that of
 * a card, then order their corners for [Warper].
 *
 * Every measure is computed on the downscaled frame, so [FrameDetection.blurScore] and
 * [FrameDetection.meanLuma] are comparable between devices and resolutions, while the returned
 * corners are scaled back to full-frame coordinates.
 *
 * Not thread-safe: the intermediate Mats are reused between calls to keep the per-frame cost flat.
 * One instance per analysis thread (the pipeline of T14 uses a single-thread dispatcher); call
 * [close] when it stops.
 */
class CardQuadDetector : AutoCloseable {

    private val scaled = Mat()
    private val blurred = Mat()
    private val edges = Mat()
    private val hierarchy = Mat()
    private val laplacian = Mat()
    private val laplacianMean = MatOfDouble()
    private val laplacianStdDev = MatOfDouble()

    /**
     * Analyses one greyscale frame — typically [CameraFrame.grey], whose coordinates the returned
     * quads share.
     *
     * @throws IllegalArgumentException if [grey] is empty or not single-channel.
     */
    fun detect(grey: Mat): FrameDetection {
        require(!grey.empty()) { "Card detection needs a non-empty frame" }
        require(grey.channels() == 1) {
            "Card detection needs a greyscale frame, got ${grey.channels()} channels"
        }
        val width = grey.cols()
        val height = grey.rows()
        val scale = min(1.0, DOWNSCALE_MAX_SIDE / max(width, height).toDouble())
        val work = if (scale < 1.0) {
            Imgproc.resize(grey, scaled, Size(), scale, scale, Imgproc.INTER_AREA)
            scaled
        } else {
            grey
        }
        return FrameDetection(
            outcome = outcomeOf(findQuads(work, upscale = 1.0 / scale)),
            blurScore = laplacianVariance(work),
            meanLuma = Core.mean(work).`val`[0],
            frameWidth = width,
            frameHeight = height,
        )
    }

    /** Releases the reusable native buffers. */
    override fun close() {
        scaled.release()
        blurred.release()
        edges.release()
        hierarchy.release()
        laplacian.release()
        laplacianMean.release()
        laplacianStdDev.release()
    }

    private fun outcomeOf(quads: List<CardQuad>): QuadOutcome = when (quads.size) {
        0 -> QuadOutcome.None
        1 -> QuadOutcome.Single(quads[0])
        else -> QuadOutcome.Multiple(quads)
    }

    private fun findQuads(work: Mat, upscale: Double): List<CardQuad> {
        Imgproc.GaussianBlur(work, blurred, Size(GAUSSIAN_KERNEL, GAUSSIAN_KERNEL), 0.0)
        Imgproc.Canny(blurred, edges, CANNY_LOW_THRESHOLD, CANNY_HIGH_THRESHOLD)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(
            edges,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE,
        )
        val minArea = work.rows().toDouble() * work.cols() * MIN_AREA_FRACTION
        val candidates = ArrayList<CardQuad>(2)
        val curve = MatOfPoint2f()
        val approximation = MatOfPoint2f()
        try {
            for (contour in contours) {
                if (Imgproc.contourArea(contour) < minArea) continue
                contour.convertTo(curve, CvType.CV_32F)
                val epsilon = APPROX_EPSILON_FRACTION * Imgproc.arcLength(curve, true)
                Imgproc.approxPolyDP(curve, approximation, epsilon, true)
                if (approximation.total() != 4L) continue
                val points = approximation.toArray()
                if (!isConvex(points)) continue
                val quad = orderCorners(points, upscale) ?: continue
                if (quad.aspectRatio < MIN_ASPECT_RATIO || quad.aspectRatio > MAX_ASPECT_RATIO) continue
                candidates += quad
            }
        } finally {
            curve.release()
            approximation.release()
            contours.forEach { it.release() }
        }
        return largestDistinct(candidates, work)
    }

    /**
     * Sorts by decreasing size and drops quads centred on an already kept one. `RETR_EXTERNAL`
     * normally yields one contour per card, but a thick border or a glare edge can produce a second
     * nearly identical quad, which must not be reported as two cards (EC-5).
     */
    private fun largestDistinct(candidates: List<CardQuad>, work: Mat): List<CardQuad> {
        if (candidates.size <= 1) return candidates
        val minSeparation = hypot(work.cols().toDouble(), work.rows().toDouble()) *
            MIN_CENTER_SEPARATION_FRACTION
        val kept = ArrayList<CardQuad>(candidates.size)
        for (quad in candidates.sortedByDescending { it.width * it.height }) {
            if (kept.none { it.centerDistance(quad) < minSeparation }) kept += quad
        }
        return kept
    }

    private fun isConvex(points: Array<Point>): Boolean {
        val polygon = MatOfPoint(*points)
        return try {
            Imgproc.isContourConvex(polygon)
        } finally {
            polygon.release()
        }
    }

    /**
     * Orders the four [points] as top-left, top-right, bottom-right, bottom-left and scales them
     * back to full-frame coordinates by [upscale].
     *
     * The classic sum/difference assignment only holds for a quad that is roughly axis-aligned; for
     * a strongly rotated one it picks the same point twice, which is reported as "not a card" here
     * rather than warped into nonsense.
     */
    private fun orderCorners(points: Array<Point>, upscale: Double): CardQuad? {
        var topLeft = 0
        var bottomRight = 0
        var topRight = 0
        var bottomLeft = 0
        for (index in points.indices) {
            val sum = points[index].x + points[index].y
            val difference = points[index].x - points[index].y
            if (sum < points[topLeft].x + points[topLeft].y) topLeft = index
            if (sum > points[bottomRight].x + points[bottomRight].y) bottomRight = index
            if (difference > points[topRight].x - points[topRight].y) topRight = index
            if (difference < points[bottomLeft].x - points[bottomLeft].y) bottomLeft = index
        }
        if (setOf(topLeft, topRight, bottomRight, bottomLeft).size != 4) return null
        return CardQuad(
            topLeft = scalePoint(points[topLeft], upscale),
            topRight = scalePoint(points[topRight], upscale),
            bottomRight = scalePoint(points[bottomRight], upscale),
            bottomLeft = scalePoint(points[bottomLeft], upscale),
        )
    }

    private fun scalePoint(point: Point, upscale: Double): Point =
        Point(point.x * upscale, point.y * upscale)

    /** Variance of the Laplacian: the usual cheap sharpness measure, higher is sharper. */
    private fun laplacianVariance(work: Mat): Double {
        Imgproc.Laplacian(work, laplacian, CvType.CV_64F)
        Core.meanStdDev(laplacian, laplacianMean, laplacianStdDev)
        val stdDev = laplacianStdDev.toArray().firstOrNull() ?: 0.0
        return stdDev * stdDev
    }

    companion object {

        /**
         * Detection runs on a frame whose longest side is at most this, which is plenty for a card
         * filling a good part of the frame and keeps the contour pass a couple of milliseconds.
         */
        const val DOWNSCALE_MAX_SIDE = 480

        /** A card presented to the camera covers at least 8 % of the frame. */
        const val MIN_AREA_FRACTION = 0.08

        /** Accepted portrait aspect ratios; a card is ~1.40 (88×63 mm). */
        const val MIN_ASPECT_RATIO = 1.25
        const val MAX_ASPECT_RATIO = 1.85

        /**
         * Sharpness floor below which the frame is « trop flou » (EC-7). Measured on the
         * downscaled frame; to be confirmed on the reference device (docs/benchmark.md).
         */
        const val MIN_BLUR_SCORE = 80.0

        /** Mean luminance (0..255) below which the torch is suggested (EC-7). */
        const val MIN_MEAN_LUMA = 60.0

        private const val GAUSSIAN_KERNEL = 5.0
        private const val CANNY_LOW_THRESHOLD = 50.0
        private const val CANNY_HIGH_THRESHOLD = 150.0

        /** `approxPolyDP` epsilon, as a fraction of the contour perimeter. */
        private const val APPROX_EPSILON_FRACTION = 0.02

        /** Two quads closer than this fraction of the frame diagonal are the same card. */
        private const val MIN_CENTER_SEPARATION_FRACTION = 0.05
    }
}
