package org.tcgscan.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.Coil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tcgscan.app.AppConfig
import org.tcgscan.app.data.AppPrefs
import org.tcgscan.app.data.db.DbProvider
import org.tcgscan.app.data.db.OwnershipDao
import org.tcgscan.app.data.net.ManifestResult
import org.tcgscan.app.data.net.PackManifest
import org.tcgscan.app.data.pack.InstalledPack
import org.tcgscan.app.data.pack.PackInstallState
import org.tcgscan.app.data.pack.PackRemovalResult
import org.tcgscan.app.data.pack.PackRepository
import org.tcgscan.app.data.pack.PackStatus
import org.tcgscan.app.data.pack.PackUpdateResult
import org.tcgscan.app.ui.image.ImageLoaderProvider

/**
 * State and actions of the Settings screen: the default scan language (FR-5), the pack lifecycle —
 * install, update with the size announced *before* the download, removal with the EC-20 warning
 * (FR-23, FR-24) — and the bounded image cache (FR-25).
 *
 * Every pack decision goes through [PackRepository]; the only reason this ViewModel touches Room is
 * [OwnershipDao.countByLang], which feeds the removal warning (the pack layer never reads Room).
 * No outcome is surfaced as an exception: the UI renders [PackInstallState] and [SettingsMessage].
 */
class SettingsViewModel(
    private val appContext: Context,
    private val prefs: AppPrefs = AppPrefs(appContext),
    private val packs: PackRepository = PackRepository(appContext),
    private val ownership: OwnershipDao = DbProvider.get(appContext).ownershipDao(),
) : ViewModel() {

    private val installStates = MutableStateFlow<Map<String, PackInstallState>>(emptyMap())
    private val screen = MutableStateFlow(ScreenState())
    private val installJobs = mutableMapOf<String, Job>()

    val uiState: StateFlow<SettingsUiState> = combine(
        packs.installed,
        packs.latestManifest,
        installStates,
        screen,
        prefs.defaultScanLang,
    ) { installed, manifest, states, screenState, storedLang ->
        SettingsUiState(
            defaultScanLang = effectiveDefaultScanLang(storedLang, installed.keys),
            packs = langsOf(installed.keys, manifest).map { lang ->
                PackUiState(
                    lang = lang,
                    installed = installed[lang],
                    status = manifest?.let {
                        PackStatus(lang, installed[lang], it.pack(lang), it.version)
                    },
                    install = states[lang] ?: PackInstallState.Idle,
                )
            },
            manifest = screenState.manifest,
            pendingRemoval = screenState.pendingRemoval,
            message = screenState.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SettingsUiState(),
    )

    init {
        refreshManifest()
    }

    /** Reads `manifest.json` so sizes and « mettre à jour » reflect what is published (FR-23). */
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

    /** Language a scan session starts with when the OCR cannot tell (FR-5). */
    fun setDefaultScanLang(lang: String) {
        viewModelScope.launch {
            prefs.setDefaultScanLang(lang)
        }
    }

    /**
     * Downloads and installs — or replaces — the pack of [lang]. Progress, resume, checksum and
     * free-space outcomes all reach the row through [PackUiState.install].
     */
    fun installPack(lang: String) {
        if (installJobs[lang]?.isActive == true) return
        installStates.update { it + (lang to PackInstallState.Preparing(lang)) }
        installJobs[lang] = viewModelScope.launch {
            when (val terminal = packs.install(lang) { state -> publish(lang, state) }) {
                is PackInstallState.Installed -> {
                    installStates.update { it - lang }
                    screen.update { it.copy(message = SettingsMessage.PackInstalled(lang)) }
                }

                is PackInstallState.Failure -> publish(lang, terminal)
            }
        }
    }

    /** Stops a transfer; the staged `.part` file is kept so the next attempt resumes it (EC-2). */
    fun cancelInstall(lang: String) {
        installJobs.remove(lang)?.cancel()
        installStates.update { it - lang }
    }

    /** Clears a failure so the row offers « installer » / « mettre à jour » again. */
    fun dismissInstallFailure(lang: String) {
        installStates.update { it - lang }
    }

    /**
     * First step of a removal: the owned-card count is read here and handed to the pack layer,
     * which answers [PackRemovalResult.ConfirmationRequired] while that count is non-zero — so the
     * warning is shown before anything is deleted (EC-20, FR-24).
     */
    fun removePack(lang: String) {
        viewModelScope.launch {
            apply(packs.remove(lang, ownership.countByLang(lang)))
        }
    }

    /** Second step: the user saw the warning and accepted. Possessions are still kept (EC-20). */
    fun confirmRemoval() {
        val pending = screen.value.pendingRemoval ?: return
        screen.update { it.copy(pendingRemoval = null) }
        viewModelScope.launch {
            apply(packs.remove(pending.lang, pending.ownedCardCount, confirmed = true))
        }
    }

    fun dismissRemoval() {
        screen.update { it.copy(pendingRemoval = null) }
    }

    /** Empties the bounded Coil caches of the card visuals (FR-25). */
    fun clearImageCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ImageLoaderProvider.clear(Coil.imageLoader(appContext))
            }
            screen.update { it.copy(message = SettingsMessage.ImageCacheCleared) }
        }
    }

    /** Called once the transient [SettingsUiState.message] has been displayed. */
    fun messageShown() {
        screen.update { it.copy(message = null) }
    }

    private fun publish(lang: String, state: PackInstallState) {
        installStates.update { it + (lang to state) }
    }

    private fun apply(result: PackRemovalResult) {
        val message = when (result) {
            is PackRemovalResult.ConfirmationRequired -> {
                screen.update {
                    it.copy(pendingRemoval = PendingRemoval(result.lang, result.ownedCardCount))
                }
                return
            }

            is PackRemovalResult.Removed ->
                SettingsMessage.PackRemoved(result.lang, result.ownedCardsKept)

            is PackRemovalResult.NotInstalled -> SettingsMessage.PackNotInstalled(result.lang)
            is PackRemovalResult.StorageError -> SettingsMessage.RemovalFailed(result.message)
        }
        screen.update { it.copy(message = message) }
    }

    /** The languages shown, supported ones first (D10) then anything installed or published. */
    private fun langsOf(installed: Set<String>, manifest: PackManifest?): List<String> {
        val published = manifest?.packs?.keys ?: emptySet()
        val extra = (installed + published) - SUPPORTED_LANGS.toSet()
        return if (extra.isEmpty()) SUPPORTED_LANGS else SUPPORTED_LANGS + extra.sorted()
    }

    /**
     * The stored choice, unless its pack has since been removed while another one is installed:
     * a session must never default to a language that cannot be matched.
     */
    private fun effectiveDefaultScanLang(stored: String?, installed: Set<String>): String =
        stored?.takeIf { installed.isEmpty() || it in installed }
            ?: installed.firstOrNull()
            ?: SUPPORTED_LANGS.first()

    /** State the screen owns, as opposed to what the pack layer and DataStore emit. */
    private data class ScreenState(
        val manifest: ManifestUiState = ManifestUiState.Idle,
        val pendingRemoval: PendingRemoval? = null,
        val message: SettingsMessage? = null,
    )

    companion object {

        /** Pack languages this build supports (D10); `jp` is not a TCGdex code. */
        val SUPPORTED_LANGS: List<String> = listOf("fr", "ja")

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * T31 replaces this with the application-wide `AppContainer`; until then the screen builds
         * its own repository over the process-wide DataStore and Room singletons.
         */
        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            return viewModelFactory { initializer { SettingsViewModel(app) } }
        }
    }
}

