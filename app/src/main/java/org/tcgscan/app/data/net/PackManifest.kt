package org.tcgscan.app.data.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tcgscan.app.data.pack.PackPaths

/**
 * `manifest.json`, the index of the published language packs.
 *
 * `docs/pack-format.md` §6 is authoritative for this shape; any disagreement is a bug here. Unknown
 * keys are tolerated so the pipeline can add fields without breaking installed builds.
 */
@Serializable
data class PackManifest(
    val schema: Int,
    val version: String,
    val generatedAt: String = "",
    val packs: Map<String, PackEntry> = emptyMap(),
) {

    /** The pack published for [lang] (`fr`, `ja`), or null when the manifest has none. */
    fun pack(lang: String): PackEntry? = packs[lang]

    /**
     * Null when this manifest is usable, otherwise a short reason. Checked once, at the network
     * boundary ([ManifestService]), so the install and UI layers can trust every field (FR-23).
     */
    fun validationError(): String? {
        if (version.isBlank()) return "empty version"
        if (version.length > MAX_VERSION_LENGTH) return "version too long"
        if (packs.isEmpty()) return "no pack published"
        for ((lang, entry) in packs) {
            if (!PackPaths.isValidLang(lang)) return "invalid language code: ${lang.safe()}"
            entry.validationError()?.let { return "pack $lang: $it" }
        }
        return null
    }

    companion object {

        /** Manifest schema this build understands; a greater value is refused as unsupported. */
        const val SCHEMA = 1

        private const val MAX_VERSION_LENGTH = 64

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * @throws kotlinx.serialization.SerializationException when [body] is not JSON at all (the
         *   `no available server` case) or does not carry the required manifest fields.
         */
        fun parse(body: String): PackManifest = json.decodeFromString(serializer(), body)
    }
}

/** One published pack: the `.gz` release asset plus everything needed to install it safely. */
@Serializable
data class PackEntry(
    val file: String,
    val bytes: Long,
    val sha256: String,
    val cardCount: Int,
    val packSchema: Int,
    val installedBytes: Long,
) {

    /** Free space the install needs: the download *and* the decompressed `pack.db` (EC-3). */
    val requiredBytes: Long get() = bytes + installedBytes

    /** Null when the entry is usable, otherwise a short reason; see [PackManifest.validationError]. */
    fun validationError(): String? = when {
        // The file name is appended to the release base to form a URL *and* becomes the staging
        // file name, so it must stay a plain name: no path segment, no scheme, no traversal. The
        // rule is PackPaths', so an entry accepted here cannot fail PackPaths.stagingFile later.
        !PackPaths.isValidArchiveName(file) -> "invalid file name: ${file.safe()}"
        !SHA256_PATTERN.matches(sha256) -> "invalid sha256: ${sha256.safe()}"
        bytes <= 0L -> "non-positive bytes: $bytes"
        installedBytes <= 0L -> "non-positive installedBytes: $installedBytes"
        cardCount <= 0 -> "non-positive cardCount: $cardCount"
        packSchema <= 0 -> "non-positive packSchema: $packSchema"
        else -> null
    }

    private companion object {
        val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
    }
}

/** Untrusted text made safe for a failure message: truncated, control characters (newlines) dropped. */
private fun String.safe(): String = take(40).filterNot { it.isISOControl() }
