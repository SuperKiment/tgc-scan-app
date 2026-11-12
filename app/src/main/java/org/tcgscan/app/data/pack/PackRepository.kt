package org.tcgscan.app.data.pack

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tcgscan.app.AppConfig
import org.tcgscan.app.data.net.ManifestResult
import org.tcgscan.app.data.net.ManifestService
import org.tcgscan.app.data.net.PackEntry
import org.tcgscan.app.data.net.PackManifest

/**
 * The single entry point of the pack lifecycle for the UI: what is installed, what is published,
 * and the install / update / removal actions (FR-23, FR-24).
 *
 * It holds the last manifest read from the network so a screen can show a pack's size *before* any
 * download starts, and so `updateAvailable` answers without a second request. The manifest is only
 * cached in memory: a pack decision is never taken on a stale file.
 *
 * One instance per process (wired in `di/AppContainer`), built with the application context.
 */
class PackRepository(
    context: Context,
    private val manifestService: ManifestService = ManifestService(),
    private val installedPacks: InstalledPacksStore = InstalledPacksStore(context.applicationContext),
    private val installer: PackInstaller = PackInstaller(context.applicationContext, installedPacks),
) {

    /** Every installed pack, keyed by language code; emits on each install, update and removal. */
    val installed: Flow<Map<String, InstalledPack>> = installedPacks.installed

    private val _latestManifest = MutableStateFlow<PackManifest?>(null)

    /** The last successfully read manifest, or `null` while the app has never been online. */
    val latestManifest: StateFlow<PackManifest?> = _latestManifest.asStateFlow()

    /**
     * Reads `manifest.json` and compares it with what is installed (FR-23).
     *
     * Never throws: no connection gives [PackUpdateResult.Offline], which is the « Réessayer »
     * case of EC-1, and every other cause comes back as [PackUpdateResult.Failed].
     */
    suspend fun checkForUpdates(): PackUpdateResult = when (val result = manifestService.fetch()) {
        is ManifestResult.Success -> {
            _latestManifest.value = result.manifest
            PackUpdateResult.Success(result.manifest, statuses(result.manifest))
        }

        is ManifestResult.Offline -> PackUpdateResult.Offline(result.message)
        is ManifestResult.Failure -> PackUpdateResult.Failed(result)
    }

    /**
     * Installed-versus-published state of every language, from the cached manifest; `null` until
     * [checkForUpdates] has succeeded once.
     */
    suspend fun status(lang: String): PackStatus? =
        _latestManifest.value?.let { manifest ->
            PackStatus(lang, installedPacks.current()[lang], manifest.pack(lang), manifest.version)
        }

    /**
     * True when a newer pack than the installed one is published for [lang] and this build can read
     * it; false while nothing is installed (that is an install, not an update), while no manifest
     * has been read, and for a pack that needs a newer app (EC-17).
     */
    suspend fun updateAvailable(lang: String): Boolean = status(lang)?.updateAvailable == true

    /**
     * Installs — or replaces — the pack of [lang], using the cached manifest and fetching it first
     * when it is not there yet.
     *
     * @param onProgress see `PackInstaller.install`; the terminal state is returned.
     */
    suspend fun install(
        lang: String,
        onProgress: (PackInstallState) -> Unit = {},
    ): PackInstallState.Terminal {
        val manifest = _latestManifest.value ?: when (val refreshed = checkForUpdates()) {
            is PackUpdateResult.Success -> refreshed.manifest
            is PackUpdateResult.Offline ->
                return PackInstallState.ManifestFailure(lang, ManifestResult.Offline(refreshed.message))

            is PackUpdateResult.Failed -> return PackInstallState.ManifestFailure(lang, refreshed.reason)
        }
        val entry = manifest.pack(lang) ?: return PackInstallState.NotPublished(lang)
        if (entry.packSchema > AppConfig.PACK_SCHEMA) {
            // Refused before a single byte is transferred; the installed pack stays usable (EC-17).
            return PackInstallState.UnsupportedPackSchema(lang, entry.packSchema)
        }
        return installer.install(lang, entry, manifest.version, onProgress)
    }

    /**
     * Removes the pack of [lang] (FR-24). [ownedCardCount] comes from the collection — this layer
     * never reads Room — and a non-zero count yields [PackRemovalResult.ConfirmationRequired] until
     * [confirmed] is set, so the user is warned before anything is deleted; the possessions
     * themselves are always kept (EC-20).
     */
    suspend fun remove(
        lang: String,
        ownedCardCount: Int = 0,
        confirmed: Boolean = false,
    ): PackRemovalResult = installer.remove(lang, ownedCardCount, confirmed)

    private suspend fun statuses(manifest: PackManifest): List<PackStatus> {
        val current = installedPacks.current()
        val langs = LinkedHashSet(manifest.packs.keys).apply { addAll(current.keys) }
        return langs.sorted().map { lang ->
            PackStatus(lang, current[lang], manifest.pack(lang), manifest.version)
        }
    }
}

/** Typed outcome of [PackRepository.checkForUpdates]. */
sealed interface PackUpdateResult {

    /** The manifest was read; [packs] holds one entry per published *or* installed language. */
    data class Success(val manifest: PackManifest, val packs: List<PackStatus>) : PackUpdateResult

    /** No usable connection: the app keeps working with the packs already installed (EC-1). */
    data class Offline(val message: String) : PackUpdateResult

    /** Anything else, with the typed cause the manifest layer reported. */
    data class Failed(val reason: ManifestResult.Failure) : PackUpdateResult
}

/**
 * One language seen from both sides: [installed] is what is on disk, [available] what the manifest
 * of version [availableVersion] publishes. Every « peut-on installer / mettre à jour ? » decision
 * of the UI is derived here, so the rule lives in exactly one place.
 */
data class PackStatus(
    val lang: String,
    val installed: InstalledPack?,
    val available: PackEntry?,
    val availableVersion: String,
) {

    /** Size of the archive to download, shown before the transfer starts (FR-23). */
    val downloadBytes: Long? get() = available?.bytes

    /** Free space the install needs: archive plus decompressed pack (EC-3). */
    val requiredBytes: Long? get() = available?.requiredBytes

    /** True when the published pack needs a newer app, so it must not be offered (EC-17). */
    val requiresAppUpdate: Boolean
        get() = available != null && available.packSchema > AppConfig.PACK_SCHEMA

    /** True for a language with no pack on disk and a usable one published. */
    val canInstall: Boolean get() = installed == null && available != null && !requiresAppUpdate

    /** True when the published pack is a different build than the installed one. */
    val updateAvailable: Boolean
        get() = installed != null &&
            available != null &&
            !requiresAppUpdate &&
            installed.version != availableVersion
}
