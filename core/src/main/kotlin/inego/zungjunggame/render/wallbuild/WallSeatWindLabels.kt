package inego.zungjunggame.render.wallbuild

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.fonts.Fonts
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import inego.zungjunggame.render.ui.AppPalette

/**
 * Large Traditional Chinese + English seat winds on the four edges of the
 * yellow table square (not the rotated wall segments). Home assignment:
 * bottom = East, right = South, top = West, left = North — just inside the
 * rim. Never a tile graphic.
 */
class WallSeatWindLabels {

    companion object {
        /** Traditional Chinese seat-wind characters (東南西北). */
        private const val SEAT_WIND_HAN = "東南西北"
        private const val PULSE_PERIOD_SEC = 1.1f
    }

    private val ivory = AppPalette.ink
    private val accent = AppPalette.accent
    private var eastPulsePhase = 0f

    fun reset() {
        eastPulsePhase = 0f
    }

    /** Advance East pulse when [highlightEast] is active. */
    fun update(dtSec: Float, highlightEast: Boolean) {
        if (dtSec <= 0f || !highlightEast) return
        eastPulsePhase =
            (eastPulsePhase + dtSec * (2f * PI.toFloat() / PULSE_PERIOD_SEC)) % (2f * PI.toFloat())
    }

    /**
     * @param highlightEast East pulses; other winds dim when [dimOthers] is true.
     * @param rotationTurns orbits every wind counter-clockwise (1 = one seat).
     */
    fun draw(
        batch: SpriteBatch,
        squareLeft: Float,
        squareBottom: Float,
        squareSide: Float,
        tableMm: Float,
        tileMm: Float,
        highlightEast: Boolean,
        alpha: Float,
        /**
         * Extra px toward the table center past the normal rim pad. Used on the
         * deal-hands beat so labels sit inside the hand squares instead of under them.
         */
        inwardShiftPx: Float = 0f,
        /** Completed seats + fractional ease; 0 keeps the home assignment. */
        rotationTurns: Float = 0f,
        /** When false, East still pulses but South / West / North stay full alpha. */
        dimOthers: Boolean = true,
    ) {
        if (squareSide < 8f) return
        Fonts.ensureFallback()
        // Skip 東南西北 when no CJK face loaded (tofu otherwise).
        val showHan = Fonts.cjkCapable
        if (showHan) Fonts.warmGlyphs(SEAT_WIND_HAN)

        // Large face rasterized at design size 48 — draw at ≤1 scale (never upscale
        // bitmap fonts; that is what made Desktop Han blurry).
        val hanFont = Fonts.display
        val enFont = Fonts.button
        val pxPerMm = (squareSide / tableMm).coerceAtLeast(1e-4f)
        val tileH = tileMm * pxPerMm
        val hanNativeH =
            if (showHan) measureMixedH(hanFont, "東", 1f).coerceAtLeast(1f) else 1f
        // Shrink only on tiny tables; never scale above 1 (sharp FreeType pixels).
        val maxHanH = min(tileH * 1.35f, Fonts.px(52f)).coerceAtLeast(Fonts.px(22f))
        val hanScale = if (showHan) min(1f, maxHanH / hanNativeH) else 1f
        val enScale = 1f
        // Clear air between 東 and East.
        val gap = if (showHan) Fonts.px(12f) else 0f
        // Soft pulse on East when highlighted (layout uses unscaled size so the
        // label does not jump around the rim).
        val eastPulse = if (highlightEast) 1f + 0.10f * sin(eastPulsePhase) else 1f

        // side → (han, english key); 0 bottom, 1 right, 2 top, 3 left.
        data class Seat(val side: Int, val han: String, val enKey: String)
        val seats = listOf(
            Seat(0, "東", "wind.east"),
            Seat(1, "南", "wind.south"),
            Seat(2, "西", "wind.west"),
            Seat(3, "北", "wind.north"),
        )
        for ((side, han, enKey) in seats) {
            val isEast = side == 0
            val dim = if (highlightEast && dimOthers && !isEast) 0.28f else 1f
            val pulse = if (highlightEast && isEast) eastPulse else 1f
            val seatHanScale = hanScale * pulse
            val seatEnScale = enScale * pulse
            val hanA = Color(accent.r, accent.g, accent.b, alpha * dim)
            val enA = Color(ivory.r, ivory.g, ivory.b, alpha * 0.95f * dim)
            val en = L10n.get(enKey)
            // Anchor from unscaled stack so the rim slot stays put while East pulses.
            val hanW0 = if (showHan) measureMixedW(hanFont, han, hanScale) else 0f
            val hanH0 = if (showHan) measureMixedH(hanFont, han, hanScale) else 0f
            val enW0 = measureMixedW(enFont, en, enScale)
            val enH0 = measureMixedH(enFont, en, enScale)
            val (scx, scy) = tableEdgeLabelCenter(
                squareLeft = squareLeft,
                squareBottom = squareBottom,
                squareSide = squareSide,
                side = side,
                stackW = max(hanW0, enW0),
                stackH = if (showHan) hanH0 + gap + enH0 else enH0,
                inwardShiftPx = inwardShiftPx.coerceAtLeast(0f),
                rotationTurns = rotationTurns,
            )
            val hanW = if (showHan) measureMixedW(hanFont, han, seatHanScale) else 0f
            val hanH = if (showHan) measureMixedH(hanFont, han, seatHanScale) else 0f
            val enW = measureMixedW(enFont, en, seatEnScale)
            val enH = measureMixedH(enFont, en, seatEnScale)
            val stackH = if (showHan) hanH + gap + enH else enH
            val stackTop = scy + stackH * 0.5f
            if (showHan) {
                drawScaledMixed(
                    batch, hanFont, han, scx - hanW * 0.5f, stackTop, seatHanScale, hanA,
                )
            }
            drawScaledMixed(
                batch, enFont, en,
                scx - enW * 0.5f,
                if (showHan) stackTop - hanH - gap else stackTop,
                seatEnScale, enA,
            )
        }
    }

