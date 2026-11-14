package inego.zungjunggame.render.wallbuild

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure wall slot geometry for the wall-build tutorial diagram.
 *
 * Table square is in diagram pixels; tile size is derived from a fixed
 * millimetre map (table / tile long side) and [tileAspect] (short/long).
 *
 * Slot id is side-major: [slotId]. Floor 0 = bottom (peek offset), 1 = top.
 * Side 0 = East / bottom, then right, top, left (CCW).
 */
object WallSlotLayout {

    /** East / bottom wall side index. */
    const val EAST_SIDE = 0

    /** South / right wall side index. */
    const val SOUTH_SIDE = 1

    /** Stacks per wall side. */
    const val STACKS_PER_SIDE = 17

    /** Tiles per stack (bottom + top). */
    const val FLOORS_PER_STACK = 2

    const val SIDE_COUNT = 4

    const val SLOT_COUNT = SIDE_COUNT * STACKS_PER_SIDE * FLOORS_PER_STACK

    /**
     * Pushed-wall joint: each side’s left edge sits on the next side’s bottom
     * this many tile-widths from that next side’s left (4-fold pinwheel).
     * Tuned in the wall-geometry demo.
     */
    const val WALL_PUSH_OFFSET_TILES = 13.34f

    /**
     * Placement angle of the pushed composition about table center (deg CCW).
     * Demo look is 8.2°; +180° keeps each side on the same table edge as the
     * formed wall (no cross-through). Tile *facing* uses this − 180° so stacks
     * only ease ~8° from the formed-wall orientation (no 180° spin).
     */
    const val WALL_PUSH_ANGLE_DEG = 188.2f

    /**
     * Gap opened on the break beat: uncounted East stacks shift this many
     * tile-widths further left along the wall.
     */
    const val WALL_BREAK_GAP_TILES = 1.35f

    /**
     * How far the dead-wall 7 stacks shift pure screen-down from the East wall
     * line (in tile-step units). Larger than the break gap so the row reads as
     * a clearly separate band under the table.
     */
    const val DEAD_WALL_DOWN_TILES = 2.25f

    /** Stacks taken for the dead wall (always 7 × 2 tiles). */
    const val TAKE7_STACKS = 7

    /** Deal rounds: each player takes 2 stacks, three times → 12 tiles each. */
    const val DEAL_ROUNDS = 3

    /** Stacks each player takes per deal round. */
    const val DEAL_STACKS_PER_TAKE = 2

    /** Total stacks that leave the live wall for opening hands. */
    const val DEAL_STACKS_TOTAL = DEAL_ROUNDS * SIDE_COUNT * DEAL_STACKS_PER_TAKE

    /** Non-dealer lined hand length (12 from stacks + 13th). */
    const val HAND_TILES_OTHER = DEAL_ROUNDS * DEAL_STACKS_PER_TAKE * FLOORS_PER_STACK + 1

    /** East lined hand length (13 + opening draw). */
    const val HAND_TILES_EAST = HAND_TILES_OTHER + 1

    /**
     * Gap between a seat’s three 2-stack “squares” along the hand line
     * (tile-step units). Large enough to read as separate groups.
     */
    const val HAND_GROUP_GAP_TILES = 0.75f

    /**
     * How far hand centers sit **inside** the table square from each rim
     * (tile-step units past half-tile). On the deal beat, seat-wind labels
     * shift further inward past this band so hands do not cover them.
     */
    const val HAND_IN_TILES = 0.35f

    /** Axis-aligned table square in diagram pixels. */
    data class TableSquare(val left: Float, val bottom: Float, val side: Float)

    /**
     * Shared wall-tile metrics at fixed mm scale — same portrait footprint as
     * face-down / shuffle (short × long). Short edge runs along the wall
     * (edge-to-edge); long edge is radial. Not landscape bars.
     */
    data class Metrics(
        val tileW: Float,
        val tileH: Float,
        val step: Float,
        val peekX: Float,
        val peekY: Float,
        val ox: Float,
        val oy: Float,
        val left: Float,
        val bottom: Float,
        val right: Float,
        val top: Float,
    )

    /** Upper-tile center and facing for one stack. */
    data class UpperPose(val cx: Float, val cy: Float, val rot: Float)

