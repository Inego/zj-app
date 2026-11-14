package inego.zungjunggame.render.table

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.render.assets.WhitePixel
import kotlin.math.atan2
import kotlin.math.hypot
import inego.zungjunggame.render.ui.AppPalette

/**
 * Reusable renderer for glowing quadratic Bézier leader curves on the table
 * (e.g. claim-target connectors and win-on-discard curves).
 *
 * Draws through the shared [WhitePixel] and never owns or disposes it: a curve
 * drawer outlives context-loss recoveries, so holding a private texture here
 * meant holding a dead GL name.
 *
 * [whitePixel] is a **getter, not a field**, and deliberately so. Recovery drops
 * the shared instance after a context loss and the next [WhitePixel.get] builds a
 * new one; a field cached at construction would keep drawing through a dead GL
 * name forever. A getter keeps every call site unchanged and always current.
 */
class ConnectorCurveDrawer : Disposable {
    val whitePixel: Texture get() = WhitePixel.get()

    data class CurveStyle(
        val outerColor: Color,
        val coreColor: Color,
        val thickness: Float = 2.5f,
        val outerThicknessMultiplier: Float = 2.4f,
        val bendFraction: Float = 0.22f,
        val segments: Int = 36,
    ) {
        companion object {
            /** Soft amber glow for pending discard claim options. */
            val CLAIM_DISCARD = CurveStyle(
                outerColor = AppPalette.tint(AppPalette.accent, 0.22f),
                coreColor = AppPalette.connectorClaim,
                thickness = 2.0f,
                outerThicknessMultiplier = 2.4f,
                bendFraction = 0.22f,
            )

            /** Vibrant orange glow for winning on an opponent's discard (Player not involved). */
            val WIN_ON_DISCARD = CurveStyle(
                outerColor = AppPalette.tint(AppPalette.warning, 0.35f),
                coreColor = AppPalette.connectorWin,
                thickness = 2.5f,
                outerThicknessMultiplier = 2.4f,
                bendFraction = 0.22f,
            )

            /** Emerald green glow when the Player wins on another player's discard. */
            val WIN_ON_DISCARD_PLAYER = CurveStyle(
                outerColor = AppPalette.connectorWinForYouOuter,
                coreColor = AppPalette.connectorWinForYou,
                thickness = 2.5f,
                outerThicknessMultiplier = 2.4f,
                bendFraction = 0.22f,
            )

            /** Crimson red glow when an opponent wins on the Player's discard. */
            val WIN_ON_DISCARD_AGAINST_PLAYER = CurveStyle(
                outerColor = AppPalette.connectorWinAgainstYouOuter,
                coreColor = AppPalette.connectorWinAgainstYou,
                thickness = 2.5f,
                outerThicknessMultiplier = 2.4f,
                bendFraction = 0.22f,
            )

            /**
             * Resolves the appropriate curve style for a win-on-discard event based on player involvement:
             * - Green when the Player wins on an opponent's discard ([winnerSeat] == [playerSeat])
             * - Red when an opponent wins on the Player's discard ([winSource] == [playerSeat])
             * - Orange when neither party is the Player ([winnerSeat] != [playerSeat] && [winSource] != [playerSeat])
             */
            fun forWinOnDiscard(winnerSeat: Int, winSource: Int, playerSeat: Int): CurveStyle =
                when {
                    winnerSeat == playerSeat -> WIN_ON_DISCARD_PLAYER
                    winSource == playerSeat -> WIN_ON_DISCARD_AGAINST_PLAYER
                    else -> WIN_ON_DISCARD
                }
        }
    }

    /**
     * Mathematical representation of a 2D quadratic Bézier curve.
     */
    data class QuadraticBezier(
        val x0: Float,
        val y0: Float,
        val cx: Float,
        val cy: Float,
        val x1: Float,
        val y1: Float,
    ) {
        fun evaluate(t: Float): Pair<Float, Float> {
            val omt = 1f - t
            val x = omt * omt * x0 + 2f * omt * t * cx + t * t * x1
            val y = omt * omt * y0 + 2f * omt * t * cy + t * t * y1
            return x to y
        }
    }

