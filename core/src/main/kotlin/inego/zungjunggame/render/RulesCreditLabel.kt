package inego.zungjunggame.render

import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.platform.SafeArea
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.AppPalette
import inego.zungjunggame.render.ui.OverlayViewport

/**
 * Main-menu felt stamp: the rules/scoring attribution, right-aligned in the
 * bottom-right safe corner one line above the [VersionLabel] stamp.
 *
 * Drawn on the board layer (behind the menu card). Not a hit target.
 */
object RulesCreditLabel {
    private const val KEY = "menu.rules_credit"
    private val ink = AppPalette.inkMuted

    fun text(): String = L10n.get(KEY)

    fun render(batch: SpriteBatch, screenW: Float, screenH: Float) {
        if (screenW <= 0f || screenH <= 0f) return
        Fonts.ensureCreated()
        val label = text()
        val font = Fonts.small
        val versionFont = Fonts.body
        val textW = Fonts.textWidth(font, label)
        val versionH = Fonts.textHeight(versionFont, VersionLabel.text())
        val margin = Fonts.px(OverlayViewport.MARGIN_DP)
        val place = VersionLabel.placement(screenW, textW, versionH, SafeArea.current(), margin)
        val gap = Fonts.px(6f)
        Fonts.drawText(batch, font, label, place.left, place.baselineY + versionH + gap, ink)
    }
}
