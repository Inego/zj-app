package inego.zungjunggame.features.score

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.utils.Align
import inego.zungjunggame.engine.HandScoreDetails
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.handend.HandEndAction
import inego.zungjunggame.render.handend.HandEndActionChrome
import inego.zungjunggame.render.handend.HandEndPlacedChip
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.render.online.RatingLine
import inego.zungjunggame.render.tiles.TileEasing
import inego.zungjunggame.render.ui.CloseButton
import inego.zungjunggame.render.ui.ParkGeometry
import inego.zungjunggame.render.ui.VerticalScrollbar
import kotlin.math.max

/** Inputs for [ScoreBreakdownLayout.measure] (everything except fold/park lives on the overlay). */
internal data class ScoreBreakdownMeasureInput(
    val details: HandScoreDetails,
    /** The header's verdict: the winner's name, or the draw label. */
    val headline: String,
    /** The muted run before [headline] ("Winner: "); empty for a draw. */
    val headlineLabel: String,
    /** The header's second line: where the winning tile came from; empty on a self-draw. */
    val headlineDetail: String,
    /** The muted run before [headlineDetail] ("From: "), or the whole self-draw phrase. */
    val headlineDetailLabel: String,
    /** The collapsed bar's title line: the winner's name, or the draw label. */
    val compactHeadline: String,
    /** The collapsed bar's small second line ("from Left", "self-draw"); empty on a draw. */
    val compactDetail: String,
    /** Finished-game result line, or null while the game continues. */
    val gameResult: String?,
    /** Finished-game span (`mm:ss`), or null while the game continues. */
    val durationLabel: String? = null,
    val settlement: List<SettlementSeatLine>,
    val ratingLines: List<RatingLine>,
    /** The Shadow Ranked summary, or null for an ordinary card. */
    val comparison: ShadowRankedComparison?,
    val primaryAction: HandEndAction,
    val primaryLabel: String,
    val secondaryAction: HandEndAction?,
    val secondaryLabel: String,
    val showActions: Boolean,
    val showScoreTable: Boolean = true,
    val primaryEnabled: Boolean,
    val primaryTimeLeftMs: Long?,
    val screenW: Float,
    val screenH: Float,
    /**
     * The rect the card is sized and parked in, or null for the window's safe
     * area. A card composed into a provider's space (the tutorial) is bounded by
     * the board pane inside that space, not by the window: the pane is already
     * inside the safe area, and the chrome beside it is not the card's area.
     */
    val bounds: OverlayViewport.Rect? = null,
    val foldProgress: Float,
    val parkX: Float,
    val parkY: Float,
)

/**
 * Every height the expanded card spends on its body at one face scale.
 *
 * The body's faces and its vertical rhythm move together, so a shrink for a
 * short viewport actually shortens the card instead of only thinning the ink.
 * [ScoreBreakdownLayout.measure] asks for this at 1 to learn the natural
 * height, then again at the scale that fits the slot.
 */
private data class BodyPlan(
    val headlineH: Float,
    val headlineGap: Float,
    val titleH: Float,
    val titleBandH: Float,
    val infoSide: Float,
    val rowH: Float,
    val patternTopInset: Float,
    val totalH: Float,
    val settleRowH: Float,
    val rowGap: Float,
    val totalGap: Float,
    val dividerH: Float,
    val settleGap: Float,
    val settleRowGap: Float,
    val ratingSectionGap: Float,
    val ratingTitleGapTop: Float,
    val ratingTitleGapBottom: Float,
    val resultLineH: Float,
    val durationLineH: Float,
    val resultGap: Float,
    val chromeH: Float,
    val bodyContentH: Float,
    val comparisonMetrics: ShadowRankedComparisonTable.Metrics?,
)

/**
 * Hug-height geometry for the hand-end win card: expanded/collapsed sizes,
 * fold lerp, park clamp, settlement columns, and the Scores / Next hand block
 * (with the online Back to Online home on the line under it).
 *
 * The overlay copies panel/block/park from this snapshot, lays out the body
 * viewport, then paints.
 */
