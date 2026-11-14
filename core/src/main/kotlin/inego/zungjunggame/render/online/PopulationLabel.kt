package inego.zungjunggame.render.online

import com.badlogic.gdx.graphics.Color
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.PresenceSnapshot
import inego.zungjunggame.render.ui.AppPalette

/**
 * Formats the player population count line ("X online · Y playing · Z waiting"),
 * shared by the Online hub's Live tab and the Waiting panel ([OnlineConnectOverlay]).
 */
object PopulationLabel {
    val defaultColor = AppPalette.inkWarmMuted

    fun text(online: Int, playing: Int, waiting: Int): String =
        L10n.format("online.live.population", online, playing, waiting)

    fun text(snapshot: PresenceSnapshot?): String? =
        snapshot?.let { text(it.online, it.playing, it.waiting) }
}
