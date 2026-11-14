package inego.zungjunggame.render.seatwinds

import inego.zungjunggame.engine.Tiles
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Geometry for four seat-wind labels on a table square.
 *
 * Side 0 = You (home East), then ccw: 1 right, 2 opposite, 3 left.
 * Player captions stay on the outer edges; winds sit inside the rim and
 * orbit with [rotationTurns]. +1 = every wind one seat counter-clockwise.
 */
object SeatWindsLayout {

    val WIND_TYPES: IntArray = intArrayOf(Tiles.EAST, Tiles.SOUTH, Tiles.WEST, Tiles.NORTH)

    data class Metrics(
        val pad: Float,
        val seatGap: Float,
        val seatLabelW: Float,
        val seatLabelH: Float,
        val windInset: Float,
    )

    data class WindPose(
        val tileType: Int,
        val homeSide: Int,
        val cx: Float,
        val cy: Float,
    )

    data class SeatCaption(
        val side: Int,
        val cx: Float,
        val cy: Float,
    )

    data class Plan(
        val squareLeft: Float,
        val squareBottom: Float,
        val squareSide: Float,
        val tableCx: Float,
        val tableCy: Float,
        val winds: List<WindPose>,
        val seats: List<SeatCaption>,
    )

    /**
     * Home angle for [side] in radians, Y-up, 0 = right, increasing ccw.
     * Bottom = −π/2, right = 0, top = π/2, left = π.
     */
    fun homeAngleRad(side: Int): Float =
        (side and 3) * (PI.toFloat() * 0.5f) - (PI.toFloat() * 0.5f)

    fun plan(
        left: Float,
        bottom: Float,
        areaW: Float,
        areaH: Float,
        rotationTurns: Float,
        metrics: Metrics,
    ): Plan {
        val innerW = (areaW - metrics.pad * 2f).coerceAtLeast(0f)
        val innerH = (areaH - metrics.pad * 2f).coerceAtLeast(0f)
        val marginX = metrics.seatGap + metrics.seatLabelW
        val marginY = metrics.seatGap + metrics.seatLabelH
        val square = min(innerW - 2f * marginX, innerH - 2f * marginY).coerceAtLeast(0f)
        val tableCx = left + areaW * 0.5f
        val tableCy = bottom + areaH * 0.5f
        val squareLeft = tableCx - square * 0.5f
        val squareBottom = tableCy - square * 0.5f
        val half = square * 0.5f
        val radius = (half - metrics.windInset).coerceAtLeast(0f)
        val quarter = PI.toFloat() * 0.5f

        val winds = WIND_TYPES.mapIndexed { i, type ->
            val angle = homeAngleRad(i) + rotationTurns * quarter
            WindPose(
                tileType = type,
                homeSide = i,
                cx = tableCx + cos(angle) * radius,
                cy = tableCy + sin(angle) * radius,
            )
        }

        val seats = (0..3).map { side ->
            val out = half + metrics.seatGap
            when (side) {
                0 -> SeatCaption(0, tableCx, tableCy - out - metrics.seatLabelH * 0.5f)
                1 -> SeatCaption(1, tableCx + out + metrics.seatLabelW * 0.5f, tableCy)
                2 -> SeatCaption(2, tableCx, tableCy + out + metrics.seatLabelH * 0.5f)
                else -> SeatCaption(3, tableCx - out - metrics.seatLabelW * 0.5f, tableCy)
            }
        }

        return Plan(
            squareLeft = squareLeft,
            squareBottom = squareBottom,
            squareSide = square,
            tableCx = tableCx,
            tableCy = tableCy,
            winds = winds,
            seats = seats,
        )
    }
}
