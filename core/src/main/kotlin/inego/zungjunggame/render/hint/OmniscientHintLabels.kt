package inego.zungjunggame.render.hint

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.hints.OmniscientHintArmSnapshot
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.table.HandHit
import inego.zungjunggame.render.ui.AppPalette
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Compact per-option best-known score labels for Omniscient
 * ("God guidance") hints above hand tiles and claim buttons.
 *
 * Drawn directly on the felt (no dark box) using the shadow-free full-alpha
 * [Fonts.tileLabel] face with a crisp dark 8-way outline so digits stay
 * sharp on both the green felt and overlapping white discard-river tiles.
 */
object OmniscientHintLabels {
    /** Design size of [Fonts.tileLabel] is 24 px; 0.625× yields ~15 px crisp digits. */
    private const val BASE_SCALE = 0.625f
    /** Larger scale for the best (yellow) score so the top option stands out immediately. */
    private const val BEST_BASE_SCALE = 0.78f
    private const val MIN_LABEL_SCALE = 0.55f

    private val OUTLINE_COLOR: Color = AppPalette.tint(AppPalette.panelDeep, 0.90f)
    private val BEST_INK: Color = AppPalette.accentBright
    private val WIN_INK: Color = AppPalette.inkStrong
    private val EMPTY_INK: Color = AppPalette.inkMuted
    private val BOLT_INK: Color = AppPalette.accentBright
    private const val BOLT_HEIGHT_RATIO = 1.08f
    private const val BOLT_ASPECT = 0.62f

    /** Reusable 4-vertex (20-float) buffer for drawing vector triangles via [WhitePixel]. */
    private val triVerts = FloatArray(20)

    /** 8-way unit offsets for the crisp dark text outline. */
    private val OUTLINE_OFFSETS = floatArrayOf(
        -1f, 0f,
        1f, 0f,
        0f, -1f,
        0f, 1f,
        -0.707f, -0.707f,
        0.707f, -0.707f,
        -0.707f, 0.707f,
        0.707f, 0.707f,
    )

    /** Best `(score, minSteps)` across [arms], where faster wins break ties on top score. */
    fun bestOutcome(arms: List<OmniscientHintArmSnapshot>): Pair<Int, Int> {
        var maxScore = 0
        var minSteps = Int.MAX_VALUE
        for (arm in arms) {
            if (arm.bestScore <= 0) continue
            val steps = if (arm.bestSteps > 0) arm.bestSteps else Int.MAX_VALUE
            if (arm.bestScore > maxScore || (arm.bestScore == maxScore && steps < minSteps)) {
                maxScore = arm.bestScore
                minSteps = steps
            }
        }
        return maxScore to minSteps
    }

    fun isBestArm(arm: OmniscientHintArmSnapshot, maxScore: Int, minStepsAtMax: Int): Boolean {
        if (maxScore <= 0 || arm.bestScore != maxScore) return false
        val steps = if (arm.bestSteps > 0) arm.bestSteps else Int.MAX_VALUE
        return steps == minStepsAtMax
    }

    /** Lowest positive step count across all winning [arms], or [Int.MAX_VALUE] if none win. */
    fun minSteps(arms: List<OmniscientHintArmSnapshot>): Int {
        var best = Int.MAX_VALUE
        for (arm in arms) {
            if (arm.bestScore > 0 && arm.bestSteps in 1 until best) {
                best = arm.bestSteps
            }
        }
        return best
    }

    fun isFastestArm(arm: OmniscientHintArmSnapshot, minStepsAll: Int): Boolean =
        minStepsAll < Int.MAX_VALUE && arm.bestScore > 0 && arm.bestSteps == minStepsAll

