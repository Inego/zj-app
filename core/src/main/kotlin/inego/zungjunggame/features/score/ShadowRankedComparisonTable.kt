package inego.zungjunggame.features.score

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.net.ShadowRankedFormat
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.seats.PlayerLabels
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/**
 * The Shadow Ranked block on the hand-end card.
 *
 * This table owns the column geometry and the pixels. [columns] and [metrics]
 * measure it; the layout stores the [Metrics] snapshot and [draw] paints that
 * snapshot, so the header, the rows, and the footer share one set of widths.
 *
 * Top to bottom: a caption (the attempt id), a column-header row, one row per
 * comparison, then the finisher. That row carries their rating before the
 * attempt, their match total, and the finish's rating total. The line under it is
 * their rating after. Each comparison's Δ is that opponent's contribution to the
 * finisher's rating. The caption and the rating line wrap to the card's inner
 * width. The columns scale down only when the card is already as wide as it can
 * be.
 *
 * A comparison rebuilt from the attempts list omits the ratings and the
 * per-opponent Δ; those cells paint empty rather than a fabricated value, and the
 * rating line is dropped entirely.
 */
internal class ShadowRankedComparisonTable {
    private val tint = Color()
    private val botTint = Color()

    /** Unscaled column widths and the strings that set the card's width. */
    data class Columns(
        val nameW: Float,
        val ratingW: Float,
        val scoreW: Float,
        val deltaW: Float,
        val colGap: Float,
        val columnsW: Float,
        val contentW: Float,
    )

    /**
     * The block [draw] walks. Widths are already scaled to the card's inner width.
     * [blockH] is the sum of the advances in [draw]. Row heights and gaps are the
     * card body's fit factor applied to their design sizes; the two caption blocks
     * stay at full size because [draw] paints them unscaled.
     */
    data class Metrics(
        val gap: Float,
        val dividerH: Float,
        val titleGapTop: Float,
        val captionH: Float,
        val captionGap: Float,
        val headerH: Float,
        val headerGap: Float,
        val rowH: Float,
        val rowGap: Float,
        val footerGap: Float,
        val footerH: Float,
        val ratingGap: Float,
        val ratingH: Float,
        val nameW: Float,
        val ratingW: Float,
        val scoreW: Float,
        val deltaW: Float,
        val colGap: Float,
        val blockH: Float,
    )

