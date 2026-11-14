package inego.zungjunggame.platform

/**
 * Platform Google Sign-In. Shared code talks only to this interface; GIS,
 * Credential Manager, and desktop loopback + PKCE stay in their launchers.
 *
 * Platform launchers install [factory] before the first [create] call.
 * The default is a no-op so desktop/android stubs and tests compile without
 * a Google SDK on the TeaVM classpath.
 *
 * Callbacks run on the libGDX application thread when [com.badlogic.gdx.Gdx.app]
 * is available, or immediately in headless/test environments.
 */
interface GoogleAuthProvider {

    /** Google OAuth client ID used as the ID-token audience for this platform. */
    val clientId: String

    /** True when this platform can obtain a Google ID token. */
    val isAvailable: Boolean

    /**
     * Obtain a Google ID token bound to [nonce] (from [inego.zungjunggame.net.AuthApi.googleChallenge]).
     *
     * A new call cancels any in-flight request. After [cancel], this must not
     * invoke [onDone].
     */
    fun requestIdToken(nonce: String, onDone: (Result<String>) -> Unit)

    /** Drop an in-flight [requestIdToken]. Late callbacks must not fire. */
    fun cancel()

    /**
     * Forget locally cached Google credential state without signing the user
     * out of Google globally.
     */
    fun clearCredentialState()

    companion object {
        var factory: () -> GoogleAuthProvider = { NoOpGoogleAuthProvider() }

        fun create(): GoogleAuthProvider = factory()
    }
}

/** Safe default when no platform factory has been installed. */
class NoOpGoogleAuthProvider : GoogleAuthProvider {
    override val clientId: String = ""
    override val isAvailable: Boolean = false

    override fun requestIdToken(nonce: String, onDone: (Result<String>) -> Unit) {
        postToAppThread {
            onDone(
                Result.failure(
                    IllegalStateException("Google Sign-In is not available on this platform"),
                ),
            )
        }
    }

    override fun cancel() {}

    override fun clearCredentialState() {}
}
