package inego.zungjunggame.net

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Net.*

/**
 * HTTP transport for `GET /leaderboard`. The endpoint is public, so [token] is
 * optional: null sends no Authorization header and the server answers with a
 * null `self`.
 *
 * Callbacks always run on the libGDX application thread.
 */
interface LeaderboardSender {
    fun list(
        token: String?,
        generation: Long?,
        limit: Int,
        offset: Int,
        onDone: (Result<LeaderboardPage>) -> Unit,
    )
}

object LeaderboardApi : LeaderboardSender {
    override fun list(
        token: String?,
        generation: Long?,
        limit: Int,
        offset: Int,
        onDone: (Result<LeaderboardPage>) -> Unit,
    ) {
        val query = buildString {
            append("/leaderboard?limit=").append(limit)
            append("&offset=").append(offset)
            if (generation != null) append("&generation=").append(generation)
        }
        val request = HttpRequest(HttpMethods.GET).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + query
            timeOut = 10_000
            setHeader("Accept", "application/json")
            if (token != null) setHeader("Authorization", "Bearer $token")
        }
        Gdx.net.sendHttpRequest(request, object : HttpResponseListener {
            override fun handleHttpResponse(httpResponse: HttpResponse) {
                val status = httpResponse.status.statusCode
                val body = httpResponse.resultAsString
                val result = when (status) {
                    200 -> runCatching { LeaderboardJson.parsePage(body) }
                    401 -> Result.failure(
                        LeaderboardException(
                            AuthJson.parseErrorMessage(body, "Unauthorized"),
                            unauthorized = true,
                        ),
                    )
                    else -> Result.failure(
                        LeaderboardException(
                            AuthJson.parseErrorMessage(body, "Leaderboard failed ($status)"),
                        ),
                    )
                }
                Gdx.app.postRunnable { onDone(result) }
            }

            override fun failed(t: Throwable) {
                Gdx.app.postRunnable {
                    onDone(
                        Result.failure(
                            LeaderboardException(t.message ?: "Could not reach the server", t),
                        ),
                    )
                }
            }

            override fun cancelled() {
                Gdx.app.postRunnable {
                    onDone(Result.failure(LeaderboardException("Leaderboard request cancelled")))
                }
            }
        })
    }
}

class LeaderboardException(
    message: String,
    cause: Throwable? = null,
    val unauthorized: Boolean = false,
) : Exception(message, cause)