    /**
     * @return the y below this table.
     */
    fun draw(
        batch: SpriteBatch,
        px: Texture,
        comparison: ShadowRankedComparison,
        metrics: Metrics,
        contentLeft: Float,
        contentRight: Float,
        startYTop: Float,
        viewTop: Float,
        viewBottom: Float,
        captionFont: BitmapFont,
        rowFont: BitmapFont,
        alpha: Float,
        /** The card's body shrink; the rows paint at it. The caption does not. */
        fontScale: Float = 1f,
    ): Float {
                val wrapW = (contentRight - contentLeft).coerceAtLeast(1f)
        var y = startYTop

        y -= metrics.gap
        if (y >= viewBottom && y <= viewTop + metrics.dividerH) {
            batch.setColor(AppPalette.tint(AppPalette.panelRim, 0.35f * alpha))
            batch.draw(px, contentLeft, y - metrics.dividerH, contentRight - contentLeft, metrics.dividerH)
            batch.setColor(Color.WHITE)
        }
        y -= metrics.dividerH + metrics.titleGapTop

        if (y >= viewBottom && y - metrics.captionH <= viewTop) {
            captionFont.color.set(fade(AppPalette.totalAccent, alpha))
            captionFont.draw(batch, captionText(comparison), contentLeft, y, wrapW, Align.left, true)
            captionFont.color = Color.WHITE
        }
        y -= metrics.captionH + metrics.captionGap

        if (y >= viewBottom && y - metrics.headerH <= viewTop) {
            drawNumericRow(
                batch = batch,
                font = rowFont,
                contentLeft = contentLeft,
                baseline = y,
                name = "",
                nameColor = AppPalette.inkMuted,
                rating = L10n.get("shadowRanked.col_rating"),
                ratingColor = AppPalette.inkMuted,
                score = L10n.get("shadowRanked.col_score"),
                scoreColor = AppPalette.inkMuted,
                delta = L10n.get("shadowRanked.col_delta"),
                deltaColor = AppPalette.inkMuted,
                metrics = metrics,
                alpha = alpha,
                scale = fontScale,
            )
        }
        y -= metrics.headerH + metrics.headerGap
        rule(batch, px, contentLeft, contentRight, y, viewTop, viewBottom, metrics.dividerH, 0.35f * alpha)
        y -= metrics.dividerH + metrics.headerGap

        val last = comparison.comparisons.lastIndex
        for ((index, row) in comparison.comparisons.withIndex()) {
            if (y >= viewBottom && y - metrics.rowH <= viewTop) {
                val nameColor = if (row.isBot) {
                    PlayerLabels.botNameTint(AppPalette.ink, botTint)
                } else {
                    AppPalette.ink
                }
                drawNumericRow(
                    batch = batch,
                    font = rowFont,
                    contentLeft = contentLeft,
                    baseline = y,
                    name = row.name,
                    nameColor = nameColor,
                    rating = row.opponentRating?.let(ShadowRankedFormat::ratingValue).orEmpty(),
                    ratingColor = AppPalette.inkMuted,
                    score = ShadowRankedFormat.signedScore(row.opponentScore),
                    scoreColor = AppPalette.inkSoft,
                    delta = row.delta?.let(ShadowRankedFormat::signedRating).orEmpty(),
                    deltaColor = row.delta?.let(AppPalette::deltaInk) ?: AppPalette.inkMuted,
                    metrics = metrics,
                    alpha = alpha,
                    scale = fontScale,
                )
            }
            y -= metrics.rowH
            if (index < last) y -= metrics.rowGap
        }

        y -= metrics.footerGap
        rule(batch, px, contentLeft, contentRight, y, viewTop, viewBottom, metrics.dividerH, 0.45f * alpha)
        y -= metrics.dividerH + metrics.footerGap

        if (y >= viewBottom && y - metrics.footerH <= viewTop) {
            val highlightPadY = Fonts.px(2f)
            val rowBottom = y - metrics.footerH + Fonts.px(2f)
            val rowTop = y + highlightPadY
            batch.setColor(AppPalette.tint(AppPalette.rowWash, 0.06f * alpha))
            batch.draw(
                px,
                contentLeft - Fonts.px(4f),
                rowBottom,
                (contentRight - contentLeft) + Fonts.px(8f),
                rowTop - rowBottom,
            )
            batch.setColor(Color.WHITE)
            drawNumericRow(
                batch = batch,
                font = rowFont,
                contentLeft = contentLeft,
                baseline = y,
                name = L10n.get("shadowRanked.score_you"),
                nameColor = AppPalette.ink,
                rating = comparison.ratingBefore?.let(ShadowRankedFormat::ratingValue).orEmpty(),
                ratingColor = AppPalette.inkMuted,
                score = ShadowRankedFormat.signedScore(comparison.playerScore),
                scoreColor = AppPalette.totalAccent,
                delta = ShadowRankedFormat.signedRating(comparison.ratingDelta),
                deltaColor = AppPalette.deltaInk(comparison.ratingDelta),
                metrics = metrics,
                alpha = alpha,
                scale = fontScale,
            )
        }
        y -= metrics.footerH + metrics.ratingGap

        val rating = ratingText(comparison)
        if (rating.isNotEmpty() && y >= viewBottom && y - metrics.ratingH <= viewTop) {
            captionFont.color.set(fade(AppPalette.totalAccent, alpha))
            captionFont.draw(batch, rating, contentLeft, y, wrapW, Align.left, true)
            captionFont.color = Color.WHITE
        }
        y -= metrics.ratingH
        rowFont.color = Color.WHITE
        return y
    }

