package inego.zungjunggame.net

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Net.HttpMethods
import com.badlogic.gdx.Net.HttpRequest
import com.badlogic.gdx.Net.HttpResponse
import com.badlogic.gdx.Net.HttpResponseListener
import com.badlogic.gdx.utils.JsonReader
import inego.zungjunggame.platform.DesktopCrashHook
import inego.zungjunggame.platform.postToAppThread

data class TableTicket(
    val ticket: String,
    val tableId: String,
    val seat: Int,
)

/** HTTP failure while acquiring a table ticket. */
class TableTicketException(
    val httpStatus: Int,
    message: String,
) : Exception(message) {
    val tableGone: Boolean
        get() = httpStatus == 404 || httpStatus == 403
}

/**
 * Client for acquiring short-lived connection tickets for live tables.
 */
object TableTicketApi {

    fun requestTicket(
        tableId: String,
        token: String,
        onDone: (Result<TableTicket>) -> Unit,
    ) {
        val request = HttpRequest(HttpMethods.POST).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + "/tables/$tableId/ticket"
            timeOut = 10_000
            setHeader("Authorization", "Bearer $token")
            setHeader("Accept", "application/json")
        }

        Gdx.net.sendHttpRequest(request, object : HttpResponseListener {
            override fun handleHttpResponse(httpResponse: HttpResponse) {
                val status = httpResponse.status.statusCode
                val body = httpResponse.resultAsString
                val result = if (status in 200..201) {
                    runCatching {
                        val json = JsonReader().parse(body)
                            ?: throw IllegalArgumentException("empty ticket response")
                        TableTicket(
                            ticket = json.getString("ticket"),
                            tableId = json.getString("table_id"),
                            seat = json.getInt("seat"),
                        )
                    }
                } else {
                    Result.failure(
                        TableTicketException(
                            status,
                            "Failed to acquire table ticket ($status): $body",
                        ),
                    )
                }
                post { onDone(result) }
            }

            override fun failed(t: Throwable) {
                post { onDone(Result.failure(t)) }
            }

            override fun cancelled() {
                post { onDone(Result.failure(Exception("Table ticket request cancelled"))) }
            }
        })
    }

    private fun post(action: () -> Unit) {
        postToAppThread { DesktopCrashHook.run("TableTicketApi") { action() } }
    }
}
