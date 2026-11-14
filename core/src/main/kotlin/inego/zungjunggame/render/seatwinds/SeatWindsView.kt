package inego.zungjunggame.render.seatwinds

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.seats.PlayerLabels
import inego.zungjunggame.render.ui.UiPlate
import inego.zungjunggame.render.wallbuild.WallSeatWindLabels
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/**
 * East-player diagram: the same 東/East rim labels as the wall lesson, with
 * player seats fixed (You / Right / Opposite / Left). Beat 2 loops pause → 90° ccw
 * → pause.
 */
class SeatWindsView {

    companion object {
        private val STILL = SeatWindsBeat(
            bodyKey = "",
            rotateDeal = false,
            pulseEast = true,
        )
        /** Same 800×40 mm scale as the wall lesson so 東/East matches that size. */
        private const val TABLE_MM = 800f
        private const val TILE_MM = 40f
    }

    private val glyphLayout = GlyphLayout()
    private val ivory = AppPalette.ink
    private val accent = AppPalette.accent
    private val windLabels = WallSeatWindLabels()

    private var beat: SeatWindsBeat = STILL
    private var active = false
    private var elapsed = 0f

    private var areaLeft = 0f
    private var areaBottom = 0f
    private var areaW = 0f
    private var areaH = 0f
    private var hasArea = false

    fun present(bodyKey: String) {
        val next = SeatWindsBeats.of(bodyKey) ?: STILL
        if (next.bodyKey != beat.bodyKey) {
            elapsed = 0f
            windLabels.reset()
        }
        beat = next
        active = true
    }

    fun suspend() {
        active = false
    }

    /**
     * A clock in this view is running, so the surface must keep presenting.
     *
     * The same contract [inego.zungjunggame.render.wallbuild.WallBuildView.needsFrames]
     * publishes for the wall lesson's own seat-wind pulse, and for the same reason:
     * under `setContinuousRendering(false)` a frame is only presented when something
     * asks for one, so a clock that is not reported here does not animate — it
     * advances only when unrelated input happens to deliver a frame, which reads as
     * a hard freeze on an untouched screen and uneven stepping under a moving mouse.
     *
     * Both beats loop, so this is true for the whole time the diagram is up rather
     * than only while a transition runs. A beat with neither clock (a future still
     * assignment) settles and stops asking.
     */
    fun needsFrames(): Boolean = active && (beat.rotateDeal || beat.pulseEast)

    fun ensureArea(left: Float, bottom: Float, w: Float, h: Float) {
        areaLeft = left
        areaBottom = bottom
        areaW = w
        areaH = h
        hasArea = w > 1f && h > 1f
    }

    fun update(dtSec: Float) {
        if (!active || dtSec <= 0f) return
        if (beat.rotateDeal) {
            elapsed += dtSec
            val loop = SeatWindsMotion.loopSec
            if (elapsed >= loop) elapsed %= loop
        }
        windLabels.update(dtSec, beat.pulseEast)
    }

    fun draw(
        batch: SpriteBatch,
        whitePixel: Texture,
        alpha: Float,
    ) {
        if (!active || !hasArea || alpha < 0.01f) return
        val phase = if (beat.rotateDeal) {
            SeatWindsMotion.phase(elapsed)
        } else {
            SeatWindsMotion.Phase(steps = 0, rotateT = 0f)
        }
        val seatFont = Fonts.small
        val metrics = measureMetrics(seatFont)
        val plan = SeatWindsLayout.plan(
            left = areaLeft,
            bottom = areaBottom,
            areaW = areaW,
            areaH = areaH,
            rotationTurns = phase.rotationTurns,
            metrics = metrics,
        )
        if (plan.squareSide < 8f) return

        drawTable(batch, whitePixel, plan, alpha)
        drawSeatCaptions(batch, seatFont, plan, alpha)
        if (beat.rotateDeal && phase.atRest) {
            drawHandNumber(batch, seatFont, plan, phase.handNumber, alpha)
        }
        windLabels.draw(
            batch = batch,
            squareLeft = plan.squareLeft,
            squareBottom = plan.squareBottom,
            squareSide = plan.squareSide,
            tableMm = TABLE_MM,
            tileMm = TILE_MM,
            highlightEast = beat.pulseEast,
            alpha = alpha,
            rotationTurns = phase.rotationTurns,
            dimOthers = beat.pulseEast && !beat.rotateDeal,
        )
    }

    private fun measureMetrics(seatFont: BitmapFont): SeatWindsLayout.Metrics {
        var seatW = 0f
        var seatH = 0f
        for (side in 0..3) {
            val text = PlayerLabels.relativeName(side)
            seatW = max(seatW, Fonts.textWidth(seatFont, text))
            glyphLayout.setText(seatFont, text)
            seatH = max(seatH, glyphLayout.height)
        }
        return SeatWindsLayout.Metrics(
            pad = Fonts.px(6f),
            seatGap = Fonts.px(10f),
            seatLabelW = seatW,
            seatLabelH = seatH,
            windInset = Fonts.px(28f),
        )
    }

    private fun drawTable(
        batch: SpriteBatch,
        whitePixel: Texture,
        plan: SeatWindsLayout.Plan,
        alpha: Float,
    ) {
        val prev = batch.color.cpy()
        batch.setColor(AppPalette.tint(AppPalette.feltShade, 0.35f * alpha))
        batch.draw(
            whitePixel,
            plan.squareLeft,
            plan.squareBottom,
            plan.squareSide,
            plan.squareSide,
        )
        val border = Fonts.px(2.5f).coerceAtLeast(1.5f)
        UiPlate.drawFrame(
            batch, whitePixel,
            plan.squareLeft, plan.squareBottom, plan.squareSide, plan.squareSide,
            accent.r, accent.g, accent.b, 0.55f * alpha, border,
        )
        batch.color = prev
    }

    private fun drawSeatCaptions(
        batch: SpriteBatch,
        font: BitmapFont,
        plan: SeatWindsLayout.Plan,
        alpha: Float,
    ) {
        val color = Color(ivory.r, ivory.g, ivory.b, alpha * 0.88f)
        for ((side, cx, cy) in plan.seats) {
            val text = PlayerLabels.relativeName(side)
            drawCentered(batch, font, text, cx, cy, color)
        }
    }

    private fun drawHandNumber(
        batch: SpriteBatch,
        font: BitmapFont,
        plan: SeatWindsLayout.Plan,
        handNumber: Int,
        alpha: Float,
    ) {
        val text = L10n.format("status.hand", handNumber)
        drawCentered(
            batch, font, text, plan.tableCx, plan.tableCy,
            Color(accent.r, accent.g, accent.b, alpha * 0.95f),
        )
    }

    private fun drawCentered(
        batch: SpriteBatch,
        font: BitmapFont,
        text: String,
        cx: Float,
        cy: Float,
        color: Color,
    ) {
        if (text.isEmpty()) return
        val w = Fonts.textWidth(font, text)
        glyphLayout.setText(font, text)
        val h = glyphLayout.height
        Fonts.drawText(batch, font, text, cx - w * 0.5f, cy + h * 0.5f, color)
    }
}