    private fun drawNumericRow(
        batch: SpriteBatch,
        font: BitmapFont,
        contentLeft: Float,
        baseline: Float,
        name: String,
        nameColor: Color,
        rating: String,
        ratingColor: Color,
        score: String,
        scoreColor: Color,
        delta: String,
        deltaColor: Color,
        metrics: Metrics,
        alpha: Float,
        scale: Float,
    ) {
        // The columns were sized to fit; the face shrinks with the card's body so a
        // cell can neither run right out of its column nor left out of a
        // right-aligned one.
        Fonts.withFaceScale(font, scale) {
            var x = contentLeft
            font.color.set(fade(nameColor, alpha))
            font.draw(batch, name, x, baseline, metrics.nameW, Align.left, false)
            x += metrics.nameW + metrics.colGap
            font.color.set(fade(ratingColor, alpha))
            font.draw(batch, rating, x, baseline, metrics.ratingW, Align.right, false)
            x += metrics.ratingW + metrics.colGap
            font.color.set(fade(scoreColor, alpha))
            font.draw(batch, score, x, baseline, metrics.scoreW, Align.right, false)
            x += metrics.scoreW + metrics.colGap
            font.color.set(fade(deltaColor, alpha))
            font.draw(batch, delta, x, baseline, metrics.deltaW, Align.right, false)
            font.color = Color.WHITE
        }
    }

    private fun rule(
        batch: SpriteBatch,
        px: Texture,
        contentLeft: Float,
        contentRight: Float,
        yTop: Float,
        viewTop: Float,
        viewBottom: Float,
        ruleH: Float,
        alpha: Float,
    ) {
        if (yTop < viewBottom || yTop > viewTop + ruleH) return
        batch.setColor(AppPalette.tint(AppPalette.panelRim, alpha))
        batch.draw(px, contentLeft, yTop - ruleH, contentRight - contentLeft, ruleH)
        batch.setColor(Color.WHITE)
    }

    private fun fade(src: Color, alpha: Float): Color {
        tint.set(src.r, src.g, src.b, src.a * alpha)
        return tint
    }

