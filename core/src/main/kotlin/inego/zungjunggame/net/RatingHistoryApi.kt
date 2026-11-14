package inego.zungjunggame.net

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Net.HttpMethods
import com.badlogic.gdx.Net.HttpRequest
import com.badlogic.gdx.Net.HttpResponse
import com.badlogic.gdx.Net.HttpResponseListener
import com.badlogic.gdx.utils.TimeUtils
import inego.zungjunggame.platform.postToAppThread
import inego.zungjunggame.ui.stack.RatingHistoryTarget

/**
 * A dispatched rating-history request that can be abandoned.
 *
 * [cancel] is safe to call after the request finished (the platform's listener is
 * already gone, so it does nothing) and safe to call from the application thread
 * while the request is in flight. A cancelled request always reports a failure,
 * and the owner drops it: nothing waiting on a cancelled request ever applies.
 */
fun interface RatingHistoryHandle {
    fun cancel()

    companion object {
        /** A request whose transport offers no cancellation. */
        val None: RatingHistoryHandle = RatingHistoryHandle { }
    }
}

/** HTTP transport for the two rating-history endpoints. */
interface RatingHistorySender {
    /**
     * Send [query]. [token] is the `Self` request's bearer credential; a public
     * player read passes null and sends no Authorization header.
     *
     * [onDone] always runs on the libGDX application thread and exactly once per
     * accepted request (a cancelled request reports its cancellation). It carries
     * a typed [RatingHistoryFailure] — never a raw transport exception.
     */
    fun fetch(
        query: RatingHistoryQuery,
        token: String?,
        onDone: (Result<RatingHistoryPage>) -> Unit,
    ): RatingHistoryHandle
}

/**
 * HTTP transport for `GET /me/rating-history` and
 * `GET /players/{history_id}/rating-history`.
 *
 * The request is either parameterless (the all-time answer) or carries exactly
 * one `level` and one contiguous `tiles` run, so the two ambiguous shapes the
 * server rejects with a `400` cannot be produced by a caller: [RatingHistoryQuery]
 * has only the two valid forms.
 *
 * **Timeout.** [TIMEOUT_MS] is set on every request, but only the desktop and
 * Android backends honor it: the TeaVM backend's `WebNet` never applies
 * `HttpRequest.timeOut`, so on the web a request can stay open forever. That is
 * why the data owner carries its own deadline and treats a late response as
 * stale rather than relying on the platform to fail one; this adapter still
 * classifies a transport failure by how long the request had run, so a platform
 * timeout is reported as [RatingHistoryFailure.Timeout] on the backends that
 * have one.
 *
 * Callbacks always run on the libGDX application thread.
 */
object RatingHistoryApi : RatingHistorySender {
    /**
     * The request's declared timeout. The owner's deadline is at least this long,
     * so the platform's own `Timeout` normally arrives first where the platform
     * enforces one.
     */
    const val TIMEOUT_MS: Long = 15_000L

    override fun fetch(
        query: RatingHistoryQuery,
        token: String?,
        onDone: (Result<RatingHistoryPage>) -> Unit,
    ): RatingHistoryHandle {
        val sentAtMs = TimeUtils.millis()
        val request = HttpRequest(HttpMethods.GET).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + pathOf(query) + parametersOf(query)
            timeOut = TIMEOUT_MS.toInt()
            setHeader("Accept", "application/json")
            if (token != null) setHeader("Authorization", "Bearer $token")
        }
        Gdx.net.sendHttpRequest(request, object : HttpResponseListener {
            override fun handleHttpResponse(httpResponse: HttpResponse) {
                val result = resultOf(httpResponse.status.statusCode, httpResponse.resultAsString)
                postToAppThread { onDone(result) }
            }

            override fun failed(t: Throwable) {
                val failure = transportFailure(t, TimeUtils.millis() - sentAtMs)
                postToAppThread { onDone(Result.failure(failure)) }
            }

            override fun cancelled() {
                postToAppThread {
                    onDone(Result.failure(RatingHistoryFailure.Network("Rating history request cancelled")))
                }
            }
        })
        return RatingHistoryHandle { Gdx.net?.cancelHttpRequest(request) }
    }

    private fun pathOf(query: RatingHistoryQuery): String = when (val target = query.target) {
        RatingHistoryTarget.Self -> "/me/rating-history"
        is RatingHistoryTarget.Player ->
            "/players/" + encodePathSegment(target.historyId) + "/rating-history"
    }

    private fun parametersOf(query: RatingHistoryQuery): String = when (query) {
        is RatingHistoryQuery.AllTime -> ""
        is RatingHistoryQuery.Tiles ->
            "?level=" + query.level + "&tiles=" + query.tiles.joinToString(",")
    }

    /**
     * Percent-encode one path segment (RFC 3986 unreserved characters pass
     * through). A `history_id` is 32 lowercase hex characters and never changes,
     * but the handle is opaque server text carried through a text codec, so it is
     * never trusted as a URL fragment.
     */
    private fun encodePathSegment(value: String): String {
        val hex = "0123456789ABCDEF"
        return buildString(value.length) {
            for (byte in value.encodeToByteArray()) {
                val code = byte.toInt() and 0xFF
                val unreserved = code in 'A'.code..'Z'.code ||
                    code in 'a'.code..'z'.code ||
                    code in '0'.code..'9'.code ||
                    code == '-'.code || code == '.'.code || code == '_'.code || code == '~'.code
                if (unreserved) {
                    append(code.toChar())
                } else {
                    append('%')
                    append(hex[code ushr 4])
                    append(hex[code and 0x0F])
                }
            }
        }
    }

    private fun resultOf(status: Int, body: String): Result<RatingHistoryPage> = when (status) {
        200 -> parse(body)
        400 -> Result.failure(
            RatingHistoryFailure.Rejected(AuthJson.parseErrorMessage(body, "Rating history rejected ($status)")),
        )
        401 -> Result.failure(
            RatingHistoryFailure.Unauthorized(AuthJson.parseErrorMessage(body, "Unauthorized")),
        )
        404 -> Result.failure(
            RatingHistoryFailure.NotFound(AuthJson.parseErrorMessage(body, "Resource not found")),
        )
        // 5xx, and 429/503 once C3's admission control exists: retry with backoff.
        else -> Result.failure(
            RatingHistoryFailure.Server(AuthJson.parseErrorMessage(body, "Rating history failed ($status)")),
        )
    }

    private fun parse(body: String): Result<RatingHistoryPage> = try {
        Result.success(RatingHistoryJson.parsePage(body))
    } catch (t: Throwable) {
        Result.failure(
            RatingHistoryFailure.Unreadable(t.message ?: "Unreadable rating history response", t),
        )
    }

    /**
     * Classify a transport failure by how long the request had run. The platform's
     * timeout exception differs between JVM and Wasm (and the web backend has
     * none), so the elapsed wait — the same request's own `timeOut` — is the one
     * honest signal, exactly as `ShadowRankedWire` classifies its own.
     */
    private fun transportFailure(cause: Throwable, elapsedMs: Long): RatingHistoryFailure {
        val message = cause.message ?: "Could not reach the server"
        return if (elapsedMs >= TIMEOUT_MS) {
            RatingHistoryFailure.Timeout(message, cause)
        } else {
            RatingHistoryFailure.Network(message, cause)
        }
    }
}
