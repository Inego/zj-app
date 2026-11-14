package inego.zungjunggame.features.tutorial

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.render.TableFelt
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.AppPalette
import kotlin.math.max

/**
 * The board-overlay scrim.
 *
 * A stacked coach keeps its copy above the table — the board is handed the pane
 * below the copy band — so the top dim covers the coach's own felt and the
 * bottom dim covers the reserved Back / Next band; the table between them stays
 * clear. A side layout dims its copy column instead and leaves the board pane
 * untouched.
 */
internal class CoachCopyScrim {

    /** Board-overlay veil behind the coach copy. */
    private val copyDim = AppPalette.feltScrim

    /** Edge of the side column, where the board pane begins. */
    private val veilEdge = AppPalette.veil

    /** Dim bands over the live board: the copy band and the button band. */
    fun drawFeltDim(
        batch: SpriteBatch,
        px: Texture,
        screenW: Float,
        screenH: Float,
        copyScrimBottom: Float,
        buttonBandTop: Float,
    ) {
        // Dim only the laid-out title and body, and the band around the
        // Back / Next row. The table between them stays clear.
        // Dense enough that seat labels and tile faces do not compete with
        // the coach copy, still translucent so the board stays present.
        batch.setColor(copyDim)
        val copyScrimH = screenH - copyScrimBottom
        if (copyScrimH > 0.5f) {
            batch.draw(px, 0f, copyScrimBottom, screenW, copyScrimH)
        }
        // The live table keeps its hand above this band, so the scrim
        // stops there and does not darken the tiles.
        batch.draw(px, 0f, 0f, screenW, buttonBandTop)
        batch.setColor(Color.WHITE)
    }

    /** Opaque felt, for a beat that owns the whole table. */
    fun drawSolidFelt(batch: SpriteBatch, px: Texture, screenW: Float, screenH: Float) {
        batch.setColor(TableFelt.color)
        batch.draw(px, 0f, 0f, screenW, screenH)
        batch.setColor(Color.WHITE)
    }

    /**
     * Side-layout veil: the copy column carries the felt and the dim, and a
     * vertical edge marks where the board pane begins.
     *
     * The column sits *beside* the table rather than over it, so it lays its own
     * felt first — the same base the table paints — and dims that. Nothing below
     * the copy needs a band: the column runs the full height.
     */
    fun drawColumnDim(
        batch: SpriteBatch,
        px: Texture,
        screenW: Float,
        screenH: Float,
        columnLeft: Float,
    ) {
        val left = columnLeft.coerceIn(0f, screenW)
        val w = (screenW - left).coerceAtLeast(0f)
        if (w > 0.5f) {
            batch.setColor(TableFelt.color)
            batch.draw(px, left, 0f, w, screenH)
            batch.setColor(copyDim)
            batch.draw(px, left, 0f, w, screenH)
            batch.setColor(veilEdge)
            val lineW = max(1.5f, Fonts.px(2f))
            batch.draw(px, left - lineW * 0.5f, 0f, lineW, screenH)
        }
        batch.setColor(Color.WHITE)
    }
}
