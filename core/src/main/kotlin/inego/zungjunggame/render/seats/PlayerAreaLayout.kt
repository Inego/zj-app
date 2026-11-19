package inego.zungjunggame.render.seats

import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.PlayerState
import inego.zungjunggame.session.table.MeldGroup
import inego.zungjunggame.session.table.SeatView
import inego.zungjunggame.session.table.expandMelds
import kotlin.math.abs
import kotlin.math.max

/**
 * Pure layout: expands a [inego.zungjunggame.engine.PlayerState] (+ discard river) into local tile placements
 * for one seat.
 *
 * Local coordinates: origin at the midpoint of the **full visible** hand row
 * (sorted concealed tiles + reserved parked wall-draw slot + melds); +x along the
 * seat edge (player's right); +y toward the table center. Tile positions are centers.
 *
 * Within that centered block, open melds sit on the right and a parked wall-draw
 * sits just left of the melds (right of the sorted hand). Waiting hands (concealed
 * count ≡ 1 mod 3: 13, 10, …) keep that parked-draw width even while the slot is
 * empty, so the existing tiles do not slide when the wall tile arrives.
 *
 * Open melds rotate one tile 90° and place it on the end facing the discarder
 * (left / middle / right of the group from the owner's view). Concealed kongs
 * use the classic outer-closed, middle-open faces.
 */
object PlayerAreaLayout {

    /** Extra rotation (CCW degrees) relative to the seat's upright orientation. */
    const val SIDEWAYS_ROTATION_DEG = 90f

    /** Region of a placement within a seat area (for animation slot keys). */
    enum class Region {
        Hand,
        Drawn,
        Meld,
        River,
    }

    data class TilePlacement(
        val lx: Float,
        val ly: Float,
        val open: Boolean,
        /** Engine tile type when [open]; ignored when closed. */
        val tileType: Int = -1,
        val w: Float,
        val h: Float,
        /**
         * True for a face-up hand tile the human may click to discard.
         * Melds and river tiles stay false.
         */
        val selectableHand: Boolean = false,
        /** True when this is the parked newly-drawn tile to the right of the hand. */
        val isDrawnSlot: Boolean = false,
        /**
         * Added to the seat's base [SeatSide.rotationDeg] when drawing.
         * Sideways claim tiles use [SIDEWAYS_ROTATION_DEG].
         */
        val rotationOffsetDeg: Float = 0f,
        /** Seat-area region for stable animation matching. */
        val region: Region = Region.Hand,
        /** Index within hand/river, or meld group index. */
        val regionIndex: Int = 0,
        /** Tile index within a meld group. */
        val regionSubIndex: Int = 0,
        /**
         * The reserved empty draw slot on a waiting hand. It is part of the seat's
         * area, and it is not a tile: nothing reconciles it onto the board.
         */
        val emptyDrawReservation: Boolean = false,
    )

    data class Metrics(
        val handTileW: Float,
        val handTileH: Float,
        val discardTileW: Float,
        val discardTileH: Float,
        val tileGap: Float,
        val meldGap: Float,
        val rowGap: Float,
        val discardsPerRow: Int,
        val maxDiscardRows: Int,
        /** Extra gap between sorted hand and the drawn tile on the right. */
        val drawnGap: Float = 0f,
    ) {
        /** Uniformly scales tile sizes and gaps (row capacity fields unchanged). */
        fun scaled(factor: Float): Metrics {
            if (factor == 1f) return this
            return copy(
                handTileW = handTileW * factor,
                handTileH = handTileH * factor,
                discardTileW = discardTileW * factor,
                discardTileH = discardTileH * factor,
                tileGap = tileGap * factor,
                meldGap = meldGap * factor,
                rowGap = rowGap * factor,
                drawnGap = drawnGap * factor,
            )
        }
    }

    /** Normalized layout input shared by local and privacy-preserving views. */
    private data class LayoutInput(
        val sortedHand: List<Int>,
        val parkedDrawn: Int,
        val hasDrawnSlot: Boolean,
        val melds: List<MeldGroup>,
        val river: List<Int>,
        val handFacesAvailable: Boolean,
        val drawnFacesAvailable: Boolean,
    )

