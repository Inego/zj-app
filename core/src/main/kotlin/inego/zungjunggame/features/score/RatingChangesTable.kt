package inego.zungjunggame.features.score

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.seats.PlayerLabels
import inego.zungjunggame.render.online.RatingSeatLine
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/**
 * Dedicated component that measures and paints post-game rating changes
 * as a neat, formatted, colored table matching the hand score deltas table.
 */
class RatingChangesTable {
    private val tint = Color()
    private val scratchBotColor = Color()

    data class ColumnMetrics(
        val nameW: Float,
        val beforeW: Float,
        val deltaW: Float,
        val afterW: Float,
        val colGap: Float,
        val contentW: Float,
        val blockH: Float,
    )

    fun measure(
        lines: List<RatingSeatLine>,
        font: BitmapFont,
        glyph: GlyphLayout,
        captionFont: BitmapFont = font,
        caption: String = L10n.get("hand.rating_changes"),
        colGap: Float = Fonts.px(16f),
        minNameW: Float = 0f,
        minBeforeW: Float = 0f,
        minDeltaW: Float = 0f,
        minAfterW: Float = 0f,
        dividerH: Float = max(1f, Fonts.px(1.5f)),
        sectionGap: Float = Fonts.px(18f),
        titleGapTop: Float = Fonts.px(10f),
        titleGapBottom: Float = Fonts.px(8f),
        rowGap: Float = Fonts.px(4f),
    ): ColumnMetrics {
        if (lines.isEmpty()) {
            return ColumnMetrics(
                nameW = 0f,
                beforeW = 0f,
                deltaW = 0f,
                afterW = 0f,
                colGap = colGap,
                contentW = 0f,
                blockH = 0f,
            )
        }

        var nameW = minNameW
        var beforeW = minBeforeW
        var deltaW = minDeltaW
        var afterW = minAfterW
        for ((name, before, delta, after, _, _, _, pending) in lines) {
            nameW = max(nameW, Fonts.textWidth(font, name))
            if (!pending) {
                glyph.setText(font, before.toString())
                beforeW = max(beforeW, glyph.width)
                glyph.setText(font, ScoreBreakdownText.formatSignedDelta(delta))
                deltaW = max(deltaW, glyph.width)
                glyph.setText(font, after.toString())
                afterW = max(afterW, glyph.width)
            } else {
                glyph.setText(font, L10n.get("history.rating_pending"))
                val pendingW = glyph.width
                val scoreAreaW = beforeW + deltaW + afterW + colGap * 2f
                if (pendingW > scoreAreaW) {
                    val extra = (pendingW - scoreAreaW) / 3f
                    beforeW += extra
                    deltaW += extra
                    afterW += extra
                }
            }
        }

        glyph.setText(font, "0000")
        beforeW = max(beforeW, glyph.width)
        afterW = max(afterW, glyph.width)
        glyph.setText(font, "+0000")
        deltaW = max(deltaW, glyph.width)

        glyph.setText(captionFont, caption)
        val captionW = glyph.width
        val captionH = captionFont.lineHeight

        val contentW = max(captionW, nameW + beforeW + deltaW + afterW + colGap * 3f)
        val rowH = font.lineHeight
        val n = lines.size
        val blockH = sectionGap + dividerH + titleGapTop + captionH + titleGapBottom +
            n * rowH + (n - 1).coerceAtLeast(0) * rowGap

        return ColumnMetrics(
            nameW = nameW,
            beforeW = beforeW,
            deltaW = deltaW,
            afterW = afterW,
            colGap = colGap,
            contentW = contentW,
            blockH = blockH,
        )
    }

