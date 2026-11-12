package org.tcgscan.app.scan

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The scan photos of the review screen (FR-26).
 *
 * One JPEG per detection, written as
 * `<filesDir>/scan/<sessionId>/<detectionId>.jpg` — inside the app's private storage, so no other
 * app and no media scanner ever sees it, and nothing here is exported (FR-27 keeps photos out of
 * the backup too).
 *
 * The photo is the *perspective-corrected* card, i.e. exactly what the matcher looked at: it is
 * what « à vérifier » shows next to the candidates, and the visual fallback for a card with no
 * TCGdex image (FR-26/EC-15).
 *
 * A write never leaves a half-file behind that anything would trust: the path only reaches
 * `scan_detections.photo_path` once [save] has returned it, so a process killed mid-compression
 * leaves an unreferenced file which the session's own [deleteSession] removes with the directory.
 */
class ScanPhotoStore(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Directory holding the photos of one session; it may not exist yet. */
    fun sessionDir(sessionId: Long): File = File(scanRoot(), sessionId.toString())

    /** Path a detection's photo has, whether or not the file is there. */
    fun photoFile(sessionId: Long, detectionId: Long): File =
        File(sessionDir(sessionId), "$detectionId$PHOTO_EXTENSION")

    /**
     * Writes the warped card as a quality-[JPEG_QUALITY] JPEG and returns its absolute path.
     *
     * @param cardRgb the perspective-corrected card, 8-bit RGB (`CV_8UC3`) — the Mat `Warper.warp`
     *   produces; it is not modified.
     * @return the path to store in `scan_detections.photo_path`, or `null` when the photo could not
     *   be written. A missing photo degrades the review row to a text-only card (FR-26), so a full
     *   disk must not cost the user the detection itself.
     */
    fun save(sessionId: Long, detectionId: Long, cardRgb: Mat): String? {
        require(sessionId > 0L) { "Invalid session id $sessionId" }
        require(detectionId > 0L) { "Invalid detection id $detectionId" }
        require(!cardRgb.empty()) { "Cannot store an empty card crop" }
        require(cardRgb.type() == CvType.CV_8UC3) {
            "Expected an 8-bit RGB card crop (CV_8UC3), got ${CvType.typeToString(cardRgb.type())}"
        }

        val target = photoFile(sessionId, detectionId)
        val dir = target.parentFile
        if (dir == null || (!dir.isDirectory && !dir.mkdirs())) {
            Log.w(TAG, "Cannot create the photo directory of session $sessionId")
            return null
        }

        // matToBitmap reads a CV_8UC3 Mat as RGB, which is the order every stage of the scan keeps
        // (see FrameConverter and the D6 contract), so no channel swap is needed here.
        val bitmap = Bitmap.createBitmap(cardRgb.cols(), cardRgb.rows(), Bitmap.Config.ARGB_8888)
        try {
            Utils.matToBitmap(cardRgb, bitmap)
            FileOutputStream(target).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    Log.w(TAG, "JPEG compression of detection $detectionId failed")
                    deleteFailedWrite(target)
                    return null
                }
                output.flush()
                output.fd.sync()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Cannot write ${target.name} of session $sessionId", e)
            deleteFailedWrite(target)
            return null
        } finally {
            bitmap.recycle()
        }
        return target.absolutePath
    }

    /**
     * Deletes one photo by the path stored on its detection row — the rejected and committed rows
     * of the review screen (FR-26).
     *
     * A path outside `<filesDir>/scan/` is refused rather than deleted: this method is fed values
     * read back from the database, and nothing but a scan photo is ever removed by it.
     *
     * @return true when the file is gone, which includes the case where it was already absent.
     */
    fun delete(photoPath: String): Boolean {
        val file = File(photoPath)
        if (!file.path.startsWith(scanRoot().path + File.separator)) {
            Log.w(TAG, "Refusing to delete $photoPath, which is not a scan photo")
            return false
        }
        return !file.exists() || file.delete()
    }

    /** Deletes every photo of one session, directory included (FR-26, `discard`). */
    fun deleteSession(sessionId: Long): Boolean {
        val dir = sessionDir(sessionId)
        return !dir.exists() || dir.deleteRecursively()
    }

    private fun deleteFailedWrite(target: File) {
        if (target.exists() && !target.delete()) {
            Log.w(TAG, "Cannot delete the partial photo ${target.name}")
        }
    }

    private fun scanRoot(): File = File(appContext.filesDir, SCAN_DIR_NAME)

    companion object {

        private const val TAG = "ScanPhotoStore"

        private const val SCAN_DIR_NAME = "scan"

        private const val PHOTO_EXTENSION = ".jpg"

        /** JPEG quality of a scan photo: enough to read the card, small enough for a long burst. */
        const val JPEG_QUALITY = 80
    }
}