    /**
     * Distance from the local origin to the outer edge of [p] along the seat
     * edge (+x). Sideways claim tiles use height as their along-row footprint.
     */
    fun alongRowHalfExtent(p: TilePlacement): Float {
        val halfFoot =
            if (p.rotationOffsetDeg != 0f) p.h * 0.5f else p.w * 0.5f
        return abs(p.lx) + halfFoot
    }

    /**
     * Max [alongRowHalfExtent] over [placements], or 0 if empty.
     * The seat row fits in local x ∈ [−span, +span] when this is [span].
     */
    fun alongRowHalfSpan(placements: List<TilePlacement>): Float {
        var span = 0f
        for (p in placements) {
            span = max(span, alongRowHalfExtent(p))
        }
        return span
    }

    /** A seat's usable span along its edge, and the centre of that span. */
    data class EdgeSpan(val half: Float, val center: Float)

    /**
     * Usable span along a seat edge, given the screen size parallel and
     * perpendicular to that edge.
     *
     * - Seats on the **shorter** side may use the full edge (`screenAlong/2 − pad`),
     *   less [alongFloor] at the low end. On a wide window that is left/right; on
     *   a tall window, bottom/top.
     * - Seats on the **longer** side (and both on a square) live in the felt
     *   between the two hands that face them: [nearEdge] and [farEdge] are the
     *   inner edges of those hands, measured along this edge. The row centres on
     *   that band, not on the raw pane — the bottom hand is pushed up by its
     *   chrome, so the band's centre is not the pane's centre.
     *
     * [screenAlong] / [screenOrtho]: width & height for bottom/top, height & width
     * for left/right. [alongFloor] is chrome reserved at the low end of this edge
     * (the tutorial coach's Back / Next band under a live board); a side seat must
     * end above it, not slide into it. [alongCeiling] is the matching reserve at
     * the high end (the corner menu where it reaches into a side hand).
     */
    fun availableAlongEdgeSpan(
        screenAlong: Float,
        screenOrtho: Float,
        nearEdge: Float,
        farEdge: Float,
        pad: Float = 0f,
        alongFloor: Float = 0f,
        alongCeiling: Float = 0f,
    ): EdgeSpan {
        // Shorter side keeps the edge down to the space above reserved chrome;
        // the longer side yields to the hands at both ends.
        if (screenAlong < screenOrtho) {
            val lo = (alongFloor + pad).coerceIn(0f, screenAlong)
            val hi = (screenAlong - max(pad, alongCeiling)).coerceAtLeast(lo + 1f)
            return EdgeSpan(
                half = ((hi - lo) * 0.5f).coerceAtLeast(1f),
                center = (lo + hi) * 0.5f,
            )
        }
        val lo = nearEdge.coerceIn(0f, screenAlong)
        val hi = farEdge.coerceIn(lo, screenAlong)
        return EdgeSpan(
            half = ((hi - lo) * 0.5f - pad).coerceAtLeast(1f),
            center = (lo + hi) * 0.5f,
        )
    }

    /**
     * Like [layout], but shrinks tile metrics just enough that the seat row
     * (hand, melds, river) fits in local x ∈ [−maxHalfSpan, +maxHalfSpan].
     * Used when a seat's along-edge budget is tight (e.g. short-side seats, or
     * long-side seats after orthogonal strips are reserved).
     *
     * Fit scale measures the laid-out row, which already reserves parked-draw
     * width on waiting hands. That keeps tile size stable across draw→discard
     * and prevents the real parked tile from overflowing when it appears.
     */
    fun layoutFittingAlongEdge(
        player: PlayerState,
        river: List<Int>,
        showHandFaces: Boolean,
        metrics: Metrics,
        maxHalfSpan: Float,
        ownerSeat: Int = 0,
        drawnTile: Int = -1,
        handSelectable: Boolean = false,
    ): List<TilePlacement> {
        val probe = layout(
            player = player,
            river = river,
            showHandFaces = showHandFaces,
            metrics = metrics,
            ownerSeat = ownerSeat,
            drawnTile = drawnTile,
            handSelectable = handSelectable,
        )
        if (maxHalfSpan <= 0f) return probe
        val span = alongRowHalfSpan(probe)
        if (span <= maxHalfSpan) return probe
        return layout(
            player = player,
            river = river,
            showHandFaces = showHandFaces,
            metrics = metrics.scaled(maxHalfSpan / span),
            ownerSeat = ownerSeat,
            drawnTile = drawnTile,
            handSelectable = handSelectable,
        )
    }