    /**
     * Draws the section divider, this table's caption, and its rows.
     * The caption sits directly above the rows; this table paints it.
     *
     * @return updated yTop below this table.
     */
    fun draw(
        batch: SpriteBatch,
        px: Texture,
        lines: List<RatingSeatLine>,
        contentLeft: Float,
        contentRight: Float,
        startYTop: Float,
        viewTop: Float,
        viewBottom: Float,
        nameW: Float,
        beforeW: Float,
        deltaW: Float,
        afterW: Float,
        colGap: Float,
        rowH: Float,
        rowGap: Float,
        dividerH: Float,
        sectionGap: Float,
        titleGapTop: Float,
        titleGapBottom: Float,
        font: BitmapFont,
        captionFont: BitmapFont = font,
        caption: String = L10n.get("hand.rating_changes"),
        alpha: Float,
        drawDivider: Boolean = true,
    ): Float {
        if (lines.isEmpty()) return startYTop
        var yTop = startYTop

        if (drawDivider) {
            yTop -= sectionGap
            if (yTop >= viewBottom && yTop <= viewTop + dividerH) {
                batch.setColor(AppPalette.tint(AppPalette.panelRim, 0.35f * alpha))
                batch.draw(px, contentLeft, yTop - dividerH, contentRight - contentLeft, dividerH)
                batch.setColor(Color.WHITE)
            }
            yTop -= dividerH + titleGapTop
        }

        if (caption.isNotEmpty()) {
            if (yTop >= viewBottom && yTop - captionFont.lineHeight <= viewTop) {
                captionFont.color.set(fade(AppPalette.totalAccent, alpha))
                captionFont.draw(batch, caption, contentLeft, yTop)
                captionFont.color = Color.WHITE
            }
            yTop -= captionFont.lineHeight + titleGapBottom
        }

        val highlightPadY = Fonts.px(2f)
        for ((name, before, delta, after, isHuman, isBot, _, pending) in lines) {
            val baseline = yTop
            val rowBottom = baseline - rowH + Fonts.px(2f)
            val rowTop = baseline + highlightPadY
            if (baseline >= viewBottom && rowBottom <= viewTop) {
                if (isHuman) {
                    batch.setColor(AppPalette.tint(AppPalette.rowWash, 0.06f * alpha))
                    batch.draw(
                        px,
                        contentLeft - Fonts.px(4f),
                        rowBottom,
                        (contentRight - contentLeft) + Fonts.px(8f),
                        rowTop - rowBottom,
                    )
                    batch.setColor(Color.WHITE)
                }

                val nameColor = if (isBot) {
                    PlayerLabels.botNameTint(AppPalette.ink, scratchBotColor)
                } else if (isHuman) {
                    AppPalette.ink
                } else {
                    AppPalette.ink
                }

                var x = contentLeft
                font.color.set(fade(nameColor, alpha))
                Fonts.drawText(batch, font, name, x, baseline, font.color)
                x += nameW + colGap

                if (pending) {
                    font.color.set(fade(AppPalette.inkMuted, alpha))
                    val pendingW = (contentRight - x).coerceAtLeast(1f)
                    font.draw(
                        batch,
                        L10n.get("history.rating_pending"),
                        x,
                        baseline,
                        pendingW,
                        Align.right,
                        false,
                    )
                } else {
                    font.color.set(fade(AppPalette.inkMuted, alpha))
                    font.draw(
                        batch,
                        before.toString(),
                        x,
                        baseline,
                        beforeW,
                        Align.right,
                        false,
                    )
                    x += beforeW + colGap

                    val deltaColor = AppPalette.deltaInk(delta)
                    font.color.set(fade(deltaColor, alpha))
                    font.draw(
                        batch,
                        ScoreBreakdownText.formatSignedDelta(delta),
                        x,
                        baseline,
                        deltaW,
                        Align.right,
                        false,
                    )
                    x += deltaW + colGap

                    font.color.set(fade(AppPalette.inkSoft, alpha))
                    font.draw(
                        batch,
                        after.toString(),
                        x,
                        baseline,
                        afterW,
                        Align.right,
                        false,
                    )
                }
                font.color = Color.WHITE
            }
            yTop -= rowH + rowGap
        }
        return yTop
    }

    private fun fade(src: Color, alpha: Float): Color {
        tint.set(src.r, src.g, src.b, src.a * alpha)
        return tint
    }
}
