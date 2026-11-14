package inego.zungjunggame.net

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Net.*

/**
 * HTTP transport for `GET /matches`. Callers pass the Bearer [token].
 *
 * Callbacks always run on the libGDX application thread.
 */
interface MatchHistorySender {
    fun list(
        token: String,
        limit: Int,
        offset: Int,
        onDone: (Result<MatchList>) -> Unit,
    )
}

object MatchHistoryApi : MatchHistorySender {
    override fun list(
        token: String,
        limit: Int,
        offset: Int,
        onDone: (Result<MatchList>) -> Unit,
    ) {
        val request = HttpRequest(HttpMethods.GET).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + "/matches?limit=$limit&offset=$offset"
            timeOut = 10_000
            setHeader("Accept", "application/json")
            setHeader("Authorization", "Bearer $token")
        }
        Gdx.net.sendHttpRequest(request, object : HttpResponseListener {
            override fun handleHttpResponse(httpResponse: HttpResponse) {
                val status = httpResponse.status.statusCode
                val body = httpResponse.resultAsString
                val result = when (status) {
                    200 -> runCatching { MatchHistoryJson.parseList(body) }
                    401 -> Result.failure(
                        MatchHistoryException(
                            AuthJson.parseErrorMessage(body, "Unauthorized"),
                            unauthorized = true,
                        ),
                    )
                    else -> Result.failure(
                        MatchHistoryException(
                            AuthJson.parseErrorMessage(body, "Match history failed ($status)"),
                        ),
                    )
                }
                Gdx.app.postRunnable { onDone(result) }
            }

            override fun failed(t: Throwable) {
                Gdx.app.postRunnable {
                    onDone(
                        Result.failure(
                            MatchHistoryException(t.message ?: "Could not reach the server", t),
                        ),
                    )
                }
            }

            override fun cancelled() {
                Gdx.app.postRunnable {
                    onDone(Result.failure(MatchHistoryException("Match history request cancelled")))
                }
            }
        })
    }
}

class MatchHistoryException(
    message: String,
    cause: Throwable? = null,
    val unauthorized: Boolean = false,
) : Exception(message, cause)
