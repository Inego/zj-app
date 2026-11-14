package inego.zungjunggame.render.table

import inego.zungjunggame.render.seats.SeatSide
import inego.zungjunggame.render.tiles.TileEasing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns the active-seat panel from one seat to another about the screen centre.
 *
 * The panel is a rectangle in the seat's own frame: along the seat's edge, and
 * outward from the centre toward that edge. A change of seat rotates that frame
 * by the shortest angle and blends the rectangle inside it, so the shape stays a
 * rectangle and arrives on the new seat's measured band. The usual turn order is
 * counter-clockwise, Bottom → Right → Opposite → Left. A half turn is the same
 * length either way; [TileEasing.angleDeltaSignedDeg] keeps the positive one, so
 * the tie follows that order.
 *
 * The clock is [advance]. A turn lasts the tile flight minus [LEAD_SEC], so the
 * panel is in place shortly before a draw or a claim finishes moving.
 * [sample] reads the live target band and the live flight length, so the panel
 * is still tracking that seat's tiles when the turn ends. The first time there
 * is nothing to turn from, and after [snap], the panel is simply there.
 *
 * Owns the motion only. [ActiveSeatFelt] owns the band and the wash;
 * [ActiveSeatTriangle] paints both shapes. [PlayTableView] measures the band,
 * advances the clock, and asks for another frame while [isTurning]. The turn is
 * not tile motion: callers must not treat it as the board being busy.
 */
class ActiveSeatSweep {
    private var turning = false
    private var elapsedSec = 0f
    private var durationSec = 0f
    private var fromPose: SeatPose? = null
    private var aimPose: SeatPose? = null
    private var aimSide: SeatSide? = null
    private var shown: SeatPose? = null

    /** True until the panel has landed. A settled seat is false. */
    fun isTurning(): Boolean = turning

    fun advance(deltaSec: Float) {
        if (!turning || deltaSec <= 0f) return
        elapsedSec += deltaSec
        if (durationSec > 0f && elapsedSec < durationSec) return
        // Land here, not in [sample]. A covered board still advances, and a turn
        // that waited for a paint to finish would keep asking for frames forever.
        turning = false
        val landed = aimPose
        if (landed != null) shown = landed
        fromPose = null
        elapsedSec = 0f
        durationSec = 0f
    }

    /**
     * Drop the turn and the pose it was heading for.
     *
     * The next [sample] sits on whatever seat is measured then. Used when the
     * hand is replaced or the window changes size, so a pose from the old
     * picture is not spun onto the new one.
     */
    fun snap() {
        turning = false
        elapsedSec = 0f
        durationSec = 0f
        fromPose = null
        aimPose = null
        aimSide = null
        shown = null
    }

    /**
     * Corners to paint this frame, eight floats:
     * inner-along0, inner-along1, outer-along1, outer-along0, each as x, y.
     *
     * The two inner points are the edge that faces the centre. They are the
     * triangle's base; the outer two close the panel.
     */
    fun sample(
        side: SeatSide,
        panel: FloatArray,
        centerX: Float,
        centerY: Float,
        flightSec: Float,
    ): FloatArray {
        val target = SeatPose(outwardAngleDeg(side), localOf(side, panel, centerX, centerY))
        val turnSec = (flightSec - LEAD_SEC).coerceAtLeast(0f)
        val current = shown ?: return settle(side, target, centerX, centerY)
        if (!turning) {
            if (side == aimSide) {
                return settle(side, target, centerX, centerY)
            }
            return startTurn(current, side, target, turnSec, centerX, centerY)
        }
        val origin = fromPose ?: current
        // A seat change mid-turn keeps the pose already reached. The new band is
        // not the one this turn was blending toward.
        val aim = if (side == aimSide) target else (aimPose ?: target)
        val span = if (side == aimSide) turnSec else durationSec
        val u = if (span <= 0f) 1f else TileEasing.easeOutCubic(TileEasing.clamp01(elapsedSec / span))
        val visual = blend(origin, aim, u)
        if (side != aimSide) {
            return startTurn(visual, side, target, turnSec, centerX, centerY)
        }
        if (span <= 0f || elapsedSec >= span) {
            return settle(side, target, centerX, centerY)
        }
        durationSec = span
        aimPose = target
        shown = visual
        return cornersOf(visual, centerX, centerY)
    }