    /**
     * Metrics for [sq] using physical table/tile sizes in mm and short/long
     * [tileAspect] (e.g. [inego.zungjunggame.tutorial.slide.layout.DiagramLayout.TILE_ASPECT]).
     */
    fun metrics(
        sq: TableSquare,
        tableMm: Float,
        tileMm: Float,
        tileAspect: Float,
    ): Metrics {
        val pxPerMm = (sq.side / tableMm).coerceAtLeast(1e-4f)
        val tileH = tileMm * pxPerMm
        val tileW = tileH * tileAspect
        return Metrics(
            tileW = tileW,
            tileH = tileH,
            // Short edge along the wall, edge-to-edge.
            step = tileW,
            // Bottom floor peeks under upper: screen-right and screen-down.
            peekX = tileW * 0.28f,
            peekY = tileH * 0.18f,
            ox = sq.left + sq.side * 0.5f,
            oy = sq.bottom + sq.side * 0.5f,
            left = sq.left,
            bottom = sq.bottom,
            right = sq.left + sq.side,
            top = sq.bottom + sq.side,
        )
    }

    /** Stable slot id for a wall position (side-major; used by push mapping). */
    fun slotId(side: Int, alongI: Int, floor: Int): Int =
        side * (STACKS_PER_SIDE * FLOORS_PER_STACK) + alongI * FLOORS_PER_STACK + floor

    /** Side index 0..3 for [slotId]. */
    fun slotSide(slot: Int): Int = slot / (STACKS_PER_SIDE * FLOORS_PER_STACK)

    /** Along-wall stack index 0..16 for [slotId]. */
    fun slotAlong(slot: Int): Int =
        (slot % (STACKS_PER_SIDE * FLOORS_PER_STACK)) / FLOORS_PER_STACK


    /**
     * Slot ids still on the wall after the full opening deal (not in any seat’s
     * hand). [live] = residual live wall; [dead] = take-7 dead wall.
     * Pure geometry — independent of the lesson tile map.
     */
    fun residualWallSlots(breakStackCount: Int): ResidualWallSlots {
        val hands = HashSet<Int>(64)
        for (seat in 0 until SIDE_COUNT) {
            hands.addAll(dealHandSlotIds(breakStackCount, seat))
        }
        val live = ArrayList<Int>(80)
        val dead = ArrayList<Int>(TAKE7_STACKS * FLOORS_PER_STACK)
        for (slot in 0 until SLOT_COUNT) {
            if (slot in hands) continue
            val side = slotSide(slot)
            val along = slotAlong(slot)
            if (isTake7Stack(breakStackCount, side, along)) {
                dead += slot
            } else {
                live += slot
            }
        }
        return ResidualWallSlots(live = live, dead = dead)
    }

    data class ResidualWallSlots(val live: List<Int>, val dead: List<Int>)

    /** Upper-tile center for one stack on the axis-aligned formed wall. */
    fun formedUpperPose(side: Int, alongI: Int, m: Metrics): UpperPose {
        val wallLen = STACKS_PER_SIDE * m.step
        val along0 = (m.right - m.left - wallLen) * 0.5f
        val halfRadial = m.tileH * 0.5f
        val along = along0 + (alongI + 0.5f) * m.step
        return when (side) {
            0 -> UpperPose(m.left + along, m.bottom + halfRadial, 0f) // bottom
            1 -> UpperPose(m.right - halfRadial, m.bottom + along, 90f) // right
            2 -> UpperPose(m.right - along, m.top - halfRadial, 180f) // top
            else -> UpperPose(m.left + halfRadial, m.top - along, 270f) // left
        }
    }

    /** Target center for a floor of a stack (bottom floor includes peek offset). */
    fun floorTarget(upper: UpperPose, floor: Int, m: Metrics): Pair<Float, Float> {
        val isBottom = floor == 0
        val cx = if (isBottom) upper.cx + m.peekX else upper.cx
        val cy = if (isBottom) upper.cy - m.peekY else upper.cy
        return cx to cy
    }

    /**
     * First [alongI] on East that stays on the right (highlighted) when
     * [breakStackCount] stacks are counted from the right end. Returns -1 when
     * there is no break gap ([breakStackCount] ≤ 0).
     */
    fun firstHighlightedAlong(breakStackCount: Int): Int {
        if (breakStackCount <= 0) return -1
        val n = breakStackCount.coerceIn(0, STACKS_PER_SIDE)
        return (STACKS_PER_SIDE - n).coerceIn(0, STACKS_PER_SIDE)
    }

