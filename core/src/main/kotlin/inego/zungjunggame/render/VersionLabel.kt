package inego.zungjunggame.render

import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.AppVersion
import inego.zungjunggame.platform.SafeArea
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.AppPalette
import inego.zungjunggame.render.ui.OverlayViewport

/**
 * Main-menu felt stamp: [AppVersion.LABEL] in the bottom-right safe corner.
 *
 * Drawn on the board layer (behind the menu card). Not a hit target.
 */
object VersionLabel {
    private val ink = AppPalette.inkMuted

    data class Placement(
        val left: Float,
        val baselineY: Float,
    )

    fun text(): String = AppVersion.LABEL

    /**
     * Bottom-right of the felt, inside [safe] plus [marginPx].
     *
     * [baselineY] is the libGDX font baseline (y of the top of capital letters
     * is roughly this value).
     */
    fun placement(
        screenW: Float,
        textW: Float,
        textH: Float,
        safe: SafeArea.Insets,
        marginPx: Float,
    ): Placement {
        val right = (screenW - safe.right - marginPx).coerceAtLeast(marginPx)
        val left = (right - textW).coerceAtLeast(safe.left + marginPx)
        val baselineY = safe.bottom + marginPx + textH
        return Placement(left, baselineY)
    }

    fun render(batch: SpriteBatch, screenW: Float, screenH: Float) {
        if (screenW <= 0f || screenH <= 0f) return
        Fonts.ensureCreated()
        val label = text()
        val font = Fonts.body
        val textW = Fonts.textWidth(font, label)
        val textH = Fonts.textHeight(font, label)
        val margin = Fonts.px(OverlayViewport.MARGIN_DP)
        val place = placement(screenW, textW, textH, SafeArea.current(), margin)
        Fonts.drawText(batch, font, label, place.left, place.baselineY, ink)
    }
}
