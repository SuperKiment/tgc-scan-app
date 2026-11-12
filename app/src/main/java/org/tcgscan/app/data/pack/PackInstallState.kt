package org.tcgscan.app.data.pack

import org.tcgscan.app.AppConfig
import org.tcgscan.app.data.net.ManifestResult

/**
 * Every state a pack install goes through, and every way it can end (docs/pack-format.md §6).
 *
 * `PackInstaller` reports the intermediate states through its `onProgress` callback and returns a
 * [Terminal] one; the Settings and onboarding screens render both from this single type, so no
 * exception from the install path ever reaches the UI.
 *
 * A [Failure] always means the previously installed pack is still in place and usable: the install
 * only becomes visible through the final `renameTo` (EC-2, EC-3, EC-17).
 */
sealed interface PackInstallState {

    /** No install in flight. */
    data object Idle : PackInstallState

    /** Staging preparation and the free-space check, before the first byte is requested (EC-3). */
    data class Preparing(val lang: String) : PackInstallState

    /** Bytes are landing in the staging `.part` file; resumed from [bytesWritten] if interrupted. */
    data class Downloading(
        val lang: String,
        val bytesWritten: Long,
        val totalBytes: Long,
    ) : PackInstallState {

        /** Progress in `0f..1f`; `0f` while the total is unknown. */
        val fraction: Float
            get() = if (totalBytes > 0L) (bytesWritten.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    /** The archive is complete and its SHA-256 is being computed (FR-23). */
    data class Verifying(val lang: String) : PackInstallState

    /** Gunzip, `pack_schema` check and the atomic `renameTo`. */
    data class Installing(val lang: String) : PackInstallState

    /** A state no other state follows: either [Installed] or a [Failure]. */
    sealed interface Terminal : PackInstallState

    /** The pack of [InstalledPack.lang] is in place and recorded in `InstalledPacksStore`. */
    data class Installed(val pack: InstalledPack) : Terminal

    /** Nothing was installed and the old pack — if any — is untouched. */
    sealed interface Failure : Terminal {
        val lang: String
    }

    /** `AppConfig.GITHUB_REPO` is still the placeholder (D13): no pack can be downloaded. */
    data class RepoNotConfigured(override val lang: String) : Failure

    /** The manifest itself could not be read; [reason] carries the typed cause (EC-1). */
    data class ManifestFailure(
        override val lang: String,
        val reason: ManifestResult.Failure,
    ) : Failure

    /** The manifest is readable but publishes no pack for this language. */
    data class NotPublished(override val lang: String) : Failure

    /** A manifest entry this build refuses to act on; see `PackEntry.validationError`. */
    data class MalformedEntry(override val lang: String, val message: String) : Failure

    /** Connection lost during the download; the `.part` file is kept for the next resume (EC-2). */
    data class Offline(override val lang: String, val message: String) : Failure

    /** Unexpected status while downloading the archive (404 before the release exists, 403, 5xx…). */
    data class HttpError(override val lang: String, val code: Int) : Failure

    /**
     * Not enough room for `bytes + installedBytes`; nothing was downloaded (EC-3). Both sizes are
     * carried so the screen can show what is required *before* any transfer starts (FR-23).
     */
    data class NotEnoughSpace(
        override val lang: String,
        val requiredBytes: Long,
        val availableBytes: Long,
    ) : Failure

    /** The download does not have the size the manifest announces; it was discarded. */
    data class SizeMismatch(
        override val lang: String,
        val expectedBytes: Long,
        val actualBytes: Long,
    ) : Failure

    /** SHA-256 mismatch: the archive was deleted and a new attempt may be offered (EC-2). */
    data class ChecksumMismatch(
        override val lang: String,
        val expectedSha256: String,
        val actualSha256: String,
    ) : Failure

    /**
     * The downloaded pack is built for a newer app (EC-17): it is refused, the old pack is kept and
     * the user is invited to update the app.
     */
    data class UnsupportedPackSchema(
        override val lang: String,
        val packSchema: Int,
        val supportedPackSchema: Int = AppConfig.PACK_SCHEMA,
    ) : Failure

    /** Verified bytes that are not a readable pack: a pipeline bug, not a transfer problem. */
    data class CorruptArchive(override val lang: String, val message: String) : Failure

    /** The local file system refused an operation. */
    data class StorageError(override val lang: String, val message: String) : Failure
}

/**
 * Outcome of `PackInstaller.remove` / `PackRepository.remove`.
 *
 * Removal never deletes a possession: owned cards of the language survive the pack (EC-20, FR-24).
 * The count of those possessions is supplied by the caller — the pack layer never reads Room — and
 * a non-zero count turns the first call into [ConfirmationRequired] so the warning is shown
 * *before* anything is deleted.
 */
sealed interface PackRemovalResult {

    /** Cards of this language are owned; call again with `confirmed = true` to proceed (EC-20). */
    data class ConfirmationRequired(val lang: String, val ownedCardCount: Int) : PackRemovalResult

    /** The pack directory is gone and the record dropped; [ownedCardsKept] possessions remain. */
    data class Removed(val lang: String, val ownedCardsKept: Int) : PackRemovalResult

    /** Nothing to remove for this language. */
    data class NotInstalled(val lang: String) : PackRemovalResult

    /** The pack files could not be deleted; the record is left in place. */
    data class StorageError(val lang: String, val message: String) : PackRemovalResult
}
