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
 *
 * A surface hands in what [inego.zungjunggame.net.PresenceClient.freshRttMs]
 * returned: a sample older than the presence staleness window is a dash, not a
 * number. Printing the dash here rather than leaving text out is what keeps the
 * slot in the hub header and in the HUD detail line from collapsing.
 */
object LatencyLabel {
    const val GOOD_MS = 80
    const val FAIR_MS = 160

    /** Printed when there is no sample to trust. */
    const val NO_SAMPLE = "\u2014"

    private val good = AppPalette.success
    private val fair = AppPalette.accent
    private val poor = AppPalette.danger
    private val missing = AppPalette.inkWarmMuted

    data class HudPlacement(
        val left: Float,
        val baselineY: Float,
    )

    fun text(ms: Int?): String =
        if (ms == null) NO_SAMPLE else L10n.format("online.latency", ms)

    fun color(ms: Int?): Color = when {
        ms == null -> missing
        ms < GOOD_MS -> good
        ms < FAIR_MS -> fair
        else -> poor
    }
}
