package org.tcgscan.app.scan

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import org.tcgscan.app.scan.detect.CameraFrame
import org.tcgscan.app.scan.detect.CardQuad
import org.tcgscan.app.scan.detect.CardQuadDetector
import org.tcgscan.app.scan.detect.FrameConverter
import org.tcgscan.app.scan.detect.QuadOutcome
import org.tcgscan.app.scan.detect.Stabilizer
import org.tcgscan.app.scan.detect.StabilizerDecision
import org.tcgscan.app.scan.detect.Warper
import org.tcgscan.app.scan.match.MatchPack
import org.tcgscan.app.scan.match.Matcher
import org.tcgscan.app.scan.ocr.OcrEngine
import org.tcgscan.app.scan.text.CardTextParser
import org.tcgscan.app.scan.text.ScriptDetector
import java.io.Closeable
import java.util.concurrent.Executors

/**
 * What one analysed frame means for the scan screen.
 *
 * Everything but [Detected] and [Duplicate] is transient feedback: the burst is never interrupted
 * by a question (FR-6), the screen only shows a hint and keeps going.
 */
sealed interface ScanEvent {

    /** Nothing to report: no card, or a card that has not held still long enough yet. */
    data object Idle : ScanEvent

    /** Several cards in the frame: no capture, « une seule carte » (EC-5). */
    data object MultipleCards : ScanEvent

    /** A card is there but too soft to be read (EC-7). */
    data object TooBlurry : ScanEvent

    /** The frame is too dark: « activez la lampe » (EC-7). */
    data object LowLight : ScanEvent

    /** A card was captured, identified and persisted; [detectionId] is its row (FR-6/EC-13). */
    data class Detected(val detectionId: Long) : ScanEvent

    /**
     * A card already captured in this burst was captured again (FR-7/EC-9): [detectionId] is the
     * row recording the repeat, [ofDetectionId] the capture it repeats. Nothing was added to the
     * review screen.
     */
    data class Duplicate(val detectionId: Long, val ofDetectionId: Long) : ScanEvent
}

/**
 * The scan loop, from a camera frame to a persisted detection (FR-1, FR-6, FR-7).
 *
 * One frame goes through: convert → detect the card quad → stabilise → warp → embed → OCR → parse →
 * match → persist. Every stage owns native buffers it reuses, so the whole chain runs on a single
 * dedicated thread — the [dispatcher] this instance owns — and [onFrame] may be called from
 * anywhere, including the CameraX analyser callback.
 *
 * Two guards decide what is worth identifying: the quality gates of EC-7, which reject a frame
 * *before* the stabiliser sees it, and `Stabilizer`'s own cooldown, which keeps a card that stays
 * in front of the lens from being captured twice (FR-7). `DedupTracker` is the session-wide
 * backstop for a card that left the frame and came back (EC-9).
 *
 * The pipeline writes detections and photos; it never writes the collection. Only the review screen
 * does, and only on the user's validation (FR-9).
 */