    /**
     * Draws a glowing quadratic Bézier curve from [x0, y0] to [x1, y1], bending inward
     * toward the reference table center ([centerRefX, centerRefY]).
     */
    fun drawArc(
        batch: SpriteBatch,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        centerRefX: Float,
        centerRefY: Float,
        style: CurveStyle,
        overrideThickness: Float? = null,
    ) {
        val bezier = computeArc(
            x0 = x0,
            y0 = y0,
            x1 = x1,
            y1 = y1,
            centerRefX = centerRefX,
            centerRefY = centerRefY,
            bendFraction = style.bendFraction,
        ) ?: return

        val baseThickness = overrideThickness ?: style.thickness
        val outerThickness = baseThickness * style.outerThicknessMultiplier

        drawQuadraticCurve(
            batch = batch,
            bezier = bezier,
            thickness = outerThickness,
            color = style.outerColor,
            segments = style.segments,
        )
        drawQuadraticCurve(
            batch = batch,
            bezier = bezier,
            thickness = baseThickness,
            color = style.coreColor,
            segments = style.segments,
        )
        batch.setColor(Color.WHITE)
    }

    fun drawQuadraticCurve(
        batch: SpriteBatch,
        bezier: QuadraticBezier,
        thickness: Float,
        color: Color,
        segments: Int = 36,
    ) {
        drawQuadraticCurve(
            batch = batch,
            x0 = bezier.x0,
            y0 = bezier.y0,
            cx = bezier.cx,
            cy = bezier.cy,
            x1 = bezier.x1,
            y1 = bezier.y1,
            thickness = thickness,
            color = color,
            segments = segments,
        )
    }

    fun drawQuadraticCurve(
        batch: SpriteBatch,
        x0: Float,
        y0: Float,
        cx: Float,
        cy: Float,
        x1: Float,
        y1: Float,
        thickness: Float,
        color: Color,
        segments: Int = 36,
    ) {
        batch.setColor(color)
        val segs = segments.coerceAtLeast(1)
        var prevX = x0
        var prevY = y0
        for (i in 1..segs) {
            val t = i / segs.toFloat()
            val omt = 1f - t
            val x = omt * omt * x0 + 2f * omt * t * cx + t * t * x1
            val y = omt * omt * y0 + 2f * omt * t * cy + t * t * y1
            drawThickSegment(batch, prevX, prevY, x, y, thickness)
            prevX = x
            prevY = y
        }
    }

    fun drawThickSegment(
        batch: SpriteBatch,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        thickness: Float,
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx, dy)
        if (len < 0.25f) return
        val angleDeg = atan2(dy, dx) * RAD_TO_DEG
        batch.draw(
            whitePixel,
            x0,
            y0 - thickness * 0.5f,
            0f,
            thickness * 0.5f,
            len,
            thickness,
            1f,
            1f,
            angleDeg,
            0,
            0,
            1,
            1,
            false,
            false,
        )
    }

    /** Nothing to release: [whitePixel] is shared. */
    override fun dispose() = Unit

    companion object {
        private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()

        /**
         * Pure mathematical calculation of the inward-bending quadratic Bézier arc.
         * Returns null if chord distance is less than 1 pixel.
         */
        fun computeArc(
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
            centerRefX: Float,
            centerRefY: Float,
            bendFraction: Float = 0.22f,
        ): QuadraticBezier? {
            val chordLen = hypot(x1 - x0, y1 - y0)
            if (chordLen < 1f) return null

            val midX = (x0 + x1) * 0.5f
            val midY = (y0 + y1) * 0.5f
            val nx = -(y1 - y0) / chordLen
            val ny = (x1 - x0) / chordLen

            val towardCenter = (centerRefX - midX) * nx + (centerRefY - midY) * ny
            val bendSign = if (towardCenter >= 0f) 1f else -1f
            val bend = chordLen * bendFraction
            val cpx = midX + nx * bend * bendSign
            val cpy = midY + ny * bend * bendSign

            return QuadraticBezier(x0, y0, cpx, cpy, x1, y1)
        }
    }
}