/** Everything the Settings screen renders. */
data class SettingsUiState(
    val defaultScanLang: String = SettingsViewModel.SUPPORTED_LANGS.first(),
    val packs: List<PackUiState> = emptyList(),
    val manifest: ManifestUiState = ManifestUiState.Idle,
    val pendingRemoval: PendingRemoval? = null,
    val message: SettingsMessage? = null,
) {

    /** No pack on disk: scanning is impossible and the screen says so (EC-1). */
    val hasInstalledPack: Boolean get() = packs.any { it.installed != null }

    /** Languages the default-scan-language choice may offer: those with a pack. */
    val installedLangs: List<String> get() = packs.filter { it.installed != null }.map { it.lang }

    /** False while `AppConfig.GITHUB_REPO` is the placeholder: nothing can be downloaded (D13). */
    val repoConfigured: Boolean get() = AppConfig.isGithubRepoConfigured()
}

/** One language, from both sides: what is on disk and what the manifest publishes. */
data class PackUiState(
    val lang: String,
    val installed: InstalledPack?,
    val status: PackStatus?,
    val install: PackInstallState,
) {

    /** True while bytes are moving or the archive is being verified or unpacked. */
    val isWorking: Boolean
        get() = install !is PackInstallState.Idle && install !is PackInstallState.Terminal

    /** The last failure of this pack, kept until the user retries or dismisses it (EC-2, EC-3). */
    val failure: PackInstallState.Failure? get() = install as? PackInstallState.Failure

    /** Archive size, shown *before* any transfer starts (FR-23). */
    val downloadBytes: Long? get() = status?.downloadBytes

    /** Free space the install needs: archive plus decompressed pack (EC-3). */
    val requiredBytes: Long? get() = status?.requiredBytes

    /** The published pack needs a newer app, so it is announced but not offered (EC-17). */
    val requiresAppUpdate: Boolean get() = status?.requiresAppUpdate == true

    val canInstall: Boolean get() = status?.canInstall == true && !isWorking

    val canUpdate: Boolean get() = status?.updateAvailable == true && !isWorking

    val canRemove: Boolean get() = installed != null && !isWorking
}

/** How the last `manifest.json` read went; drives the « Réessayer » affordance of EC-1. */
sealed interface ManifestUiState {

    data object Idle : ManifestUiState

    data object Loading : ManifestUiState

    data class Loaded(val version: String) : ManifestUiState

    /** No usable connection: installed packs keep working (EC-1). */
    data object Offline : ManifestUiState

    /** `AppConfig.GITHUB_REPO` still carries its placeholder value (D13). */
    data object RepoNotConfigured : ManifestUiState

    data class Failed(val reason: ManifestResult.Failure) : ManifestUiState
}

/** A removal waiting for the EC-20 confirmation; [ownedCardCount] cards of [lang] are owned. */
data class PendingRemoval(val lang: String, val ownedCardCount: Int)

/** Transient feedback, mapped to a resource string by the screen. */
sealed interface SettingsMessage {

    data class PackInstalled(val lang: String) : SettingsMessage

    /** [ownedCardsKept] possessions survived the removal, as EC-20 requires. */
    data class PackRemoved(val lang: String, val ownedCardsKept: Int) : SettingsMessage

    data class PackNotInstalled(val lang: String) : SettingsMessage

    data class RemovalFailed(val message: String) : SettingsMessage

    data object ImageCacheCleared : SettingsMessage
}