    /**
     * Dead-wall take of 7 stacks: [stackIndex] 0..6 from the right edge of the
     * gap (first dice-counted East stack) rightward; continues onto South when
     * fewer than 7 remain on East. Null if out of range or no break.
     */
    fun take7Stack(breakStackCount: Int, stackIndex: Int): Pair<Int, Int>? {
        if (stackIndex !in 0 until TAKE7_STACKS) return null
        val n = breakStackCount.coerceIn(0, STACKS_PER_SIDE)
        if (n <= 0) return null
        val firstEast = firstHighlightedAlong(n)
        // Stacks on East to the right of the gap (= dice count).
        val eastCount = STACKS_PER_SIDE - firstEast
        return if (stackIndex < eastCount) {
            EAST_SIDE to (firstEast + stackIndex)
        } else {
            val southAlong = stackIndex - eastCount
            if (southAlong >= STACKS_PER_SIDE) null
            else SOUTH_SIDE to southAlong
        }
    }

    /** Whether [side]/[alongI] is one of the 7 dead-wall stacks for this break. */
    fun isTake7Stack(breakStackCount: Int, side: Int, alongI: Int): Boolean {
        for (k in 0 until TAKE7_STACKS) {
            val p = take7Stack(breakStackCount, k) ?: continue
            if (p.first == side && p.second == alongI) return true
        }
        return false
    }

    /**
     * Live-wall stack [stackIndex] after the break, in deal/draw order.
     *
     * Starts on East **immediately left of the gap** and walks gap→left, then
     * clockwise North → West → South (skipping the dead-wall 7). Any East
     * stacks still on the wall **right of the dead wall** (dice count > 7)
     * are last — they are not part of the open end of the live wall.
     *
     * Index 0 is always the first stack left of the split. Null if out of
     * range or no break.
     */
    fun liveWallStack(breakStackCount: Int, stackIndex: Int): Pair<Int, Int>? {
        if (stackIndex < 0) return null
        val n = breakStackCount.coerceIn(0, STACKS_PER_SIDE)
        if (n <= 0) return null
        val firstEast = firstHighlightedAlong(n)
        var remaining = stackIndex

        // 1. East left of the gap: gap → left (descending alongI).
        if (firstEast > 0) {
            for (alongI in (firstEast - 1) downTo 0) {
                if (remaining == 0) return EAST_SIDE to alongI
                remaining--
            }
        }

        // 2. Clockwise around the other three sides; skip dead-wall stacks
        //    (South may hold the spill when dice count < 7).
        for (side in intArrayOf(3, 2, SOUTH_SIDE)) {
            for (alongI in (STACKS_PER_SIDE - 1) downTo 0) {
                if (isTake7Stack(n, side, alongI)) continue
                if (remaining == 0) return side to alongI
                remaining--
            }
        }

        // 3. East stacks right of the gap that are not in the dead wall
        //    (leftover when dice count > 7): right end → toward dead wall.
        for (alongI in (STACKS_PER_SIDE - 1) downTo firstEast) {
            if (isTake7Stack(n, EAST_SIDE, alongI)) continue
            if (remaining == 0) return EAST_SIDE to alongI
            remaining--
        }
        return null
    }

    /**
     * Seat / group / stack-in-group for deal index 0..[DEAL_STACKS_TOTAL)-1.
     * Order: East takes 2, South 2, West 2, North 2, then next round — three
     * rounds. Each seat ends with three separated 2-stack squares.
     */
    data class DealTarget(val seat: Int, val groupIndex: Int, val stackInGroup: Int)

    fun dealTarget(dealStackIndex: Int): DealTarget? {
        if (dealStackIndex !in 0 until DEAL_STACKS_TOTAL) return null
        val seat = (dealStackIndex / DEAL_STACKS_PER_TAKE) % SIDE_COUNT
        val groupIndex = dealStackIndex / (SIDE_COUNT * DEAL_STACKS_PER_TAKE)
        val stackInGroup = dealStackIndex % DEAL_STACKS_PER_TAKE
        return DealTarget(seat, groupIndex, stackInGroup)
    }

