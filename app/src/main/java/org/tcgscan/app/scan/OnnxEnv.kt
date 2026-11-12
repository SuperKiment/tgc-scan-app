package org.tcgscan.app.scan

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.IOException

/**
 * The single ONNX Runtime environment of the process and the only factory of [OrtSession]s (D5/D6).
 *
 * One instance per process, held by the app container (T31). It owns every session it creates, so
 * [close] releases all native inference memory at once — a session keeps its whole weight set
 * resident (the embedder alone is 24 MB), which must not leak when the scan screen goes away.
 *
 * The [OrtEnvironment] itself is a process-wide singleton inside ORT and is deliberately **not**
 * closed here: closing it would tear down the native environment under any session created
 * afterwards.
 */
class OnnxEnv(context: Context) : Closeable {

    private val appContext: Context = context.applicationContext

    /** Sessions created through [createSession] and not yet closed. */
    private val sessions = ArrayList<OrtSession>(SESSION_SLOTS)

    private var closed = false

    /** The process-wide ORT environment; needed by callers to build input tensors. */
    val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    /**
     * Intra-op thread count: one core is left to the camera pipeline and the UI, and more than four
     * threads only adds scheduling noise on a phone (D6 measured 47 ms/image with two threads).
     */
    val intraOpThreads: Int =
        Runtime.getRuntime().availableProcessors().minus(1).coerceIn(1, MAX_INTRA_OP_THREADS)

    init {
        // NFR-5: the app sends nothing anywhere. The telemetry provider of the AAR is already
        // removed from the merged manifest (D5); this is the belt to that braces and is a no-op on
        // the Android builds of ORT, so a failure must never keep the app from starting.
        runCatching { environment.setTelemetry(false) }
            .onFailure { Log.w(TAG, "ORT refused to disable telemetry", it) }
    }

    /**
     * Opens a session over [asset], extracting it from the APK if needed.
     *
     * @throws IOException when the asset cannot be extracted (see [ModelAssets.file]).
     * @throws ai.onnxruntime.OrtException when ORT cannot load the model.
     */
    @Synchronized
    fun createSession(asset: ModelAsset): OrtSession {
        check(!closed) { "OnnxEnv is closed; cannot open a session over ${asset.fileName}" }
        val modelFile = ModelAssets.file(appContext, asset)
        // The options are copied into the session at creation time, so they are closed right away.
        val options = OrtSession.SessionOptions()
        return try {
            options.setIntraOpNumThreads(intraOpThreads)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(modelFile.absolutePath, options)
                .also { sessions.add(it) }
        } finally {
            options.close()
        }
    }

    /** Closes one session and forgets it. Idempotent for a session already closed this way. */
    @Synchronized
    fun closeSession(session: OrtSession) {
        if (sessions.remove(session)) {
            closeQuietly(session)
        }
    }

    /** Closes every session still open. The ORT environment itself survives (see the class doc). */
    @Synchronized
    override fun close() {
        closed = true
        sessions.forEach(::closeQuietly)
        sessions.clear()
    }

    private fun closeQuietly(session: OrtSession) {
        // OrtSession.close() throws a checked OrtException in Java; a failure to release native
        // memory is worth a log but must not mask the reason the caller was shutting down.
        runCatching { session.close() }.onFailure { Log.w(TAG, "Closing an ORT session failed", it) }
    }

    private companion object {

        const val TAG = "OnnxEnv"

        /** Never more than four intra-op threads on a phone. */
        const val MAX_INTRA_OP_THREADS = 4

        /** Embedder + OCR detection + two recognisers: four sessions at most. */
        const val SESSION_SLOTS = 4
    }
}
