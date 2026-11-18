package inego.zungjunggame.net

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Net.HttpMethods
import com.badlogic.gdx.Net.HttpRequest
import com.badlogic.gdx.Net.HttpResponse
import com.badlogic.gdx.Net.HttpResponseListener
import com.badlogic.gdx.utils.TimeUtils
import inego.zungjunggame.platform.postToAppThread

/**
 * The two axes of a Shadow Ranked attempt, exactly the ranked-match axes
 * (`rounds`, `allow_chicken`). The wire strings match the
 * server's `GameRounds::as_str`.
 */
enum class ChallengeRounds(val wire: String) {
    One("one"),
    Two("two"),
}

/** A Shadow Ranked attempt the server assigned, plus the ticket for its solo table. */
data class ChallengeStart(
    /** Public id in display form, two groups of four (`7K3H-M9PQ`). */
    val publicId: String,
    val tableId: String,
    val seat: Int,
    /** Connection ticket for the attempt's WebSocket, as for any table. */
    val ticket: String,
)

/**
 * How the Shadow Ranked wire's transport failed. The adapter is the only producer;
 * the clients map it to their own failure vocabularies.
 */
enum class ShadowRankedWireError {
    /** The server rejected the session (HTTP 401). */
    Unauthorized,
    /** The server answered with a refusal status, or the request was cancelled. */
    Refused,
    /** The request did not complete within [ShadowRankedWire.TIMEOUT_MS]. */
    Timeout,
    /** The request failed in transport for any other reason. */
    Network,
}