    /**
     * Upper-tile center for one stack in a seat’s opening hand: three separated
     * 2-stack “squares” just **inside** that seat’s table rim. Seat-wind labels
     * draw under tiles and shift inward past this band. [rot] matches
     * formed-wall facing (0° East, …). Dead wall stays outside under East;
     * East’s hand sits above the rim line.
     */
    fun dealHandUpperPose(
        seat: Int,
        groupIndex: Int,
        stackInGroup: Int,
        m: Metrics,
    ): UpperPose? {
        if (seat !in 0 until SIDE_COUNT) return null
        if (groupIndex !in 0 until DEAL_ROUNDS) return null
        if (stackInGroup !in 0 until DEAL_STACKS_PER_TAKE) return null

        val groupSpan = DEAL_STACKS_PER_TAKE * m.step
        val gap = HAND_GROUP_GAP_TILES * m.step
        val total = DEAL_ROUNDS * groupSpan + (DEAL_ROUNDS - 1) * gap
        // Along-line center of this group, then stack within the group.
        val groupCenter =
            -total * 0.5f + groupSpan * 0.5f + groupIndex * (groupSpan + gap)
        val along = groupCenter + (stackInGroup - 0.5f) * m.step
        val halfRadial = m.tileH * 0.5f
        val inset = halfRadial + HAND_IN_TILES * m.step
        return when (seat) {
            0 -> UpperPose( // East / bottom — inside, above the dead wall
                cx = m.ox + along,
                cy = m.bottom + inset,
                rot = 0f,
            )
            1 -> UpperPose( // South / right
                cx = m.right - inset,
                cy = m.oy + along,
                rot = 90f,
            )
            2 -> UpperPose( // West / top
                cx = m.ox + along,
                cy = m.top - inset,
                rot = 180f,
            )
            else -> UpperPose( // North / left
                cx = m.left + inset,
                cy = m.oy + along,
                rot = 270f,
            )
        }
    }

    /**
     * After the 24-stack open deal (12 tiles each), each seat takes one more
     * tile for 13. Source order on the live wall: East upper of stack 24,
     * South lower of 24, West upper of 25, North lower of 25.
     */
    data class Deal13thSource(val seat: Int, val liveStackIndex: Int, val floor: Int)

    val DEAL_13TH_SOURCES: List<Deal13thSource> = listOf(
        Deal13thSource(seat = 0, liveStackIndex = 24, floor = 1),
        Deal13thSource(seat = 1, liveStackIndex = 24, floor = 0),
        Deal13thSource(seat = 2, liveStackIndex = 25, floor = 1),
        Deal13thSource(seat = 3, liveStackIndex = 25, floor = 0),
    )

    /**
     * Upper-tile center for the 13th tile in a seat's hand: placed just after the
     * 3rd group (group index 2) along that seat's hand line.
     */
    fun deal13thTileUpperPose(seat: Int, m: Metrics): UpperPose? {
        if (seat !in 0 until SIDE_COUNT) return null
        val groupSpan = DEAL_STACKS_PER_TAKE * m.step
        val gap = HAND_GROUP_GAP_TILES * m.step
        val total = DEAL_ROUNDS * groupSpan + (DEAL_ROUNDS - 1) * gap
        val group2Center = -total * 0.5f + groupSpan * 0.5f + 2 * (groupSpan + gap)
        // One tile past the last group: half a group span + gap + half a tile step.
        val along = group2Center + 0.5f * groupSpan + gap + 0.5f * m.step
        val halfRadial = m.tileH * 0.5f
        val inset = halfRadial + HAND_IN_TILES * m.step
        return when (seat) {
            0 -> UpperPose(cx = m.ox + along, cy = m.bottom + inset, rot = 0f)
            1 -> UpperPose(cx = m.right - inset, cy = m.oy + along, rot = 90f)
            2 -> UpperPose(cx = m.ox + along, cy = m.top - inset, rot = 180f)
            else -> UpperPose(cx = m.left + inset, cy = m.oy + along, rot = 270f)
        }
    }

    /**
     * After each seat has 13, East draws the next live-wall tile (stack 26 upper)
     * to open the hand at 14.
     */
    data class Deal14thEastSource(val liveStackIndex: Int, val floor: Int)

