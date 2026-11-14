package inego.zungjunggame.teavm

import com.github.xpenatan.gdx.teavm.backends.web.BrowserFiber
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.platform.GoogleAuthProvider
import inego.zungjunggame.platform.GoogleSignInCancelled
import inego.zungjunggame.platform.GoogleSignInException
import inego.zungjunggame.platform.postToAppThread

/**
 * Wasm Google Identity Services provider. Renders Google's official button in
 * an HTML overlay, then returns the ID token to [AccountController].
 *
 * Session storage is not this class's job: [inego.zungjunggame.net.AuthSession]
 * persists the refresh token through Gdx `Preferences` (browser
 * `localStorage`), so a reload restores the session without GIS.
 */
class WasmGoogleAuthProvider : GoogleAuthProvider {
    override val clientId: String = NativeGis.resolveClientId("")

    override val isAvailable: Boolean
        get() = clientId.isNotBlank()

    @Volatile
    private var requestGen = 0

    override fun requestIdToken(nonce: String, onDone: (Result<String>) -> Unit) {
        cancel()
        val gen = ++requestGen
        fun finish(result: Result<String>) {
            postToAppThread {
                if (gen != requestGen) return@postToAppThread
                requestGen += 1
                NativeGis.cancel()
                onDone(result)
            }
        }
        if (clientId.isBlank()) {
            finish(Result.failure(GoogleSignInException.fromGisCode("init_failed")))
            return
        }
        if (!NativeGis.isSecureContext()) {
            finish(Result.failure(GoogleSignInException.fromGisCode("insecure")))
            return
        }
        NativeGis.loadClient(
            { _ ->
                BrowserFiber.enter {
                    if (gen != requestGen) return@enter
                    NativeGis.startSignIn(
                        clientId,
                        nonce,
                        L10n.get("gis.overlay_title"),
                        L10n.get("gis.overlay_cancel"),
                        { token ->
                            BrowserFiber.enter {
                                if (token.isNullOrBlank()) {
                                    finish(Result.failure(GoogleSignInException.fromGisCode("empty_credential")))
                                } else {
                                    finish(Result.success(token))
                                }
                            }
                        },
                        { code ->
                            BrowserFiber.enter {
                                finish(Result.failure(GoogleSignInException.fromGisCode(code ?: "unknown")))
                            }
                        },
                        { _ ->
                            BrowserFiber.enter {
                                finish(Result.failure(GoogleSignInCancelled()))
                            }
                        },
                    )
                }
            },
            { code ->
                BrowserFiber.enter {
                    finish(Result.failure(GoogleSignInException.fromGisCode(code ?: "script_load")))
                }
            },
        )
    }

    override fun cancel() {
        requestGen += 1
        NativeGis.cancel()
    }

    override fun clearCredentialState() {
        NativeGis.disableAutoSelect()
    }
}
