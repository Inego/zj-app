package inego.zungjunggame

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx

/**
 * Cold-start timing diagnostics (Honor often redacts app logcat).
 *
 * logcat filter (when not redacted): `adb logcat -s ZjBoot`
 * File mirror: app private [BOOT_LOG_FILE]
 * Pull: `adb exec-out run-as inego.zungjunggame cat files/zj_boot.log`
 * Desktop: log only (no file under assets/).
 */
object BootDiagnostics {
    private const val TAG = "ZjBoot"
    private const val BOOT_LOG_FILE = "zj_boot.log"

    private val t0Ns = System.nanoTime()
    private var seq = 0

    /** Milliseconds since first class touch (process-local). */
    fun elapsedMs(): Long = (System.nanoTime() - t0Ns) / 1_000_000L

    fun mark(step: String, detail: String = "") {
        seq += 1
        val extra = if (detail.isEmpty()) "" else " $detail"
        val line = "#$seq +${elapsedMs()}ms $step$extra"
        // Prefer Gdx when available; fall back so AndroidLauncher can mark earlier.
        try {
            val app = Gdx.app
            if (app != null) {
                app.log(TAG, line)
                if (app.type == Application.ApplicationType.Android) {
                    try {
                        Gdx.files.local(BOOT_LOG_FILE).writeString(line + "\n", /* append = */ true)
                    } catch (_: Exception) {
                        // Never let diagnostics break boot.
                    }
                }
                return
            }
        } catch (_: Throwable) {
            // Gdx not ready.
        }
        // Pre-Gdx or headless: System.out still helps desktop / Studio.
        println("$TAG: $line")
    }

    /** Truncate the boot log at a new cold start so pulls stay readable. */
    fun resetFile() {
        try {
            val app = Gdx.app ?: return
            if (app.type != Application.ApplicationType.Android) return
            Gdx.files.local(BOOT_LOG_FILE).writeString(
                "--- cold start ${System.currentTimeMillis()} ---\n",
                /* append = */ false,
            )
        } catch (_: Exception) {
            // ignore
        }
    }
}
