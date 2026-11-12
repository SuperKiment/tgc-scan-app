package org.tcgscan.app.scan

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * The five ML assets shipped inside the APK (D7/D8, `docs/models.md`, `tools/models.lock`).
 *
 * They are **never** downloaded at runtime: scanning works offline right after install and the
 * model and code versions can never drift apart. `tools/fetch_models.sh` puts them in
 * `app/src/main/assets/models/` and verifies [sha256] at build time, so there is no reason to hash
 * 48 MB again on every launch — [bytes] is the runtime guard (see [ModelAssets]).
 *
 * @property fileName name under `assets/models/`, also the name used in `<filesDir>/models/`.
 * @property bytes exact size of the asset, from `tools/models.lock`.
 * @property sha256 sha256 of the asset, from `tools/models.lock`. Not recomputed on the device; it
 *   is the value a pack's `pack_meta.embed_model` is compared against (`docs/pack-format.md` §2.1,
 *   `PackMeta.matchesEmbedModel`).
 */
enum class ModelAsset(
    val fileName: String,
    val bytes: Long,
    val sha256: String,
) {

    /** Visual embedding model, int8 export of `facebook/dinov2-small` (D6). */
    EMBED_DINOV2S_INT8(
        fileName = "embed_dinov2s_int8.onnx",
        bytes = 24_446_700L,
        sha256 = "c179f8f7f592449c4c1bca4cd124a7538021428c5ffb89afde9503935b197efb",
    ),

    /** PP-OCRv4 text detection. */
    OCR_DET(
        fileName = "ocr_det.onnx",
        bytes = 4_745_517L,
        sha256 = "d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9",
    ),

    /** PP-OCRv3 latin recognition (185-character dictionary, French accents included). */
    OCR_REC_LATIN(
        fileName = "ocr_rec_latin.onnx",
        bytes = 8_993_434L,
        sha256 = "2bdd80b2a52fb2bb08b7407867eeb04fc8059b828dd46e500a9d48ba76ace9d8",
    ),

    /** PP-OCRv3 japanese recognition (4399-character dictionary). */
    OCR_REC_JAPAN(
        fileName = "ocr_rec_japan.onnx",
        bytes = 10_089_078L,
        sha256 = "2e397c27461751befc82eca78c9b19e87322ebee34e7316468b21856f9977d64",
    ),

    /** Character dictionary of [OCR_REC_LATIN]; 185 lines, index 0 is the CTC blank. */
    DICT_LATIN(
        fileName = "dict_latin.txt",
        bytes = 468L,
        sha256 = "8e6d4e3629788c35c31f7e530287d6147b549bb7a265bd6708bb281134429e2c",
    ),

    /** Character dictionary of [OCR_REC_JAPAN]; 4399 lines, index 0 is the CTC blank. */
    DICT_JAPAN(
        fileName = "dict_japan.txt",
        bytes = 17_332L,
        sha256 = "1dcfcb41eec90576a945b3084f22ade11ced506e24f14879245b071698f308e8",
    ),
}

/**
 * Materialises the APK's model assets as real files under `<filesDir>/models/`.
 *
 * ONNX Runtime needs a **file path** for models of this size (an `AssetManager` stream would have
 * to be fully buffered in the Java heap first), so every asset is copied once, on first use.
 *
 * The guard is deliberately cheap — name plus [ModelAsset.bytes] — because the sha256 of each asset
 * was already verified by `tools/fetch_models.sh` at build time and hashing 48 MB on every launch
 * would be wasted battery. A truncated copy (killed process, full disk) therefore still re-copies,
 * and the copy itself only lands on its final name through a `renameTo`, so a partial file is never
 * handed to ORT.
 */
object ModelAssets {

    /** Directory of the assets inside the APK. */
    private const val ASSET_DIR = "models"

    /** Directory of the extracted copies, under `filesDir`. */
    private const val INSTALL_DIR = "models"

    private const val TMP_SUFFIX = ".part"

    private const val COPY_BUFFER_BYTES = 128 * 1024

    /** `<filesDir>/models/`, the directory holding the extracted assets. */
    fun installDir(context: Context): File = File(context.filesDir, INSTALL_DIR)

    /** Where [asset] lives once extracted, whether or not it has been extracted yet. */
    fun installedFile(context: Context, asset: ModelAsset): File =
        File(installDir(context), asset.fileName)

    /**
     * Returns the extracted [asset], copying it out of the APK when it is missing or truncated.
     *
     * Synchronised because two inference engines ([Embedder] and the OCR engine of T10) may warm up
     * on different threads and would otherwise race on the same temporary file.
     *
     * @throws IOException when the asset cannot be read from the APK or written to `filesDir`;
     *   failing loudly is intentional — recognition is never silently stubbed out (D7).
     */
    @Synchronized
    fun file(context: Context, asset: ModelAsset): File {
        val target = installedFile(context, asset)
        if (target.isFile && target.length() == asset.bytes) return target

        val dir = installDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Cannot create model directory ${dir.absolutePath}")
        }

        val tmp = File(dir, asset.fileName + TMP_SUFFIX)
        if (tmp.exists() && !tmp.delete()) {
            throw IOException("Cannot delete stale model copy ${tmp.absolutePath}")
        }

        val copied = try {
            context.assets.open("$ASSET_DIR/${asset.fileName}").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
            }
        } catch (e: IOException) {
            tmp.delete()
            throw IOException("Cannot extract model asset $ASSET_DIR/${asset.fileName}", e)
        }

        if (copied != asset.bytes) {
            tmp.delete()
            throw IOException(
                "Model asset ${asset.fileName} is $copied B, expected ${asset.bytes} B; " +
                    "run `bash tools/fetch_models.sh` and rebuild",
            )
        }
        if (target.exists() && !target.delete()) {
            tmp.delete()
            throw IOException("Cannot replace model file ${target.absolutePath}")
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Cannot move ${tmp.absolutePath} onto ${target.absolutePath}")
        }
        return target
    }

    /**
     * Extracts every asset, for callers that prefer to pay the ~48 MB copy up front (first run)
     * rather than on the first scan.
     *
     * @throws IOException on the first asset that cannot be extracted.
     */
    fun installAll(context: Context) {
        ModelAsset.entries.forEach { file(context, it) }
    }
}