/** HTTP failure while calling the Shadow Ranked wire. */
class ShadowRankedWireException(
    message: String,
    val error: ShadowRankedWireError,
    /** The server's machine-readable error code (`NOT_FOUND`, `CHALLENGE_NOT_FINISHED`, …). */
    val code: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * HTTP transport that starts a Shadow Ranked attempt through the wire's
 * duplicate-challenge start (`POST /challenges`; the wire name is retained).
 */
interface ShadowRankedStartWire {
    fun start(
        token: String,
        rounds: ChallengeRounds,
        allowChicken: Boolean,
        onDone: (Result<ChallengeStart>) -> Unit,
    )
}

/**
 * HTTP transport for `GET /challenges/pool`: how many challenges the selected
 * draft would hand this player, and how many of the player's own are still open.
 */
interface ShadowRankedPoolWire {
    fun pool(
        token: String,
        rounds: ChallengeRounds,
        allowChicken: Boolean,
        onDone: (Result<ShadowRankedPoolCounts>) -> Unit,
    )
}

/** HTTP transport for `GET /challenges`: the player's attempts, newest first. */
interface ShadowRankedAttemptsWire {
    fun list(
        token: String,
        limit: Int = 20,
        cursor: String? = null,
        onDone: (Result<ChallengeList>) -> Unit,
    ) {
        list(token, onDone)
    }

    fun list(token: String, onDone: (Result<ChallengeList>) -> Unit) {
        list(token, 20, null, onDone)
    }
}

/**
 * HTTP transport for `GET /challenges/{publicId}/hands`: one challenge's hand
 * tables for every finished attempt. A sibling of [ShadowRankedAttemptsWire] so
 * the attempts fake is not forced to grow a method it never serves.
 */
interface ShadowRankedHandsWire {
    fun loadHands(
        token: String,
        publicId: String,
        onDone: (Result<ChallengeHands>) -> Unit,
    )
}

/**
 * The one thin wire adapter behind the Shadow Ranked clients: it owns the request
 * shape, the [ChallengeJson] bridge, and the timeout. [TIMEOUT_MS] is the single
 * declaration of the request's `timeOut` **and** the boundary a transport failure
 * is classified by, so the request's wait and the clients' `Timeout` verdict cannot
 * drift — and no client guesses a timeout from the platform exception's class,
 * which differs between JVM and Wasm.
 *
 * Callbacks always run on the libGDX application thread.
 */
object ShadowRankedWire :
    ShadowRankedStartWire,
    ShadowRankedAttemptsWire,
    ShadowRankedHandsWire,
    ShadowRankedPoolWire {
    /** The request timeout, and the boundary [shadowRankedTransportFailure] classifies by. */
    const val TIMEOUT_MS = 15_000

    override fun start(
        token: String,
        rounds: ChallengeRounds,
        allowChicken: Boolean,
        onDone: (Result<ChallengeStart>) -> Unit,
    ) {
        val sentAtMs = TimeUtils.millis()
        val request = HttpRequest(HttpMethods.POST).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + "/challenges"
            timeOut = TIMEOUT_MS
            setHeader("Content-Type", "application/json")
            setHeader("Accept", "application/json")
            setHeader("Authorization", "Bearer $token")
            content = ChallengeJson.startRequestBody(rounds, allowChicken)
        }
        Gdx.net.sendHttpRequest(request, object : HttpResponseListener {
            override fun handleHttpResponse(httpResponse: HttpResponse) {
                val status = httpResponse.status.statusCode
                val body = httpResponse.resultAsString
                val result = when (status) {
                    in 200..201 -> runCatching { ChallengeJson.parseStart(body) }
                    401 -> Result.failure(
                        ShadowRankedWireException(
                            AuthJson.parseErrorMessage(body, "Unauthorized"),
                            ShadowRankedWireError.Unauthorized,
                        ),
                    )
                    else -> Result.failure(
                        ShadowRankedWireException(
                            AuthJson.parseErrorMessage(body, "Shadow Ranked failed ($status)"),
                            ShadowRankedWireError.Refused,
                        ),
                    )
                }
                post { onDone(result) }
            }

            override fun failed(t: Throwable) {
                post { onDone(Result.failure(shadowRankedTransportFailure(t, TimeUtils.millis() - sentAtMs))) }
            }

            override fun cancelled() {
                post {
                    onDone(
                        Result.failure(
                            ShadowRankedWireException("Shadow Ranked request cancelled", ShadowRankedWireError.Refused),
                        ),
                    )
                }
            }
        })
    }

    override fun list(
        token: String,
        limit: Int,
        cursor: String?,
        onDone: (Result<ChallengeList>) -> Unit,
    ) {
        val sentAtMs = TimeUtils.millis()
        val query = buildString {
            append("?limit=").append(limit)
            if (!cursor.isNullOrBlank()) {
                append("&cursor=").append(cursor)
            }
        }
        val request = HttpRequest(HttpMethods.GET).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + "/challenges" + query
            timeOut = TIMEOUT_MS
            setHeader("Accept", "application/json")
            setHeader("Authorization", "Bearer $token")
        }
        Gdx.net.sendHttpRequest(request, object : HttpResponseListener {
            override fun handleHttpResponse(httpResponse: HttpResponse) {
                val status = httpResponse.status.statusCode
                val body = httpResponse.resultAsString
                val result = when (status) {
                    200 -> runCatching { ChallengeJson.parseList(body) }
                    401 -> Result.failure(
                        ShadowRankedWireException(
                            AuthJson.parseErrorMessage(body, "Unauthorized"),
                            ShadowRankedWireError.Unauthorized,
                        ),
                    )
                    else -> Result.failure(
                        ShadowRankedWireException(
                            AuthJson.parseErrorMessage(body, "Shadow Ranked attempts failed ($status)"),
                            ShadowRankedWireError.Refused,
                        ),
                    )
                }
                post { onDone(result) }
            }

            override fun failed(t: Throwable) {
                post { onDone(Result.failure(shadowRankedTransportFailure(t, TimeUtils.millis() - sentAtMs))) }
            }

            override fun cancelled() {
                post {
                    onDone(
                        Result.failure(
                            ShadowRankedWireException("Shadow Ranked attempts request cancelled", ShadowRankedWireError.Refused),
                        ),
                    )
                }
            }
        })
    }

    override fun pool(
        token: String,
        rounds: ChallengeRounds,
        allowChicken: Boolean,
        onDone: (Result<ShadowRankedPoolCounts>) -> Unit,
    ) {
        val sentAtMs = TimeUtils.millis()
        val request = HttpRequest(HttpMethods.GET).apply {
            url = ApiConfig.baseUrl.trimEnd('/') +
                "/challenges/pool?rounds=${rounds.wire}&allow_chicken=$allowChicken"
            timeOut = TIMEOUT_MS
            setHeader("Accept", "application/json")
            setHeader("Authorization", "Bearer $token")
        }
        Gdx.net.sendHttpRequest(request, object : HttpResponseListener {
            override fun handleHttpResponse(httpResponse: HttpResponse) {
                val status = httpResponse.status.statusCode
                val body = httpResponse.resultAsString
                val result = when (status) {
                    200 -> runCatching { ChallengeJson.parsePool(body) }
                    401 -> Result.failure(
                        ShadowRankedWireException(
                            AuthJson.parseErrorMessage(body, "Unauthorized"),
                            ShadowRankedWireError.Unauthorized,
                        ),
                    )
                    else -> Result.failure(
                        ShadowRankedWireException(
                            AuthJson.parseErrorMessage(body, "Shadow Ranked pool failed ($status)"),
                            ShadowRankedWireError.Refused,
                        ),
                    )
                }
                post { onDone(result) }
            }

            override fun failed(t: Throwable) {
                post { onDone(Result.failure(shadowRankedTransportFailure(t, TimeUtils.millis() - sentAtMs))) }
            }

            override fun cancelled() {
                post {
                    onDone(
                        Result.failure(
                            ShadowRankedWireException("Shadow Ranked pool request cancelled", ShadowRankedWireError.Refused),
                        ),
                    )
                }
            }
        })
    }

    override fun loadHands(
        token: String,
        publicId: String,
        onDone: (Result<ChallengeHands>) -> Unit,
    ) {
        val sentAtMs = TimeUtils.millis()
        val request = HttpRequest(HttpMethods.GET).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + "/challenges/$publicId/hands"
            timeOut = TIMEOUT_MS
            setHeader("Accept", "application/json")
            setHeader("Authorization", "Bearer $token")
        }
        Gdx.net.sendHttpRequest(request, object : HttpResponseListener {
            override fun handleHttpResponse(httpResponse: HttpResponse) {
                val status = httpResponse.status.statusCode
                val body = httpResponse.resultAsString
                val result = when (status) {
                    200 -> runCatching { ChallengeJson.parseHands(body) }
                    401 -> Result.failure(
                        ShadowRankedWireException(
                            AuthJson.parseErrorMessage(body, "Unauthorized"),
                            ShadowRankedWireError.Unauthorized,
                            code = AuthJson.parseErrorCode(body),
                        ),
                    )
                    else -> Result.failure(
                        ShadowRankedWireException(
                            AuthJson.parseErrorMessage(body, "Shadow Ranked hands failed ($status)"),
                            ShadowRankedWireError.Refused,
                            code = AuthJson.parseErrorCode(body),
                        ),
                    )
                }
                post { onDone(result) }
            }

            override fun failed(t: Throwable) {
                post { onDone(Result.failure(shadowRankedTransportFailure(t, TimeUtils.millis() - sentAtMs))) }
            }

            override fun cancelled() {
                post {
                    onDone(
                        Result.failure(
                            ShadowRankedWireException("Shadow Ranked hands request cancelled", ShadowRankedWireError.Refused),
                        ),
                    )
                }
            }
        })
    }

    private fun post(action: () -> Unit) = postToAppThread(action)
}

/**
 * Classifies a transport failure of one request by how long it had run: a failure
 * that arrives no earlier than [ShadowRankedWire.TIMEOUT_MS] after the request was
 * sent is that request's own timeout, and anything sooner is a plain
 * [ShadowRankedWireError.Network] failure. The adapter calls this from its `failed`
 * handler so the duration it sets on the request is also the one it classifies by.
 */
internal fun shadowRankedTransportFailure(cause: Throwable, elapsedMs: Long): ShadowRankedWireException =
    ShadowRankedWireException(
        message = cause.message ?: "Could not reach the server",
        error = if (elapsedMs >= ShadowRankedWire.TIMEOUT_MS) {
            ShadowRankedWireError.Timeout
        } else {
            ShadowRankedWireError.Network
        },
        cause = cause,
    )
