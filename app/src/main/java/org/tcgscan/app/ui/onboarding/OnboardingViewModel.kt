package org.tcgscan.app.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tcgscan.app.data.AppPrefs
import org.tcgscan.app.data.net.ManifestResult
import org.tcgscan.app.data.pack.PackInstallState
import org.tcgscan.app.data.pack.PackRepository
import org.tcgscan.app.data.pack.PackStatus
import org.tcgscan.app.data.pack.PackUpdateResult
import org.tcgscan.app.di.appContainer
import org.tcgscan.app.ui.settings.ManifestUiState
import org.tcgscan.app.ui.settings.PackUiState
import org.tcgscan.app.ui.settings.SettingsViewModel

/**
 * First-run flow: the fan-project notice, the pack choice with the sizes read from the manifest,
 * then the download. No network leaves the user on an explanatory screen with « Réessayer » and an
 * exit that finishes onboarding without a pack, where scanning stays disabled (EC-1).
 *
 * Every pack decision goes through [PackRepository]; [AppPrefs] records that onboarding is over
 * (finished or skipped) and the default scan language. No outcome is an exception: the screen
 * renders [OnboardingUiState] and the [PackInstallState] of each pack.
 */
class OnboardingViewModel(
    private val prefs: AppPrefs,
    private val packs: PackRepository,
) : ViewModel() {

    private val screen = MutableStateFlow(ScreenState())
    private val installStates = MutableStateFlow<Map<String, PackInstallState>>(emptyMap())
    private var downloadJob: Job? = null

    val uiState: StateFlow<OnboardingUiState> = combine(
        packs.installed,
        packs.latestManifest,
        installStates,
        screen,
    ) { installed, manifest, states, screenState ->
        OnboardingUiState(
            step = screenState.step,
            manifest = screenState.manifest,
            packs = SettingsViewModel.SUPPORTED_LANGS.map { lang ->
                PackUiState(
                    lang = lang,
                    installed = installed[lang],
                    status = manifest?.let {
                        PackStatus(lang, installed[lang], it.pack(lang), it.version)
                    },
                    install = states[lang] ?: PackInstallState.Idle,
                )
            },
            selected = screenState.selected,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = OnboardingUiState(),
    )

    init {
        // Read while the user is still on the notice, so the sizes are ready for the next step.
        refreshManifest()
    }

    /** Reads `manifest.json`; the « Réessayer » of EC-1. */
    fun refreshManifest() {
        if (screen.value.manifest is ManifestUiState.Loading) return
        screen.update { it.copy(manifest = ManifestUiState.Loading) }
        viewModelScope.launch {
            val next = when (val result = packs.checkForUpdates()) {
                is PackUpdateResult.Success -> ManifestUiState.Loaded(result.manifest.version)
                is PackUpdateResult.Offline -> ManifestUiState.Offline
                is PackUpdateResult.Failed -> when (result.reason) {
                    ManifestResult.RepoNotConfigured -> ManifestUiState.RepoNotConfigured
                    else -> ManifestUiState.Failed(result.reason)
                }
            }
            screen.update { it.copy(manifest = next) }
        }
    }

    /** Leaves the notice for the pack choice. */
    fun acceptNotice() {
        screen.update { it.copy(step = OnboardingStep.PackChoice) }
    }

    fun toggleLang(lang: String) {
        screen.update { it.copy(selected = if (lang in it.selected) it.selected - lang else it.selected + lang) }
    }

    /** Step back; refused while bytes are moving, so a transfer is never abandoned silently. */
    fun back() {
        if (downloadJob?.isActive == true) return
        screen.update {
            when (it.step) {
                OnboardingStep.Download -> it.copy(step = OnboardingStep.PackChoice)
                OnboardingStep.PackChoice -> it.copy(step = OnboardingStep.Notice)
                OnboardingStep.Notice -> it
            }
        }
    }

    /**
     * Installs the chosen packs one after the other. Also the « Réessayer » of a failed download:
     * packs already installed are skipped and the staged `.part` of a failed one is resumed (EC-2).
     */
    fun startDownload() {
        if (downloadJob?.isActive == true) return
        val chosen = SettingsViewModel.SUPPORTED_LANGS.filter { it in screen.value.selected }
        if (chosen.isEmpty()) return
        screen.update { it.copy(step = OnboardingStep.Download) }
        installStates.update { it - chosen.toSet() }
        downloadJob = viewModelScope.launch {
            val installed = packs.installed.first()
            for (lang in chosen) {
                if (lang in installed) continue
                installStates.update { it + (lang to PackInstallState.Preparing(lang)) }
                when (val terminal = packs.install(lang) { state -> publish(lang, state) }) {
                    is PackInstallState.Installed -> installStates.update { it - lang }
                    is PackInstallState.Failure -> publish(lang, terminal)
                }
            }
        }
    }

    /**
     * Ends onboarding for good, with or without a pack. The scan language defaults to the first
     * installed pack so a session never starts on a language that cannot be matched (FR-5).
     */
    fun finish(onFinished: () -> Unit) {
        viewModelScope.launch {
            val installed = packs.installed.first()
            SettingsViewModel.SUPPORTED_LANGS.firstOrNull { it in installed }
                ?.let { prefs.setDefaultScanLang(it) }
            prefs.setOnboardingDone()
            onFinished()
        }
    }

    private fun publish(lang: String, state: PackInstallState) {
        installStates.update { it + (lang to state) }
    }

    private data class ScreenState(
        val step: OnboardingStep = OnboardingStep.Notice,
        val manifest: ManifestUiState = ManifestUiState.Idle,
        val selected: Set<String> = emptySet(),
    )

    companion object {

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(context: Context): ViewModelProvider.Factory {
            val container = context.appContainer
            return viewModelFactory {
                initializer {
                    OnboardingViewModel(prefs = container.appPrefs, packs = container.packRepository)
                }
            }
        }
    }
}

/** The three screens of onboarding, in order. */
enum class OnboardingStep { Notice, PackChoice, Download }

/** Everything the onboarding screens render. */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Notice,
    val manifest: ManifestUiState = ManifestUiState.Idle,
    val packs: List<PackUiState> = emptyList(),
    val selected: Set<String> = emptySet(),
) {

    /** At least one pack must be chosen before the download can start. */
    val canStartDownload: Boolean get() = selected.isNotEmpty()

    /** Some pack is on disk, so scanning will work once onboarding ends. */
    val hasInstalledPack: Boolean get() = packs.any { it.installed != null }

    /** The packs of the current download, in display order. */
    val chosenPacks: List<PackUiState> get() = packs.filter { it.lang in selected }

    val isDownloading: Boolean get() = chosenPacks.any { it.isWorking }

    val downloadFailed: Boolean get() = !isDownloading && chosenPacks.any { it.failure != null }

    /** Every chosen pack is installed and nothing is moving: the « terminé » state. */
    val downloadDone: Boolean
        get() = canStartDownload && chosenPacks.all { it.installed != null && !it.isWorking }
}
