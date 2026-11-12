package org.tcgscan.app.scan

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import org.opencv.core.Mat
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The visual embedder: a warped card crop in, 384 comparable floats out (D6,
 * `docs/pack-format.md` §3).
 *
 * It runs `embed_dinov2s_int8.onnx` — the very file the CI pipeline runs — over the tensor built by
 * [Preprocess], keeps the **CLS token** of `last_hidden_state` and L2-normalises it, so a dot
 * product between two embeddings is their cosine similarity.
 *
 * One instance owns one ORT session (24 MB of weights) and one reusable input buffer, so it is
 * **not** thread-safe: it belongs to the single-thread scan dispatcher of `ScanPipeline` (T14).
 * [close] gives the session back to the [OnnxEnv] that created it.
 */
class Embedder private constructor(
    private val env: OnnxEnv,
    private val session: OrtSession,
) : Closeable {

    /** `pixel_values` per D6; read from the model so a changed export fails loudly here. */
    private val inputName: String = session.inputNames.single()

    private val inputShape = longArrayOf(
        1L,
        Preprocess.CHANNELS.toLong(),
        Preprocess.EMBED_INPUT_SIDE.toLong(),
        Preprocess.EMBED_INPUT_SIDE.toLong(),
    )

    private val input: FloatBuffer = Preprocess.newEmbedInputBuffer()

    /**
     * Embeds one card crop.
     *
     * @param cardRgb the perspective-corrected card, 8-bit RGB (`CV_8UC3`); it is not modified.
     * @return a freshly allocated, L2-normalised [DIM]-length vector. A degenerate model output
     *   (a zero-norm CLS token, which a real card cannot produce) yields the all-zero vector: it
     *   scores a cosine of 0 against every reference, so the detection lands in « à vérifier »
     *   instead of crashing the scan loop.
     * @throws IllegalArgumentException when [cardRgb] does not honour [Preprocess]'s contract.
     * @throws ai.onnxruntime.OrtException when inference fails.
     */
    fun embed(cardRgb: Mat): FloatArray {
        Preprocess.writeEmbedInput(cardRgb, input)
        OnnxTensor.createTensor(env.environment, input, inputShape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val output = result[0] as? OnnxTensor
                    ?: error("Embedding model returned ${result[0].type}, expected a tensor")
                val shape = output.info.shape
                check(shape.size == OUTPUT_RANK && shape[0] == 1L && shape[2] == DIM.toLong()) {
                    "Expected last_hidden_state (1, tokens, $DIM), got ${shape.contentToString()}"
                }
                val values = output.floatBuffer
                    ?: error("last_hidden_state is not a float32 tensor")
                // The CLS token is the first of the 257 tokens, so it is the first DIM floats.
                val vector = FloatArray(DIM)
                values.get(vector, 0, DIM)
                return normalize(vector)
            }
        }
    }

    /**
     * The int8 form of [embed], encoded exactly like `embeddings.vec` in a pack
     * (`docs/pack-format.md` §3.3). Debug and diagnostics only — matching works on the float vector
     * and quantises it inside `EmbeddingIndex`.
     */
    fun embedQuantized(cardRgb: Mat): ByteArray = quantize(embed(cardRgb))

    /** Returns the session to [OnnxEnv]; the instance must not be used afterwards. */
    override fun close() {
        env.closeSession(session)
    }

    companion object {

        /** Dimension of the embedding, `pack_meta.embed_dim` (D6/D9). */
        const val DIM = 384

        /** `last_hidden_state` is `(1, tokens, DIM)`. */
        private const val OUTPUT_RANK = 3

        /** int8 scale of `embeddings.vec`: `round(v * 127)`, clipped (`docs` §3.3). */
        private const val INT8_SCALE = 127

        /** Below this norm the CLS token carries no direction worth normalising. */
        private const val MIN_NORM = 1e-6

        /** Opens the embedder over [ModelAsset.EMBED_DINOV2S_INT8], the only model it ever reads. */
        fun open(env: OnnxEnv): Embedder =
            Embedder(env, env.createSession(ModelAsset.EMBED_DINOV2S_INT8))

        /**
         * Encodes an L2-normalised vector the way the pack builder does, so the integer dot product
         * of two encoded vectors divided by `127²` is their cosine (`docs` §3.3).
         */
        fun quantize(vector: FloatArray): ByteArray {
            require(vector.size == DIM) { "Expected a $DIM-length vector, got ${vector.size}" }
            return ByteArray(DIM) { i ->
                (vector[i] * INT8_SCALE).roundToInt().coerceIn(-INT8_SCALE, INT8_SCALE).toByte()
            }
        }

        /** L2-normalises [vector] in place and returns it; zeroes it when it has no direction. */
        private fun normalize(vector: FloatArray): FloatArray {
            var sumOfSquares = 0.0
            for (value in vector) sumOfSquares += value.toDouble() * value
            val norm = sqrt(sumOfSquares)
            if (norm < MIN_NORM) {
                vector.fill(0f)
                return vector
            }
            for (i in vector.indices) vector[i] = (vector[i] / norm).toFloat()
            return vector
        }
    }
}