    private fun startTurn(
        origin: SeatPose,
        side: SeatSide,
        target: SeatPose,
        turnSec: Float,
        centerX: Float,
        centerY: Float,
    ): FloatArray {
        // A flight already shorter than the lead is in place as the tile sets off.
        if (turnSec <= 0f) {
            return settle(side, target, centerX, centerY)
        }
        fromPose = origin
        aimPose = target
        aimSide = side
        elapsedSec = 0f
        durationSec = turnSec
        turning = true
        shown = origin
        return cornersOf(origin, centerX, centerY)
    }

    private fun settle(side: SeatSide, target: SeatPose, centerX: Float, centerY: Float): FloatArray {
        turning = false
        elapsedSec = 0f
        durationSec = 0f
        fromPose = null
        aimPose = target
        aimSide = side
        shown = target
        return cornersOf(target, centerX, centerY)
    }

    private data class SeatLocal(
        val along0: Float,
        val along1: Float,
        val outInner: Float,
        val outOuter: Float,
    )

    private data class SeatPose(val angleDeg: Float, val local: SeatLocal)

    private fun blend(a: SeatPose, b: SeatPose, u: Float) = SeatPose(
        angleDeg = TileEasing.angleLerpDeg(a.angleDeg, b.angleDeg, u),
        local = SeatLocal(
            along0 = TileEasing.lerp(a.local.along0, b.local.along0, u),
            along1 = TileEasing.lerp(a.local.along1, b.local.along1, u),
            outInner = TileEasing.lerp(a.local.outInner, b.local.outInner, u),
            outOuter = TileEasing.lerp(a.local.outOuter, b.local.outOuter, u),
        ),
    )

    /**
     * Degrees of the outward axis, 0 pointing at the right edge, positive
     * counter-clockwise. That is the turn order around the table.
     */
    private fun outwardAngleDeg(side: SeatSide): Float = when (side) {
        SeatSide.Bottom -> -90f
        SeatSide.Right -> 0f
        SeatSide.Opposite -> 90f
        SeatSide.Left -> 180f
    }

    /**
     * [panel] `(x, y, width, height)` in the seat frame.
     *
     * `along` grows toward the seated player's right. `out` grows from the
     * screen centre toward that seat's edge. `along0` is the player's left end.
     */
    private fun localOf(side: SeatSide, panel: FloatArray, centerX: Float, centerY: Float): SeatLocal {
        val x = panel[0]
        val y = panel[1]
        val w = panel[2]
        val h = panel[3]
        return when (side) {
            SeatSide.Bottom -> SeatLocal(
                along0 = x - centerX,
                along1 = x + w - centerX,
                outInner = centerY - (y + h),
                outOuter = centerY - y,
            )
            SeatSide.Right -> SeatLocal(
                along0 = y - centerY,
                along1 = y + h - centerY,
                outInner = x - centerX,
                outOuter = x + w - centerX,
            )
            SeatSide.Opposite -> SeatLocal(
                along0 = centerX - (x + w),
                along1 = centerX - x,
                outInner = y - centerY,
                outOuter = y + h - centerY,
            )
            SeatSide.Left -> SeatLocal(
                along0 = centerY - (y + h),
                along1 = centerY - y,
                outInner = centerX - (x + w),
                outOuter = centerX - x,
            )
        }
    }

    private fun cornersOf(pose: SeatPose, centerX: Float, centerY: Float): FloatArray {
        val radians = pose.angleDeg * PI.toFloat() / 180f
        val outX = cos(radians)
        val outY = sin(radians)
        // Player's right is a quarter turn counter-clockwise from outward.
        val alongX = -outY
        val local = pose.local
        val out = FloatArray(8)
        put(out, 0, centerX, centerY, alongX, outX, outX, outY, local.along0, local.outInner)
        put(out, 2, centerX, centerY, alongX, outX, outX, outY, local.along1, local.outInner)
        put(out, 4, centerX, centerY, alongX, outX, outX, outY, local.along1, local.outOuter)
        put(out, 6, centerX, centerY, alongX, outX, outX, outY, local.along0, local.outOuter)
        return out
    }

    private fun put(
        out: FloatArray,
        at: Int,
        centerX: Float,
        centerY: Float,
        alongX: Float,
        alongY: Float,
        outX: Float,
        outY: Float,
        along: Float,
        outward: Float,
    ) {
        out[at] = centerX + along * alongX + outward * outX
        out[at + 1] = centerY + along * alongY + outward * outY
    }

    private companion object {
        /** How long before the tile flight ends the panel should already be there. */
        const val LEAD_SEC = 0.05f
    }
}
