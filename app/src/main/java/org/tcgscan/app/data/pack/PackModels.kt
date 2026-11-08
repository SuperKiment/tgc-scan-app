package org.tcgscan.app.data.pack

import org.tcgscan.app.AppConfig

/**
 * Read-only views over the rows of an installed pack (D9, docs/pack-format.md §2).
 *
 * These are plain immutable data holders: no card metadata is ever copied into Room, the pack stays
 * the single source of truth for the catalogue.
 */

/** Values of `cards.has_embed` (docs/pack-format.md §2.2). */
object HasEmbed {

    /** No TCGdex image for this card: OCR-only, never pre-checked (FR-3). */
    const val NONE = 0

    /** Embedding computed from the image of this very card, in this very language. */
    const val OWN_IMAGE = 1

    /** Embedding computed from the EN image of the same id (D15/FR-4): never pre-checked. */
    const val EN_IMAGE_FALLBACK = 2
}

/**
 * The `pack_meta` table, parsed. [raw] keeps every key so a future `pack_schema` can add entries
 * without a code change here.
 */
data class PackMeta(
    val packSchema: Int,
    val lang: String,
    val builtAt: String,
    val cardCount: Int,
    val embedModel: String,
    val embedDim: Int,
    val raw: Map<String, String>,
) {

    /** False for a pack built by a newer pipeline than this build can read (EC-17). */
    val isSchemaSupported: Boolean get() = packSchema <= AppConfig.PACK_SCHEMA

    /**
     * True when [embedModel] is the `<asset-name>@<sha256>` of the model bundled in this build, so
     * the pack vectors are comparable with the on-device ones (docs/pack-format.md §2.1).
     */
    fun matchesEmbedModel(assetName: String, sha256: String): Boolean =
        embedModel.equals("$assetName@$sha256", ignoreCase = true)

    companion object {

        const val KEY_PACK_SCHEMA = "pack_schema"
        const val KEY_LANG = "lang"
        const val KEY_BUILT_AT = "built_at"
        const val KEY_CARD_COUNT = "card_count"
        const val KEY_EMBED_MODEL = "embed_model"
        const val KEY_EMBED_DIM = "embed_dim"

        /**
         * Parses the `pack_meta` key/value rows.
         *
         * @throws PackFormatException when a required key is missing or not a number.
         */
        fun from(values: Map<String, String>): PackMeta = PackMeta(
            packSchema = values.requireInt(KEY_PACK_SCHEMA),
            lang = values.requireString(KEY_LANG),
            builtAt = values.requireString(KEY_BUILT_AT),
            cardCount = values.requireInt(KEY_CARD_COUNT),
            embedModel = values.requireString(KEY_EMBED_MODEL),
            embedDim = values.requireInt(KEY_EMBED_DIM),
            raw = values,
        )

        private fun Map<String, String>.requireString(key: String): String =
            get(key)?.takeIf { it.isNotBlank() }
                ?: throw PackFormatException("pack_meta.$key is missing")

        private fun Map<String, String>.requireInt(key: String): Int =
            requireString(key).trim().toIntOrNull()
                ?: throw PackFormatException("pack_meta.$key is not an integer: ${get(key)}")
    }
}

/** One row of `sets`. [cardCountTotal] is the printed denominator of « 136/201 » (docs §5). */
data class PackSet(
    val id: String,
    val name: String,
    val seriesId: String,
    val cardCountTotal: Int,
    val cardCountOfficial: Int,
    val releaseDate: String?,
    val symbolUrl: String?,
    val logoUrl: String?,
)

/**
 * One row of `cards`. [imageUrl] is the TCGdex image *stem*; the concrete asset is `$imageUrl/low.webp`
 * (docs §4.5) and is never redistributed in the pack.
 */
data class PackCard(
    val cardId: String,
    val localId: String,
    val name: String,
    val nameNorm: String,
    val setId: String,
    val imageUrl: String?,
    val hasEmbed: Int,
) {

    /** True only for [HasEmbed.OWN_IMAGE]: the sole case a scan may be pre-checked (D16/FR-3). */
    val hasOwnVisualReference: Boolean get() = hasEmbed == HasEmbed.OWN_IMAGE

    /** True when the card has a vector in `embeddings`, whatever its provenance. */
    val hasAnyEmbedding: Boolean get() = hasEmbed != HasEmbed.NONE
}

/** What [InstalledPacksStore] remembers about an installed pack: the manifest facts, not the rows. */
data class InstalledPack(
    val lang: String,
    val version: String,
    val cardCount: Int,
    val bytes: Long,
    val packSchema: Int,
) {

    /** False when this pack is newer than the app's reader (EC-17); it must not be used. */
    val isSchemaSupported: Boolean get() = packSchema <= AppConfig.PACK_SCHEMA
}

/** A pack file that exists but does not honour docs/pack-format.md. */
class PackFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