    /**
     * Label center just inside the yellow table-square edge for [side]
     * (0 bottom, 1 right, 2 top, 3 left). Independent of wall pinwheel geometry.
     * [inwardShiftPx] pulls the label toward the table center (deal-hands beat).
     * [rotationTurns] orbits that home slot counter-clockwise on the inset ellipse.
     */
    private fun tableEdgeLabelCenter(
        squareLeft: Float,
        squareBottom: Float,
        squareSide: Float,
        side: Int,
        stackW: Float,
        stackH: Float,
        inwardShiftPx: Float = 0f,
        rotationTurns: Float = 0f,
    ): Pair<Float, Float> {
        val midX = squareLeft + squareSide * 0.5f
        val midY = squareBottom + squareSide * 0.5f
        val half = squareSide * 0.5f
        // Inset so the stack sits fully inside the rim (pad past the accent border).
        val border = Fonts.px(2.5f).coerceAtLeast(1.5f)
        val pad = border + Fonts.px(6f) + inwardShiftPx
        val rx = (half - pad - stackW * 0.5f).coerceAtLeast(0f)
        val ry = (half - pad - stackH * 0.5f).coerceAtLeast(0f)
        val angle = homeAngleRad(side) + rotationTurns * (PI.toFloat() * 0.5f)
        return (midX + cos(angle) * rx) to (midY + sin(angle) * ry)
    }

    /** Home angle for [side] in radians, Y-up, 0 = right, increasing ccw. */
    private fun homeAngleRad(side: Int): Float =
        (side and 3) * (PI.toFloat() * 0.5f) - (PI.toFloat() * 0.5f)

    /** Scale + measure in one block so the box matches what [drawScaledMixed] paints. */
    private fun measureMixedW(font: BitmapFont, text: String, scale: Float): Float =
        Fonts.withFaceScale(font, scale) { Fonts.textWidth(font, text) }

    private fun measureMixedH(font: BitmapFont, text: String, scale: Float): Float =
        Fonts.withFaceScale(font, scale) { Fonts.textHeight(font, text) }

    private fun drawScaledMixed(
        batch: SpriteBatch,
        font: BitmapFont,
        text: String,
        x: Float,
        baselineY: Float,
        scale: Float,
        color: Color,
    ) = Fonts.withFaceScale(font, scale) {
        Fonts.drawText(batch, font, text, x, baselineY, color)
    }
}
