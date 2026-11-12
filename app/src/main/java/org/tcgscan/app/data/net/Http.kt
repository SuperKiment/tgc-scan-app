package org.tcgscan.app.data.net

import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.coroutines.delay
import okhttp3.CookieJar
import okhttp3.OkHttpClient

/**
 * The single HTTP stack of the app: one [OkHttpClient] shared by the manifest fetch, the pack
 * download and the image loader, so connections, the thread pool and the connection cache are
 * created exactly once.
 *
 * The app talks to two hosts only — the TCGdex image CDN (through Coil) and
 * `github.com/<repo>/releases/latest/download` (D13). No cookies are kept, nothing is cached in
 * memory and no interceptor inspects traffic (NFR-5).
 */
object Http {

    /** Per-connection and per-read budget; there is deliberately no call timeout (see [client]). */
    private const val TIMEOUT_SECONDS = 15L

    /** Retries granted by [retryOnNonJson] on top of the first attempt. */
    const val MAX_RETRIES = 3

    /** Backoff before retry 1, 2 and 3. */
    private val RETRY_BACKOFF_MILLIS = longArrayOf(1_000L, 2_000L, 4_000L)

    /**
     * Built lazily so a process that never goes online never creates it.
     *
     * Redirects are followed because `releases/latest/download/<asset>` is a plain redirect to the
     * asset (D13). The default call timeout (none) is kept on purpose: a pack is tens of megabytes
     * and only the per-read timeout may bound it.
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Runs [block] once and retries it up to [retries] times with a 1/2/4 s backoff while it
     * reports a [Attempt.Transient] outcome — a 5xx status or a body that is not JSON at all (the
     * measured intermittent `no available server` plain-text answer of the release host).
     *
     * Deliberately **not** an OkHttp interceptor: deciding whether a body is JSON requires reading
     * it, and an interceptor that consumes the body cannot hand it back to the caller.
     *
     * @return the successful attempt, the first [Attempt.Terminal] outcome, or the last transient
     *   failure once the retries are exhausted.
     */
    suspend fun <T, E> retryOnNonJson(
        retries: Int = MAX_RETRIES,
        block: suspend () -> Attempt<T, E>,
    ): Attempt<T, E> {
        require(retries >= 0) { "retries must be >= 0, was $retries" }
        var last: Attempt<T, E>? = null
        for (round in 0..retries) {
            if (round > 0) delay(RETRY_BACKOFF_MILLIS[min(round - 1, RETRY_BACKOFF_MILLIS.lastIndex)])
            val outcome = block()
            when (outcome) {
                is Attempt.Success -> return outcome
                is Attempt.Terminal -> return outcome
                is Attempt.Transient -> last = outcome
            }
        }
        return checkNotNull(last) { "retryOnNonJson produced no outcome" }
    }
}

/**
 * Outcome of one HTTP exchange, as seen by [Http.retryOnNonJson]: either a value, a failure worth
 * another try, or a failure that no retry can fix. [E] is the caller's typed failure, so nothing
 * above this layer ever handles an exception.
 */
sealed interface Attempt<out T, out E> {

    /** Terminal success. */
    data class Success<out T>(val value: T) : Attempt<T, Nothing>

    /** Transient failure: retried while attempts remain, then [failure] is returned. */
    data class Transient<out E>(val failure: E) : Attempt<Nothing, E>

    /** Definitive failure: returned immediately, without any retry. */
    data class Terminal<out E>(val failure: E) : Attempt<Nothing, E>
}

/** Short, non-null description of a failure, for the `message` of a typed result. */
internal fun Throwable.describe(): String =
    message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
