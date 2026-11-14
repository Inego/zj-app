package inego.zungjunggame.platform

/** User dismissed the Google overlay. Not an auth failure. */
class GoogleSignInCancelled : RuntimeException("Google Sign-In cancelled")

/**
 * Failure obtaining a Google ID token. [retryable] maps to
 * [inego.zungjunggame.online.AuthError.retryable].
 */
class GoogleSignInException(
    message: String,
    val retryable: Boolean,
    val code: String? = null,
) : RuntimeException(message) {
    companion object {
        fun fromGisCode(code: String): Exception = when (code) {
            "cancelled", "user_cancel" -> GoogleSignInCancelled()
            "script_load", "timeout" -> GoogleSignInException(
                "Could not load Google Sign-In. Check your connection and try again.",
                retryable = true,
                code = code,
            )
            "insecure" -> GoogleSignInException(
                "Google Sign-In requires HTTPS.",
                retryable = false,
                code = code,
            )
            "init_failed" -> GoogleSignInException(
                "Google Sign-In is not available for this site.",
                retryable = false,
                code = code,
            )
            "popup_blocked" -> GoogleSignInException(
                "The Google sign-in window was blocked. Allow pop-ups and try again.",
                retryable = true,
                code = code,
            )
            "empty_credential", "button_failed", "fedcm_failed" -> GoogleSignInException(
                "Google Sign-In failed. Try again.",
                retryable = true,
                code = code,
            )
            else -> GoogleSignInException(
                "Google Sign-In failed. Try again.",
                retryable = true,
                code = code.ifBlank { null },
            )
        }

        /**
         * Map Android Credential Manager failures. Codes are the provider's
         * own names, not GIS codes; [fromGisCode] stays web-only.
         */
        fun fromAndroidCode(code: String): Exception = when (code) {
            "cancelled", "user_cancel" -> GoogleSignInCancelled()
            "no_credential" -> GoogleSignInException(
                "No Google account is available. Try again.",
                retryable = true,
                code = code,
            )
            "interrupted" -> GoogleSignInException(
                "Google Sign-In was interrupted. Try again.",
                retryable = true,
                code = code,
            )
            "provider_config" -> GoogleSignInException(
                "Google Sign-In requires Google Play services. Try again.",
                retryable = true,
                code = code,
            )
            "unsupported", "init_failed" -> GoogleSignInException(
                "Google Sign-In is not available on this device.",
                retryable = false,
                code = code,
            )
            "empty_credential", "parse" -> GoogleSignInException(
                "Google Sign-In failed. Try again.",
                retryable = true,
                code = code,
            )
            else -> GoogleSignInException(
                "Google Sign-In failed. Try again.",
                retryable = true,
                code = code.ifBlank { null },
            )
        }

        /**
         * Map desktop loopback / PKCE failures. Codes are this provider's
         * names, not GIS or Android codes.
         */
        fun fromDesktopCode(code: String): Exception = when (code) {
            "cancelled", "user_cancel", "access_denied" -> GoogleSignInCancelled()
            "timeout" -> GoogleSignInException(
                "Google Sign-In timed out. Try again.",
                retryable = true,
                code = code,
            )
            "browser" -> GoogleSignInException(
                "Could not open a browser for Google Sign-In. Try again.",
                retryable = true,
                code = code,
            )
            "network", "token" -> GoogleSignInException(
                "Could not reach Google. Check your connection and try again.",
                retryable = true,
                code = code,
            )
            "unsupported", "init_failed" -> GoogleSignInException(
                "Google Sign-In is not available on this computer.",
                retryable = false,
                code = code,
            )
            "empty_credential", "parse", "state" -> GoogleSignInException(
                "Google Sign-In failed. Try again.",
                retryable = true,
                code = code,
            )
            else -> GoogleSignInException(
                "Google Sign-In failed. Try again.",
                retryable = true,
                code = code.ifBlank { null },
            )
        }
    }
}
