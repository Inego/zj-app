package inego.zungjunggame.net

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Net.HttpMethods
import com.badlogic.gdx.Net.HttpRequest
import com.badlogic.gdx.Net.HttpResponse
import com.badlogic.gdx.Net.HttpResponseListener

/** HTTP transport for `GET /notification-prefs`: the player's notification settings. */
interface NotificationPrefsReader {
    fun get(token: String, onDone: (Result<NotificationPrefs>) -> Unit)
}

/** HTTP transport for `PUT /notification-prefs`: replace the player's notification settings. */
interface NotificationPrefsWriter {
    fun put(token: String, prefs: NotificationPrefs, onDone: (Result<NotificationPrefs>) -> Unit)
}

/**
 * HTTP transport for the notification settings.
 *
 * Callbacks always run on the libGDX application thread.
 */
object NotificationPrefsApi : NotificationPrefsReader, NotificationPrefsWriter {
    override fun get(token: String, onDone: (Result<NotificationPrefs>) -> Unit) {
        val request = HttpRequest(HttpMethods.GET).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + "/notification-prefs"
            timeOut = 15_000
            setHeader("Accept", "application/json")
            setHeader("Authorization", "Bearer $token")
        }
        send(request, "Notification settings", onDone)
    }

    override fun put(
        token: String,
        prefs: NotificationPrefs,
        onDone: (Result<NotificationPrefs>) -> Unit,
    ) {
        val request = HttpRequest(HttpMethods.PUT).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + "/notification-prefs"
            timeOut = 15_000
            setHeader("Content-Type", "application/json")
            setHeader("Accept", "application/json")
            setHeader("Authorization", "Bearer $token")
            content = NotificationPrefsJson.encode(prefs)
        }
        send(request, "Notification settings", onDone)
    }

    private fun send(
        request: HttpRequest,
        label: String,
        onDone: (Result<NotificationPrefs>) -> Unit,
    ) {
        Gdx.net.sendHttpRequest(request, object : HttpResponseListener {
            override fun handleHttpResponse(httpResponse: HttpResponse) {
                val status = httpResponse.status.statusCode
                val body = httpResponse.resultAsString
                val result = when (status) {
                    200 -> runCatching { NotificationPrefsJson.parse(body) }
                    401 -> Result.failure(
                        NotificationPrefsException(
                            AuthJson.parseErrorMessage(body, "Unauthorized"),
                            unauthorized = true,
                        ),
                    )
                    else -> Result.failure(
                        NotificationPrefsException(
                            AuthJson.parseErrorMessage(body, "$label failed ($status)"),
                        ),
                    )
                }
                Gdx.app.postRunnable { onDone(result) }
            }

            override fun failed(t: Throwable) {
                Gdx.app.postRunnable {
                    onDone(
                        Result.failure(
                            NotificationPrefsException(t.message ?: "Could not reach the server", t),
                        ),
                    )
                }
            }

            override fun cancelled() {
                Gdx.app.postRunnable {
                    onDone(Result.failure(NotificationPrefsException("$label request cancelled")))
                }
            }
        })
    }
}
