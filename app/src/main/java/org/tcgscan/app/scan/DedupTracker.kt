package org.tcgscan.app.scan

import org.tcgscan.app.scan.text.ParsedCardText

/**
 * An earlier capture of the session that the current one repeats (FR-7/EC-9).
 *
 * [detectionId] is the row the user already has in the review screen; the repeat is recorded as a
 * `DUPLICATE` detection and nothing is added to it.
 */
data class DuplicateCapture(
    val detectionId: Long,
    /** Cosine between the two captures, at least [DedupTracker.DUPLICATE_MIN_COSINE]. */
    val cosine: Float,
)

/**
 * Keeps one card out of the review screen twice, for the whole burst (FR-7/EC-9).
 *
 * `Stabilizer` already blocks the frames that follow a capture while the card stays in front of the
 * lens, but it cannot recognise a card that left the frame and came back — the hand that slides the
 * previous card back under the camera. This tracker is that second line: it compares the new
 * capture with every capture of the session on **two** independent facts, and only calls it a
 * repeat when both agree:
 *
 * 1. the visual cosine is at least [DUPLICATE_MIN_COSINE], and
 * 2. the printed numbers read by the OCR match.
 *
 * The number condition is what keeps an identical-art reprint — the very pair the matcher works
 * hardest to tell apart (FR-2) — from being merged into a single possession: two cards that look
 * the same but are numbered differently are two cards. The price is symmetrical and deliberate: a
 * capture whose number could not be read is never called a repeat, so the user may see the same
 * card twice in « à vérifier ». Validating both rows still yields a single possession, since
 * ownership is binary (FR-12), whereas a wrong merge would silently lose a card.
 *
 * Not thread-safe; one instance per scan session, living on the pipeline's scan dispatcher. A
 * remembered capture costs ~1.5 kB (384 floats), so even a very long burst stays negligible.
 */
class DedupTracker(private val minCosine: Float = DUPLICATE_MIN_COSINE) {

    init {
        require(minCosine in 0f..1f) { "minCosine must be in 0..1, got $minCosine" }
    }

    private val captures = ArrayList<TrackedCapture>()

    /** How many captures of the session are being tracked. */
    val size: Int get() = captures.size

    /**
     * Looks for an earlier capture of the same card.
     *
     * @param embedding the L2-normalised embedding of the capture, `null` when none could be
     *   computed — there is then no visual evidence at all and the capture is never a repeat.
     * @param parsedText what the OCR read on it.
     * @return the earliest matching capture, or `null` when this is a new card. The *earliest* one
     *   wins on purpose: the surviving row is the one the user has already seen.
     */
    fun inspect(embedding: FloatArray?, parsedText: ParsedCardText): DuplicateCapture? {
        if (embedding == null) return null
        val numbers = numbersOf(parsedText)
        if (numbers.isEmpty()) return null
        for (capture in captures) {
            if (!capture.numbersAgreeWith(numbers, parsedText.total)) continue
            val cosine = cosine(capture.embedding, embedding) ?: continue
            if (cosine >= minCosine) return DuplicateCapture(capture.detectionId, cosine)
        }
        return null
    }

    /**
     * Records a capture that was added to the session, so a later one can be recognised as its
     * repeat. Called with the detection row that was just persisted.
     */
    fun remember(detectionId: Long, embedding: FloatArray?, parsedText: ParsedCardText) {
        require(detectionId > 0L) { "Invalid detection id $detectionId" }
        if (embedding == null) return
        captures += TrackedCapture(
            detectionId = detectionId,
            embedding = embedding,
            localIds = numbersOf(parsedText),
            total = parsedText.total,
        )
    }

    /** Forgets the burst; called when a session ends or another one is opened. */
    fun clear() {
        captures.clear()
    }

    /** The printed numbers of a reading, as the set [inspect] intersects. */
    private fun numbersOf(parsedText: ParsedCardText): Set<String> =
        parsedText.localIdCandidates.toHashSet()

    /**
     * Cosine of two L2-normalised embeddings, i.e. their dot product; `null` for a pair of
     * different lengths, which only a changed embedding model could produce.
     */
    private fun cosine(a: FloatArray, b: FloatArray): Float? {
        if (a.size != b.size) return null
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i]
        return dot.toFloat()
    }

    private class TrackedCapture(
        val detectionId: Long,
        val embedding: FloatArray,
        val localIds: Set<String>,
        val total: Int?,
    ) {

        /**
         * True when the printed numbers of the two captures can be the same card's: a common
         * `local_id` reading and no contradicting denominator — « 136/201 » and « 136/185 » are the
         * same number in two different sets.
         */
        fun numbersAgreeWith(otherLocalIds: Set<String>, otherTotal: Int?): Boolean {
            if (localIds.none { it in otherLocalIds }) return false
            return total == null || otherTotal == null || total == otherTotal
        }
    }

    companion object {

        /**
         * Cosine above which two captures are the same cardboard. Far above
         * [ScanTuning.MIN_VISUAL_COSINE], which compares a camera frame with a *pack* visual: here
         * both sides are camera frames of the same card under the same light, so anything below
         * this is a different print.
         */
        const val DUPLICATE_MIN_COSINE = 0.97f
    }
}