    /**
     * Draw one outlined score (plus a High Voltage bolt when [isFastest]) centered
     * at [cx], sitting just above [choiceTopY]. Scales uniformly to fit [maxWidth]
     * so narrow portrait hand columns never collide.
     */
    fun drawScoreBadge(
        batch: SpriteBatch,
        cx: Float,
        choiceTopY: Float,
        gapAboveChoice: Float,
        arm: OmniscientHintArmSnapshot,
        isBest: Boolean,
        isFastest: Boolean = false,
        maxWidth: Float = Float.POSITIVE_INFINITY,
        glyphLayout: GlyphLayout = GlyphLayout(),
    ) {
        Fonts.ensureCreated()
        val font = Fonts.tileLabel
        val hasWin = arm.bestScore > 0
        val showBolt = hasWin && isFastest
        val mainText = when {
            hasWin -> arm.bestScore.toString()
            arm.playouts > 0 || arm.solved -> "\u2014"
            else -> "\u2026"
        }
        val baseScale = if (hasWin && isBest) BEST_BASE_SCALE else BASE_SCALE
        val outlinePx = max(1f, Fonts.px(1.25f))
        val rawBoltGap = if (showBolt) Fonts.px(1.5f) else 0f

        val prevScaleX = font.scaleX
        val prevScaleY = font.scaleY
        val prevColor = font.color.cpy()

        font.data.setScale(prevScaleX * baseScale, prevScaleY * baseScale)
        glyphLayout.setText(font, mainText)
        val rawMainW = glyphLayout.width
        val rawMainH = glyphLayout.height
        font.data.setScale(prevScaleX, prevScaleY)

        val rawBoltW = if (showBolt) rawMainH * BOLT_HEIGHT_RATIO * BOLT_ASPECT else 0f
        val rawW = rawMainW + rawBoltGap + rawBoltW + outlinePx * 2f
        val fitScale =
            if (maxWidth.isFinite() && rawW > maxWidth && rawW > 0f) {
                (maxWidth / rawW).coerceIn(MIN_LABEL_SCALE, 1f)
            } else {
                1f
            }

        val mainScale = baseScale * fitScale
        val effOutline = max(1f, outlinePx * fitScale)
        val effBoltGap = rawBoltGap * fitScale

        try {
            font.data.setScale(prevScaleX * mainScale, prevScaleY * mainScale)
            glyphLayout.setText(font, mainText)
            val mainW = glyphLayout.width
            val mainH = glyphLayout.height
            val boltH = if (showBolt) mainH * BOLT_HEIGHT_RATIO else 0f
            val boltW = if (showBolt) boltH * BOLT_ASPECT else 0f

            val contentW = mainW + effBoltGap + boltW
            val contentLeft = cx - contentW * 0.5f
            val baseBottom = choiceTopY + gapAboveChoice + effOutline
            val mainBaseline = baseBottom + mainH
            val mainInk = when {
                !hasWin -> EMPTY_INK
                isBest -> BEST_INK
                else -> WIN_INK
            }

            drawOutlined(batch, font, mainText, contentLeft, mainBaseline, effOutline, mainInk)
            if (showBolt) {
                val boltLeft = contentLeft + mainW + effBoltGap
                val boltBottom = baseBottom + (mainH - boltH) * 0.5f
                drawOutlinedBolt(batch, boltLeft, boltBottom, boltW, boltH, effOutline)
            }
        } finally {
            font.data.setScale(prevScaleX, prevScaleY)
            font.color = prevColor
        }
    }

    /**
     * Draw a crisp vector High Voltage bolt (⚡) via [WhitePixel] triangles with an
     * 8-way dark outline matching the score digits. Drawn as geometry rather than a
     * font glyph because neither bundled font (Roboto / NotoSansSC) ships U+26A1.
     */
    private fun drawOutlinedBolt(
        batch: SpriteBatch,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        outlinePx: Float,
    ) {
        val outlineBits = OUTLINE_COLOR.toFloatBits()
        for (i in OUTLINE_OFFSETS.indices step 2) {
            drawBoltFill(
                batch,
                left + OUTLINE_OFFSETS[i] * outlinePx,
                bottom + OUTLINE_OFFSETS[i + 1] * outlinePx,
                w,
                h,
                outlineBits,
            )
        }
        drawBoltFill(batch, left, bottom, w, h, BOLT_INK.toFloatBits())
    }

