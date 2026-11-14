package inego.zungjunggame.render.hint

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.hints.McHintArmSnapshot
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.table.HandHit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/**
 * Compact avgΔ / playout labels on HINT options (hand tiles and claim buttons).
 *
 * Score deltas span hundreds of points; fixed 3-decimal strings like
 * `+1234.567` are wider than a phone hand tile and stack into neighbors.
 * [formatAvgDelta] uses fewer decimals as magnitude grows so typical labels
 * stay short. Both lines shrink uniformly when [drawTwoLine] is given a
 * finite [maxWidth] (portrait Android hands).
 */
object McHintLabels {
    /** Floor so dense portrait hands stay legible after width fitting. */
    private const val MIN_LABEL_SCALE = 0.55f

    /** Playout count is secondary: smaller than avg Δ. */
    const val PLAYOUTS_SCALE = 0.78f

    /**
     * Playout count is secondary to avg Δ. Dark gray vanishes on the green felt;
     * use a soft light gray so it stays readable but clearly quieter than avg Δ.
     */
    private val PLAYOUTS_COLOR = AppPalette.tint(AppPalette.inkMuted, 0.88f)

    /**
     * Compact signed avg Δ for on-tile labels.
     */
    fun formatAvgDelta(avgDelta: Double): String {
        val a = abs(avgDelta)
        return when {
            a >= 100.0 -> String.format(Locale.US, "%+.0f", avgDelta)
            a >= 10.0 -> String.format(Locale.US, "%+.1f", avgDelta)
            else -> String.format(Locale.US, "%+.2f", avgDelta)
        }
    }

    fun formatPlayouts(n: Int): String = n.toString()

    /**
     * Two-line MC label: avg Δ on top (caller font color), playout count below
     * in a smaller dark-gray style.
     *
     * [choiceTopY] is the top of the tile/button. The playout line is laid out so
     * its full glyph box sits just above that edge (+ [gapAboveChoice]); avg Δ
     * stacks above it. Callers must not pass a baseline — BitmapFont Y is baseline,
     * so a raw "top + small pad" would clip the lower line into the choice.
     *
     * When [maxWidth] is finite and either line is wider, both lines shrink
     * uniformly so the label stays inside that column (portrait Android hands).
     */
    fun drawTwoLine(
        batch: SpriteBatch,
        font: BitmapFont,
        cx: Float,
        choiceTopY: Float,
        gapAboveChoice: Float,
        avgDelta: Double,
        n: Int,
        lineGap: Float,
        maxWidth: Float = Float.POSITIVE_INFINITY,
        glyphLayout: GlyphLayout = GlyphLayout(),
    ) {
        val avgText = formatAvgDelta(avgDelta)
        val nText = formatPlayouts(n)

        val prevScaleX = font.scaleX
        val prevScaleY = font.scaleY
        val prevColor = font.color.cpy()

        glyphLayout.setText(font, avgText)
        val rawAvgW = glyphLayout.width
        font.data.setScale(prevScaleX * PLAYOUTS_SCALE, prevScaleY * PLAYOUTS_SCALE)
        glyphLayout.setText(font, nText)
        val rawNW = glyphLayout.width
        font.data.setScale(prevScaleX, prevScaleY)

        val rawMaxW = max(rawAvgW, rawNW)
        val fitScale =
            if (maxWidth.isFinite() && rawMaxW > maxWidth && rawMaxW > 0f) {
                (maxWidth / rawMaxW).coerceIn(MIN_LABEL_SCALE, 1f)
            } else {
                1f
            }

        try {
            val nScale = prevScaleX * PLAYOUTS_SCALE * fitScale
            font.data.setScale(nScale, prevScaleY * PLAYOUTS_SCALE * fitScale)
            glyphLayout.setText(font, nText)
            val nW = glyphLayout.width
            val nH = glyphLayout.height
            // Baseline so the glyph box clears the choice (layout.height ≈ ascent for digits).
            val nBaseline = choiceTopY + gapAboveChoice + nH
            font.color = PLAYOUTS_COLOR
            font.draw(batch, nText, cx - nW * 0.5f, nBaseline)

            font.data.setScale(prevScaleX * fitScale, prevScaleY * fitScale)
            font.color = prevColor
            glyphLayout.setText(font, avgText)
            val avgW = glyphLayout.width
            val avgH = glyphLayout.height
            // FreeType shadow sits below glyphs; leave a little extra air between lines.
            val stackGap = max(lineGap, avgH * 0.12f)
            val avgBaseline = nBaseline + stackGap + avgH
            font.draw(batch, avgText, cx - avgW * 0.5f, avgBaseline)
        } finally {
            font.data.setScale(prevScaleX, prevScaleY)
            font.color = prevColor
        }
    }

    /** Labels above human hand tiles for discard options with MC stats. */
    fun drawAboveHands(
        batch: SpriteBatch,
        arms: List<McHintArmSnapshot>,
        hits: List<HandHit>,
        glyphLayout: GlyphLayout,
    ) {
        if (arms.isEmpty() || hits.isEmpty()) return
        val byTile = HashMap<Int, McHintArmSnapshot>(8)
        for (arm in arms) {
            if (arm.choice.kind != ChoiceKind.Discard) continue
            if (arm.n <= 0) continue
            byTile[arm.choice.tile()] = arm
        }
        if (byTile.isEmpty()) return

        val font = Fonts.small
        val prev = font.color.cpy()
        font.color = AppPalette.tint(AppPalette.accentBright, 0.95f)
        val lineGap = Fonts.px(2f)
        val gapAboveChoice = Fonts.px(2f)
        for ((worldX, worldY, w, h, tileType) in hits) {
            val arm = byTile[tileType] ?: continue
            drawTwoLine(
                batch = batch,
                font = font,
                cx = worldX,
                // Top edge of the tile; helper lifts playout line fully above it.
                choiceTopY = worldY + h * 0.5f,
                gapAboveChoice = gapAboveChoice,
                avgDelta = arm.avgDelta,
                n = arm.n,
                lineGap = lineGap,
                // Keep each label inside its tile column so portrait hand rows
                // (narrow tiles + density-scaled fonts) do not pile onto neighbors.
                maxWidth = w * 0.96f,
                glyphLayout = glyphLayout,
            )
        }
        font.color = prev
    }
}
