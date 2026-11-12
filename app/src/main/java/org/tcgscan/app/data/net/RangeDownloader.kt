package org.tcgscan.app.data.net

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

/** Download progress: bytes already on disk and the announced total. */
typealias DownloadProgress = (bytesWritten: Long, totalBytes: Long) -> Unit

/**
 * Resumable HTTP download of a single release asset.
 *
 * Bytes always land in `<target>.part`, which survives an interrupted download (or the process
 * being killed) and is resumed with `Range: bytes=<existing>-` on the next call; [target] itself
 * only ever appears complete, through one `renameTo` (EC-2). The caller — `PackInstaller` (T8) —
 * still verifies the SHA-256 afterwards: a completed download is not a trusted one.
 */
class RangeDownloader(private val client: OkHttpClient = Http.client) {

    /**
     * Downloads [url] into [target], resuming a previous attempt when possible.
     *
     * @param expectedBytes announced size of the asset (`manifest.json` `packs[].bytes`); a
     *   different final size is reported as [DownloadResult.SizeMismatch] and nothing is kept.
     * @param onProgress called on [Dispatchers.IO], at most every 256 KiB.
     * @return [DownloadResult.Success] with [target], or a typed failure; cancellation propagates
     *   as a `CancellationException` and leaves the `.part` file in place for the next resume.
     */
    suspend fun download(
        url: String,
        target: File,
        expectedBytes: Long,
        onProgress: DownloadProgress = { _, _ -> },
    ): DownloadResult = withContext(Dispatchers.IO) {
        require(expectedBytes > 0L) { "expectedBytes must be > 0, was $expectedBytes" }
        perform(url, target, expectedBytes, onProgress)
    }

    private suspend fun perform(
        url: String,
        target: File,
        expectedBytes: Long,
        onProgress: DownloadProgress,
    ): DownloadResult {
        val dir = target.parentFile
            ?: return DownloadResult.StorageError("no parent directory for $target")
        if (!dir.isDirectory && !dir.mkdirs()) {
            return DownloadResult.StorageError("cannot create ${dir.path}")
        }
        val part = File(dir, target.name + PART_SUFFIX)
        // A `.part` larger than the announced asset is stale (the release changed): start over.
        if (part.length() > expectedBytes) part.delete()

        var offset = part.length()
        // A previous call wrote every byte but died before the rename: nothing left to download.
        if (offset == expectedBytes) {
            onProgress(offset, expectedBytes)
            return finish(part, target, expectedBytes)
        }
        var restarted = false
        while (true) {
            val missing = expectedBytes - offset
            if (missing > 0L && !hasFreeSpace(dir, missing)) {
                return DownloadResult.NotEnoughSpace(missing, usableSpace(dir))
            }
            when (val transfer = transfer(url, part, offset, expectedBytes, onProgress)) {
                Transfer.Done -> return finish(part, target, expectedBytes)

                is Transfer.StaleRange -> {
                    // The server refused our range: what is on disk no longer belongs to this
                    // asset. Drop it and run the whole download once, from zero.
                    part.delete()
                    if (restarted || offset == 0L) {
                        return DownloadResult.HttpError(transfer.code)
                    }
                    offset = 0L
                    restarted = true
                }

                is Transfer.Failed -> return transfer.failure
            }
        }
    }

    /**
     * True when the volume holding [target] can still take [requiredBytes] plus a small margin.
     * `PackInstaller` calls it with `bytes + installedBytes` before any download starts (EC-3).
     */
    fun freeSpaceOk(target: File, requiredBytes: Long): Boolean =
        hasFreeSpace(target.parentFile ?: target, requiredBytes)