    /**
     * Privacy-preserving layout fitting: operates on [inego.zungjunggame.session.table.SeatView] rather than a full omniscient [PlayerState].
     */
    fun layoutFittingAlongEdge(
        seatView: SeatView,
        showHandFaces: Boolean,
        metrics: Metrics,
        maxHalfSpan: Float,
        ownerSeat: Int = seatView.seat,
        handSelectable: Boolean = false,
    ): List<TilePlacement> {
        val probe = layout(
            seatView = seatView,
            showHandFaces = showHandFaces,
            metrics = metrics,
            handSelectable = handSelectable,
        )
        if (maxHalfSpan <= 0f) return probe
        val span = alongRowHalfSpan(probe)
        if (span <= maxHalfSpan) return probe
        return layout(
            seatView = seatView,
            showHandFaces = showHandFaces,
            metrics = metrics.scaled(maxHalfSpan / span),
            handSelectable = handSelectable,
        )
    }

    /**
     * Builds hand (visible or closed), open melds, and discard rows.
     *
     * @param showHandFaces true for the human seat; opponents get closed backs
     * @param river finalized discards plus optional pending (just-discarded) tile
     * @param drawnTile if >= 0 and present in the hand, one instance is shown on the
     *   right with a gap; the rest of the hand is sorted on the left
     * @param handSelectable when true (human discard turn), hand tiles are marked selectable
     */
    fun layout(
        seatView: SeatView,
        showHandFaces: Boolean,
        metrics: Metrics,
        handSelectable: Boolean = false,
    ): List<TilePlacement> {
        val hasVisibleHand = showHandFaces && seatView.concealedTiles != null
        val concealedCount = max(0, seatView.concealedCount - if (seatView.hasDrawnTile) 1 else 0)
        val sortedHand = if (hasVisibleHand) {
            seatView.concealedTiles
        } else {
            List(concealedCount) { -1 }
        }
        return layout(
            input = LayoutInput(
                sortedHand = sortedHand,
                parkedDrawn = if (hasVisibleHand) seatView.drawnTile else -1,
                hasDrawnSlot = seatView.hasDrawnTile,
                melds = seatView.melds,
                river = seatView.river,
                handFacesAvailable = hasVisibleHand,
                drawnFacesAvailable = hasVisibleHand,
            ),
            showHandFaces = showHandFaces,
            metrics = metrics,
            handSelectable = handSelectable,
        )
    }

    fun layout(
        player: PlayerState,
        river: List<Int>,
        showHandFaces: Boolean,
        metrics: Metrics,
        ownerSeat: Int = 0,
        drawnTile: Int = -1,
        handSelectable: Boolean = false,
    ): List<TilePlacement> = layout(
        input = run {
            val (sortedHand, parkedDrawn) = expandHandWithDrawn(player, drawnTile)
            LayoutInput(
                sortedHand = sortedHand.toList(),
                parkedDrawn = parkedDrawn,
                hasDrawnSlot = parkedDrawn >= 0,
                melds = expandMelds(player, ownerSeat),
                river = river,
                handFacesAvailable = true,
                drawnFacesAvailable = true,
            )
        },
        showHandFaces = showHandFaces,
        metrics = metrics,
        handSelectable = handSelectable,
    )

