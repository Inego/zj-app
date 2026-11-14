package inego.zungjunggame.render.table

import inego.zungjunggame.platform.SafeArea
import inego.zungjunggame.render.seats.SeatSide

/**
 * The brighter felt panel under the **active** player's area — hand, drawn tile,
 * melds and seat label, on whichever side that seat sits.
 *
 * Follows [inego.zungjunggame.session.table.TableViewState.activeSeat], so it moves
 * with play. That is deliberately not the same question as "where is the human":
 * this answers *whose turn is it*, while the human's own area is always
 * [inego.zungjunggame.render.seats.SeatSide.Bottom]. During your own turn the two
 * coincide, which is exactly why a human-only version looked finished at first.
 *
 * ## Geometry: read the tiles, do not re-derive it
 *
 * [band] takes the seat's **measured tile bounds** — the world-space min/max over
 * that seat's hand, parked draw slot and melds, which
 * [inego.zungjunggame.render.table.PlayTableView.collectSeatSlots] already computes
 * for the tiles it is about to draw. Nothing here re-derives a rim, a half-span or a
 * reserved strip from `PlayTableView.TableLayout`, because those are the *inputs* to
 * that measurement, and re-deriving them is how the panel and the tiles drifted apart
 * in the first place.
 *
 * Across the seat's own edge the panel runs from the safe screen edge out past the
 * tiles, which is why the seat label needs no special handling: the label is drawn
 * in the gutter between that edge and the hand, so covering the gutter covers the
 * label. No font metric enters this file, and none is needed.
 *
 * On the other three sides — past the tiles toward the table, and off both ends of
 * the row — the air is one fifth of the tile height. That height is the bounds'
 * extent across the seat edge: the hand, the draw slot and the melds share one
 * center line, and the upright tiles set the extent.
 *
 * Geometry and colour are pure and GL-free, so the band can be asserted in a
 * plain JVM test. [ActiveSeatTriangle] paints the wash.
 */
object ActiveSeatFelt {

    /**
     * Fraction of white blended over the felt.
     *
     * `SpriteBatch` is `SrcAlpha, OneMinusSrcAlpha`: the quad's RGB is what the felt
     * lerps toward, and this is how far. White lightens. A near-black quad darkens,
     * which is what `AppPalette.feltDeep` did when it was treated as additive.
     *
     * Public so [wash] can be asserted without a GL context. That blend is the whole
     * difference between a brighter panel and a darker one.
     */
    const val WASH = 0.13f

    /**
     * The colour [draw] blends, as `(r, g, b, a)`.
     *
     * White at [WASH]: the felt's own hue is kept, since white has none to shift.
     * Exposed so the blend is testable — the bug it guards was invisible to every
     * geometry assertion.
     */
    fun wash(alpha: Float = 1f): FloatArray =
        floatArrayOf(1f, 1f, 1f, WASH * alpha)

    /**
     * Air past the tiles toward the table, and off both ends of the row, as a
     * fraction of the upright tile's height. The seat's own screen edge is not
     * padded: the panel runs out to it.
     */
    private const val AIR_OF_TILE_HEIGHT = 1f / 5f

    /**
     * The panel for [side] in world coordinates `(x, y, width, height)`, or null when
     * there is nothing to draw.
     *
     * [bounds] is the seat's measured tile extent as `minX, minY, maxX, maxY` — the
     * hand row, the parked draw slot and any melds, and **not** the discard river,
     * which belongs to the table rather than to the seat. Each panel therefore covers
     * only its own seat's area, which is what stops a Left panel merging with the
     * Bottom one at the corner.
     */
    fun band(
        side: SeatSide,
        screenW: Float,
        screenH: Float,
        bounds: FloatArray,
        safe: SafeArea.Insets,
    ): FloatArray? {
        if (screenW <= 0f || screenH <= 0f || bounds.size < 4) return null
        val minX = bounds[0]
        val minY = bounds[1]
        val maxX = bounds[2]
        val maxY = bounds[3]
        // An empty or inverted measurement draws nothing rather than a stray quad.
        if (maxX < minX || maxY < minY) return null

        val vertical = side == SeatSide.Left || side == SeatSide.Right
        // Across-edge extent of tiles that share one center line: the upright tile height.
        val tileHeight = if (vertical) maxX - minX else maxY - minY
        val air = tileHeight * AIR_OF_TILE_HEIGHT
        // Across the seat's own edge the panel runs between two things: the safe
        // screen edge the seat sits against, and one fifth of a tile height past
        // the tiles' face toward the table centre. Bottom and Left grow *in* from
        // the near screen edge, so their
        // extent is `inner - outer`; Opposite and Right grow *out* from a far tile
        // face to the opposite screen edge, so theirs is `outer - inner`. Collapsing
        // that distinction puts the Opposite and Right panels in the wrong half of
        // the frame, which is a bug this geometry previously had.
        val outer: Float
        val inner: Float
        val innerIsFar: Boolean
        when (side) {
            SeatSide.Bottom -> {
                outer = safe.bottom.coerceAtLeast(0f)
                inner = maxY + air
                innerIsFar = true
            }
            SeatSide.Opposite -> {
                outer = screenH - safe.top.coerceAtLeast(0f)
                inner = minY - air
                innerIsFar = false
            }
            SeatSide.Left -> {
                outer = safe.left.coerceAtLeast(0f)
                inner = maxX + air
                innerIsFar = true
            }
            SeatSide.Right -> {
                outer = screenW - safe.right.coerceAtLeast(0f)
                inner = minX - air
                innerIsFar = false
            }
        }
        val depth = if (innerIsFar) inner - outer else outer - inner
        if (depth <= 0f) return null

        // Along the seat's own edge, the panel is exactly the tiles' extent plus air.
        val alongNear = (if (vertical) minY else minX) - air
        val alongFar = (if (vertical) maxY else maxX) + air
        val alongScreen = if (vertical) screenH else screenW
        val left = alongNear.coerceAtLeast(0f)
        val length = (alongFar.coerceAtMost(alongScreen) - left).coerceAtLeast(0f)
        if (length <= 0f) return null

        val origin = minOf(outer, inner).coerceAtLeast(0f)
        return if (vertical) {
            // A side seat's hand is a vertical strip, so its panel is one too: a Left
            // panel and a Bottom panel then meet at the corner without merging.
            floatArrayOf(origin, left, depth.coerceAtMost(screenW - origin), length)
        } else {
            floatArrayOf(left, origin, length, depth.coerceAtMost(screenH - origin))
        }
    }
}
