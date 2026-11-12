package org.tcgscan.app.data.pack

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Filesystem layout of the language packs (D9, docs/pack-format.md §1).
 *
 * ```
 * <filesDir>/packs/.staging/pack-fr.db.gz   downloaded archive (+ its .part), deleted after install
 * <filesDir>/packs/fr/pack.db.tmp           gunzipped pack, still unverified
 * <filesDir>/packs/fr/pack.db               the installed pack, the only file readers ever open
 * ```
 *
 * The staging directory sits under the same `packs/` root on purpose: `PackInstaller` finishes the
 * install with a single `renameTo`, which is only atomic within one filesystem (docs §6).
 */
object PackPaths {

    /** Name of the installed, verified pack database. */
    const val PACK_DB_FILE_NAME = "pack.db"

    /** Name of the gunzipped but not yet verified pack database. */
    const val PACK_DB_TMP_FILE_NAME = "pack.db.tmp"

    private const val PACKS_DIR_NAME = "packs"

    /** Leading dot: no TCGdex language code can collide with the staging directory. */
    private const val STAGING_DIR_NAME = ".staging"

    private val LANG_PATTERN = Regex("[a-z]{2,3}")

    /** Leading character is alphanumeric, which also rejects `.`, `..` and a leading `-`. */
    private val ARCHIVE_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    /**
     * True for a plausible TCGdex language code (`fr`, `ja` — D10).
     *
     * This is *the* rule for both layers: `PackManifest.validationError` rejects a manifest whose
     * keys do not pass it, so a manifest accepted at the network boundary can never make
     * [packDir] throw.
     */
    fun isValidLang(lang: String): Boolean = LANG_PATTERN.matches(lang)

    /**
     * True for a usable `manifest.packs[].file`: a plain file name, no separator, no traversal, no
     * surprise length. Same rule on both sides — see [isValidLang] and [stagingFile].
     */
    fun isValidArchiveName(archiveName: String): Boolean =
        ARCHIVE_NAME_PATTERN.matches(archiveName)

    /** Root of every installed pack. */
    fun packsRoot(context: Context): File = File(context.filesDir, PACKS_DIR_NAME)

    /** Directory holding the pack of [lang]. */
    fun packDir(context: Context, lang: String): File = File(packsRoot(context), requireLang(lang))

    /** The installed pack database of [lang]; opened read-only by [PackCatalog]. */
    fun packDb(context: Context, lang: String): File =
        File(packDir(context, lang), PACK_DB_FILE_NAME)

    /** Gunzip target of an install in progress; renamed onto [packDb] once verified. */
    fun packDbTmp(context: Context, lang: String): File =
        File(packDir(context, lang), PACK_DB_TMP_FILE_NAME)

    /** True when [lang] has a usable pack on disk. */
    fun isInstalled(context: Context, lang: String): Boolean = packDb(context, lang).isFile

    /** Languages with a pack file on disk, sorted; blocking (lists a directory). */
    fun installedLangs(context: Context): List<String> =
        packsRoot(context).list().orEmpty()
            .filter { isValidLang(it) && isInstalled(context, it) }
            .sorted()

    /** Size of the installed pack of [lang], `0` when it is absent. */
    fun installedBytes(context: Context, lang: String): Long =
        packDb(context, lang).let { if (it.isFile) it.length() else 0L }

    /** Directory for downloaded archives, outside any language directory. */
    fun stagingDir(context: Context): File = File(packsRoot(context), STAGING_DIR_NAME)

    /**
     * Staging path for the release asset [archiveName] (`manifest.packs[].file`, docs §6).
     *
     * The name comes from a downloaded manifest, so it is validated here rather than trusted — with
     * [isValidArchiveName], the very check the network boundary applied, so a manifest that passed
     * `PackManifest.validationError` never lands here as an [IllegalArgumentException].
     */
    fun stagingFile(context: Context, archiveName: String): File {
        require(isValidArchiveName(archiveName)) { "Invalid pack archive name: $archiveName" }
        return File(stagingDir(context), archiveName)
    }

    /** Creates [packDir] if needed and returns it. */
    fun ensurePackDir(context: Context, lang: String): File = ensureDir(packDir(context, lang))

    /** Creates [stagingDir] if needed and returns it. */
    fun ensureStagingDir(context: Context): File = ensureDir(stagingDir(context))

    private fun ensureDir(dir: File): File {
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Cannot create pack directory ${dir.absolutePath}")
        }
        return dir
    }

    private fun requireLang(lang: String): String {
        require(isValidLang(lang)) { "Invalid pack language code: $lang" }
        return lang
    }
}
