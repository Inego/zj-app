package inego.zungjunggame.net

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Net.*
import inego.zungjunggame.platform.postToAppThread

/**
 * HTTP transport for presence. Callers pass the Bearer [token]; this never
 * reads [AuthSession].
 *
 * Callbacks always run on the libGDX application thread.
 */
interface PresenceSender {
    fun heartbeat(
        token: String,
        activity: PresenceActivity,
        minOtherHumans: Int? = null,
        rounds: QueueRounds? = null,
        chicken: QueueChicken? = null,
        onDone: (Result<PresenceSnapshot>) -> Unit,
    )

    fun leave(
        token: String,
        onDone: (Result<Unit>) -> Unit,
    )
}

object PresenceApi : PresenceSender {
    override fun heartbeat(
        token: String,
        activity: PresenceActivity,
        minOtherHumans: Int?,
        rounds: QueueRounds?,
        chicken: QueueChicken?,
        onDone: (Result<PresenceSnapshot>) -> Unit,
    ) {
        val request = HttpRequest(HttpMethods.POST).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + "/presence/heartbeat"
            timeOut = 10_000
            setHeader("Content-Type", "application/json")
            setHeader("Accept", "application/json")
            setHeader("Authorization", "Bearer $token")
            content = PresenceJson.heartbeatRequestBody(activity, minOtherHumans, rounds, chicken)
        }
        Gdx.net.sendHttpRequest(request, object : HttpResponseListener {
            override fun handleHttpResponse(httpResponse: HttpResponse) {
                val status = httpResponse.status.statusCode
                val body = httpResponse.resultAsString
                val result = when (status) {
                    200 -> runCatching { PresenceJson.parseSnapshot(body) }
                    401 -> Result.failure(
                        PresenceException(
                            AuthJson.parseErrorMessage(body, "Unauthorized"),
                            unauthorized = true,
                        ),
                    )
                    else -> Result.failure(
                        PresenceException(
                            AuthJson.parseErrorMessage(body, "Presence failed ($status)"),
                        ),
                    )
                }
                postCallback { onDone(result) }
            }

            override fun failed(t: Throwable) {
                postCallback {
                    onDone(
                        Result.failure(
                            PresenceException(t.message ?: "Could not reach the server", t),
                        ),
                    )
                }
            }

            override fun cancelled() {
                postCallback {
                    onDone(Result.failure(PresenceException("Presence request cancelled")))
                }
            }
        })
    }

    override fun leave(
        token: String,
        onDone: (Result<Unit>) -> Unit,
    ) {
        val request = HttpRequest(HttpMethods.POST).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + "/presence/leave"
            timeOut = 10_000
            setHeader("Accept", "application/json")
            setHeader("Authorization", "Bearer $token")
        }
        Gdx.net.sendHttpRequest(request, object : HttpResponseListener {
            override fun handleHttpResponse(httpResponse: HttpResponse) {
                val status = httpResponse.status.statusCode
                val body = httpResponse.resultAsString
                val result = when (status) {
                    204, 200 -> Result.success(Unit)
                    401 -> Result.failure(
                        PresenceException(
                            AuthJson.parseErrorMessage(body, "Unauthorized"),
                            unauthorized = true,
                        ),
                    )
                    else -> Result.failure(
                        PresenceException(
                            AuthJson.parseErrorMessage(body, "Presence leave failed ($status)"),
                        ),
                    )
                }
                postCallback { onDone(result) }
            }

            override fun failed(t: Throwable) {
                postCallback {
                    onDone(
                        Result.failure(
                            PresenceException(t.message ?: "Could not reach the server", t),
                        ),
                    )
                }
            }

            override fun cancelled() {
                postCallback {
                    onDone(Result.failure(PresenceException("Presence leave cancelled")))
                }
            }
        })
    }

    private fun postCallback(action: () -> Unit) = postToAppThread(action)
}

class PresenceException(
    message: String,
    cause: Throwable? = null,
    val unauthorized: Boolean = false,
) : Exception(message, cause)
