package org.tcgscan.app.data.pack

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * The visual index of one pack: every `embeddings.vec` of the pack in a single direct
 * [ByteBuffer], with a parallel `card_id` array.
 *
 * Brute force by design (D9, docs/pack-format.md §3.4): ~17 k French vectors × 384 int8 ≈ 6.6 MB
 * and ≈ 6.6 M multiply-accumulates, a few milliseconds — there is **no ANN index** in the pack and
 * none is to be added without changing `pack_schema`.
 *
 * [topK] is the single similarity entry point of the app; no other code reads [vectors]. It
 * allocates nothing per call besides its result list (the query quantisation and the running
 * top-k live in buffers allocated once at load time), which is why it is `synchronized`: the scan
 * pipeline calls it from one dispatcher, and a stray second caller must not corrupt those buffers.
 */
class EmbeddingIndex private constructor(
    val lang: String,
    val dim: Int,
    /** `pack_meta.embed_model` — the caller must refuse to match vectors from another model. */
    val embedModel: String,
    private val cardIds: Array<String>,
    private val vectors: ByteBuffer,
) {

    /** Number of indexed cards, i.e. cards whose `has_embed != 0`. */
    val size: Int get() = cardIds.size

    /** Resident size of the index in bytes. */
    val byteSize: Int get() = size * dim

    private val quantizedQuery = IntArray(dim)
    private val bestScores = IntArray(MAX_TOP_K)
    private val bestRows = IntArray(MAX_TOP_K)

    /**
     * The [k] most similar cards to [query], best first, as `card_id to cosine`.
     *
     * [query] is an L2-normalised 384-float embedding (docs §3.2). It is quantised with the very
     * rule the pipeline used for the stored vectors — `round(v × 127)`, clipped to `[-127, 127]` —
     * so the cosine is the integer dot product divided by `127²` (docs §3.3).
     *
     * @throws IllegalArgumentException when [query] has the wrong length or [k] exceeds [MAX_TOP_K].
     */
    @Synchronized
    fun topK(query: FloatArray, k: Int): List<Pair<String, Float>> {
        require(query.size == dim) { "Query has ${query.size} dims, pack expects $dim" }
        require(k in 0..MAX_TOP_K) { "k=$k outside 0..$MAX_TOP_K" }
        val wanted = minOf(k, size)
        if (wanted == 0) return emptyList()

        val q = quantizedQuery
        for (d in 0 until dim) {
            q[d] = (query[d] * QUANT_SCALE).roundToInt().coerceIn(-QUANT_SCALE, QUANT_SCALE)
        }

        var filled = 0
        for (row in 0 until size) {
            var dot = 0
            val base = row * dim
            for (d in 0 until dim) {
                dot += q[d] * vectors.get(base + d)
            }
            if (filled == wanted && dot <= bestScores[wanted - 1]) continue
            // Insertion sort into the running top-k; it holds at most MAX_TOP_K entries.
            var pos = if (filled < wanted) filled else wanted - 1
            while (pos > 0 && bestScores[pos - 1] < dot) {
                bestScores[pos] = bestScores[pos - 1]
                bestRows[pos] = bestRows[pos - 1]
                pos--
            }
            bestScores[pos] = dot
            bestRows[pos] = row
            if (filled < wanted) filled++
        }

        val result = ArrayList<Pair<String, Float>>(filled)
        for (i in 0 until filled) {
            result.add(cardIds[bestRows[i]] to bestScores[i] / COSINE_DIVISOR)
        }
        return result
    }

    companion object {

        private const val TAG = "EmbeddingIndex"

        /** Hard ceiling of the preallocated top-k buffers; `ScanTuning.TOP_K` stays below it. */
        const val MAX_TOP_K = 64

        /** int8 quantisation scale of docs/pack-format.md §3.3. */
        const val QUANT_SCALE = 127

        private const val COSINE_DIVISOR = (QUANT_SCALE * QUANT_SCALE).toFloat()

        /**
         * Loads the index of the installed pack of [lang], or returns `null` when that language has
         * no pack installed (EC-1).
         *
         * @throws PackFormatException when the pack is unreadable or its `pack_meta` is invalid.
         */
        fun load(context: Context, lang: String): EmbeddingIndex? =
            PackCatalog.open(context, lang)?.use { load(it) }

        /**
         * Loads the index from an already open [catalog]. Rows whose blob is not exactly
         * `embed_dim` bytes are skipped rather than trusted — a malformed pack degrades the visual
         * match, it never corrupts the buffer.
         */
        fun load(catalog: PackCatalog): EmbeddingIndex {
            val meta = catalog.meta()
            if (!meta.isSchemaSupported) {
                throw PackFormatException(
                    "Pack ${meta.lang} has pack_schema ${meta.packSchema}, too new to read",
                )
            }
            val dim = meta.embedDim
            if (dim <= 0) throw PackFormatException("pack_meta.embed_dim is $dim")

            val expectedRows = catalog.embeddingCount()
            val buffer = ByteBuffer.allocateDirect(expectedRows * dim)
            val ids = arrayOfNulls<String>(expectedRows)
            var rows = 0
            var skipped = 0
            catalog.forEachEmbedding { cardId, vec ->
                if (vec.size != dim || rows >= expectedRows) {
                    skipped++
                } else {
                    buffer.put(vec)
                    ids[rows] = cardId
                    rows++
                }
            }
            if (skipped > 0) {
                Log.w(TAG, "Pack ${meta.lang}: skipped $skipped embedding row(s) of unexpected size")
            }

            @Suppress("UNCHECKED_CAST")
            val cardIds = if (rows == expectedRows) {
                ids as Array<String>
            } else {
                Array(rows) { ids[it] as String }
            }
            return EmbeddingIndex(
                lang = meta.lang,
                dim = dim,
                embedModel = meta.embedModel,
                cardIds = cardIds,
                vectors = buffer,
            )
        }
    }
}
