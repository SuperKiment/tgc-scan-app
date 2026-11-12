package org.tcgscan.app.ui.scan

import ai.onnxruntime.OrtException
import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.opencv.core.CvException
import org.tcgscan.app.data.AppPrefs
import org.tcgscan.app.data.db.DbProvider
import org.tcgscan.app.data.db.ScanDetectionState
import org.tcgscan.app.data.pack.EmbeddingIndex
import org.tcgscan.app.data.pack.InstalledPack
import org.tcgscan.app.data.pack.InstalledPacksStore
import org.tcgscan.app.data.pack.PackCatalog
import org.tcgscan.app.data.pack.PackFormatException
import org.tcgscan.app.scan.ModelAsset
import org.tcgscan.app.scan.OnnxEnv
import org.tcgscan.app.scan.ScanEvent
import org.tcgscan.app.scan.ScanPhotoStore
import org.tcgscan.app.scan.ScanPipeline
import org.tcgscan.app.scan.ScanSessionRepository
import org.tcgscan.app.scan.match.MatchPack
import org.tcgscan.app.ui.settings.SettingsViewModel

/**
 * Drives the scan screen: owns the [ScanPipeline] while the screen is visible, feeds it the camera
 * frames, and turns its [ScanEvent]s into the counter, the transient hints and the haptic + flash
 * feedback of a detection (FR-6).
 *
 * Lifecycle: the pipeline holds ~34 MB of ORT sessions, so it is opened by [onScreenStart] and
 * released by [onScreenStop] — the camera and the models only live while the screen is on. The
 * *burst* outlives that: [currentSessionId] is kept, and a re-opened pipeline is re-bound to it, so
 * a phone call or a trip to the review screen never splits a burst in two (EC-13).
 *
 * Nothing here writes the collection (FR-9).
 */