internal data class ScoreBreakdownLayout(
    val panel: Panel,
    val block: Block,
    val parkX: Float,
    val parkY: Float,
    val expandedAlpha: Float,
    val collapsedAlpha: Float,
    val body: Body,
    val chrome: Chrome,
    val pattern: Pattern,
    val settle: Settle,
    val compact: Compact,
    val actions: Actions,
    /** Finished-game result line and the slot it is drawn in. Empty when none. */
    val result: Result,
    /** Finished-game duration line, drawn under the result. Empty when none. */
    val duration: Duration,
    /** The Shadow Ranked table, or null when there is none. */
    val comparison: ShadowRankedComparisonTable.Metrics?,
) {
    data class Panel(val left: Float, val bottom: Float, val w: Float, val h: Float)
    data class Block(val left: Float, val bottom: Float, val w: Float, val h: Float)

    /**
     * The finished-game place and which row owns it.
     *
     * [slot] is the one decision the height and the painter share. [Body] counts
     * the line, and the painter draws it, only for [ResultSlot.Body].
     * [RatingCaption] is the rating table's own header: that table paints [line]
     * above its rows, in the caption block the rating section reserved. The hand
     * table (winner, pattern rows, total) does not contain it.
     */
    data class Result(
        val line: String,
        val lineH: Float,
        /** Gap under the line when it is the body's first line. */
        val gapBottom: Float,
        val slot: ResultSlot,
    )

    /**
     * The finished-game duration line.
     *
     * Drawn by the body directly under the result line (or the headline when the
     * place heads the rating table). Both rows are body content, so `contentH`
     * counts it and the painter draws it in the same order.
     */
    data class Duration(
        val line: String,
        val lineH: Float,
        /** Gap under the line, before the pattern rows. */
        val gapBottom: Float,
    )

    /** Which row [Result.line] occupies; see [Result]. */
    enum class ResultSlot {
        /** The body's first line, above the pattern rows. No rating table to head. */
        Body,

        /** The rating table's caption, directly above its rows. */
        RatingCaption,

        /** No finished game: nothing to draw, no row spent. */
        None,
    }

    data class Body(
        val bottom: Float,
        val height: Float,
        val contentH: Float,
        /** Card inset at the body's sides; the content column spans what is left. */
        val padX: Float,
        /**
         * One shrink for the whole body: the tighter of the width fit (the card
         * cannot grow past its world) and the height fit (the body cannot push
         * the card past its slot). Every face and its vertical rhythm paint at
         * this factor, so no row stays full size while another shrinks. A body
         * that still overflows at [Fonts.MIN_FIT_SCALE] keeps its sticky scroll
         * rather than shrinking past a readable size.
         */
        val scale: Float,
    )
    data class Chrome(
        /** Right edge every header label ends on — the credits axis. */
        val titleLabelRight: Float,
        /** Left edge every header name starts on, one gutter past the axis. */
        val titleValueLeft: Float,
        val drawSide: Float,
        val collapseLeft: Float,
        val collapseBottom: Float,
        /** Top of the header's title line (the block's top), centered in the band the control shares. */
        val titleTop: Float,
        /** Top of the header's second line under the title; null when the header is one line. */
        val titleDetailTop: Float?,
    )
    data class Pattern(
        val infoSide: Float,
        val infoColW: Float,
        /** Body leading that keeps the first info control clear of the top clip. */
        val topInset: Float,
        val rowH: Float,
        val rowGap: Float,
        val totalH: Float,
        val totalGap: Float,
        val dividerH: Float,
        val scoreSample: Float,
    )
    data class Settle(
        val gap: Float,
        val rowH: Float,
        val rowGap: Float,
        val nameW: Float,
        val beforeW: Float,
        val deltaW: Float,
        val afterW: Float,
        val colGap: Float,
        val ratingSectionGap: Float = Fonts.px(18f),
        val ratingTitleGapTop: Float = Fonts.px(10f),
        val ratingTitleGapBottom: Float = Fonts.px(8f),
    )
    data class Compact(
        val padX: Float,
        val gap: Float,
        /** Draw y of the winner line (the top of its cap band). */
        val titleTop: Float,
        /** Draw y of the small source line's cap band; null when the bar carries one line. */
        val detailTop: Float?,
        /**
         * Face scale that makes the total's cap band as tall as the label block —
         * the name's line and, on a win, the small line under it.
         */
        val scoreScale: Float,
    )

    data class Actions(
        val show: Boolean,
        /** Scores / CTA chips, then the under-row chip when the block has one. */
        val chips: List<HandEndPlacedChip>,
        /** Placed block height, both lines included. */
        val height: Float,
    )

    companion object {
        /** Panel size ease; matches the reference TOC drawer feel, slightly longer for height. */
        const val FOLD_ANIM_SEC = 0.22f
        /** Same 8 dp slop as [inego.zungjunggame.render.ui.TocBrowserColumn] pull-tab drag. */
        const val DRAG_SLOP_DP = 8f
        /** Eased fold progress at which the expanded body is fully faded out. */
        private const val BODY_FADE_END = 0.45f
        /** Eased fold progress at which the compact bar starts fading in. */
        private const val COMPACT_FADE_START = 0.55f

        fun measure(input: ScoreBreakdownMeasureInput, glyph: GlyphLayout): ScoreBreakdownLayout {
            val titleFont = Fonts.labelActive
            val rowFont = Fonts.body
            val totalFont = Fonts.labelActive
            val settleFont = Fonts.body
            val components = input.details.components
            val totalLabel = L10n.get("scores.total")
            val comparisonInput = input.comparison
            // Which line the place is, and which row owns it. One decision, so the
            // height below and the painter cannot disagree about whether a body row
            // was spent. When a rating table is present the place is that table's
            // caption; otherwise the table keeps its own "Rating changes" header.
            val resultPlan = resolveResult(
                gameResult = input.gameResult,
                hasRatingLines = input.ratingLines.isNotEmpty(),
            )
            val resultLine = resultPlan.line
            val ratingCaption = if (resultPlan.slot == ResultSlot.RatingCaption) {
                resultLine
            } else {
                L10n.get("hand.rating_changes")
            }
            val resultGap = Fonts.px(12f)

            val infoSide = max(Fonts.px(22f), rowFont.lineHeight + Fonts.px(2f))
                .coerceAtMost(Fonts.px(28f))
            val infoGap = Fonts.px(8f)
            val infoColW = if (components.isEmpty()) 0f else infoSide + infoGap

            val totalLabelW = Fonts.textWidth(totalFont, totalLabel)
            var patternNameW = 0f
            for (c in components) {
                patternNameW = max(patternNameW, Fonts.textWidth(rowFont, ScoreBreakdownText.patternDisplayName(c)))
            }
            val nameColW = max(totalLabelW, patternNameW)

            val scoreSample = maxScoreWidth(
                glyph,
                rowFont,
                totalFont,
                components.map { it.points },
                input.details.totalScore,
            )
            val colGap = Fonts.px(28f)
            // The card's world: the window's safe area, or the pane a provider
            // hands it (the tutorial board), which already sits inside it.
            val avail = input.bounds
                ?: OverlayViewport.available(input.screenW, input.screenH)
            // One inset for the card's sides and its bottom; the header band owns
            // the top. The card is capped by its world (the window, or the pane
            // it belongs to), so every dp of padding here comes straight out of
            // the board behind it.
            val portrait = avail.height > avail.width
            val pad = Fonts.px(if (portrait) 16f else 28f)
            val chromeSide = CloseButton.defaultSide()
            val chromeInset = CloseButton.cornerInset()
            val chromeSlot = chromeInset + chromeSide
            /** Clear space between the title area and the corner control. */
            val titleGapX = Fonts.px(10f)
            /** Gutter between the header's label column and its name column. */
            val labelGutter = Fonts.px(12f)
            /** Clear space between the header band and the body under it. */
            val headerGap = Fonts.px(12f)
            val rowGap = Fonts.px(6f)
            val totalGap = Fonts.px(12f)
            val settleGap = Fonts.px(14f)
            val settleRowGap = Fonts.px(4f)
            val dividerH = max(1f, Fonts.px(1.5f))

            var seatNameW = 0f
            var beforeW = 0f
            var deltaW = 0f
            var afterW = 0f
            for ((name, before, delta, after) in input.settlement) {
                glyph.setText(settleFont, name)
                seatNameW = max(seatNameW, glyph.width)
                glyph.setText(settleFont, before.toString())
                beforeW = max(beforeW, glyph.width)
                glyph.setText(settleFont, ScoreBreakdownText.formatSignedDelta(delta))
                deltaW = max(deltaW, glyph.width)
                glyph.setText(settleFont, after.toString())
                afterW = max(afterW, glyph.width)
            }
            for (line in input.ratingLines) {
                glyph.setText(settleFont, line.name)
                seatNameW = max(seatNameW, glyph.width)
                if (!line.pending) {
                    glyph.setText(settleFont, line.before.toString())
                    beforeW = max(beforeW, glyph.width)
                    glyph.setText(settleFont, ScoreBreakdownText.formatSignedDelta(line.delta))
                    deltaW = max(deltaW, glyph.width)
                    glyph.setText(settleFont, line.after.toString())
                    afterW = max(afterW, glyph.width)
                }
            }
            glyph.setText(settleFont, "0000")
            beforeW = max(beforeW, glyph.width)
            afterW = max(afterW, glyph.width)
            glyph.setText(settleFont, "+0000")
            deltaW = max(deltaW, glyph.width)
            val settleColGap = Fonts.px(16f)
            val settleContentW = seatNameW + beforeW + deltaW + afterW + settleColGap * 3f

            var ratingContentW = 0f
            val ratingSectionGap = Fonts.px(18f)
            val ratingTitleGapTop = Fonts.px(10f)
            val ratingTitleGapBottom = Fonts.px(8f)
            if (input.ratingLines.isNotEmpty()) {
                glyph.setText(totalFont, ratingCaption)
                ratingContentW = max(ratingContentW, glyph.width)
                for (line in input.ratingLines) {
                    if (line.pending) {
                        glyph.setText(settleFont, L10n.get("history.rating_pending"))
                        ratingContentW = max(ratingContentW, seatNameW + settleColGap + glyph.width)
                    }
                }
            }

            val contentW = infoColW + nameColW + colGap + scoreSample
            // The header is set like film credits: every label ends on one axis
            // ("Winner:", "From:") and every name starts on the next one, so a line
            // measures as a label + the gutter + a name. The two lines need not each
            // have both runs, and they use different faces, so each is measured in
            // its own face before the columns take the widest of the two.
            val headlineText = input.headlineLabel + input.headline
            val headlineDetailText = input.headlineDetailLabel + input.headlineDetail
            val titleHeader = headerLineMetrics(titleFont, input.headlineLabel, input.headline)
            val detailHeader =
                headerLineMetrics(Fonts.body, input.headlineDetailLabel, input.headlineDetail)
            val labelColumnW = max(titleHeader.labelW, detailHeader.labelW)
            val valueColumnW = max(titleHeader.valueW, detailHeader.valueW)
            val titleBlockW = if (labelColumnW > 0f) {
                labelColumnW + labelGutter + valueColumnW
            } else {
                valueColumnW
            }
            var resultContentW = 0f
            var resultLineH = 0f
            if (resultLine.isNotEmpty()) {
                glyph.setText(titleFont, resultLine)
                resultContentW = glyph.width
                resultLineH = glyph.height
            }
            // The duration is a subtitle under the result (or the headline), so it
            // measures in the body font and reserves its own row.
            val durationLine = input.durationLabel?.trim().orEmpty()
            var durationContentW = 0f
            var durationLineH = 0f
            if (durationLine.isNotEmpty()) {
                glyph.setText(rowFont, durationLine)
                durationContentW = glyph.width
                durationLineH = glyph.height
            }
            // The comparison table's natural width sizes the card. Its caption and
            // rating line are measured against the inner width once that settles.
            val comparisonColumns = comparisonInput?.let {
                ShadowRankedComparisonTable.columns(it, glyph, totalFont, settleFont)
            }
            val comparisonRawW = comparisonColumns?.contentW ?: 0f
            // The header's text block must clear the corner control it shares its
            // band with. Sizing the card from the text alone would let a long name
            // run under that control whenever the world does not cap the card.
            val titleExtraW = chromeSlot + titleGapX - pad
            val widestContent = maxOf(
                contentW,
                titleBlockW + titleExtraW,
                settleContentW,
                ratingContentW,
                resultContentW,
                durationContentW,
                comparisonRawW,
            )
            val expandedW = max(Fonts.px(280f), widestContent + pad * 2f)
                .coerceAtMost(minOf(input.screenW * 0.92f, avail.width))
            val innerW = expandedW - pad * 2f

            // Columns keep their own fit, so a row still fills the card and its
            // right-hand numbers stay on the edge.
            val settleScale = Fonts.fitScale(settleContentW, innerW)

            val nameAvailW = (innerW - infoColW - colGap - scoreSample).coerceAtLeast(1f)
            // The title block starts at the body's content edge and stops a clear gap
            // short of the corner control, so the two never sit shoulder to shoulder
            // in the band they share.
            val titleAreaW = (expandedW - pad - chromeSlot - titleGapX).coerceAtLeast(1f)

            // The width fit. It holds at 1 until the card's world caps it: the
            // card already reserves the title area beside the corner control, so
            // folding the title in earlier would shrink everything for no reason.
            // Once capped it is the tightest per-block fit, so the title, the
            // pattern names *and* scores, the total, the settlement and the
            // comparison all paint at one factor, and each still fits the width it
            // reserved.
            val widthCapped = Fonts.fitScale(widestContent, innerW) < 1f
            val widthScale = if (!widthCapped) {
                1f
            } else {
                minOf(
                    Fonts.fitScale(patternNameW, nameAvailW),
                    settleScale,
                    Fonts.fitScale(titleBlockW, titleAreaW),
                    Fonts.fitScale(comparisonColumns?.columnsW ?: 0f, innerW),
                )
            }

            // The header's second line — the seat the winning tile came from, or the
            // self-draw phrase — stacks under the title at the same shrink.
            val hasHeadlineDetail =
                input.headlineDetail.isNotEmpty() || input.headlineDetailLabel.isNotEmpty()

            // One factor for the whole body, width or height. Every face and the
            // vertical rhythm measured in that face move together: row pitch, the
            // gaps, the dividers and the trailing leading all scale, so a short
            // slot (the tutorial's board column in landscape) can fit its players
            // instead of scrolling them. The info control is a tap target and the
            // corner control belongs to the frame, so neither follows the body all
            // the way down; their floors set the tightest the rows and header band
            // can hug.
            fun bodyPlan(scale: Float): BodyPlan {
                val headlineH = Fonts.textHeight(titleFont, headlineText) * scale
                val headlineGap = if (hasHeadlineDetail) Fonts.px(2f) * scale else 0f
                val headlineDetailH = if (hasHeadlineDetail) {
                    Fonts.textHeight(Fonts.body, headlineDetailText) * scale
                } else {
                    0f
                }
                val titleH = headlineH + headlineGap + headlineDetailH
                val scaledInfoSide =
                    (infoSide * scale).coerceAtLeast(Fonts.px(22f))
                val rowH = max(rowFont.lineHeight * scale, scaledInfoSide)
                // The info control is centred on the row's scaled cap box, so it is
                // taller than the text and overhangs the row's top by this much.
                val patternOverhang = if (components.isEmpty()) {
                    0f
                } else {
                    ((scaledInfoSide - rowFont.capHeight * scale) * 0.5f).coerceAtLeast(0f)
                }
                val totalH = totalFont.lineHeight * scale
                val settleRowH = settleFont.lineHeight * scale
                val scaledRowGap = rowGap * scale
                val scaledTotalGap = totalGap * scale
                val scaledDividerH = (dividerH * scale).coerceAtLeast(1f)
                val scaledSettleGap = settleGap * scale
                val scaledSettleRowGap = settleRowGap * scale
                val n = components.size
                val rowsBlock =
                    if (n == 0) rowFont.lineHeight * scale
                    else n * rowH + (n - 1).coerceAtLeast(0) * scaledRowGap
                val settleN = input.settlement.size.coerceAtLeast(0)
                val settleBlock =
                    if (settleN == 0) 0f
                    else scaledSettleGap + scaledDividerH + scaledSettleGap +
                        settleN * settleRowH +
                        (settleN - 1).coerceAtLeast(0) * scaledSettleRowGap
                val ratingN = input.ratingLines.size
                // The rating table paints its caption at full size, so its own line
                // height stays unscaled; its rows and the space around it shrink.
                val ratingCaptionH = totalFont.lineHeight
                val scaledRatingTitleGapTop = ratingTitleGapTop * scale
                val scaledRatingTitleGapBottom = ratingTitleGapBottom * scale
                val ratingCaptionBlock =
                    scaledRatingTitleGapTop + ratingCaptionH + scaledRatingTitleGapBottom
                val ratingBlock =
                    if (ratingN == 0) 0f
                    else ratingSectionGap * scale + scaledDividerH + ratingCaptionBlock +
                        ratingN * settleRowH +
                        (ratingN - 1).coerceAtLeast(0) * scaledSettleRowGap

                val comparisonMetrics = if (comparisonInput != null && comparisonColumns != null) {
                    ShadowRankedComparisonTable.metrics(
                        comparisonColumns,
                        innerW,
                        glyph,
                        totalFont,
                        settleFont,
                        comparisonInput,
                        scale,
                    )
                } else {
                    null
                }
                val comparisonBlock = comparisonMetrics?.blockH ?: 0f

                // The title and the corner control share one header band. The band
                // is as tall as the taller of the two and held off the card's edge
                // by the control's own corner inset, so the control has a margin on
                // every side it faces — the title to its left, the body under it —
                // instead of the band being sized off the title alone and the
                // control spilling into the first row.
                val titleBandH = max(titleH, chromeSide)
                val headerH = chromeInset + titleBandH + headerGap
                val chromeH = pad + headerH
                val scaledResultGap = resultGap * scale
                val scaledResultLineH = resultLineH * scale
                val scaledDurationLineH = durationLineH * scale
                // A place that heads the rating table is not a row of the hand
                // table. Counting it here would reserve an empty line under the winner.
                val resultBlock =
                    if (resultPlan.slot == ResultSlot.Body) {
                        scaledResultLineH + scaledResultGap
                    } else {
                        0f
                    }
                val durationBlock =
                    if (durationLine.isNotEmpty()) {
                        scaledDurationLineH + scaledResultGap
                    } else {
                        0f
                    }
                // The first icon may hang above its row; a result or duration line
                // already supplies that leading, so reserve only the uncovered rest.
                val patternTopInset =
                    (patternOverhang - resultBlock - durationBlock).coerceAtLeast(0f)
                // The last row's box is a face line tall, while its glyphs stop at
                // the face's descent. Cut that leading: the card cannot end one
                // line's leading below the last inked row, or its bottom would read
                // looser than its sides. The trailing box is clipped and holds no ink.
                val tailFont = when {
                    // The comparison's closing rating line is measured down to its
                    // ink; with no rating line its footer row ends the body.
                    comparisonMetrics != null ->
                        if (comparisonMetrics.ratingH > 0f) null else settleFont
                    input.settlement.isNotEmpty() || input.ratingLines.isNotEmpty() -> settleFont
                    else -> totalFont
                }
                val tailLeading = tailFont?.let { trailingLeading(it, scale) } ?: 0f
                val bodyContentH = resultBlock + durationBlock + patternTopInset + rowsBlock +
                    scaledTotalGap + scaledDividerH + scaledTotalGap + totalH + settleBlock +
                    ratingBlock + comparisonBlock - tailLeading

                return BodyPlan(
                    headlineH = headlineH,
                    headlineGap = headlineGap,
                    titleH = titleH,
                    titleBandH = titleBandH,
                    infoSide = scaledInfoSide,
                    rowH = rowH,
                    patternTopInset = patternTopInset,
                    totalH = totalH,
                    settleRowH = settleRowH,
                    rowGap = scaledRowGap,
                    totalGap = scaledTotalGap,
                    dividerH = scaledDividerH,
                    settleGap = scaledSettleGap,
                    settleRowGap = scaledSettleRowGap,
                    ratingSectionGap = ratingSectionGap * scale,
                    ratingTitleGapTop = scaledRatingTitleGapTop,
                    ratingTitleGapBottom = scaledRatingTitleGapBottom,
                    resultLineH = scaledResultLineH,
                    durationLineH = scaledDurationLineH,
                    resultGap = scaledResultGap,
                    chromeH = chromeH,
                    bodyContentH = bodyContentH,
                    comparisonMetrics = comparisonMetrics,
                )
            }

            val compactPadX = Fonts.px(16f)
            val compactPadY = Fonts.px(8f)
            val compactGap = Fonts.px(12f)
            // Clear the title's descenders before the small line's cap band starts.
            val compactLineGap = max(Fonts.px(2f), -titleFont.data.descent)
            val compactDetailFont = Fonts.small
            val compactDetail = input.compactDetail
            val hasCompactDetail = compactDetail.isNotEmpty()
            val scoreText = input.details.totalScore.toString()
            glyph.setText(titleFont, input.compactHeadline)
            val compactTitleW = glyph.width
            val compactDetailW = if (hasCompactDetail) {
                Fonts.textWidth(compactDetailFont, compactDetail)
            } else {
                0f
            }
            // The label's ink: the name's cap band plus, on a win, the small source
            // line under it. The total spans this whole band, so it is the height the
            // collapsed card and the total's face scale both follow.
            val compactTextH = if (hasCompactDetail) {
                titleFont.capHeight + compactLineGap +
                    Fonts.textHeight(compactDetailFont, compactDetail)
            } else {
                max(glyph.height, totalFont.capHeight)
            }
            // The total spans the label's whole ink, so it paints on [Fonts.display]
            // — the face whose caps are already near that height — scaled to land
            // exactly on it. Digits take the small remainder without spending a
            // second large atlas on one line.
            val compactScoreScale = compactTextH / Fonts.display.capHeight
            val compactScoreW = Fonts.withFaceScale(Fonts.display, compactScoreScale) {
                glyph.setText(Fonts.display, scoreText)
                glyph.width
            }
            val compactTextW = max(compactTitleW, compactDetailW)
            val compactRowW = compactPadX + compactTextW + compactGap + compactScoreW +
                compactGap + chromeSlot + compactPadX
            val collapsedW = compactRowW
                .coerceAtMost(minOf(input.screenW * 0.92f, avail.width))
                .coerceAtLeast(Fonts.px(180f).coerceAtMost(avail.width))
            // The total spans both lines, so each text line stops before its column.
            val compactTextMaxW = (collapsedW - compactPadX * 2f - chromeSlot -
                compactGap * 2f - compactScoreW).coerceAtLeast(1f)
            glyph.setText(
                titleFont,
                input.compactHeadline,
                0,
                input.compactHeadline.length,
                Color.WHITE,
                compactTextMaxW,
                Align.left,
                false,
                "...",
            )
            val compactBlockH = max(compactTextH, chromeSide * 0.85f)
            val collapsedH = compactPadY * 2f + compactBlockH

            val touch = InputProfile.detect() == InputProfile.Touch
            val underGap = if (touch) Fonts.px(14f) else Fonts.px(12f)
            val chipBlock = if (input.showActions) {
                HandEndActionChrome.measureChipBlock(
                    glyph,
                    HandEndActionChrome.chipSpecs(
                        primaryAction = input.primaryAction,
                        primaryLabel = primaryDisplayLabel(input),
                        primaryEnabled = input.primaryEnabled,
                        showScoreTable = input.showScoreTable,
                        showComparison = input.comparison != null,
                    ),
                    HandEndActionChrome.underRowSpecs(
                        input.secondaryAction,
                        input.secondaryLabel.ifEmpty { L10n.get("hand.back_home") },
                    ),
                    touch,
                    maxRowW = avail.width,
                )
            } else {
                HandEndActionChrome.measureChipBlock(glyph, emptyList(), touch = touch)
            }
            val btnBlockW = chipBlock.width
            val maxBtnH = chipBlock.height
            val footerH = if (input.showActions) underGap + maxBtnH else 0f

            // The body gets whatever the chrome and the action row under the card
            // leave, and shrinks to fit it. The natural plan is measured at 1; the
            // slot subtracts the header band as it is at full size, so a smaller
            // title band can only leave a few pixels spare, never a scroll. The
            // floor is the shared readable one: below it the body keeps its sticky
            // scroll instead of shrinking into illegibility.
            val naturalPlan = bodyPlan(1f)
            val bodyAvailH = (avail.height - naturalPlan.chromeH - footerH).coerceAtLeast(1f)
            var bodyScale = minOf(widthScale, Fonts.fitScale(naturalPlan.bodyContentH, bodyAvailH))
            var plan = if (bodyScale == 1f) naturalPlan else bodyPlan(bodyScale)
            // The first ratio assumes the body shrinks as a whole. The rating and
            // comparison captions keep their size, so a fixed part can survive the
            // shrink; two corrections against the body's own plan settle any card
            // that fits without falling through to the scroll.
            var refinements = 0
            while (bodyScale > Fonts.MIN_FIT_SCALE && refinements++ < 2) {
                val slot = (avail.height - plan.chromeH - footerH).coerceAtLeast(1f)
                if (plan.bodyContentH <= slot) break
                bodyScale = (bodyScale * slot / plan.bodyContentH)
                    .coerceIn(Fonts.MIN_FIT_SCALE, bodyScale)
                plan = bodyPlan(bodyScale)
            }

            val fit = OverlayViewport.fitSticky(
                avail.height,
                plan.chromeH,
                plan.bodyContentH,
                footerH,
            )
            val expandedH = fit.cardH

            // The numeric columns keep to the content's right edge, where the
            // pattern and total scores above them sit. The name column is the
            // flexible one: whatever width the widest block leaves over goes there,
            // so the last number cannot stop short of the edge the rest of the card
            // is aligned to. A scrolling body hands the columns the bar's strip.
            val gutter = if (fit.maxScrollY > 0.5f) VerticalScrollbar.gutterWidth() else 0f
            val sNameW = seatNameW * settleScale +
                (innerW - gutter - settleContentW * settleScale).coerceAtLeast(0f)
            val sBeforeW = beforeW * settleScale
            val sDeltaW = deltaW * settleScale
            val sAfterW = afterW * settleScale
            val sGap = settleColGap * settleScale
            // The comparison table's columns keep the same edge, for the same reason.
            val comparisonPlaced = plan.comparisonMetrics?.let { m ->
                val columnsW = m.nameW + m.ratingW + m.scoreW + m.deltaW + m.colGap * 3f
                m.copy(nameW = m.nameW + (innerW - gutter - columnsW).coerceAtLeast(0f))
            }

            val foldT = TileEasing.easeInOutCubic(input.foldProgress)
            val panelW = TileEasing.lerp(expandedW, collapsedW, foldT)
            val panelH = TileEasing.lerp(expandedH, collapsedH, foldT)
            val expandedAlpha = (1f - foldT / BODY_FADE_END).coerceIn(0f, 1f)
            val collapsedAlpha = ((foldT - COMPACT_FADE_START) / (1f - COMPACT_FADE_START))
                .coerceIn(0f, 1f)

            val totalBlockH = panelH + footerH
            val originPanelLeft = avail.left + (avail.width - panelW) * 0.5f
            val originBtnRowLeft = avail.left + (avail.width - btnBlockW) * 0.5f
            val originBtnCenterX = avail.left + avail.width * 0.5f
            val originBlockBottom = avail.bottom + (avail.height - totalBlockH) * 0.5f
            val originBlockLeft = minOf(originPanelLeft, originBtnRowLeft)
            val originBlockW = maxOf(panelW, btnBlockW)

            // Shared with the drag that moves the block: the layout and the park
            // must clamp identically, or the block stops at an edge while the
            // finger keeps accumulating travel (see ParkGeometry).
            val placed = ParkGeometry.place(
                restLeft = originBlockLeft,
                restBottom = originBlockBottom,
                blockW = originBlockW,
                blockH = totalBlockH,
                parkX = input.parkX,
                parkY = input.parkY,
                bounds = avail,
            )
            val placedLeft = placed.left
            val placedBottom = placed.bottom
            val appliedOx = placed.parkX
            val appliedOy = placed.parkY

            val panelLeft = originPanelLeft + appliedOx
            val panelBottom = if (input.showActions) {
                placedBottom + underGap + maxBtnH
            } else {
                placedBottom
            }
            val bodyBottom = panelBottom + pad
            val bodyH = (panelH - plan.chromeH).coerceAtLeast(0f)

            val chromeDrawSide = chromeSide.coerceAtMost((panelH - Fonts.px(4f)).coerceAtLeast(1f))
            val btnTopRightBottom = panelBottom + panelH - chromeInset - chromeDrawSide
            val btnCenterBottom = panelBottom + (panelH - chromeDrawSide) * 0.5f
            val collapseLeft = panelLeft + panelW - chromeInset - chromeDrawSide
            val collapseBottom = TileEasing.lerp(btnTopRightBottom, btnCenterBottom, foldT)
            // The title block's top, centered in the band it shares with the control.
            val titleTop = panelBottom + panelH - chromeInset -
                (plan.titleBandH - plan.titleH) * 0.5f
            // The source line sits under the title's last row; the block is what
            // [titleBandH] and [titleTop] centre.
            val titleDetailTop = if (hasHeadlineDetail) {
                titleTop - plan.headlineH - plan.headlineGap
            } else {
                null
            }

            // The collapsed bar's two lines stack from the block's top; the block is
            // centered in the panel so the chrome's minimum height keeps it symmetric.
            val compactTitleTop: Float
            val compactDetailTop: Float?
            if (hasCompactDetail) {
                compactTitleTop = panelBottom + (panelH + compactTextH) * 0.5f
                compactDetailTop = compactTitleTop - titleFont.capHeight - compactLineGap
            } else {
                compactTitleTop = panelBottom + panelH * 0.5f + titleFont.capHeight * 0.5f
                compactDetailTop = null
            }

            return ScoreBreakdownLayout(
                panel = Panel(panelLeft, panelBottom, panelW, panelH),
                block = Block(placedLeft, placedBottom, originBlockW, totalBlockH),
                parkX = appliedOx,
                parkY = appliedOy,
                expandedAlpha = expandedAlpha,
                collapsedAlpha = collapsedAlpha,
                body = Body(bodyBottom, bodyH, plan.bodyContentH, pad, bodyScale),
                chrome = Chrome(
                    // The header's two columns: labels end on the first edge, names
                    // begin on the second. With no label anywhere (a draw) both
                    // collapse onto the body's content edge.
                    titleLabelRight = panelLeft + pad + labelColumnW,
                    titleValueLeft = panelLeft + pad + labelColumnW +
                        if (labelColumnW > 0f) labelGutter else 0f,
                    drawSide = chromeDrawSide,
                    collapseLeft = collapseLeft,
                    collapseBottom = collapseBottom,
                    titleTop = titleTop,
                    titleDetailTop = titleDetailTop,
                ),
                pattern = Pattern(
                    infoSide = plan.infoSide,
                    infoColW = infoColW,
                    topInset = plan.patternTopInset,
                    rowH = plan.rowH,
                    rowGap = plan.rowGap,
                    totalH = plan.totalH,
                    totalGap = plan.totalGap,
                    dividerH = plan.dividerH,
                    scoreSample = scoreSample,
                ),
                settle = Settle(
                    gap = plan.settleGap,
                    rowH = plan.settleRowH,
                    rowGap = plan.settleRowGap,
                    nameW = sNameW,
                    beforeW = sBeforeW,
                    deltaW = sDeltaW,
                    afterW = sAfterW,
                    colGap = sGap,
                    ratingSectionGap = plan.ratingSectionGap,
                    ratingTitleGapTop = plan.ratingTitleGapTop,
                    ratingTitleGapBottom = plan.ratingTitleGapBottom,
                ),
                compact = Compact(
                    compactPadX,
                    compactGap,
                    compactTitleTop,
                    compactDetailTop,
                    compactScoreScale,
                ),
                result = Result(
                    line = resultLine,
                    lineH = plan.resultLineH,
                    // Used only for [ResultSlot.Body]. The rating caption uses the
                    // table's own gaps.
                    gapBottom = plan.resultGap,
                    slot = resultPlan.slot,
                ),
                duration = Duration(
                    line = durationLine,
                    lineH = plan.durationLineH,
                    gapBottom = plan.resultGap,
                ),
                actions = Actions(
                    show = input.showActions,
                    chips = HandEndActionChrome.placeChipBlock(
                        chipBlock,
                        originBtnCenterX + appliedOx,
                        placedBottom,
                    ),
                    height = maxBtnH,
                ),
                comparison = comparisonPlaced,
            )
        }

        /**
         * Decides what the finished-game place says and which row owns it:
         * the body's first line, the rating table's caption, or nothing.
         *
         * The place is the rating table's header when that table is on the card.
         * It is not joined with the table's "Rating changes" label, and it is not
         * also a row of the hand table. One decision, with no fonts or geometry,
         * is what lets the height and the painter agree.
         */
        internal fun resolveResult(
            gameResult: String?,
            hasRatingLines: Boolean,
        ): ResolvedResult {
            val place = gameResult?.trim().orEmpty()
            if (place.isEmpty()) return ResolvedResult("", ResultSlot.None)
            if (!hasRatingLines) return ResolvedResult(place, ResultSlot.Body)
            return ResolvedResult(place, ResultSlot.RatingCaption)
        }

        /** [resolveResult]'s answer: the line, and the row it will occupy. */
        internal data class ResolvedResult(val line: String, val slot: ResultSlot)

        /**
         * The leading [font] leaves under its last line's ink at [scale].
         *
         * A row box is one face line tall, while the glyphs stop
         * [BitmapFont.capHeight] above the baseline and
         * [com.badlogic.gdx.graphics.g2d.BitmapFont.BitmapFontData.descent] below
         * it. The painter draws a line's cap top at its row's top, so this is the
         * empty box a hugging card would otherwise keep under the last row.
         */
        private fun trailingLeading(font: BitmapFont, scale: Float): Float {
            val ink = (font.capHeight - font.data.descent) * scale
            return (font.lineHeight - ink).coerceAtLeast(0f)
        }

        private fun primaryDisplayLabel(input: ScoreBreakdownMeasureInput): String {
            val base = input.primaryLabel.ifEmpty { L10n.get("hand.next") }
            if (input.primaryAction != HandEndAction.NextHand || !input.primaryEnabled) return base
            return HandEndActionChrome.withCountdown(base, input.primaryTimeLeftMs)
        }

        /**
         * One header line measured for the credits columns.
         *
         * A line's label asks for room in the label column and its name for room in
         * the name column; either may be empty. A line with no name — the self-draw
         * phrase — is still a label, so it sizes the label column and is right-
         * aligned there with the rest.
         */
        private fun headerLineMetrics(
            font: BitmapFont,
            label: String,
            name: String,
        ): HeaderLineMetrics =
            HeaderLineMetrics(
                labelW = Fonts.textWidth(font, label),
                valueW = Fonts.textWidth(font, name),
            )

        /** [headerLineMetrics]' answer: the width this line asks of each column. */
        private data class HeaderLineMetrics(val labelW: Float, val valueW: Float)

        private fun maxScoreWidth(
            glyph: GlyphLayout,
            rowFont: BitmapFont,
            totalFont: BitmapFont,
            points: List<Int>,
            total: Int,
        ): Float {
            var w = 0f
            for (p in points) {
                glyph.setText(rowFont, p.toString())
                w = max(w, glyph.width)
            }
            glyph.setText(totalFont, total.toString())
            w = max(w, glyph.width)
            glyph.setText(rowFont, "0000")
            return max(w, glyph.width)
        }
    }
}
