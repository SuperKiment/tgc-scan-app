package org.tcgscan.app.data.net

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.tcgscan.app.AppConfig

/**
 * Reads `manifest.json` from the release download URL (D13).
 *
 * The manifest is a **release asset behind a plain redirect**: no GitHub API, no token, no rate
 * limit. That host intermittently answers a plain-text `no available server` body with a 200
 * status, so a body that is not JSON is treated as a transient failure and retried, exactly like a
 * 5xx (see `docs/pack-format.md` §4.7 for the pipeline side of the same rule).
 */
class ManifestService(
    private val client: OkHttpClient = Http.client,
    private val manifestUrl: String = AppConfig.MANIFEST_URL,
) {

    /** Never throws: every outcome is a [ManifestResult]. Safe to call from a ViewModel scope. */
    suspend fun fetch(): ManifestResult = withContext(Dispatchers.IO) {
        if (!AppConfig.isGithubRepoConfigured()) {
            return@withContext ManifestResult.RepoNotConfigured
        }
        when (val outcome = Http.retryOnNonJson { attempt() }) {
            is Attempt.Success -> ManifestResult.Success(outcome.value)
            is Attempt.Transient -> outcome.failure
            is Attempt.Terminal -> outcome.failure
        }
    }

    private fun attempt(): Attempt<PackManifest, ManifestResult.Failure> {
        val request = Request.Builder()
            .url(manifestUrl)
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = readCapped(response)
                    ?: return@use Attempt.Terminal(
                        ManifestResult.Malformed("manifest larger than $MAX_MANIFEST_BYTES bytes"),
                    )
                when {
                    response.code >= HTTP_SERVER_ERROR ->
                        Attempt.Transient(ManifestResult.Unavailable("HTTP ${response.code}"))

                    !response.isSuccessful ->
                        Attempt.Terminal(ManifestResult.HttpError(response.code))

                    else -> parsed(body)
                }
            }
        } catch (e: IOException) {
            // No usable connection: retrying here would only delay the « réessayer » screen (EC-1).
            Attempt.Terminal(ManifestResult.Offline(e.describe()))
        }
    }

    /** Body text, or null when it exceeds [MAX_MANIFEST_BYTES] (declared length or actual bytes). */
    private fun readCapped(response: Response): String? {
        val body = response.body ?: return ""
        if (body.contentLength() > MAX_MANIFEST_BYTES) return null
        val source = body.source()
        // request() buffers up to the limit and returns false on early EOF instead of throwing.
        source.request(MAX_MANIFEST_BYTES + 1)
        if (source.buffer.size > MAX_MANIFEST_BYTES) return null
        return source.buffer.readString(Charsets.UTF_8)
    }

    private fun parsed(body: String): Attempt<PackManifest, ManifestResult.Failure> {
        val manifest = try {
            PackManifest.parse(body)
        } catch (e: SerializationException) {
            return Attempt.Transient(ManifestResult.Unavailable("non-JSON body: ${e.describe()}"))
        }
        if (manifest.schema > PackManifest.SCHEMA) {
            return Attempt.Terminal(ManifestResult.UnsupportedSchema(manifest.schema))
        }
        manifest.validationError()?.let {
            return Attempt.Terminal(ManifestResult.Malformed(it))
        }
        return Attempt.Success(manifest)
    }

    companion object {

        private const val HTTP_SERVER_ERROR = 500
        private const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024

        /**
         * Absolute URL of a pack asset, resolved against the very same
         * `releases/latest/download/` base as the manifest (D13). [PackEntry.file] is validated by
         * [PackEntry.validationError] before it ever reaches this function.
         */
        fun packUrl(entry: PackEntry): String = "${AppConfig.RELEASE_DOWNLOAD_BASE}/${entry.file}"
    }
}

/** Typed outcome of [ManifestService.fetch]; the UI maps each case to its own message. */
sealed interface ManifestResult {

    data class Success(val manifest: PackManifest) : ManifestResult

    /** Everything the user may have to be told about; see each case for the matching criterion. */
    sealed interface Failure : ManifestResult

    /** `AppConfig.GITHUB_REPO` is still the placeholder (D13): no pack can be published yet. */
    data object RepoNotConfigured : Failure

    /** No usable connection — the « réessayer » case (EC-1). */
    data class Offline(val message: String) : Failure

    /** The host answered, but not with a manifest: 5xx or a non-JSON body, after all retries. */
    data class Unavailable(val message: String) : Failure

    /** Unexpected status, typically 404 while no release has been published yet. */
    data class HttpError(val code: Int) : Failure

    /** Valid JSON, but a manifest schema newer than [PackManifest.SCHEMA]: this build is too old. */
    data class UnsupportedSchema(val schema: Int) : Failure

    /** Valid JSON of the right schema, but a field this build cannot trust. */
    data class Malformed(val message: String) : Failure
}