    companion object {
        fun columns(
            comparison: ShadowRankedComparison,
            glyph: GlyphLayout,
            captionFont: BitmapFont,
            rowFont: BitmapFont,
        ): Columns {
            val colGap = Fonts.px(16f)
            var nameW = textWidth(glyph, rowFont, L10n.get("shadowRanked.score_you"))
            var ratingW = textWidth(glyph, rowFont, L10n.get("shadowRanked.col_rating"))
            var scoreW = textWidth(glyph, rowFont, L10n.get("shadowRanked.col_score"))
            var deltaW = textWidth(glyph, rowFont, L10n.get("shadowRanked.col_delta"))
            for ((name, _, opponentRating, opponentScore, delta) in comparison.comparisons) {
                nameW = max(nameW, textWidth(glyph, rowFont, name))
                ratingW = max(ratingW, textWidth(glyph, rowFont, ratingCell(opponentRating)))
                scoreW = max(scoreW, textWidth(glyph, rowFont, ShadowRankedFormat.signedScore(
                    opponentScore
                )))
                deltaW = max(deltaW, textWidth(glyph, rowFont, deltaCell(delta)))
            }
            scoreW = max(scoreW, textWidth(glyph, rowFont, ShadowRankedFormat.signedScore(comparison.playerScore)))
            ratingW = max(ratingW, textWidth(glyph, rowFont, ratingCell(comparison.ratingBefore)))
            deltaW = max(deltaW, textWidth(glyph, rowFont, ShadowRankedFormat.signedRating(comparison.ratingDelta)))
            val columnsW = nameW + ratingW + scoreW + deltaW + colGap * 3f
            val captionW = textWidth(glyph, captionFont, captionText(comparison))
            val ratingLineW = textWidth(glyph, captionFont, ratingText(comparison))
            return Columns(
                nameW = nameW,
                ratingW = ratingW,
                scoreW = scoreW,
                deltaW = deltaW,
                colGap = colGap,
                columnsW = columnsW,
                contentW = maxOf(columnsW, captionW, ratingLineW),
            )
        }

        /**
         * @param bodyScale the card body's fit factor; row heights, gaps and rules
         *   shrink with it, while the caption blocks keep their measured height
         *   because [draw] paints them unscaled.
         */
        fun metrics(
            columns: Columns,
            innerW: Float,
            glyph: GlyphLayout,
            captionFont: BitmapFont,
            rowFont: BitmapFont,
            comparison: ShadowRankedComparison,
            bodyScale: Float = 1f,
        ): Metrics {
            val wrapW = innerW.coerceAtLeast(1f)
            val captionH = textBlockH(glyph, captionFont, captionText(comparison), wrapW)
            val ratingLine = ratingText(comparison)
            val ratingH = if (ratingLine.isEmpty()) 0f else textBlockH(glyph, captionFont, ratingLine, wrapW)

            val columnScale = Fonts.fitScale(columns.columnsW, wrapW)
            val gap = Fonts.px(18f) * bodyScale
            val dividerH = max(1f, Fonts.px(1.5f) * bodyScale)
            val titleGapTop = Fonts.px(10f) * bodyScale
            val captionGap = Fonts.px(8f) * bodyScale
            val headerGap = Fonts.px(4f) * bodyScale
            val rowH = rowFont.lineHeight * bodyScale
            val rowGap = Fonts.px(4f) * bodyScale
            val footerGap = Fonts.px(8f) * bodyScale
            val ratingGap = Fonts.px(6f) * bodyScale
            val n = comparison.comparisons.size
            val rowsH = if (n == 0) 0f else n * rowH + (n - 1) * rowGap
            val blockH = gap + dividerH + titleGapTop + captionH + captionGap +
                rowH + headerGap + dividerH + headerGap +
                rowsH +
                footerGap + dividerH + footerGap + rowH +
                ratingGap + ratingH
            return Metrics(
                gap = gap,
                dividerH = dividerH,
                titleGapTop = titleGapTop,
                captionH = captionH,
                captionGap = captionGap,
                headerH = rowH,
                headerGap = headerGap,
                rowH = rowH,
                rowGap = rowGap,
                footerGap = footerGap,
                footerH = rowH,
                ratingGap = ratingGap,
                ratingH = ratingH,
                nameW = columns.nameW * columnScale,
                ratingW = columns.ratingW * columnScale,
                scoreW = columns.scoreW * columnScale,
                deltaW = columns.deltaW * columnScale,
                colGap = columns.colGap * columnScale,
                blockH = blockH,
            )
        }

        private fun captionText(comparison: ShadowRankedComparison): String =
            L10n.format("shadowRanked.caption", comparison.publicId)

        private fun ratingText(comparison: ShadowRankedComparison): String =
            comparison.ratingAfter?.let { L10n.format("shadowRanked.rating", ShadowRankedFormat.ratingValue(it)) }
                .orEmpty()

        private fun ratingCell(value: Double?): String =
            value?.let(ShadowRankedFormat::ratingValue).orEmpty()

        private fun deltaCell(value: Double?): String =
            value?.let(ShadowRankedFormat::signedRating).orEmpty()

        private fun textWidth(glyph: GlyphLayout, font: BitmapFont, text: String): Float {
            glyph.setText(font, text)
            return glyph.width
        }

        /**
         * Height [draw] must advance for [text]. [GlyphLayout.height] ends at the
         * cap of the last line, while the glyphs continue through the font's
         * descent. The body clips to this height, so the shortfall is cut off and
         * never becomes scroll range.
         */
        private fun textBlockH(glyph: GlyphLayout, font: BitmapFont, text: String, wrapW: Float): Float {
            if (text.isEmpty()) return 0f
            glyph.setText(font, text, Color.WHITE, wrapW, Align.left, true)
            val descent = font.lineHeight - font.capHeight
            return if (descent > 0f) glyph.height + descent else glyph.height
        }
    }
}
