package org.tcgscan.app.data.pack

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.tcgscan.app.AppConfig
import org.tcgscan.app.data.net.DownloadResult
import org.tcgscan.app.data.net.ManifestService
import org.tcgscan.app.data.net.PackEntry
import org.tcgscan.app.data.net.RangeDownloader
import org.tcgscan.app.data.net.describe

/**
 * Installs, replaces and removes the language packs on disk (docs/pack-format.md §6).
 *
 * The sequence is fixed and every step can only fail *before* the pack becomes visible:
 *
 * 1. free-space check for `bytes + installedBytes` (EC-3);
 * 2. resumable download into `<filesDir>/packs/.staging/<file>` (EC-2);
 * 3. SHA-256 verification of the archive — a mismatch deletes it and keeps the old pack (EC-2);
 * 4. gunzip into `packs/<lang>/pack.db.tmp`;
 * 5. read-only open of that file, refusing `pack_meta.pack_schema > AppConfig.PACK_SCHEMA` (EC-17);
 * 6. one `renameTo` onto `packs/<lang>/pack.db` — the **only** write to that path, so there is
 *    never a partial install;
 * 7. the [InstalledPacksStore] record, written last.
 *
 * Nothing here touches the Room database: a pack is data about cards, never about possessions
 * (FR-23, FR-24, EC-20).
 */
