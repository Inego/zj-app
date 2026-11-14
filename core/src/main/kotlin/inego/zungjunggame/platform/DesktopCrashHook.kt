package inego.zungjunggame.platform

/**
 * Desktop (lwjgl3) installs this so GL-thread [com.badlogic.gdx.Gdx.app.postRunnable]
 * failures write [last-crash.txt] *before* GLFW teardown, which can abort natively
 * and skip the launcher's catch.
 *
 * Other backends leave [onThrowable] null.
 */
object DesktopCrashHook {
    @Volatile
    var onThrowable: ((thread: Thread, error: Throwable, where: String) -> Unit)? = null

    fun report(where: String, error: Throwable) {
        try {
            onThrowable?.invoke(Thread.currentThread(), error, where)
        } catch (_: Throwable) {
            // Never let diagnostics hide the original failure.
        }
    }

    inline fun run(where: String, action: () -> Unit) {
        try {
            action()
        } catch (t: Throwable) {
            report(where, t)
            throw t
        }
    }
}