    private fun layout(
        input: LayoutInput,
        showHandFaces: Boolean,
        metrics: Metrics,
        handSelectable: Boolean,
    ): List<TilePlacement> {
        val out = ArrayList<TilePlacement>(64)
        val sortedHand = input.sortedHand
        val parkedDrawn = input.parkedDrawn
        val melds = input.melds

        // Full visible hand width includes a parked wall-draw + its gap. Waiting
        // hands reserve that same width with an empty slot so the sorted tiles
        // keep their x when the draw occupies it, and publish the slot as
        // [TilePlacement.emptyDrawReservation] so the seat's area includes it
        // before the tile arrives. Discard-ready rows (14 sorted, post-claim, …)
        // have no park and need no reserve.
        val occupyDrawnSlot = input.hasDrawnSlot || parkedDrawn >= 0
        val reserveDrawnWidth = occupyDrawnSlot || sortedHand.size % 3 == 1
        val sortedHandW = rowWidth(sortedHand.size, metrics.handTileW, metrics.tileGap)
        val drawnExtra =
            if (reserveDrawnWidth) metrics.handTileW + metrics.drawnGap else 0f
        val visualHandW = sortedHandW + drawnExtra
        val meldsW = if (melds.isEmpty()) 0f else meldGroupsWidth(melds, metrics)
        val bottomGap = if (melds.isEmpty()) 0f else metrics.meldGap * 2f
        val totalVisualW = visualHandW + bottomGap + meldsW
        val handY = 0f

        // Center the full visible row; melds stay on the right of the block,
        // parked draw sits just left of melds (right of the sorted hand).
        val rightEdge = totalVisualW * 0.5f
        val handRight = rightEdge - meldsW - bottomGap
        var xCursor = handRight - visualHandW

        // Sorted hand (left), then optional drawn tile on the right of the hand.
        for ((handIndex, tile) in sortedHand.withIndex()) {
            val cx = xCursor + metrics.handTileW * 0.5f
            if (showHandFaces && input.handFacesAvailable) {
                out += TilePlacement(
                    cx,
                    handY,
                    open = true,
                    tileType = tile,
                    w = metrics.handTileW,
                    h = metrics.handTileH,
                    selectableHand = handSelectable,
                    isDrawnSlot = false,
                    region = Region.Hand,
                    regionIndex = handIndex,
                )
            } else {
                // Keep engine type for animation matching even when face is closed.
                out += TilePlacement(
                    cx,
                    handY,
                    open = false,
                    tileType = tile,
                    w = metrics.handTileW,
                    h = metrics.handTileH,
                    region = Region.Hand,
                    regionIndex = handIndex,
                )
            }
            xCursor += metrics.handTileW + metrics.tileGap
        }
        if (occupyDrawnSlot) {
            // Gap then the newly acquired tile to the right of the sorted group.
            xCursor += metrics.drawnGap - metrics.tileGap
            val cx = xCursor + metrics.handTileW * 0.5f
            if (showHandFaces && input.drawnFacesAvailable && parkedDrawn >= 0) {
                out += TilePlacement(
                    cx,
                    handY,
                    open = true,
                    tileType = parkedDrawn,
                    w = metrics.handTileW,
                    h = metrics.handTileH,
                    selectableHand = handSelectable,
                    isDrawnSlot = true,
                    region = Region.Drawn,
                    regionIndex = 0,
                )
            } else {
                out += TilePlacement(
                    cx,
                    handY,
                    open = false,
                    tileType = parkedDrawn,
                    w = metrics.handTileW,
                    h = metrics.handTileH,
                    isDrawnSlot = true,
                    region = Region.Drawn,
                    regionIndex = 0,
                )
            }
        } else if (reserveDrawnWidth) {
            // Same footprint the parked tile will occupy. Not [isDrawnSlot]: a
            // waiting hand must not grow a tile here, only the area around it.
            xCursor += metrics.drawnGap - metrics.tileGap
            val cx = xCursor + metrics.handTileW * 0.5f
            out += TilePlacement(
                cx,
                handY,
                open = false,
                tileType = -1,
                w = metrics.handTileW,
                h = metrics.handTileH,
                region = Region.Drawn,
                regionIndex = 0,
                emptyDrawReservation = true,
            )
        }

        // Melds fixed on the right of the row (right edge = rightEdge).
        // Sideways claim tiles keep upright draw size (w×h) and rotate 90°; only the
        // along-row footprint swaps (visual width becomes handTileH).
        if (melds.isNotEmpty()) {
            xCursor = rightEdge - meldsW
            for ((groupIndex, group) in melds.withIndex()) {
                for ((tileIndex, tile) in group.tiles.withIndex()) {
                    val footprintW = if (tile.rotated) metrics.handTileH else metrics.handTileW
                    val cx = xCursor + footprintW * 0.5f
                    out += TilePlacement(
                        cx,
                        handY,
                        open = tile.open,
                        tileType = tile.tileType,
                        w = metrics.handTileW,
                        h = metrics.handTileH,
                        rotationOffsetDeg = if (tile.rotated) SIDEWAYS_ROTATION_DEG else 0f,
                        region = Region.Meld,
                        regionIndex = groupIndex,
                        regionSubIndex = tileIndex,
                    )
                    xCursor += footprintW + metrics.tileGap
                }
                xCursor += metrics.meldGap
            }
        }

        // Discard rows above the hand (toward table center).
        // Newest row is always at baseY (nearest the hand); when it fills, older
        // rows shift one step toward the center so new tiles keep landing in place.
        val discards = input.river.take(metrics.discardsPerRow * metrics.maxDiscardRows)
        if (discards.isNotEmpty()) {
            val perRow = metrics.discardsPerRow.coerceAtLeast(1)
            val rowCount = (discards.size + perRow - 1) / perRow
            val baseY = metrics.handTileH * 0.5f + metrics.rowGap + metrics.discardTileH * 0.5f
            val rowPitch = metrics.discardTileH + metrics.rowGap
            for (row in 0 until rowCount) {
                val start = row * perRow
                val end = minOf(start + perRow, discards.size)
                val count = end - start
                val rowW = rowWidth(count, metrics.discardTileW, metrics.tileGap)
                var dx = -rowW * 0.5f
                // row 0 = oldest; place it farthest from the hand when multiple rows.
                val dy = baseY + (rowCount - 1 - row) * rowPitch
                for (i in start until end) {
                    val cx = dx + metrics.discardTileW * 0.5f
                    out += TilePlacement(
                        cx,
                        dy,
                        open = true,
                        tileType = discards[i],
                        w = metrics.discardTileW,
                        h = metrics.discardTileH,
                        region = Region.River,
                        regionIndex = i,
                    )
                    dx += metrics.discardTileW + metrics.tileGap
                }
            }
        }

        return out
    }

