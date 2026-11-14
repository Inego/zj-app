package inego.zungjunggame.render.online

import com.badlogic.gdx.graphics.Color
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.ui.AppPalette

/**
 * Round-trip to the game server, shown on Online home, the waiting card,
 * and the live-table HUD under the hand badge.
 *
 * The number is the last successful presence heartbeat — existing traffic,
 * not an extra ping — so a vertically scaled world server is not polled
 * beyond the 5 s keep-alive it already serves.
 */
object LatencyLabel {
    const val GOOD_MS = 80
    const val FAIR_MS = 160

    private val good = AppPalette.success
    private val fair = AppPalette.accent
    private val poor = AppPalette.danger

    data class HudPlacement(
        val left: Float,
        val baselineY: Float,
    )

    fun text(ms: Int): String = L10n.format("online.latency", ms)

    fun color(ms: Int): Color = when {
        ms < GOOD_MS -> good
        ms < FAIR_MS -> fair
        else -> poor
    }
}