    private suspend fun transfer(
        url: String,
        part: File,
        offset: Long,
        expectedBytes: Long,
        onProgress: DownloadProgress,
    ): Transfer {
        val builder = Request.Builder().url(url).get()
        if (offset > 0L) builder.header("Range", "bytes=$offset-")
        return try {
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body
                when {
                    response.code == HTTP_RANGE_NOT_SATISFIABLE -> Transfer.StaleRange(response.code)
                    !response.isSuccessful -> Transfer.Failed(DownloadResult.HttpError(response.code))
                    body == null -> Transfer.Failed(DownloadResult.StorageError("empty response body"))
                    else -> {
                        // 206 honours the range and continues the `.part`; a 200 to a ranged
                        // request means the server ignored it, so the file is rewritten from zero.
                        val resumed = offset > 0L && response.code == HttpURLConnection.HTTP_PARTIAL
                        write(
                            body = body,
                            part = part,
                            append = resumed,
                            alreadyWritten = if (resumed) offset else 0L,
                            expectedBytes = expectedBytes,
                            onProgress = onProgress,
                        )
                    }
                }
            }
        } catch (e: IOException) {
            // The `.part` is kept: the next call resumes from where this one stopped (EC-2).
            Transfer.Failed(DownloadResult.Offline(e.describe()))
        }
    }

    private suspend fun write(
        body: ResponseBody,
        part: File,
        append: Boolean,
        alreadyWritten: Long,
        expectedBytes: Long,
        onProgress: DownloadProgress,
    ): Transfer {
        var written = alreadyWritten
        var reported = written
        onProgress(written, expectedBytes)
        var overrun = false
        body.byteStream().use { input ->
            FileOutputStream(part, append).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    // Never read past the announced size: a chunked body has no natural end.
                    val room = expectedBytes - written
                    if (room <= 0L) {
                        if (input.read() >= 0) overrun = true
                        break
                    }
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), room).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    if (written - reported >= PROGRESS_STEP_BYTES) {
                        reported = written
                        onProgress(written, expectedBytes)
                    }
                }
                output.flush()
                // The partial file must stay resumable across a process death (EC-2).
                output.fd.sync()
            }
        }
        if (overrun) {
            part.delete()
            return Transfer.Failed(DownloadResult.SizeMismatch(expectedBytes, written + 1))
        }
        if (written != reported) onProgress(written, expectedBytes)
        return Transfer.Done
    }

    private fun finish(part: File, target: File, expectedBytes: Long): DownloadResult {
        val actual = part.length()
        if (actual != expectedBytes) {
            // Wrong length means the asset is not the one the manifest describes; there is nothing
            // worth resuming, so the download is dropped and the old pack stays untouched (EC-2).
            part.delete()
            return DownloadResult.SizeMismatch(expectedBytes, actual)
        }
        if (target.exists() && !target.delete()) {
            return DownloadResult.StorageError("cannot replace ${target.path}")
        }
        if (!part.renameTo(target)) {
            return DownloadResult.StorageError("cannot move ${part.name} to ${target.name}")
        }
        return DownloadResult.Success(target)
    }

    private fun hasFreeSpace(dir: File, requiredBytes: Long): Boolean {
        if (requiredBytes <= 0L) return true
        val usable = usableSpace(dir)
        // `usableSpace` is 0 when the path cannot be resolved at all; refuse rather than guess.
        return usable > 0L && usable - FREE_SPACE_MARGIN_BYTES >= requiredBytes
    }

    /** Usable space of the first existing ancestor, so a not-yet-created directory still reports. */
    private fun usableSpace(dir: File): Long {
        var candidate: File? = dir
        while (candidate != null && !candidate.exists()) candidate = candidate.parentFile
        return candidate?.usableSpace ?: 0L
    }

    /** Result of one HTTP exchange inside [download]. */
    private sealed interface Transfer {
        data object Done : Transfer
        data class StaleRange(val code: Int) : Transfer
        data class Failed(val failure: DownloadResult.Failure) : Transfer
    }

    private companion object {
        const val PART_SUFFIX = ".part"
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_STEP_BYTES = 256L * 1024L
        const val FREE_SPACE_MARGIN_BYTES = 16L * 1024L * 1024L
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}

/** Typed outcome of [RangeDownloader.download]. */
sealed interface DownloadResult {

    /** The complete asset, at the requested target path. */
    data class Success(val file: File) : DownloadResult

    sealed interface Failure : DownloadResult

    /** Connection lost or never established; the `.part` file is kept for a resume (EC-2). */
    data class Offline(val message: String) : Failure

    /** Unexpected status (404, 403, 5xx…). */
    data class HttpError(val code: Int) : Failure

    /** Not enough room on the volume; nothing was downloaded (EC-3). */
    data class NotEnoughSpace(val requiredBytes: Long, val availableBytes: Long) : Failure

    /** The asset does not have the size the manifest announces; the download was discarded. */
    data class SizeMismatch(val expectedBytes: Long, val actualBytes: Long) : Failure

    /** The local file system refused an operation. */
    data class StorageError(val message: String) : Failure
}
