package inego.zungjunggame.net

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Net.HttpMethods
import com.badlogic.gdx.Net.HttpRequest
import com.badlogic.gdx.Net.HttpResponse
import com.badlogic.gdx.Net.HttpResponseListener
import inego.zungjunggame.platform.postToAppThread

/** HTTP failure while reading or marking the player's notices. */
class NoticeException(
    message: String,
    cause: Throwable? = null,
    val unauthorized: Boolean = false,
) : Exception(message, cause)

/** HTTP transport for `GET /notices`: the player's notices, newest first. */
interface NoticeLister {
    fun list(token: String, onDone: (Result<NoticeList>) -> Unit)
}

/** HTTP transport for `POST /notices/seen`: mark notices seen for the account. */
interface NoticeMarker {
    fun markSeen(token: String, ids: List<Long>, onDone: (Result<Unit>) -> Unit)
}

/**
 * HTTP transport for the notice inbox.
 *
 * Callbacks always run on the libGDX application thread.
 */
object NoticeApi : NoticeLister, NoticeMarker {
    override fun list(token: String, onDone: (Result<NoticeList>) -> Unit) {
        val request = HttpRequest(HttpMethods.GET).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + "/notices"
            timeOut = 15_000
            setHeader("Accept", "application/json")
            setHeader("Authorization", "Bearer $token")
        }
        Gdx.net.sendHttpRequest(request, object : HttpResponseListener {
            override fun handleHttpResponse(httpResponse: HttpResponse) {
                val status = httpResponse.status.statusCode
                val body = httpResponse.resultAsString
                val result = when (status) {
                    200 -> runCatching { NoticeJson.parseList(body) }
                    401 -> Result.failure(
                        NoticeException(
                            AuthJson.parseErrorMessage(body, "Unauthorized"),
                            unauthorized = true,
                        ),
                    )
                    else -> Result.failure(
                        NoticeException(
                            AuthJson.parseErrorMessage(body, "Notices failed ($status)"),
                        ),
                    )
                }
                post { onDone(result) }
            }

            override fun failed(t: Throwable) {
                post { onDone(Result.failure(NoticeException(t.message ?: "Could not reach the server", t))) }
            }

            override fun cancelled() {
                post { onDone(Result.failure(NoticeException("Notices request cancelled"))) }
            }
        })
    }

    override fun markSeen(token: String, ids: List<Long>, onDone: (Result<Unit>) -> Unit) {
        if (ids.isEmpty()) {
            onDone(Result.success(Unit))
            return
        }
        val request = HttpRequest(HttpMethods.POST).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + "/notices/seen"
            timeOut = 15_000
            setHeader("Content-Type", "application/json")
            setHeader("Accept", "application/json")
            setHeader("Authorization", "Bearer $token")
            content = NoticeJson.seenRequestBody(ids)
        }
        Gdx.net.sendHttpRequest(request, object : HttpResponseListener {
            override fun handleHttpResponse(httpResponse: HttpResponse) {
                val status = httpResponse.status.statusCode
                val body = httpResponse.resultAsString
                val result = when (status) {
                    200, 204 -> Result.success(Unit)
                    401 -> Result.failure(
                        NoticeException(
                            AuthJson.parseErrorMessage(body, "Unauthorized"),
                            unauthorized = true,
                        ),
                    )
                    else -> Result.failure(
                        NoticeException(
                            AuthJson.parseErrorMessage(body, "Notices failed ($status)"),
                        ),
                    )
                }
                post { onDone(result) }
            }

            override fun failed(t: Throwable) {
                post { onDone(Result.failure(NoticeException(t.message ?: "Could not reach the server", t))) }
            }

            override fun cancelled() {
                post { onDone(Result.failure(NoticeException("Notices request cancelled"))) }
            }
        })
    }

    private fun post(action: () -> Unit) = postToAppThread(action)
}
