package org.tcgscan.app.scan

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Image → tensor conversion, the **single** place that knows the normalisation contract of
 * `docs/pack-format.md` §3.1.
 *
 * The pipeline (`pipeline/tcgscan_pipeline/embed.py`) performs exactly the same steps on the same
 * images, with the same OpenCV resampling kernel, so pack vectors and on-device vectors are
 * directly comparable:
 *
 * 1. RGB input (the perspective-corrected card crop on the device, the cached `low.webp` in CI);
 * 2. `resize(…, (224, 224), INTER_LINEAR)` — the aspect ratio is **squashed**, no letterbox and no
 *    centre crop;
 * 3. `float32`, divided by 255;
 * 4. ImageNet normalisation, per channel, in RGB order;
 * 5. NCHW layout.
 *
 * Nothing here is stateful: the scratch `Mat` and the pixel buffer are per-call, which costs one
 * 147 kB array per capture (captures are gated by [org.tcgscan.app.scan.Embedder]'s caller, a few
 * per second at most) and keeps the object usable from any thread.
 */
object Preprocess {

    /** Channel count of every tensor produced here; RGB, in that order. */
    const val CHANNELS = 3

    /** Side of the embedding model's square input (D6: `pixel_values (1, 3, 224, 224)`). */
    const val EMBED_INPUT_SIDE = 224

    /** Number of `float32` elements of one embedding input tensor. */
    const val EMBED_INPUT_ELEMENTS = CHANNELS * EMBED_INPUT_SIDE * EMBED_INPUT_SIDE

    /** ImageNet mean, red channel. */
    const val MEAN_R = 0.485f

    /** ImageNet mean, green channel. */
    const val MEAN_G = 0.456f

    /** ImageNet mean, blue channel. */
    const val MEAN_B = 0.406f

    /** ImageNet standard deviation, red channel. */
    const val STD_R = 0.229f

    /** ImageNet standard deviation, green channel. */
    const val STD_G = 0.224f

    /** ImageNet standard deviation, blue channel. */
    const val STD_B = 0.225f

    private const val MAX_BYTE_VALUE = 255f

    private val MEAN = floatArrayOf(MEAN_R, MEAN_G, MEAN_B)
    private val STD = floatArrayOf(STD_R, STD_G, STD_B)

    /** Number of `float32` elements of a `[1, 3, height, width]` tensor. */
    fun elementCount(width: Int, height: Int): Int = CHANNELS * width * height

    /**
     * A direct, native-order buffer of [elements] floats — the only layout ONNX Runtime can hand to
     * the native side without copying. Callers keep one and reuse it across frames.
     */
    fun newInputBuffer(elements: Int): FloatBuffer {
        require(elements > 0) { "Tensor element count must be positive, got $elements" }
        return ByteBuffer.allocateDirect(elements * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    /** The input buffer of the embedding model (D6). */
    fun newEmbedInputBuffer(): FloatBuffer = newInputBuffer(EMBED_INPUT_ELEMENTS)

    /**
     * Writes [cardRgb] into [dst] as the `pixel_values` tensor of the embedding model (D6).
     *
     * @param cardRgb the warped card crop, 8-bit RGB (`CV_8UC3`).
     * @param dst a buffer of at least [EMBED_INPUT_ELEMENTS] floats, typically from
     *   [newEmbedInputBuffer]; on return it is positioned at 0 and limited to the tensor size.
     */
    fun writeEmbedInput(cardRgb: Mat, dst: FloatBuffer) {
        writeNormalizedNchw(cardRgb, EMBED_INPUT_SIDE, EMBED_INPUT_SIDE, dst)
    }

    /**
     * Resizes [rgb] to [width] × [height] with `INTER_LINEAR`, applies the ImageNet normalisation
     * and writes the result into [dst] in NCHW order.
     *
     * @param rgb an 8-bit RGB image (`CV_8UC3`); it is not modified.
     * @param dst a buffer of at least `elementCount(width, height)` floats; on return it is
     *   positioned at 0 and limited to the tensor size, ready for `OnnxTensor.createTensor`.
     * @throws IllegalArgumentException when the inputs do not honour the contract above.
     */
    fun writeNormalizedNchw(rgb: Mat, width: Int, height: Int, dst: FloatBuffer) {
        require(!rgb.empty()) { "Cannot preprocess an empty Mat" }
        require(rgb.type() == CvType.CV_8UC3) {
            "Expected an 8-bit RGB Mat (CV_8UC3), got type ${CvType.typeToString(rgb.type())}"
        }
        require(width > 0 && height > 0) { "Invalid tensor size ${width}x$height" }
        val elements = elementCount(width, height)
        require(dst.capacity() >= elements) {
            "Destination buffer holds ${dst.capacity()} floats, $elements needed"
        }

        val resized = Mat()
        try {
            Imgproc.resize(
                rgb,
                resized,
                Size(width.toDouble(), height.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_LINEAR,
            )
            check(resized.isContinuous) { "Resized Mat is not continuous" }

            val pixels = ByteArray(elements)
            resized.get(0, 0, pixels)

            dst.clear()
            val plane = width * height
            for (channel in 0 until CHANNELS) {
                val mean = MEAN[channel]
                val std = STD[channel]
                var source = channel
                for (pixel in 0 until plane) {
                    val value = (pixels[source].toInt() and 0xFF) / MAX_BYTE_VALUE
                    dst.put((value - mean) / std)
                    source += CHANNELS
                }
            }
            dst.flip()
        } finally {
            resized.release()
        }
    }
}