class ScanViewModel(
    private val appContext: Context,
    private val sessions: ScanSessionRepository,
    private val installedPacks: InstalledPacksStore,
    private val appPrefs: AppPrefs,
) : ViewModel() {

    private val packCache = MatchPackCache(appContext)

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val detectionSignals = Channel<Unit>(Channel.CONFLATED)

    /** One tick per card added to the burst; the screen answers with a haptic tick and a flash. */
    val detectionFeedback: Flow<Unit> = detectionSignals.receiveAsFlow()

    private val hintSignals = MutableSharedFlow<ScanHint>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The packs as last refreshed; `null` until the first refresh has finished. */
    private val loadedPacks = MutableStateFlow<Map<String, InstalledPack>?>(null)

    /** The session the captures of this burst go to; `null` before the first capture of a burst. */
    private val currentSessionId = MutableStateFlow<Long?>(null)

    private val dismissedOfferId = MutableStateFlow<Long?>(null)

    @Volatile
    private var pipeline: ScanPipeline? = null

    @Volatile
    private var accepting = false

    @Volatile
    private var sessionLang: String? = null

    private val finishing = AtomicBoolean(false)

    private var opening: Job? = null

    init {
        observePacks()
        observeSessionLang()
        observeCount()
        observeResumeOffer()
        observeHints()
    }

    /** The screen is visible: opens the models, off the main thread. Idempotent. */
    fun onScreenStart() {
        if (pipeline != null || opening?.isActive == true) return
        _state.update { it.copy(engineFailed = false) }
        opening = viewModelScope.launch {
            val env = OnnxEnv(appContext)
            val opened = try {
                // Not cancellable: a pipeline that finished opening must be handed over or
                // released here, never dropped with its native buffers.
                withContext(NonCancellable + Dispatchers.Default) {
                    ScanPipeline.open(appContext, env, sessions, packCache::current)
                }
            } catch (e: Exception) {
                // ORT, the asset extraction and the native OCR setup all fail with different types;
                // the screen only needs to know that scanning is unavailable.
                Log.e(TAG, "The scan pipeline could not be opened", e)
                env.close()
                _state.update { it.copy(engineFailed = true) }
                return@launch
            }
            if (!isActive) {
                release(opened)
                return@launch
            }
            // The burst survives a stop/start of the screen: the new pipeline must keep writing to it.
            currentSessionId.value?.let { opened.resumeSession(it) }
            pipeline = opened
            accepting = true
            _state.update { it.copy(engineReady = true) }
        }
    }

    /** The screen is hidden: releases the models. The burst itself is kept. */
    fun onScreenStop() {
        accepting = false
        opening?.cancel()
        opening = null
        val released = pipeline
        pipeline = null
        _state.update { it.copy(engineReady = false) }
        if (released != null) release(released)
    }

    /**
     * Analyses one camera frame and **always closes it**. Blocking by design: it runs on the single
     * CameraX analysis thread, and `STRATEGY_KEEP_ONLY_LATEST` hands over the next frame only once
     * this one is closed — so the camera is throttled to the pipeline for free.
     */
    fun analyze(image: ImageProxy) {
        try {
            val active = pipeline
            val lang = sessionLang
            if (!accepting || active == null || lang == null) return
            handle(runBlocking { active.onFrame(image, lang) }, active)
        } catch (e: IllegalStateException) {
            // The pipeline was closed under a frame in flight: the screen is going away.
            Log.d(TAG, "Frame dropped, the pipeline is closed", e)
        } catch (e: Exception) {
            // The analysis thread must never die on one bad frame, whatever the stage that failed.
            if (e is OrtException || e is SQLiteException || e is IOException || e is CvException) {
                Log.w(TAG, "Frame analysis failed", e)
            } else {
                Log.e(TAG, "Unexpected frame analysis failure", e)
            }
            hintSignals.tryEmit(ScanHint.ANALYSIS_FAILED)
        } finally {
            image.close()
        }
    }

    fun toggleTorch() {
        _state.update { it.copy(torchOn = !it.torchOn) }
    }

    /** « Reprendre » of the resume banner (EC-13). */
    fun resumeSession(sessionId: Long) {
        viewModelScope.launch {
            // The pipeline is bound first: a capture must never open a second session in between.
            pipeline?.resumeSession(sessionId)
            currentSessionId.value = sessionId
        }
    }

    /** « Ignorer » of the resume banner: the session stays open and is offered again next time. */
    fun dismissResumeOffer(sessionId: Long) {
        dismissedOfferId.value = sessionId
    }

    /**
     * « Terminer »: closes the burst and hands the session to the review screen through
     * [onFinished]. The session rows are kept until the review validates or discards them (EC-14).
     */
    fun finish(onFinished: (sessionId: Long) -> Unit) {
        val sessionId = currentSessionId.value ?: return
        // A double tap must not navigate twice.
        if (!finishing.compareAndSet(false, true)) return
        // No frame may open a new session while the burst is being closed.
        accepting = false
        viewModelScope.launch {
            try {
                pipeline?.endSession()
                currentSessionId.value = null
                onFinished(sessionId)
            } finally {
                finishing.set(false)
            }
        }
    }

    private fun handle(event: ScanEvent, active: ScanPipeline) {
        when (event) {
            ScanEvent.Idle -> Unit
            ScanEvent.MultipleCards -> hintSignals.tryEmit(ScanHint.MULTIPLE_CARDS)
            ScanEvent.TooBlurry -> hintSignals.tryEmit(ScanHint.TOO_BLURRY)
            ScanEvent.LowLight -> hintSignals.tryEmit(ScanHint.LOW_LIGHT)
            is ScanEvent.Detected -> {
                active.activeSessionId?.let { currentSessionId.value = it }
                detectionSignals.trySend(Unit)
            }
            // Already counted by the capture it repeats: no second tick (FR-7).
            is ScanEvent.Duplicate -> active.activeSessionId?.let { currentSessionId.value = it }
        }
    }

    /** Closes the pipeline once the scan thread is idle; the packs it read stay open. */
    private fun release(released: ScanPipeline) {
        cleanupScope.launch {
            // endSession() queues behind a frame in flight on the scan thread: a cheap barrier.
            runCatching { released.endSession() }
            released.close()
        }
    }

    private fun observePacks() {
        viewModelScope.launch {
            installedPacks.installed.collect { installed ->
                withContext(Dispatchers.IO) { packCache.refresh(installed) }
                loadedPacks.value = installed
            }
        }
    }

    private fun observeSessionLang() {
        viewModelScope.launch {
            combine(loadedPacks.filterNotNull(), appPrefs.defaultScanLang) { installed, storedLang ->
                installed to effectiveLang(storedLang, installed.keys)
            }.collect { (installed, lang) ->
                sessionLang = lang
                _state.update {
                    it.copy(
                        packsLoaded = true,
                        hasPack = packCache.hasPacks,
                        installedLangs = installed.keys.sorted(),
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeCount() {
        viewModelScope.launch {
            currentSessionId
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf(0)
                    } else {
                        sessions.observeDetections(id).map { rows ->
                            rows.count { it.state !in UNCOUNTED_STATES }
                        }
                    }
                }
                .collect { count -> _state.update { it.copy(count = count) } }
        }
    }

    private fun observeResumeOffer() {
        viewModelScope.launch {
            combine(
                sessions.observeActiveSession(),
                currentSessionId,
                dismissedOfferId,
            ) { active, current, dismissed ->
                active?.id?.takeIf { current == null && it != dismissed }
            }.collect { offer -> _state.update { it.copy(resumeOfferSessionId = offer) } }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeHints() {
        viewModelScope.launch {
            hintSignals
                .transformLatest<ScanHint, ScanHint?> { hint ->
                    emit(hint)
                    delay(HINT_DURATION_MS)
                    emit(null)
                }
                .collect { hint -> _state.update { it.copy(hint = hint) } }
        }
    }

    /**
     * The stored default language, unless its pack has since been removed while another one is
     * installed: a burst must not default to a language that cannot be matched (same rule as the
     * Settings screen).
     */
    private fun effectiveLang(stored: String?, installed: Set<String>): String =
        stored?.takeIf { installed.isEmpty() || it in installed }
            ?: installed.sorted().firstOrNull()
            ?: SettingsViewModel.SUPPORTED_LANGS.first()

    override fun onCleared() {
        accepting = false
        opening?.cancel()
        val released = pipeline
        pipeline = null
        cleanupScope.launch {
            if (released != null) {
                runCatching { released.endSession() }
                released.close()
            }
            packCache.close()
        }
    }

    companion object {

        private const val TAG = "ScanViewModel"

        /** How long a hint stays up after the last frame that asked for it. */
        private const val HINT_DURATION_MS = 1_500L

        /** Rows of the burst that are not a card the user will see in the review (FR-6/FR-7). */
        private val UNCOUNTED_STATES = setOf(ScanDetectionState.DUPLICATE, ScanDetectionState.REJECTED)

        /** Releases native resources after the ViewModel scope is gone. */
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Ad-hoc wiring until the dependency container of T31 owns the database, the packs and the
         * ORT environment.
         */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ScanViewModel::class.java)) {
                        "ScanViewModel.factory asked for $modelClass"
                    }
                    val db = DbProvider.get(appContext)
                    @Suppress("UNCHECKED_CAST")
                    return ScanViewModel(
                        appContext = appContext,
                        sessions = ScanSessionRepository(
                            sessions = db.scanSessionDao(),
                            ownership = db.ownershipDao(),
                            photos = ScanPhotoStore(appContext),
                        ),
                        installedPacks = InstalledPacksStore(appContext),
                        appPrefs = AppPrefs(appContext),
                    ) as T
                }
            }
        }
    }
}