class PackInstaller(
    context: Context,
    private val installedPacks: InstalledPacksStore,
    private val downloader: RangeDownloader = RangeDownloader(),
) {

    /** Application context: an install outlives any screen that started it. */
    private val context = context.applicationContext

    /**
     * Downloads and installs the pack described by [entry] for [lang].
     *
     * @param version `manifest.version`, recorded as the installed version so a later manifest can
     *   be compared against it (FR-23); it is not part of [entry].
     * @param onProgress called on [Dispatchers.IO] with [PackInstallState.Preparing],
     *   [PackInstallState.Downloading], [PackInstallState.Verifying] and
     *   [PackInstallState.Installing]; the terminal state is returned, not emitted.
     * @return [PackInstallState.Installed] or a typed [PackInstallState.Failure]. Cancellation
     *   propagates as a `CancellationException` and leaves the download resumable (EC-2).
     */
    suspend fun install(
        lang: String,
        entry: PackEntry,
        version: String,
        onProgress: (PackInstallState) -> Unit = {},
    ): PackInstallState.Terminal = withContext(Dispatchers.IO) {
        require(PackPaths.isValidLang(lang)) { "Invalid pack language code: $lang" }
        require(version.isNotBlank()) { "Pack version must not be blank" }

        entry.validationError()?.let { return@withContext PackInstallState.MalformedEntry(lang, it) }
        if (!AppConfig.isGithubRepoConfigured()) {
            return@withContext PackInstallState.RepoNotConfigured(lang)
        }
        onProgress(PackInstallState.Preparing(lang))

        val staging = try {
            PackPaths.ensureStagingDir(context)
        } catch (e: IOException) {
            return@withContext PackInstallState.StorageError(lang, e.describe())
        }
        val archive = try {
            PackPaths.stagingFile(context, entry.file)
        } catch (e: IllegalArgumentException) {
            return@withContext PackInstallState.MalformedEntry(lang, e.describe())
        }
        // The decompressed pack and the archive coexist until the install ends, and so do the old
        // and the new pack: the required room is the sum the manifest announces (EC-3).
        if (!downloader.freeSpaceOk(archive, entry.requiredBytes)) {
            return@withContext PackInstallState.NotEnoughSpace(
                lang = lang,
                requiredBytes = entry.requiredBytes,
                availableBytes = staging.usableSpace,
            )
        }

        val download = downloader.download(
            url = ManifestService.packUrl(entry),
            target = archive,
            expectedBytes = entry.bytes,
        ) { written, total -> onProgress(PackInstallState.Downloading(lang, written, total)) }
        if (download is DownloadResult.Failure) {
            return@withContext download.toInstallFailure(lang)
        }

        onProgress(PackInstallState.Verifying(lang))
        val actualSha256 = sha256Of(archive)
            ?: return@withContext PackInstallState.StorageError(lang, "cannot read ${archive.name}")
        if (!actualSha256.equals(entry.sha256, ignoreCase = true)) {
            // Corrupt or substituted bytes: drop them entirely so the retry starts from zero, and
            // leave the pack currently installed exactly as it was (EC-2).
            if (!archive.delete()) Log.w(TAG, "Cannot delete ${archive.name} after SHA-256 mismatch")
            return@withContext PackInstallState.ChecksumMismatch(lang, entry.sha256, actualSha256)
        }

        onProgress(PackInstallState.Installing(lang))
        val tmp = try {
            PackPaths.ensurePackDir(context, lang)
            PackPaths.packDbTmp(context, lang)
        } catch (e: IOException) {
            return@withContext PackInstallState.StorageError(lang, e.describe())
        }
        decompress(lang, archive, tmp, entry.installedBytes)?.let { return@withContext it }
        inspect(lang, tmp)?.let {
            if (!tmp.delete()) Log.w(TAG, "Cannot delete ${tmp.name} of a refused pack")
            return@withContext it
        }

        val target = PackPaths.packDb(context, lang)
        // Single atomic step: readers either see the previous pack or the new one, never a mix.
        if (!tmp.renameTo(target)) {
            if (!tmp.delete()) Log.w(TAG, "Cannot delete ${tmp.name} after a failed rename")
            return@withContext PackInstallState.StorageError(
                lang,
                "cannot move ${tmp.name} to ${target.name}",
            )
        }
        if (!archive.delete()) Log.w(TAG, "Cannot delete staged archive ${archive.name}")

        val pack = InstalledPack(
            lang = lang,
            version = version,
            cardCount = entry.cardCount,
            bytes = entry.bytes,
            packSchema = entry.packSchema,
        )
        installedPacks.record(pack)
        PackInstallState.Installed(pack)
    }

    /**
     * Deletes the pack of [lang] and its record.
     *
     * Possessions are never touched: [ownedCardCount] — counted by the caller, which owns the Room
     * database — only drives the warning, and a non-zero count requires [confirmed] (EC-20).
     */
    suspend fun remove(
        lang: String,
        ownedCardCount: Int = 0,
        confirmed: Boolean = false,
    ): PackRemovalResult = withContext(Dispatchers.IO) {
        require(PackPaths.isValidLang(lang)) { "Invalid pack language code: $lang" }
        require(ownedCardCount >= 0) { "ownedCardCount must be >= 0, was $ownedCardCount" }

        val dir = PackPaths.packDir(context, lang)
        val recorded = installedPacks.current().containsKey(lang)
        if (!dir.exists() && !recorded) return@withContext PackRemovalResult.NotInstalled(lang)
        if (ownedCardCount > 0 && !confirmed) {
            return@withContext PackRemovalResult.ConfirmationRequired(lang, ownedCardCount)
        }
        if (dir.exists() && !dir.deleteRecursively()) {
            // The record stays: it still describes files that are (at least partly) there.
            return@withContext PackRemovalResult.StorageError(lang, "cannot delete ${dir.name}")
        }
        installedPacks.forget(lang)
        PackRemovalResult.Removed(lang, ownedCardsKept = ownedCardCount)
    }

    /** Gunzips [archive] into [tmp]; returns `null` on success or the typed failure. */
    private suspend fun decompress(
        lang: String,
        archive: File,
        tmp: File,
        expectedBytes: Long,
    ): PackInstallState.Failure? {
        // A leftover `.tmp` belongs to an install that died before its rename: it is never resumed.
        if (tmp.exists() && !tmp.delete()) {
            return PackInstallState.StorageError(lang, "cannot delete stale ${tmp.name}")
        }
        // Bound against gzip bombs: the manifest size plus a small tolerance, never above a hard ceiling.
        val limit = minOf(expectedBytes + DECOMPRESS_TOLERANCE_BYTES, DECOMPRESS_HARD_CEILING_BYTES)
        var written = 0L
        try {
            GZIPInputStream(archive.inputStream().buffered(BUFFER_BYTES)).use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > limit) {
                            output.close()
                            tmp.delete()
                            return PackInstallState.CorruptArchive(
                                lang,
                                "decompressed size exceeds the announced $expectedBytes B",
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
        } catch (e: ZipException) {
            tmp.delete()
            return PackInstallState.CorruptArchive(lang, e.describe())
        } catch (e: EOFException) {
            tmp.delete()
            return PackInstallState.CorruptArchive(lang, "truncated archive: ${e.describe()}")
        } catch (e: IOException) {
            tmp.delete()
            return PackInstallState.StorageError(lang, e.describe())
        }
        // Within tolerance: the archive's SHA-256 already matched, so only log a size difference.
        if (written != expectedBytes) {
            Log.w(TAG, "Pack $lang decompressed to $written B, manifest announced $expectedBytes B")
        }
        return null
    }

    /**
     * Opens the gunzipped pack read-only and returns `null` when this build may install it, or the
     * typed refusal — a newer `pack_schema` (EC-17) or a file that is not the expected pack.
     */
    private fun inspect(lang: String, tmp: File): PackInstallState.Failure? = try {
        PackCatalog.open(tmp, lang).use { catalog ->
            val meta = catalog.meta()
            when {
                meta.packSchema > AppConfig.PACK_SCHEMA ->
                    PackInstallState.UnsupportedPackSchema(lang, meta.packSchema)

                meta.lang != lang ->
                    PackInstallState.CorruptArchive(lang, "pack declares lang ${meta.lang}")

                else -> null
            }
        }
    } catch (e: PackFormatException) {
        PackInstallState.CorruptArchive(lang, e.describe())
    } catch (e: SQLiteException) {
        PackInstallState.CorruptArchive(lang, e.describe())
    }

    /** Lowercase hex SHA-256 of [file], or `null` when it cannot be read. */
    private suspend fun sha256Of(file: File): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Cannot hash ${file.name}: ${e.describe()}")
            return null
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }

    private fun DownloadResult.Failure.toInstallFailure(lang: String): PackInstallState.Failure =
        when (this) {
            is DownloadResult.Offline -> PackInstallState.Offline(lang, message)
            is DownloadResult.HttpError -> PackInstallState.HttpError(lang, code)
            is DownloadResult.NotEnoughSpace ->
                PackInstallState.NotEnoughSpace(lang, requiredBytes, availableBytes)

            is DownloadResult.SizeMismatch ->
                PackInstallState.SizeMismatch(lang, expectedBytes, actualBytes)

            is DownloadResult.StorageError -> PackInstallState.StorageError(lang, message)
        }

    private companion object {

        const val TAG = "PackInstaller"

        const val BUFFER_BYTES = 64 * 1024

        /** Slack allowed above the manifest's `installedBytes` before the archive is declared a bomb. */
        const val DECOMPRESS_TOLERANCE_BYTES = 1L * 1024 * 1024

        /** Absolute ceiling on a decompressed pack, whatever the manifest claims. */
        const val DECOMPRESS_HARD_CEILING_BYTES = 1L * 1024 * 1024 * 1024

        val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }
}
