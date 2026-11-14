package inego.zungjunggame.render.table

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.session.table.TableViewState
import inego.zungjunggame.render.ui.AppPalette

/**
 * Notice displayed above the player's hand when they draw a tile from the wall that forms
 * a patternless (chicken) hand in a game where chicken hands are disabled.
 */
object PatternlessNotice {
    const val KEY_TEXT = "table.patternless_hand_disallowed"

    val COLOR: Color = AppPalette.tint(AppPalette.accent, 0.95f)

    /**
     * GL-free predicate: true if the notice should be displayed for [view] under [allowChickenHands].
     */
    fun isApplicable(
        view: TableViewState,
        allowChickenHands: Boolean,
    ): Boolean {
        return !allowChickenHands && view.isHumanDrawnPatternlessHand
    }

    /**
     * GL-free text for this notice.
     */
    fun text(): String = L10n.get(KEY_TEXT)

    /**
     * Draw the notice centered above the hand tiles.
     *
     * [scale] is the table's shared felt-label scale (see
     * [inego.zungjunggame.render.seats.SeatLabelDrawer.tableLabelScale]); the
     * notice is felt copy and shrinks with the tiles like the seat labels.
     */
    fun draw(
        batch: SpriteBatch,
        font: BitmapFont,
        handCenterX: Float,
        bottomY: Float,
        screenW: Float,
        scale: Float = 1f,
    ) {
        val message = text()
        Fonts.withFaceScale(font, scale) {
            val textW = Fonts.textWidth(font, message)
            val textH = Fonts.textHeight(font, message)
            val padX = Fonts.px(12f)
            val targetX = handCenterX - textW * 0.5f
            val clampedX = targetX.coerceIn(padX, (screenW - textW - padX).coerceAtLeast(padX))
            val y = bottomY + textH
            Fonts.drawText(batch, font, message, clampedX, y, COLOR)
        }
    }
}
