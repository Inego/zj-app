package inego.zungjunggame.net

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Net.HttpMethods
import com.badlogic.gdx.Net.HttpRequest
import com.badlogic.gdx.Net.HttpResponse
import com.badlogic.gdx.Net.HttpResponseListener

/**
 * HTTP client for the Google challenge/exchange, session refresh, logout, the
 * account profile, and alias updates.
 *
 * Callbacks always run on the libGDX application thread.
 */
object AuthApi {
    /**
     * The signed-in account as the server holds it.
     *
     * The rating shown for an account is a cache of this payload: a match
     * settlement reports the account's rating only as of that match, so the
     * current value has to come back from the account itself.
     */
    fun me(
        token: String,
        onDone: (Result<AuthUser>) -> Unit,
    ) {
        val request = HttpRequest(HttpMethods.GET).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + "/me"
            timeOut = 10_000
            setHeader("Accept", "application/json")
            setHeader("Authorization", "Bearer $token")
        }
        send(request, "Profile", onDone) { status, body ->
            when (status) {
                200 -> runCatching { AuthJson.parseMe(body) }
                401 -> Result.failure(
                    AuthException(
                        AuthJson.parseErrorMessage(body, "Unauthorized"),
                        unauthorized = true,
                    ),
                )
                else -> Result.failure(
                    AuthException(
                        AuthJson.parseErrorMessage(body, "Profile failed ($status)"),
                    ),
                )
            }
        }
    }

    fun refresh(
        refreshToken: String,
        onDone: (Result<AuthLogin>) -> Unit,
    ) {
        val request = HttpRequest(HttpMethods.POST).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + "/auth/refresh"
            timeOut = 10_000
            setHeader("Content-Type", "application/json")
            setHeader("Accept", "application/json")
            content = AuthJson.refreshRequestBody(refreshToken)
        }
        Gdx.net.sendHttpRequest(request, object : HttpResponseListener {
            override fun handleHttpResponse(httpResponse: HttpResponse) {
                val status = httpResponse.status.statusCode
                val body = httpResponse.resultAsString
                val result = when (status) {
                    200, 201 -> runCatching { AuthJson.parseLogin(body) }
                    401 -> Result.failure(
                        AuthException(
                            AuthJson.parseErrorMessage(body, "Invalid or expired refresh token"),
                            unauthorized = true,
                        ),
                    )
                    else -> Result.failure(
                        AuthException(
                            AuthJson.parseErrorMessage(body, "Refresh failed ($status)"),
                        ),
                    )
                }
                Gdx.app.postRunnable { onDone(result) }
            }

            override fun failed(t: Throwable) {
                Gdx.app.postRunnable {
                    onDone(
                        Result.failure(
                            AuthException(t.message ?: "Could not reach the server", t),
                        ),
                    )
                }
            }

            override fun cancelled() {
                Gdx.app.postRunnable {
                    onDone(Result.failure(AuthException("Refresh cancelled")))
                }
            }
        })
    }

    fun googleChallenge(
        clientId: String,
        verifierHash: String,
        onDone: (Result<GoogleChallenge>) -> Unit,
    ) {
        val request = jsonPost(
            "/auth/google/challenge",
            AuthJson.googleChallengeRequestBody(clientId, verifierHash),
        )
        send(request, "Challenge", onDone) { status, body ->
            when (status) {
                200, 201 -> runCatching { AuthJson.parseGoogleChallenge(body) }
                404 -> Result.failure(
                    AuthException("Google sign-in is disabled on this server"),
                )
                429 -> Result.failure(
                    AuthException(
                        AuthJson.parseErrorMessage(body, "Too many pending sign-in attempts"),
                        rateLimited = true,
                    ),
                )
                else -> Result.failure(
                    AuthException(
                        AuthJson.parseErrorMessage(body, "Challenge failed ($status)"),
                    ),
                )
            }
        }
    }

    fun googleExchange(
        verifier: String,
        idToken: String,
        onDone: (Result<AuthLogin>) -> Unit,
    ) {
        val request = jsonPost(
            "/auth/google/exchange",
            AuthJson.googleExchangeRequestBody(verifier, idToken),
        )
        send(request, "Exchange", onDone) { status, body ->
            when (status) {
                200, 201 -> runCatching { AuthJson.parseLogin(body) }
                401 -> Result.failure(
                    AuthException(
                        AuthJson.parseErrorMessage(body, "Google sign-in could not be verified"),
                        unauthorized = true,
                    ),
                )
                404 -> Result.failure(
                    AuthException("Google sign-in is disabled on this server"),
                )
                429 -> Result.failure(
                    AuthException(
                        AuthJson.parseErrorMessage(body, "Too many sign-in attempts; try again shortly"),
                        rateLimited = true,
                    ),
                )
                else -> Result.failure(
                    AuthException(
                        AuthJson.parseErrorMessage(body, "Exchange failed ($status)"),
                    ),
                )
            }
        }
    }

    /**
     * Complete a special-key sign-in. The key is the review credential the
     * deployment configured a digest for; the server compares it and returns the
     * same session payload as Google.
     */
    fun specialKey(
        key: String,
        onDone: (Result<AuthLogin>) -> Unit,
    ) {
        val request = jsonPost("/auth/special-key", AuthJson.specialKeyRequestBody(key))
        send(request, "Special key sign-in", onDone) { status, body ->
            when (status) {
                200, 201 -> runCatching { AuthJson.parseLogin(body) }
                401 -> Result.failure(
                    AuthException(
                        AuthJson.parseErrorMessage(body, "Special key is not valid"),
                        unauthorized = true,
                    ),
                )
                404 -> Result.failure(
                    AuthException(
                        "Special key sign-in is disabled on this server",
                        unavailable = true,
                    ),
                )
                429 -> Result.failure(
                    AuthException(
                        AuthJson.parseErrorMessage(
                            body,
                            "Too many sign-in attempts; try again shortly",
                        ),
                        rateLimited = true,
                    ),
                )
                else -> Result.failure(
                    AuthException(
                        AuthJson.parseErrorMessage(body, "Special key sign-in failed ($status)"),
                    ),
                )
            }
        }
    }

    fun updateAlias(
        token: String,
        displayName: String,
        onDone: (Result<AuthUser>) -> Unit,
    ) {
        // POST, not PATCH: libGDX's desktop Net is HttpURLConnection, which
        // rejects PATCH ("Invalid HTTP method: PATCH") before sending. The server
        // routes POST /me to the same handler.
        val request = HttpRequest(HttpMethods.POST).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + "/me"
            timeOut = 10_000
            setHeader("Content-Type", "application/json")
            setHeader("Accept", "application/json")
            setHeader("Authorization", "Bearer $token")
            content = AuthJson.patchMeRequestBody(displayName)
        }
        send(request, "Alias update", onDone) { status, body ->
            when (status) {
                200 -> runCatching { AuthJson.parseMe(body) }
                401 -> Result.failure(
                    AuthException(
                        AuthJson.parseErrorMessage(body, "Unauthorized"),
                        unauthorized = true,
                    ),
                )
                429 -> Result.failure(
                    AuthException(
                        AuthJson.parseErrorMessage(body, "Alias change rate-limited"),
                        rateLimited = true,
                    ),
                )
                else -> Result.failure(
                    AuthException(
                        AuthJson.parseErrorMessage(body, "Alias update failed ($status)"),
                    ),
                )
            }
        }
    }

    fun setStartingLevel(
        token: String,
        level: String,
        onDone: (Result<AuthUser>) -> Unit,
    ) {
        val request = HttpRequest(HttpMethods.POST).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + "/me/starting-level"
            timeOut = 10_000
            setHeader("Content-Type", "application/json")
            setHeader("Accept", "application/json")
            setHeader("Authorization", "Bearer $token")
            content = AuthJson.startingLevelRequestBody(level)
        }
        send(request, "Starting level", onDone) { status, body ->
            when (status) {
                200 -> runCatching { AuthJson.parseMe(body) }
                401 -> Result.failure(
                    AuthException(
                        AuthJson.parseErrorMessage(body, "Unauthorized"),
                        unauthorized = true,
                    ),
                )
                409 -> Result.failure(
                    AuthException(
                        AuthJson.parseErrorMessage(body, "Starting level cannot be changed after playing a match"),
                    ),
                )
                else -> Result.failure(
                    AuthException(
                        AuthJson.parseErrorMessage(body, "Starting level failed ($status)"),
                    ),
                )
            }
        }
    }

    fun logout(token: String, onDone: (Result<Unit>) -> Unit) {
        postEmpty("/auth/logout", token, "Logout", onDone)
    }

    private fun jsonPost(path: String, body: String): HttpRequest =
        HttpRequest(HttpMethods.POST).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + path
            timeOut = 10_000
            setHeader("Content-Type", "application/json")
            setHeader("Accept", "application/json")
            content = body
        }

    private fun <T> send(
        request: HttpRequest,
        label: String,
        onDone: (Result<T>) -> Unit,
        parse: (status: Int, body: String) -> Result<T>,
    ) {
        Gdx.net.sendHttpRequest(request, object : HttpResponseListener {
            override fun handleHttpResponse(httpResponse: HttpResponse) {
                val status = httpResponse.status.statusCode
                val body = httpResponse.resultAsString
                val result = try {
                    parse(status, body)
                } catch (t: Throwable) {
                    Result.failure(AuthException(t.message ?: "$label failed", t))
                }
                Gdx.app.postRunnable { onDone(result) }
            }

            override fun failed(t: Throwable) {
                Gdx.app.postRunnable {
                    onDone(
                        Result.failure(
                            AuthException(t.message ?: "Could not reach the server", t),
                        ),
                    )
                }
            }

            override fun cancelled() {
                Gdx.app.postRunnable {
                    onDone(Result.failure(AuthException("$label cancelled")))
                }
            }
        })
    }

    private fun postEmpty(
        path: String,
        token: String,
        label: String,
        onDone: (Result<Unit>) -> Unit,
    ) {
        val request = HttpRequest(HttpMethods.POST).apply {
            url = ApiConfig.baseUrl.trimEnd('/') + path
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
                        AuthException(
                            AuthJson.parseErrorMessage(body, "Unauthorized"),
                            unauthorized = true,
                        ),
                    )
                    else -> Result.failure(
                        AuthException(
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
                            AuthException(t.message ?: "Could not reach the server", t),
                        ),
                    )
                }
            }

            override fun cancelled() {
                Gdx.app.postRunnable {
                    onDone(Result.failure(AuthException("$label cancelled")))
                }
            }
        })
    }
}

class AuthException(
    message: String,
    cause: Throwable? = null,
    val unauthorized: Boolean = false,
    val rateLimited: Boolean = false,
    /** The server does not serve this sign-in method at all. */
    val unavailable: Boolean = false,
) : Exception(message, cause)