    val DEAL_14TH_EAST_SOURCE = Deal14thEastSource(liveStackIndex = 26, floor = 1)

    /**
     * East's 14th tile: one step past the 13th along East's hand line.
     */
    fun deal14thEastUpperPose(m: Metrics): UpperPose? {
        val p13 = deal13thTileUpperPose(seat = EAST_SIDE, m) ?: return null
        return UpperPose(cx = p13.cx + m.step, cy = p13.cy, rot = p13.rot)
    }

    /**
     * Opening hand size after the full deal: 13 for non-dealers, 14 for East.
     */
    fun linedHandTileCount(seat: Int): Int =
        if (seat == EAST_SIDE) HAND_TILES_EAST else HAND_TILES_OTHER

    /**
     * Formed-wall slot ids for [seat]'s opening hand in deal order (2×2 groups,
     * then 13th, then East 14th). Caps at [tileCount] (use [linedHandTileCount]).
     * Pure geometry — caller maps slots through the lesson wall map to tile types.
     */
    fun dealHandSlotIds(
        breakStackCount: Int,
        seat: Int,
        tileCount: Int = linedHandTileCount(seat),
        include13th: Boolean = true,
        include14thEast: Boolean = true,
    ): List<Int> {
        if (seat !in 0 until SIDE_COUNT || tileCount < 1) return emptyList()
        val out = ArrayList<Int>(tileCount)
        for (dealI in 0 until DEAL_STACKS_TOTAL) {
            if (out.size >= tileCount) break
            val target = dealTarget(dealI) ?: continue
            if (target.seat != seat) continue
            val sideAlong = liveWallStack(breakStackCount, dealI) ?: continue
            val (side, alongI) = sideAlong
            for (floor in 0 until FLOORS_PER_STACK) {
                if (out.size >= tileCount) break
                out.add(slotId(side, alongI, floor))
            }
        }
        if (include13th && out.size < tileCount) {
            val src = DEAL_13TH_SOURCES.find { it.seat == seat }
            if (src != null) {
                val sideAlong = liveWallStack(breakStackCount, src.liveStackIndex)
                if (sideAlong != null) {
                    val (side, alongI) = sideAlong
                    out.add(slotId(side, alongI, src.floor))
                }
            }
        }
        if (include14thEast &&
            seat == EAST_SIDE &&
            out.size < tileCount
        ) {
            val src = DEAL_14TH_EAST_SOURCE
            val sideAlong = liveWallStack(breakStackCount, src.liveStackIndex)
            if (sideAlong != null) {
                val (side, alongI) = sideAlong
                out.add(slotId(side, alongI, src.floor))
            }
        }
        return out
    }

    /**
     * Upper-tile center for tile [index] in a seat’s **lined** hand (look-at-hand
     * beat): a single row of [tileCount] portrait tiles, centered on the same
     * hand line / inset as the deal squares. All tiles sit on the table floor
     * (caller uses floor 0). Index 0 is the seat’s left along the hand line.
     */
    fun linedHandUpperPose(
        seat: Int,
        index: Int,
        tileCount: Int,
        m: Metrics,
    ): UpperPose? {
        if (seat !in 0 until SIDE_COUNT) return null
        if (tileCount < 1 || index !in 0 until tileCount) return null
        val total = (tileCount - 1) * m.step
        val along = -total * 0.5f + index * m.step
        val halfRadial = m.tileH * 0.5f
        val inset = halfRadial + HAND_IN_TILES * m.step
        return when (seat) {
            0 -> UpperPose(cx = m.ox + along, cy = m.bottom + inset, rot = 0f)
            1 -> UpperPose(cx = m.right - inset, cy = m.oy + along, rot = 90f)
            2 -> UpperPose(cx = m.ox + along, cy = m.top - inset, rot = 180f)
            else -> UpperPose(cx = m.left + inset, cy = m.oy + along, rot = 270f)
        }
    }

