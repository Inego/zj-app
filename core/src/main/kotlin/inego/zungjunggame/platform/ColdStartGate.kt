package inego.zungjunggame.platform

/**
 * Bridges Android system splash → game bootstrap.
 *
 * Android keeps the splash on screen while [isContentReady] is false
 * ([SplashScreen.setKeepOnScreenCondition]). [Main] sets ready after the first
 * real UI frame is drawn so the splash does not peel off onto empty felt.
 *
 * Desktop ignores this (no system splash).
 */
object ColdStartGate {
    @Volatile
    private var contentReady: Boolean = false

    @JvmStatic
    fun isContentReady(): Boolean = contentReady

    /** Call at process / activity cold start before bootstrap. */
    @JvmStatic
    fun reset() {
        contentReady = false
    }

    /** Call once the first menu/session frame has been submitted. */
    @JvmStatic
    fun markContentReady() {
        contentReady = true
    }
}