/** Transient message of the scan screen, shown while a frame keeps asking for it. */
enum class ScanHint {
    /** Several cards in the frame: « une seule carte » (EC-5). */
    MULTIPLE_CARDS,

    /** Too dark: « activez la lampe » (EC-7). */
    LOW_LIGHT,

    /** The card is too soft to be read (EC-7). */
    TOO_BLURRY,

    /** A stage of the pipeline failed on a frame; scanning goes on. */
    ANALYSIS_FAILED,
}

/** Everything the scan screen draws. */
data class ScanUiState(
    /** False until the first refresh of the installed packs: « no pack » is not known yet. */
    val packsLoaded: Boolean = false,

    /** At least one pack is installed and readable; without one scanning is blocked (EC-1). */
    val hasPack: Boolean = false,
    val installedLangs: List<String> = emptyList(),

    /** The models are loaded and frames are being analysed. */
    val engineReady: Boolean = false,

    /** The models could not be opened; scanning is unavailable until the screen is re-entered. */
    val engineFailed: Boolean = false,

    /** Cards added to the current burst (FR-6). */
    val count: Int = 0,
    val hint: ScanHint? = null,
    val torchOn: Boolean = false,

    /** An unfinished session to offer for resume, `null` when there is none (EC-13). */
    val resumeOfferSessionId: Long? = null,
)

