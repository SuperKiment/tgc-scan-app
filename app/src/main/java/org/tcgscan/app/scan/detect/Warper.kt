package org.tcgscan.app.scan.detect

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Perspective correction of a detected card (FR-1).
 *
 * The quad corners of [CardQuadDetector] are mapped onto an upright rectangle, which gives the one
 * image every later stage works on: the OCR crop, the scan photo (FR-26) and, after the resize of
 * [resizeForEmbedder], the embedder tensor (D6).
 *
 * Stateless: each call returns a freshly allocated Mat that the caller owns and must `release()`.
 */
object Warper {

    /** Width of the warped card, close to the native width of a TCGdex `low.webp` visual. */
    const val CARD_WIDTH = 480

    /** Height of the warped card: 672/480 = 1.40, the aspect of an 88×63 mm card. */
    const val CARD_HEIGHT = 672

    /** Side of the square the embedder consumes (D6: `pixel_values (1,3,224,224)`). */
    const val EMBED_SIDE = 224

    /**
     * Warps the region of [source] bounded by [quad] into an upright [width]×[height] image.
     *
     * [quad] must come from a detection run on the very same frame, since its corners are in
     * [source]'s coordinate system ([CameraFrame] keeps grey and RGB aligned for exactly that).
     */
    fun warp(
        source: Mat,
        quad: CardQuad,
        width: Int = CARD_WIDTH,
        height: Int = CARD_HEIGHT,
    ): Mat {
        require(!source.empty()) { "Cannot warp an empty frame" }
        require(width > 0 && height > 0) { "Invalid warp target size ${width}x$height" }
        val sourceCorners = MatOfPoint2f(
            quad.topLeft,
            quad.topRight,
            quad.bottomRight,
            quad.bottomLeft,
        )
        val targetCorners = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(width - 1.0, 0.0),
            Point(width - 1.0, height - 1.0),
            Point(0.0, height - 1.0),
        )
        val transform = Imgproc.getPerspectiveTransform(sourceCorners, targetCorners)
        val warped = Mat()
        try {
            Imgproc.warpPerspective(
                source,
                warped,
                transform,
                Size(width.toDouble(), height.toDouble()),
                Imgproc.INTER_LINEAR,
            )
        } catch (error: Throwable) {
            warped.release()
            throw error
        } finally {
            sourceCorners.release()
            targetCorners.release()
            transform.release()
        }
        return warped
    }

    /**
     * The 224×224 variant the embedder needs, obtained by resizing an already warped [card] with
     * `INTER_LINEAR` and no letterbox — the aspect is squashed on purpose.
     *
     * This is the on-device half of the D6 contract: the CI pipeline computes pack vectors as
     * `cv2.resize(card_image, (224,224), INTER_LINEAR)`, i.e. a *resize of a card-shaped image*.
     * Warping the frame straight to a square would sample the camera frame differently and make
     * on-device and pack vectors less comparable, so the card crop is always produced first.
     */
    fun resizeForEmbedder(card: Mat): Mat {
        require(!card.empty()) { "Cannot resize an empty card crop" }
        val square = Mat()
        try {
            Imgproc.resize(
                card,
                square,
                Size(EMBED_SIDE.toDouble(), EMBED_SIDE.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_LINEAR,
            )
        } catch (error: Throwable) {
            square.release()
            throw error
        }
        return square
    }
}