    private fun drawBoltFill(
        batch: SpriteBatch,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        colorBits: Float,
    ) {
        // Upper wedge: top-right peak → mid-left corner → inner right waist
        drawTriangle(
            batch,
            left + w * 0.64f, bottom + h * 1.00f,
            left + w * 0.04f, bottom + h * 0.42f,
            left + w * 0.54f, bottom + h * 0.42f,
            colorBits,
        )
        // Lower wedge: inner left waist → mid-right corner → bottom-left tip
        drawTriangle(
            batch,
            left + w * 0.46f, bottom + h * 0.58f,
            left + w * 0.96f, bottom + h * 0.58f,
            left + w * 0.36f, bottom + h * 0.00f,
            colorBits,
        )
    }

    private fun drawTriangle(
        batch: SpriteBatch,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        colorBits: Float,
    ) {
        val v = triVerts
        v[0] = x0; v[1] = y0; v[2] = colorBits; v[3] = 0.5f; v[4] = 0.5f
        v[5] = x1; v[6] = y1; v[7] = colorBits; v[8] = 0.5f; v[9] = 0.5f
        v[10] = x2; v[11] = y2; v[12] = colorBits; v[13] = 0.5f; v[14] = 0.5f
        v[15] = x2; v[16] = y2; v[17] = colorBits; v[18] = 0.5f; v[19] = 0.5f
        batch.draw(WhitePixel.get(), v, 0, 20)
    }

    private fun drawOutlined(
        batch: SpriteBatch,
        font: BitmapFont,
        text: String,
        x: Float,
        y: Float,
        outlinePx: Float,
        ink: Color,
    ) {
        font.color = OUTLINE_COLOR
        for (i in OUTLINE_OFFSETS.indices step 2) {
            font.draw(batch, text, x + OUTLINE_OFFSETS[i] * outlinePx, y + OUTLINE_OFFSETS[i + 1] * outlinePx)
        }
        font.color = ink
        font.draw(batch, text, x, y)
    }

    /** Compact throughput string (e.g. `542 eval/s`, `1.2k eval/s`, `8.4 eval/s`), or null if unmeasured. */
    fun formatEvalsPerSec(evalsPerSec: Float): String? {
        if (!evalsPerSec.isFinite() || evalsPerSec <= 0f) return null
        val number = when {
            evalsPerSec >= 1000f -> {
                val tenths = (evalsPerSec / 100f).roundToInt()
                "${tenths / 10}.${tenths % 10}k"
            }
            evalsPerSec >= 10f -> evalsPerSec.roundToInt().toString()
            else -> {
                val tenths = (evalsPerSec * 10f).roundToInt()
                "${tenths / 10}.${tenths % 10}"
            }
        }
        return L10n.format("hint.omniscient.evals_per_sec", number)
    }

    /** Draw Omniscient score labels above human hand tiles for all legal discard arms. */
    fun drawAboveHands(
        batch: SpriteBatch,
        arms: List<OmniscientHintArmSnapshot>,
        hits: List<HandHit>,
        glyphLayout: GlyphLayout,
        tileTopY: (HandHit) -> Float = { it.worldY + it.h * 0.5f },
    ) {
        if (arms.isEmpty() || hits.isEmpty()) return
        val (maxScore, minStepsAtMax) = bestOutcome(arms)
        val minStepsAll = minSteps(arms)
        val byTile = HashMap<Int, OmniscientHintArmSnapshot>(arms.size)
        for (arm in arms) {
            if (arm.choice.kind == ChoiceKind.Discard) {
                byTile[arm.choice.tile()] = arm
            }
        }
        if (byTile.isEmpty()) return

        val gapAboveChoice = Fonts.px(3f)
        for (hit in hits) {
            val arm = byTile[hit.tileType] ?: continue
            val isBest = isBestArm(arm, maxScore, minStepsAtMax)
            val isFastest = isFastestArm(arm, minStepsAll)
            drawScoreBadge(
                batch = batch,
                cx = hit.worldX,
                choiceTopY = tileTopY(hit),
                gapAboveChoice = gapAboveChoice,
                arm = arm,
                isBest = isBest,
                isFastest = isFastest,
                maxWidth = hit.w * if (isBest) 1.04f else 0.96f,
                glyphLayout = glyphLayout,
            )
        }
    }
}
