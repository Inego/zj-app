package inego.zungjunggame.android

import android.app.Activity
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CredentialOption
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import inego.zungjunggame.BuildConfig
import inego.zungjunggame.platform.GoogleAuthProvider
import inego.zungjunggame.platform.GoogleSignInException
import inego.zungjunggame.platform.postToAppThread
import java.util.concurrent.Executor

/**
 * Android Google Sign-In via Credential Manager.
 *
 * [clientId] is the **web / server** OAuth client ID (ID-token audience),
 * the same value Wasm GIS uses. Register an Android OAuth client in that
 * Google Cloud project (package `inego.zungjunggame` + signing SHA-1) so
 * Play services will issue tokens for this audience. The Android client ID
 * is not passed in code.
 *
 * The nonce is forwarded to Google as-is; hashing it here would break
 * the server's nonce check on `/auth/google/exchange`.
 */
class AndroidGoogleAuthProvider(private val activity: Activity) : GoogleAuthProvider {
    private val credentialManager: CredentialManager = CredentialManager.create(activity)
    private val executor: Executor = ContextCompat.getMainExecutor(activity)

    override val clientId: String = BuildConfig.GOOGLE_SERVER_CLIENT_ID.trim()

    override val isAvailable: Boolean
        get() = clientId.isNotBlank()

    @Volatile
    private var requestGen = 0

    private var cancellationSignal: CancellationSignal? = null

    override fun requestIdToken(nonce: String, onDone: (Result<String>) -> Unit) {
        cancel()
        val gen = ++requestGen
        val finish: (Result<String>) -> Unit = { result ->
            postToAppThread {
                if (gen != requestGen) return@postToAppThread
                requestGen += 1
                cancellationSignal = null
                onDone(result)
            }
        }
        if (clientId.isBlank()) {
            finish(Result.failure(GoogleSignInException.fromAndroidCode("init_failed")))
            return
        }
        onMain {
            if (gen != requestGen) return@onMain
            getGoogleId(gen, nonce, authorizedOnly = true, finish)
        }
    }

    override fun cancel() {
        requestGen += 1
        cancellationSignal?.cancel()
        cancellationSignal = null
    }

    override fun clearCredentialState() {
        onMain {
            try {
                credentialManager.clearCredentialStateAsync(
                    ClearCredentialStateRequest(),
                    null,
                    executor,
                    object : CredentialManagerCallback<Void?, ClearCredentialException> {
                        override fun onResult(result: Void?) {}
                        override fun onError(e: ClearCredentialException) {
                            Log.w(TAG, "clearCredentialState: ${e.javaClass.simpleName}")
                        }
                    },
                )
            } catch (t: Throwable) {
                Log.w(TAG, "clearCredentialState: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun getGoogleId(
        gen: Int,
        nonce: String,
        authorizedOnly: Boolean,
        finish: (Result<String>) -> Unit,
    ) {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(authorizedOnly)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(authorizedOnly)
            .setNonce(nonce)
            .build()
        getCredential(gen, option) { result ->
            if (gen != requestGen) return@getCredential
            val err = result.exceptionOrNull()
            if (authorizedOnly && err is NoCredentialException) {
                getSignInWithGoogle(gen, nonce, finish)
                return@getCredential
            }
            finish(mapCredentialResult(result))
        }
    }

    private fun getSignInWithGoogle(
        gen: Int,
        nonce: String,
        finish: (Result<String>) -> Unit,
    ) {
        val option = GetSignInWithGoogleOption.Builder(clientId)
            .setNonce(nonce)
            .build()
        getCredential(gen, option) { result ->
            if (gen != requestGen) return@getCredential
            finish(mapCredentialResult(result))
        }
    }

    private fun getCredential(
        gen: Int,
        option: CredentialOption,
        onDone: (Result<GetCredentialResponse>) -> Unit,
    ) {
        if (gen != requestGen) return
        val signal = CancellationSignal()
        cancellationSignal = signal
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        try {
            credentialManager.getCredentialAsync(
                activity,
                request,
                signal,
                executor,
                object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                    override fun onResult(result: GetCredentialResponse) {
                        onDone(Result.success(result))
                    }

                    override fun onError(e: GetCredentialException) {
                        onDone(Result.failure(e))
                    }
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "getCredential: ${t.javaClass.simpleName}")
            onDone(Result.failure(t))
        }
    }

    private fun mapCredentialResult(result: Result<GetCredentialResponse>): Result<String> =
        result.fold(
            onSuccess = { idTokenFrom(it) },
            onFailure = { Result.failure(mapException(it)) },
        )

    private fun idTokenFrom(response: GetCredentialResponse): Result<String> {
        val credential = response.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return try {
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                val token = google.idToken
                if (token.isBlank()) {
                    Result.failure(GoogleSignInException.fromAndroidCode("empty_credential"))
                } else {
                    Result.success(token)
                }
            } catch (_: GoogleIdTokenParsingException) {
                Result.failure(GoogleSignInException.fromAndroidCode("parse"))
            }
        }
        return Result.failure(GoogleSignInException.fromAndroidCode("empty_credential"))
    }

    private fun mapException(err: Throwable): Exception = when (err) {
        is GetCredentialCancellationException -> GoogleSignInException.fromAndroidCode("cancelled")
        is NoCredentialException -> GoogleSignInException.fromAndroidCode("no_credential")
        is GetCredentialInterruptedException -> GoogleSignInException.fromAndroidCode("interrupted")
        is GetCredentialProviderConfigurationException ->
            GoogleSignInException.fromAndroidCode("provider_config")
        is GetCredentialUnsupportedException -> GoogleSignInException.fromAndroidCode("unsupported")
        is GoogleSignInException -> err
        else -> GoogleSignInException.fromAndroidCode("unknown")
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            activity.runOnUiThread(action)
        }
    }

    private companion object {
        const val TAG = "ZjGoogleAuth"
    }
}
