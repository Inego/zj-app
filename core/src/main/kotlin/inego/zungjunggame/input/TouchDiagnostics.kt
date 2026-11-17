package inego.zungjunggame.input

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import kotlin.random.Random

/**
 * Android-only diagnostics (Honor often hides app logcat).
 *
 * Covers touch/lifecycle and wall-build shuffle heartbeats (`shuffle start|hb|SLOW|stop`).
 *
 * logcat filter: `adb logcat -s ZjTouch`
 * File mirror: app private [TOUCH_LOG_FILE]
 * Pull: `adb exec-out run-as inego.zungjunggame cat files/zj_touch.log`
 * Desktop never writes this file (would land under assets/ with the LWJGL backend).
 */
object TouchDiagnostics {
    private const val TOUCH_LOG = "ZjTouch"
    private const val TOUCH_LOG_FILE = "zj_touch.log"

    /**
     * Names this runtime and orders its lines.
     *
     * A Wasm build runs once per browser tab, and two tabs write the same
     * `ZjTouch:` prefix into two consoles that look identical when pasted. The
     * tag tells the tabs apart; the counter tells a reader whether a line was
     * written now or was already in the scrollback.
     *
     * Seeded explicitly: TeaVM's Wasm backend has no
     * `java.util.concurrent.ThreadLocalRandom`, and the unseeded
     * `Random.Default` is what reaches for it. A seeded [Random] is pure Kotlin.
     */
    private val contextTag: String =
        Random(System.nanoTime()).nextInt(0x1000000).toString(16).padStart(6, '0')
    private var sequence = 0

    fun log(message: String) {
        val app = Gdx.app ?: return
        val tagged = "[$contextTag ${++sequence}] $message"
        app.log(TOUCH_LOG, tagged)
        // File mirror is Android-only — Gdx.files.local would land under assets/ on desktop.
        if (app.type != Application.ApplicationType.Android) return
        try {
            val line = "${System.currentTimeMillis()} $tagged\n"
            Gdx.files?.local(TOUCH_LOG_FILE)?.writeString(line, /* append = */ true)
        } catch (_: Exception) {
            // Never let diagnostics break gameplay.
        }
    }

    fun fmt(v: Float): String =
        if (v.isNaN()) "NaN" else "%.1f".format(v)

    /** Names what a release landed on: a control, a layer's chrome, or nothing. */
    fun describeHit(hit: ResolvedHit?): String = when (hit) {
        is ResolvedHit.On -> "on:${hit.instance.route.key.value}:${hit.action.id}"
        is ResolvedHit.Outside -> "outside:${hit.instance.route.key.value}"
        null -> "none"
    }

    /**
     * World + raw screen pointer sample appended to every gate line.
     *
     * [layer] is the top route key: the stack belongs to the router, so the caller
     * that owns the stack supplies it rather than this formatter reaching into it.
     */
    fun tapPrefix(x: Float, y: Float, layer: String): String {
        val graphics = Gdx.graphics
        val input = Gdx.input
        val sx = input?.let { fmt(it.x.toFloat()) } ?: "?"
        val sy = input?.let { fmt(it.y.toFloat()) } ?: "?"
        return "wx=${fmt(x)} wy=${fmt(y)} sx=$sx sy=$sy " +
            "screen=${graphics?.width ?: 0}x${graphics?.height ?: 0} layer=$layer"
    }
}
