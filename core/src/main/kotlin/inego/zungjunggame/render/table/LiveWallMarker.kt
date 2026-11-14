package inego.zungjunggame.render.table

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.AppPalette
import inego.zungjunggame.render.tiles.TileDrawer

/**
 * Center-table live-wall stock: up to three closed backs, 1.5× a hand tile,
 * with the remaining drawable count on the front card.
 *
 * The front card is the next tile taken from the wall. [TileBoard] flies that
 * visual from the front pose to its slot; this marker draws only the cards
 * behind it, steps them forward, and fades a new back in at the deepest slot
 * while three or more tiles remain.
 *
 * Each card behind the front sits left and up of the one in front of it.
 */
class LiveWallMarker(
    private val drawer: TileDrawer,
) {
    private val stack = LiveWallStack()
    private val glyphLayout = GlyphLayout()

    fun reset() {
        stack.reset()
    }

    /** True when one tile has left the live wall since the previous sync. */
    fun willDepartOne(liveWallSize: Int): Boolean = stack.willDepartOne(liveWallSize)

    fun sync(liveWallSize: Int, spawnedFromWall: Int) {
        stack.sync(liveWallSize, spawnedFromWall)
    }

    fun advance(dt: Float, durationSec: Float) {
        stack.advance(dt, durationSec)
    }

    /** True while the cards behind a take are still moving into place. */
    fun isShifting(): Boolean = stack.isShifting()

    /**
     * World Y of the top of the remaining-count text, at the highest pose the
     * count can take: the card stepping from behind into the front during a
     * take. The status banner may descend over the card backs above this line,
     * but not past it — [render] draws the number upward from the card centre,
     * so this is the highest ink the count can reach.
     *
     * [labelScale] is the table's shared felt-label scale; the anchor must use
     * the same face size [render] paints, or the banner would reserve the
     * desktop count's height over a smaller number.
     */
    fun countTopY(cy: Float, handH: Float, labelScale: Float = 1f): Float {
        Fonts.ensureCreated()
        val th = handH * TILE_SCALE
        val glyphH = Fonts.withFaceScale(Fonts.wallCount, labelScale) {
            glyphLayout.setText(Fonts.wallCount, "0")
            glyphLayout.height
        }
        return cy + th * STACK_OFFSET_Y_FRAC + glyphH * 0.5f
    }

    /**
     * @param handW hand tile width (marker tiles are [TILE_SCALE] larger)
     * @param handH hand tile height
     * @param liveWallSize remaining drawable tiles in the live wall
     * @param alpha overall opacity (handoff fade-in); ≤0 skips draw
     * @param highlight accent outline around the stack (tutorial focus)
     * @param labelScale shared felt-label scale for the count, so it shrinks with
     *        the tiles and the rest of the table's fixed labels
     */
    fun render(
        batch: SpriteBatch,
        liveWallSize: Int,
        cx: Float,
        cy: Float,
        handW: Float,
        handH: Float,
        alpha: Float = 1f,
        highlight: Boolean = false,
        labelScale: Float = 1f,
    ) {
        if (alpha < 0.01f) return
        Fonts.ensureCreated()

        val tw = handW * TILE_SCALE
        val th = handH * TILE_SCALE
        val cards = stack.cards()
        // Back → front so the count sits on the foremost card.
        for ((depth, alpha1) in cards.sortedByDescending { it.depth }) {
            val (tcx, tcy) = cardCenter(cx, cy, tw, th, depth)
            val shade = 1f - depth * 0.06f
            batch.setColor(shade, shade, shade * 0.98f, alpha * alpha1)
            drawer.drawClosed(
                batch,
                tcx,
                tcy,
                tw,
                th,
                rotationDeg = 0f,
            )
            batch.setColor(Color.WHITE)
        }

        val front = cards.filter { it.alpha > 0.5f }.minByOrNull { it.depth }
        if (highlight && front != null) {
            val (hx, hy) = cardCenter(cx, cy, tw, th, front.depth)
            drawer.drawGlowOutline(
                batch,
                hx,
                hy,
                tw,
                th,
                rotationDeg = 0f,
                color = AppPalette.tint(AppPalette.liveMarker, 0.85f * alpha),
            )
        }

        // Count rides the card stepping into the front. Bare center when the stack is empty.
        // Whole-pixel glyph locking holds that number still through the last fraction
        // of the slide, then ticks it onto the seat. The card is already subpixel.
        val labelDepth = front?.depth
        val (lx, ly) = if (labelDepth == null) {
            cx to cy
        } else {
            cardCenter(cx, cy, tw, th, labelDepth)
        }
        val font = Fonts.wallCount
        val label = liveWallSize.coerceAtLeast(0).toString()
        Fonts.withFaceScale(font, labelScale) {
            glyphLayout.setText(font, label)
            val prev = font.color.cpy()
            val lockGlyphs = font.usesIntegerPositions()
            font.color = Color(prev.r, prev.g, prev.b, alpha)
            font.setUseIntegerPositions(false)
            try {
                font.draw(
                    batch,
                    label,
                    lx - glyphLayout.width * 0.5f,
                    ly + glyphLayout.height * 0.5f,
                )
            } finally {
                font.setUseIntegerPositions(lockGlyphs)
                font.color = prev
            }
        }
    }

    private fun cardCenter(cx: Float, cy: Float, tw: Float, th: Float, depth: Float): Pair<Float, Float> {
        val ox = tw * STACK_OFFSET_X_FRAC
        val oy = th * STACK_OFFSET_Y_FRAC
        return (cx - depth * ox) to (cy + depth * oy)
    }

    companion object {
        /** Marker tile size relative to a hand tile. */
        const val TILE_SCALE = 1.5f

        /** Left shift per layer behind the front (fraction of tile width). */
        private const val STACK_OFFSET_X_FRAC = 0.14f

        /** Up shift per layer behind the front (fraction of tile height). */
        private const val STACK_OFFSET_Y_FRAC = 0.12f
    }
}