    /**
     * Upper-tile center for dead-wall stack [stackIndex] (0..6): a true
     * axis-aligned horizontal row ([rot] = 0°), anchored under the first East
     * stack right of the gap and stepped pure +X by [Metrics.step], then
     * shifted pure screen-down by [DEAD_WALL_DOWN_TILES]. South-origin stacks
     * straighten into this row. Caller supplies East’s pinwheel basis from
     * [forEachPushedSide] (used only to locate the gap-edge anchor).
     */
    fun deadWallUpperPose(
        breakStackCount: Int,
        stackIndex: Int,
        m: Metrics,
        originX: Float,
        originY: Float,
        ux: Float,
        uy: Float,
        vx: Float,
        vy: Float,
    ): UpperPose? {
        if (stackIndex !in 0 until TAKE7_STACKS) return null
        val n = breakStackCount.coerceIn(0, STACKS_PER_SIDE)
        if (n <= 0) return null
        val firstEast = firstHighlightedAlong(n)
        // Wall pose of stack 0 (gap’s right edge on East) — anchor X/Y only.
        val along0 = (STACKS_PER_SIDE - 1 - firstEast + 0.5f) * m.step
        val anchorCx = originX + along0 * ux + 0.5f * m.tileH * vx
        val anchorCy = originY + along0 * uy + 0.5f * m.tileH * vy
        val down = DEAD_WALL_DOWN_TILES * m.step
        // Absolute horizontal: rot 0, step right in screen X, pure screen-down.
        return UpperPose(
            cx = anchorCx + stackIndex * m.step,
            cy = anchorCy - down,
            rot = 0f,
        )
    }

    /**
     * Visit each side of the pinwheel: left-bottom origin and along/radial unit
     * vectors in placement space (tile facing is placeAngle − 180°).
     */
    inline fun forEachPushedSide(
        m: Metrics,
        action: (
            side: Int,
            originX: Float,
            originY: Float,
            ux: Float,
            uy: Float,
            vx: Float,
            vy: Float,
        ) -> Unit,
    ) {
        val offsetPx = WALL_PUSH_OFFSET_TILES * m.step
        val degToRad = PI.toFloat() / 180f

        // Left-bottom of side 0 (A) relative to center, then +90° CCW per side.
        val a0 = WALL_PUSH_ANGLE_DEG * degToRad
        val ux0 = cos(a0)
        val uy0 = sin(a0)
        // LB_A − R(LB_A) = offset · R(u_A), R = 90° CCW.
        var lbX = -0.5f * offsetPx * (ux0 + uy0)
        var lbY = 0.5f * offsetPx * (ux0 - uy0)

        for (side in 0 until SIDE_COUNT) {
            if (side > 0) {
                val nx = -lbY
                val ny = lbX
                lbX = nx
                lbY = ny
            }
            val placeAngle = WALL_PUSH_ANGLE_DEG + side * 90f
            val rad = placeAngle * degToRad
            val ux = cos(rad)
            val uy = sin(rad)
            // v = u rotated +90° CCW: bottom → top of the strip.
            val vx = -uy
            action(side, m.ox + lbX, m.oy + lbY, ux, uy, vx, ux)
        }
    }

    /**
     * Upper-tile center for one stack on a pushed pinwheel side.
     *
     * [breakStackCount] > 0 opens a gap on East only: uncounted stacks
     * ([alongI] < first highlighted) shift further left by [WALL_BREAK_GAP_TILES].
     * Caller supplies side basis from [forEachPushedSide].
     */
    fun pushedUpperPose(
        side: Int,
        alongI: Int,
        m: Metrics,
        breakStackCount: Int,
        originX: Float,
        originY: Float,
        ux: Float,
        uy: Float,
        vx: Float,
        vy: Float,
    ): UpperPose {
        val firstHighlighted = if (side == EAST_SIDE) {
            firstHighlightedAlong(breakStackCount)
        } else {
            -1
        }
        val breakGapPx = if (firstHighlighted >= 0) WALL_BREAK_GAP_TILES * m.step else 0f
        val shift = if (firstHighlighted >= 0 && alongI < firstHighlighted) breakGapPx else 0f
        // +180° place angle reverses along-dir vs the formed wall; put high
        // along-index at the local left so stack order still matches.
        val along = (STACKS_PER_SIDE - 1 - alongI + 0.5f) * m.step + shift
        val placeAngle = WALL_PUSH_ANGLE_DEG + side * 90f
        val faceAngle = placeAngle - 180f
        return UpperPose(
            cx = originX + along * ux + 0.5f * m.tileH * vx,
            cy = originY + along * uy + 0.5f * m.tileH * vy,
            rot = faceAngle,
        )
    }
}
