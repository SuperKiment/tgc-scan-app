package org.tcgscan.app.scan.detect

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * One camera frame, already rotated into display orientation and split into the two views the scan
 * pipeline needs (T14):
 *
 * - [grey] single channel, used by [CardQuadDetector] (quad, blur score, mean luminance);
 * - [rgb] three channels in RGB order, used by [Warper] for the crop that feeds the embedder, the
 *   OCR and the scan photo.
 *
 * Both Mats are owned by this frame and share its coordinate system, so a quad detected on [grey]
 * can be warped out of [rgb] unchanged. The frame must be [close]d once the capture is done;
 * forgetting it leaks native memory, which the Java GC will not reclaim for us.
 */
class CameraFrame internal constructor(
    val grey: Mat,
    val rgb: Mat,
    val rotationDegrees: Int,
) : AutoCloseable {

    /** Frame width in display orientation. */
    val width: Int get() = grey.cols()

    /** Frame height in display orientation. */
    val height: Int get() = grey.rows()

    override fun close() {
        grey.release()
        rgb.release()
    }
}

/**
 * Turns a CameraX `ImageProxy` (`YUV_420_888`, the only format `ImageAnalysis` produces) into
 * OpenCV Mats, honouring `ImageInfo.rotationDegrees` so every later stage works in display
 * orientation.
 *
 * The planes are packed into an NV21 buffer first, because OpenCV converts that straight to RGB
 * (`COLOR_YUV2RGB_NV21`) and its luma half *is* the greyscale frame, so no second colour
 * conversion is needed.
 *
 * Not thread-safe: the NV21 scratch buffers are reused across calls. The pipeline analyses frames
 * on a single-thread dispatcher (T14), so one instance per analyser is enough; call [close] when
 * the analyser stops.
 */
class FrameConverter : AutoCloseable {

    private var nv21 = ByteArray(0)
    private var yuv: Mat? = null
    private var lumaRow = ByteArray(0)
    private var uRow = ByteArray(0)
    private var vRow = ByteArray(0)

    /**
     * Converts [image] without consuming it: closing the `ImageProxy` stays the caller's job (the
     * CameraX analyser must close it to receive the next frame).
     *
     * @throws IllegalArgumentException for a non-YUV_420_888 image, odd dimensions (impossible for
     *   4:2:0 chroma) or a rotation that is not a multiple of 90°.
     */
    fun convert(image: ImageProxy): CameraFrame {
        require(image.format == ImageFormat.YUV_420_888) {
            "Expected ImageFormat.YUV_420_888 frames, got format ${image.format}"
        }
        val width = image.width
        val height = image.height
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "Unsupported frame size ${width}x$height"
        }
        require(image.planes.size == 3) { "Expected 3 image planes, got ${image.planes.size}" }

        val yuvMat = yuvMat(width, height)
        fillNv21(image, width, height)
        yuvMat.put(0, 0, nv21)

        val rotation = image.imageInfo.rotationDegrees
        // The first `height` rows of the NV21 Mat are the luma plane, i.e. the greyscale frame.
        val lumaView = yuvMat.submat(0, height, 0, width)
        val rgbFull = Mat()
        val grey = Mat()
        val rgb = Mat()
        try {
            Imgproc.cvtColor(yuvMat, rgbFull, Imgproc.COLOR_YUV2RGB_NV21)
            // The views above alias reusable buffers; rotate() always writes an owned copy.
            rotate(lumaView, grey, rotation)
            rotate(rgbFull, rgb, rotation)
        } catch (error: Throwable) {
            grey.release()
            rgb.release()
            throw error
        } finally {
            lumaView.release()
            rgbFull.release()
        }
        return CameraFrame(grey, rgb, rotation)
    }

    /** Releases the reusable native buffer. The frames already handed out are unaffected. */
    override fun close() {
        yuv?.release()
        yuv = null
    }

    private fun yuvMat(width: Int, height: Int): Mat {
        val rows = height + height / 2
        val existing = yuv
        if (existing != null && existing.rows() == rows && existing.cols() == width) return existing
        existing?.release()
        if (nv21.size != rows * width) nv21 = ByteArray(rows * width)
        return Mat(rows, width, CvType.CV_8UC1).also { yuv = it }
    }

    private fun fillNv21(image: ImageProxy, width: Int, height: Int) {
        copyLuma(image.planes[0], width, height)
        interleaveChroma(
            uPlane = image.planes[1],
            vPlane = image.planes[2],
            chromaWidth = width / 2,
            chromaHeight = height / 2,
            destOffset = width * height,
        )
    }

    private fun copyLuma(plane: ImageProxy.PlaneProxy, width: Int, height: Int) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1 && rowStride == width) {
            buffer.position(0)
            buffer.get(nv21, 0, width * height)
            return
        }
        if (pixelStride == 1) {
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(nv21, row * width, width)
            }
            return
        }
        lumaRow = ensureCapacity(lumaRow, rowStride)
        for (row in 0 until height) {
            val read = readRow(buffer, row, rowStride, lumaRow)
            var source = 0
            var dest = row * width
            for (column in 0 until width) {
                nv21[dest++] = if (source < read) lumaRow[source] else 0
                source += pixelStride
            }
        }
    }

    /**
     * Writes the chroma samples as NV21 expects them: V then U, per 2×2 block.
     *
     * Each row is interleaved explicitly instead of bulk-copying the V plane. That shortcut only
     * holds for V-first (NV21) buffers: on a semi-planar NV12 device the same copy silently pairs
     * every V sample with the *next* U sample, shifting the chroma by one block column. Reading the
     * two planes separately is correct for planar (pixel stride 1) and both semi-planar layouts.
     */
    private fun interleaveChroma(
        uPlane: ImageProxy.PlaneProxy,
        vPlane: ImageProxy.PlaneProxy,
        chromaWidth: Int,
        chromaHeight: Int,
        destOffset: Int,
    ) {
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        uRow = ensureCapacity(uRow, uPlane.rowStride)
        vRow = ensureCapacity(vRow, vPlane.rowStride)
        for (row in 0 until chromaHeight) {
            val uRead = readRow(uBuffer, row, uPlane.rowStride, uRow)
            val vRead = readRow(vBuffer, row, vPlane.rowStride, vRow)
            var dest = destOffset + row * chromaWidth * 2
            var uSource = 0
            var vSource = 0
            for (column in 0 until chromaWidth) {
                nv21[dest++] = if (vSource < vRead) vRow[vSource] else 0
                nv21[dest++] = if (uSource < uRead) uRow[uSource] else 0
                uSource += uPixelStride
                vSource += vPixelStride
            }
        }
    }

    /**
     * Reads row [row] of a plane into [into] and returns the number of bytes available. The last
     * row of a semi-planar chroma plane stops one byte short of `rowStride`, hence the clamp.
     */
    private fun readRow(
        buffer: ByteBuffer,
        row: Int,
        rowStride: Int,
        into: ByteArray,
    ): Int {
        buffer.position(row * rowStride)
        val available = min(rowStride, buffer.remaining())
        buffer.get(into, 0, available)
        return available
    }

    private fun ensureCapacity(array: ByteArray, size: Int): ByteArray =
        if (array.size >= size) array else ByteArray(size)

    private fun rotate(source: Mat, dest: Mat, rotationDegrees: Int) {
        when (rotationDegrees) {
            0 -> source.copyTo(dest)
            90 -> Core.rotate(source, dest, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(source, dest, Core.ROTATE_180)
            270 -> Core.rotate(source, dest, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> throw IllegalArgumentException("Unsupported frame rotation: $rotationDegrees")
        }
    }
}