class ScanPipeline(
    private val sessions: ScanSessionRepository,
    /**
     * The packs the matcher may read, keyed by language. Called on the scan thread for each
     * capture, so the provider is expected to hand back an already-open, cached map (T31).
     */
    private val installedPacks: () -> Map<String, MatchPack>,
    private val converter: FrameConverter,
    private val detector: CardQuadDetector,
    private val stabilizer: Stabilizer,
    private val embedder: Embedder,
    private val ocr: OcrEngine,
    private val dedup: DedupTracker = DedupTracker(),
) : Closeable {

    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, THREAD_NAME) }
            .asCoroutineDispatcher()

    /**
     * Serialises [analyse], [resumeSession] and [endSession]: Room suspends inside a capture and
     * frees the scan thread, so without it a resume or an end could interleave with a capture
     * half-way and the late session id would rebind an ended session.
     */
    private val stateLock = Mutex()

    @Volatile
    private var sessionId: Long? = null

    @Volatile
    private var closed = false

    /** The session the captures are written to, `null` until the first capture opens one. */
    val activeSessionId: Long? get() = sessionId

    /**
     * Analyses one camera frame.
     *
     * [image] is **not** closed here: the CameraX analyser must close it itself to receive the next
     * frame, and it has to stay valid for the whole conversion.
     *
     * @param sessionLang default language of the burst, applied when the card does not say which
     *   one it is printed in (FR-5).
     * @throws ai.onnxruntime.OrtException when a model fails; a frame the converter or the detector
     *   cannot use is skipped as [ScanEvent.Idle] instead, so one odd frame never ends a burst.
     */
    suspend fun onFrame(image: ImageProxy, sessionLang: String): ScanEvent {
        require(sessionLang.isNotBlank()) { "A scan session needs a default language" }
        check(!closed) { "ScanPipeline is closed" }
        return withContext(dispatcher) { stateLock.withLock { analyse(image, sessionLang) } }
    }

    /**
     * Writes the next captures to [sessionId] instead of a new session — the « reprendre » of the
     * resume banner (EC-13). Call it before the camera starts.
     *
     * Known limitation (EC-9): the duplicate tracker is cleared, so a card captured before the
     * resume is not recognised as a repeat afterwards.
     */
    suspend fun resumeSession(sessionId: Long) {
        require(sessionId > 0L) { "Invalid session id $sessionId" }
        withContext(dispatcher) {
            stateLock.withLock {
                this@ScanPipeline.sessionId = sessionId
                stabilizer.reset()
                dedup.clear()
            }
        }
    }

    /**
     * Forgets the burst: the stabiliser, the duplicate tracker and the bound session. The session
     * rows themselves are untouched — they belong to the review screen now (EC-14).
     */
    suspend fun endSession() {
        withContext(dispatcher) {
            stateLock.withLock {
                sessionId = null
                stabilizer.reset()
                dedup.clear()
            }
        }
    }

    /**
     * Releases every stage, the ~34 MB of ORT sessions included, and stops the scan thread.
     *
     * The stages are released *by* the scan thread as its last task: a native buffer must never be
     * freed under a frame still being analysed.
     */
    override fun close() {
        if (closed) return
        closed = true
        val released = runCatching { dispatcher.executor.execute(::closeStages) }
            .onFailure { Log.w(TAG, "Scan thread unavailable, releasing the stages inline", it) }
            .isSuccess
        if (!released) closeStages()
        dispatcher.close()
    }

    private suspend fun analyse(image: ImageProxy, sessionLang: String): ScanEvent {
        // Only the converter and the detector reject a frame with an IllegalArgumentException; any
        // other one, from the recording or the warp, is a bug and must not be hidden as Idle.
        val frame = try {
            converter.convert(image)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unusable frame skipped", e)
            return ScanEvent.Idle
        }
        return frame.use {
            val detection = try {
                detector.detect(frame.grey)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Unusable frame skipped", e)
                return ScanEvent.Idle
            }
            // The quality gates come before the stabiliser on purpose: a frame that could not be
            // identified must neither advance the stability streak nor arm the cooldown, which
            // would block the card until it leaves the frame (FR-7/EC-7).
            if (detection.isLowLight) return ScanEvent.LowLight
            if (detection.singleQuad != null && detection.isBlurry) return ScanEvent.TooBlurry

            when (val decision = stabilizer.onFrame(detection)) {
                is StabilizerDecision.Capture -> capture(frame, decision.quad, sessionLang)

                // The stabiliser is fed every frame, including the ones holding several cards: that
                // is how it learns the captured card left (FR-7).
                StabilizerDecision.Idle ->
                    if (detection.outcome is QuadOutcome.Multiple) {
                        ScanEvent.MultipleCards
                    } else {
                        ScanEvent.Idle
                    }

                is StabilizerDecision.Settling, is StabilizerDecision.Blocked -> ScanEvent.Idle
            }
        }
    }

    /** Identifies and persists one stabilised card. */
    private suspend fun capture(
        frame: CameraFrame,
        quad: CardQuad,
        sessionLang: String,
    ): ScanEvent {
        val card = Warper.warp(frame.rgb, quad)
        try {
            val embedding = embed(card)
            // The script is read off the card, never assumed from the session: a burst may mix
            // languages (FR-5), so both recognisers run and the more confident reading wins.
            val readings = ocr.read(card).toOcrLines()
            val script = ScriptDetector.detect(readings)
            val parsedText = CardTextParser.parse(readings)

            val sessionId = sessionId(sessionLang)
            dedup.inspect(embedding, parsedText)?.let { duplicate ->
                val detectionId =
                    sessions.recordDuplicate(sessionId, duplicate.detectionId, script)
                return ScanEvent.Duplicate(detectionId, duplicate.detectionId)
            }

            val outcome = Matcher.match(
                embedding = embedding,
                parsedText = parsedText,
                script = script,
                sessionLang = sessionLang,
                installedPacks = installedPacks(),
            )
            // Persisted — photo included — before the capture is reported, so an app killed one
            // frame later loses nothing (EC-13).
            val detectionId = sessions.recordDetection(sessionId, outcome, script, card)
            dedup.remember(detectionId, embedding, parsedText)
            return ScanEvent.Detected(detectionId)
        } finally {
            card.release()
        }
    }

    /** The 224×224 view of the card, embedded into the 384 floats the matcher compares (D6). */
    private fun embed(card: Mat): FloatArray {
        val square = Warper.resizeForEmbedder(card)
        return try {
            embedder.embed(square)
        } finally {
            square.release()
        }
    }

    /**
     * The session the captures go to, opened on the first one with [sessionLang] as its default
     * language. Only ever called from the scan thread, which is also the only writer of the field
     * besides [resumeSession], and both hold [stateLock].
     */
    private suspend fun sessionId(sessionLang: String): Long =
        sessionId ?: sessions.startSession(sessionLang).also { sessionId = it }

    private fun closeStages() {
        converter.close()
        detector.close()
        embedder.close()
        ocr.close()
    }

    companion object {

        private const val TAG = "ScanPipeline"

        private const val THREAD_NAME = "tcgscan-scan"

        /**
         * Builds a pipeline with its own stages. The ORT sessions are opened here (~34 MB of
         * weights), so this is a background-thread call.
         */
        fun open(
            context: Context,
            env: OnnxEnv,
            sessions: ScanSessionRepository,
            installedPacks: () -> Map<String, MatchPack>,
        ): ScanPipeline = ScanPipeline(
            sessions = sessions,
            installedPacks = installedPacks,
            converter = FrameConverter(),
            detector = CardQuadDetector(),
            stabilizer = Stabilizer(),
            embedder = Embedder.open(env),
            ocr = OcrEngine.open(context, env),
        )
    }
}