    /**
     * Sorted multiset of concealed tiles for display, with optional [drawnTile]
     * peeled off for a separate right-side slot.
     *
     * @return (sorted remaining tiles, parked drawn tile or -1)
     */
    fun expandHandWithDrawn(player: PlayerState, drawnTile: Int): Pair<IntArray, Int> {
        val counts = IntArray(NUM_TILE_TYPES)
        var total = 0
        for (t in 0 until NUM_TILE_TYPES) {
            counts[t] = player.concealed[t]
            total += counts[t]
        }
        if (total == 0) return IntArray(0) to -1

        var parked = -1
        if (drawnTile in 0 until NUM_TILE_TYPES && counts[drawnTile] > 0) {
            --counts[drawnTile]
            parked = drawnTile
            --total
        }

        val hand = IntArray(total)
        var i = 0
        for (t in 0 until NUM_TILE_TYPES) {
            repeat(counts[t]) {
                hand[i++] = t
            }
        }
        return hand to parked
    }

    private fun rowWidth(count: Int, tileW: Float, gap: Float): Float {
        if (count <= 0) return 0f
        return count * tileW + (count - 1) * gap
    }

    private fun meldGroupWidth(group: MeldGroup, metrics: Metrics): Float {
        if (group.tiles.isEmpty()) return 0f
        var w = 0f
        for ((i, t) in group.tiles.withIndex()) {
            // Rotated tiles use the upright height as their along-row footprint.
            w += if (t.rotated) metrics.handTileH else metrics.handTileW
            if (i < group.tiles.lastIndex) w += metrics.tileGap
        }
        return w
    }

    private fun meldGroupsWidth(groups: List<MeldGroup>, metrics: Metrics): Float {
        if (groups.isEmpty()) return 0f
        var w = 0f
        for ((gi, g) in groups.withIndex()) {
            w += meldGroupWidth(g, metrics)
            if (gi < groups.lastIndex) w += metrics.meldGap
        }
        return w
    }
}