/**
 * The matcher's view of the installed packs: one open [MatchPack] per language, refreshed when the
 * installed-packs store changes. Local to the scan screen until T31 moves it into the container.
 *
 * Threading: [refresh] runs on a background thread, [current] on the scan thread, once per capture.
 * A pack that leaves the cache is *retired*, not closed, because the capture in flight may still be
 * reading it; [current] closes the retired packs at the start of the next capture, when no one can
 * hold them any more.
 */
internal class MatchPackCache(private val appContext: Context) : Closeable {

    private val lock = Any()
    private var packs: Map<String, MatchPack> = emptyMap()
    private var records: Map<String, InstalledPack> = emptyMap()
    private val retired = ArrayList<MatchPack>()
    private var closed = false

    val hasPacks: Boolean get() = synchronized(lock) { packs.isNotEmpty() }

    /** The packs the matcher may read; the `installedPacks` provider of `ScanPipeline`. */
    fun current(): Map<String, MatchPack> = synchronized(lock) {
        retired.forEach { it.catalog.close() }
        retired.clear()
        packs
    }

    /**
     * Brings the cache in line with [installed]. Packs whose record did not change are kept as they
     * are; the others are re-opened. Blocking: opens SQLite files and loads vector indexes.
     * Calls must not overlap, which the single collector of the ViewModel guarantees.
     */
    fun refresh(installed: Map<String, InstalledPack>) {
        val (previousPacks, previousRecords) = synchronized(lock) {
            if (closed) return
            packs to records
        }
        val next = LinkedHashMap<String, MatchPack>()
        installed.forEach { (lang, record) ->
            // EC-17: a pack built by a newer pipeline than this build reads is never opened.
            if (!record.isSchemaSupported) return@forEach
            val pack = previousPacks[lang]?.takeIf { previousRecords[lang] == record } ?: open(lang)
            if (pack != null) next[lang] = pack
        }
        synchronized(lock) {
            if (closed) {
                next.values.filter { it !in previousPacks.values }.forEach { it.catalog.close() }
                return
            }
            packs = next
            records = installed
            retired += previousPacks.values.filter { it !in next.values }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            (packs.values + retired).forEach { it.catalog.close() }
            packs = emptyMap()
            retired.clear()
        }
    }

    /**
     * Opens the pack of [lang] with its visual index when the vectors are usable. Without the index
     * the matcher takes the OCR-only path (FR-3), so an index problem never hides the pack.
     */
    private fun open(lang: String): MatchPack? {
        val catalog = PackCatalog.open(appContext, lang) ?: return null
        return try {
            MatchPack(catalog, loadIndex(catalog, lang))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Pack $lang is not usable", e)
            catalog.close()
            null
        }
    }

    private fun loadIndex(catalog: PackCatalog, lang: String): EmbeddingIndex? = try {
        val meta = catalog.meta()
        val model = ModelAsset.EMBED_DINOV2S_INT8
        when {
            meta.lang != lang -> {
                Log.w(TAG, "Pack $lang announces language ${meta.lang}: visual index ignored")
                null
            }

            !meta.matchesEmbedModel(model.fileName, model.sha256) -> {
                Log.w(TAG, "Pack $lang was embedded with ${meta.embedModel}: visual index ignored")
                null
            }

            else -> EmbeddingIndex.load(catalog)
        }
    } catch (e: PackFormatException) {
        Log.w(TAG, "Pack $lang has no usable visual index", e)
        null
    } catch (e: SQLiteException) {
        Log.w(TAG, "Pack $lang has no usable visual index", e)
        null
    }

    private companion object {
        const val TAG = "MatchPackCache"
    }
}
