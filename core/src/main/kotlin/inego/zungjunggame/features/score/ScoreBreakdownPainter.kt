package inego.zungjunggame.features.score

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import inego.zungjunggame.engine.HandScoreComponent
import inego.zungjunggame.engine.HandScoreDetails
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.handend.HandEndActionChrome
import inego.zungjunggame.render.handend.HandEndActionHit
import inego.zungjunggame.render.seats.NameTone
import inego.zungjunggame.render.seats.PlayerLabels
import inego.zungjunggame.render.online.RatingLine
import inego.zungjunggame.render.ui.CollapseButton
import inego.zungjunggame.render.ui.InfoButton
import inego.zungjunggame.render.ui.OverlayBodyScroll
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.render.ui.AppPalette

/**
 * Draws a measured [ScoreBreakdownLayout]: card chrome, the compact winner name,
 * expanded pattern/settlement body, and the Scores / Next hand footer.
 */
internal class ScoreBreakdownPainter(
    private val glyphLayout: GlyphLayout,
) {
    private val fadeTint = Color()

    /** Scratch for [PlayerLabels.botNameTint]; one settlement row per seat, drawn in turn. */
    private val botNameTint = Color()
    private val ratingTable = RatingChangesTable()
    private val comparisonTable = ShadowRankedComparisonTable()
    fun draw(
        batch: SpriteBatch,
        camera: Camera,
        px: Texture,
        layout: ScoreBreakdownLayout,
        headline: String,
        headlineLabel: String,
        headlineTone: NameTone,
        headlineDetail: String,
        headlineDetailLabel: String,
        headlineDetailTone: NameTone,
        compactHeadline: String,
        compactDetail: String,
        details: HandScoreDetails,
        settlement: List<SettlementSeatLine>,
        ratingLines: List<RatingLine>,
        comparison: ShadowRankedComparison?,
        collapsed: Boolean,
        grabbing: Boolean,
        pointerWorldX: Float,
        pointerWorldY: Float,
        collapseButton: CollapseButton,
        body: OverlayBodyScroll,
        patternInfoButtons: ArrayList<InfoButton>,
        patternInfoPageIds: ArrayList<String>,
        buttonHits: ArrayList<HandEndActionHit>,
    ) {
        val panel = layout.panel
        val titleFont = Fonts.labelActive
        val rowFont = Fonts.body
        val totalFont = Fonts.labelActive
        val settleFont = Fonts.body
        val components = details.components
        val totalLabel = L10n.get("scores.total")

        OverlayChrome.drawPanel(
            batch, px, panel.left, panel.bottom, panel.w, panel.h,
            if (grabbing) AppPalette.panelGrab else AppPalette.panel,
            if (grabbing) AppPalette.panelRimGrab else AppPalette.panelRim,
            if (grabbing) maxOf(2.5f, Fonts.px(3f)) else maxOf(1.5f, Fonts.px(2f)),
        )

        collapseButton.layoutAt(
            layout.chrome.collapseLeft,
            layout.chrome.collapseBottom,
            layout.chrome.drawSide,
        )
        collapseButton.render(
            batch,
            px,
            pointerWorldX,
            pointerWorldY,
            expanded = !collapsed,
            pressed = grabbing,
        )

        if (layout.collapsedAlpha > 0.01f) {
            drawCompactHeadline(
                batch,
                layout,
                compactHeadline,
                compactDetail,
                headlineTone,
                headlineDetailTone,
                details.totalScore.toString(),
                titleFont,
            )
        }

        if (layout.expandedAlpha > 0.01f) {
            drawExpandedBody(
                batch,
                camera,
                px,
                layout,
                headline,
                headlineLabel,
                headlineTone,
                headlineDetail,
                headlineDetailLabel,
                headlineDetailTone,
                details,
                settlement,
                ratingLines,
                comparison,
                components,
                totalLabel,
                titleFont,
                rowFont,
                totalFont,
                settleFont,
                pointerWorldX,
                pointerWorldY,
                body,
                patternInfoButtons,
                patternInfoPageIds,
            )
        }

        drawFooter(
            batch,
            px,
            layout,
            pointerWorldX,
            pointerWorldY,
            buttonHits,
        )
    }

    private fun drawCompactHeadline(
        batch: SpriteBatch,
        layout: ScoreBreakdownLayout,
        headline: String,
        detailLine: String,
        headlineTone: NameTone,
        detailTone: NameTone,
        scoreText: String,
        titleFont: BitmapFont,
    ) {
        val panel = layout.panel
        val compact = layout.compact
        val collapsedAlpha = layout.collapsedAlpha
        val left = panel.left + compact.padX
        val scoreFont = Fonts.display
        // Measured on the same scaled face the draw uses, so the column reserved
        // here is the width the digits actually take.
        val scoreW = Fonts.withFaceScale(scoreFont, compact.scoreScale) {
            glyphLayout.setText(scoreFont, scoreText)
            glyphLayout.width
        }
        val btnRight = layout.chrome.collapseLeft
        val scoreLeft = btnRight - compact.gap - scoreW
        // Both text lines stop before the total's column: the total spans the two.
        val textMaxW = (scoreLeft - compact.gap - left).coerceAtLeast(1f)
        val titleY = compact.titleTop
        // The bar mirrors the header's tones: the human's own win reads green and
        // the "from <seat>" line turns red only when the human gave the tile away.
        val titleColor = if (headlineTone == NameTone.SelfWon) AppPalette.success else AppPalette.ink
        titleFont.color.set(fade(titleColor, collapsedAlpha))
        titleFont.draw(
            batch,
            headline,
            left,
            titleY,
            0,
            headline.length,
            textMaxW,
            Align.left,
            false,
            "...",
        )
        titleFont.color = Color.WHITE
        Fonts.withFaceScale(scoreFont, compact.scoreScale) {
            scoreFont.color.set(fade(AppPalette.totalAccent, collapsedAlpha))
            scoreFont.draw(
                batch,
                scoreText,
                scoreLeft,
                titleY,
                scoreW,
                Align.right,
                false,
            )
            scoreFont.color = Color.WHITE
        }
        val detailY = compact.detailTop
        if (detailY != null && detailLine.isNotEmpty()) {
            val detailColor =
                if (detailTone == NameTone.SelfDiscarded) AppPalette.danger else AppPalette.inkMuted
            Fonts.drawText(
                batch,
                Fonts.small,
                fitText(Fonts.small, detailLine, textMaxW),
                left,
                detailY,
                fade(detailColor, collapsedAlpha),
            )
        }
    }

    /** [text] trimmed with an ellipsis until it measures at most [maxW] on [font]. */
    private fun fitText(font: BitmapFont, text: String, maxW: Float): String {
        if (Fonts.textWidth(font, text) <= maxW) return text
        val ellipsis = "..."
        var end = text.length
        while (end > 0 && Fonts.textWidth(font, text.substring(0, end) + ellipsis) > maxW) {
            end--
        }
        return if (end == 0) ellipsis else text.substring(0, end) + ellipsis
    }

    /**
     * Draws one header line in credit style: the muted [label] ends on the label
     * column's [labelRight] axis, the bright [name] starts at [valueLeft], one
     * gutter past it.
     *
     * A line may carry only one of the two runs. "Self-draw" is a label with no
     * name, so it stays in the label column where the "Winner:" / "From:" labels
     * are; a draw's verdict has no label and takes the name column, which with no
     * labels in the block is the body's content edge.
     */
    private fun drawHeaderLine(
        batch: SpriteBatch,
        font: BitmapFont,
        label: String,
        name: String,
        tone: NameTone,
        labelRight: Float,
        valueLeft: Float,
        y: Float,
        alpha: Float,
    ) {
                if (label.isNotEmpty()) {
            // Measured with the face it draws with — the body shrink included — so
            // the label's ink, not a frame-time guess, lands on the axis.
            Fonts.drawText(
                batch,
                font,
                label,
                labelRight - Fonts.textWidth(font, label),
                y,
                fade(AppPalette.inkMuted, alpha),
            )
        }
        if (name.isNotEmpty()) {
            Fonts.drawText(batch, font, name, valueLeft, y, fade(nameColor(tone), alpha))
        }
    }

    /**
     * The color a header name is painted with. Only the human player's own part in
     * the hand changes it; every other seat is plain white.
     */
    private fun nameColor(tone: NameTone): Color = when (tone) {
        NameTone.Neutral -> Color.WHITE
        NameTone.SelfWon -> AppPalette.success
        NameTone.SelfDiscarded -> AppPalette.danger
    }

    private fun drawExpandedBody(
        batch: SpriteBatch,
        camera: Camera,
        px: Texture,
        layout: ScoreBreakdownLayout,
        headline: String,
        headlineLabel: String,
        headlineTone: NameTone,
        headlineDetail: String,
        headlineDetailLabel: String,
        headlineDetailTone: NameTone,
        details: HandScoreDetails,
        settlement: List<SettlementSeatLine>,
        ratingLines: List<RatingLine>,
        comparison: ShadowRankedComparison?,
        components: List<HandScoreComponent>,
        totalLabel: String,
        titleFont: BitmapFont,
        rowFont: BitmapFont,
        totalFont: BitmapFont,
        settleFont: BitmapFont,
        pointerWorldX: Float,
        pointerWorldY: Float,
        body: OverlayBodyScroll,
        patternInfoButtons: ArrayList<InfoButton>,
        patternInfoPageIds: ArrayList<String>,
    ) {
        val panel = layout.panel
        val expandedAlpha = layout.expandedAlpha
        val contentLeft = panel.left + layout.body.padX
        val contentRight = contentLeft + body.contentWidth(panel.w, layout.body.padX * 2f)
        val titleTop = layout.chrome.titleTop

        // One shrink for the whole body: the title, the pattern rows (names *and*
        // scores), the total, the settlement and the comparison all paint at it.
        val bodyScale = layout.body.scale

        val labelRight = layout.chrome.titleLabelRight
        val valueLeft = layout.chrome.titleValueLeft
        Fonts.withFaceScale(titleFont, bodyScale) {
            drawHeaderLine(
                batch,
                titleFont,
                headlineLabel,
                headline,
                headlineTone,
                labelRight,
                valueLeft,
                titleTop,
                expandedAlpha,
            )
        }

        // The second header line, at the same shrink and on the same two axes.
        val titleDetailTop = layout.chrome.titleDetailTop
        if (titleDetailTop != null) {
            Fonts.withFaceScale(Fonts.body, bodyScale) {
                drawHeaderLine(
                    batch,
                    Fonts.body,
                    headlineDetailLabel,
                    headlineDetail,
                    headlineDetailTone,
                    labelRight,
                    valueLeft,
                    titleDetailTop,
                    expandedAlpha,
                )
            }
        }

        val hoverX = body.hoverX(pointerWorldX)
        val hoverY = body.hoverY(pointerWorldY)
        val viewTop = body.bottom + body.height
        val viewBottom = body.bottom
        val pattern = layout.pattern
        val settle = layout.settle

        body.drawBody(batch, camera, px, pointerWorldX, pointerWorldY) {
            var yTop = body.contentTop

            // Place line only when no rating table is present. With a rating
            // table, that table paints the place as its own caption.
            val result = layout.result
            if (result.line.isNotEmpty() && result.slot == ScoreBreakdownLayout.ResultSlot.Body) {
                if (yTop >= viewBottom && yTop - result.lineH <= viewTop) {
                    Fonts.withFaceScale(titleFont, bodyScale) {
                        titleFont.color.set(fade(AppPalette.totalAccent, expandedAlpha))
                        titleFont.draw(
                            batch,
                            result.line,
                            contentLeft,
                            yTop,
                            contentRight - contentLeft,
                            Align.center,
                            false,
                        )
                        titleFont.color = Color.WHITE
                    }
                }
                yTop -= result.lineH + result.gapBottom
            }

            // The finished match's span, under the result or the headline. Muted:
            // the place is the result, the duration is context.
            val duration = layout.duration
            if (duration.line.isNotEmpty()) {
                if (yTop >= viewBottom && yTop - duration.lineH <= viewTop) {
                    Fonts.withFaceScale(rowFont, bodyScale) {
                        rowFont.color.set(fade(AppPalette.inkMuted, expandedAlpha))
                        rowFont.draw(
                            batch,
                            duration.line,
                            contentLeft,
                            yTop,
                            contentRight - contentLeft,
                            Align.center,
                            false,
                        )
                        rowFont.color = Color.WHITE
                    }
                }
                yTop -= duration.lineH + duration.gapBottom
            }

            yTop -= pattern.topInset

            if (components.isEmpty()) {
                val empty = L10n.get("scores.no_patterns")
                val emptyH = rowFont.lineHeight * bodyScale
                Fonts.withFaceScale(rowFont, bodyScale) {
                    glyphLayout.setText(rowFont, empty)
                    rowFont.color.set(fade(AppPalette.inkMuted, expandedAlpha))
                    rowFont.draw(
                        batch,
                        empty,
                        panel.left + (panel.w - glyphLayout.width) * 0.5f,
                        yTop,
                    )
                    rowFont.color = Color.WHITE
                }
                yTop -= emptyH
            } else {
                while (patternInfoButtons.size < components.size) {
                    patternInfoButtons += InfoButton()
                }
                val infoLive = expandedAlpha >= 0.999f
                for ((index, c) in components.withIndex()) {
                    val nameLeft = contentLeft + pattern.infoColW
                    if (infoLive) {
                        val info = patternInfoButtons[index]
                        // Centre the control on the same scaled cap box as the text.
                        val textCenterY = yTop - rowFont.capHeight * bodyScale * 0.5f
                        val infoBottom = textCenterY - pattern.infoSide * 0.5f
                        info.layoutAt(contentLeft, infoBottom, pattern.infoSide)
                        if (yTop >= viewBottom && yTop - pattern.rowH <= viewTop) {
                            info.render(batch, px, hoverX, hoverY)
                        }
                        patternInfoPageIds += c.id
                    }
                    if (yTop >= viewBottom && yTop - pattern.rowH <= viewTop) {
                        drawNameScoreRow(
                            batch,
                            rowFont,
                            ScoreBreakdownText.patternDisplayName(c),
                            c.points.toString(),
                            nameLeft,
                            contentRight,
                            pattern.scoreSample,
                            yTop,
                            nameColor = AppPalette.ink,
                            scoreColor = AppPalette.inkSoft,
                            alpha = expandedAlpha,
                            scale = bodyScale,
                        )
                    }
                    yTop -= pattern.rowH + pattern.rowGap
                }
                yTop += pattern.rowGap
            }

            yTop -= pattern.totalGap
            if (yTop >= viewBottom && yTop <= viewTop + pattern.dividerH) {
                batch.setColor(AppPalette.tint(AppPalette.panelRim, 0.35f * expandedAlpha))
                batch.draw(px, contentLeft, yTop - pattern.dividerH, contentRight - contentLeft, pattern.dividerH)
                batch.setColor(Color.WHITE)
            }
            yTop -= pattern.dividerH + pattern.totalGap

            if (yTop >= viewBottom && yTop - pattern.totalH <= viewTop) {
                drawNameScoreRow(
                    batch,
                    totalFont,
                    totalLabel,
                    details.totalScore.toString(),
                    contentLeft,
                    contentRight,
                    pattern.scoreSample,
                    yTop,
                    nameColor = AppPalette.totalAccent,
                    scoreColor = AppPalette.totalAccent,
                    alpha = expandedAlpha,
                    scale = bodyScale,
                )
            }
            yTop -= pattern.totalH

            if (settlement.isNotEmpty()) {
                yTop -= settle.gap
                if (yTop >= viewBottom && yTop <= viewTop + pattern.dividerH) {
                    batch.setColor(AppPalette.tint(AppPalette.panelRim, 0.28f * expandedAlpha))
                    batch.draw(px, contentLeft, yTop - pattern.dividerH, contentRight - contentLeft, pattern.dividerH)
                    batch.setColor(Color.WHITE)
                }
                yTop -= pattern.dividerH + settle.gap

                val highlightPadY = Fonts.px(2f)
                for ((name, before, delta, after, isHuman, isBot) in settlement) {
                    val baseline = yTop
                    val rowBottom = baseline - settle.rowH + Fonts.px(2f)
                    val rowTop = baseline + highlightPadY
                    if (baseline >= viewBottom && rowBottom <= viewTop) {
                        if (isHuman) {
                            batch.setColor(AppPalette.tint(AppPalette.rowWash, 0.06f * expandedAlpha))
                            batch.draw(
                                px,
                                contentLeft - Fonts.px(4f),
                                rowBottom,
                                (contentRight - contentLeft) + Fonts.px(8f),
                                rowTop - rowBottom,
                            )
                            batch.setColor(Color.WHITE)
                        }

                        // A bot is an opponent, so it takes the opponent colour dimmed.
                        val nameColor = if (isBot) {
                            PlayerLabels.botNameTint(AppPalette.ink, botNameTint)
                        } else if (isHuman) {
                            AppPalette.ink
                        } else {
                            AppPalette.ink
                        }
                        val deltaColor = AppPalette.deltaInk(delta)

                        // Columns were sized to the card; the face shrinks with the
                        // rest of the body, so a cell can neither run right out of its
                        // column nor left out of a right-aligned one.
                        Fonts.withFaceScale(settleFont, bodyScale) {
                            var x = contentLeft
                            settleFont.color.set(fade(nameColor, expandedAlpha))
                            settleFont.draw(batch, name, x, baseline, settle.nameW, Align.left, false)
                            x += settle.nameW + settle.colGap

                            settleFont.color.set(fade(AppPalette.inkMuted, expandedAlpha))
                            settleFont.draw(
                                batch,
                                before.toString(),
                                x,
                                baseline,
                                settle.beforeW,
                                Align.right,
                                false,
                            )
                            x += settle.beforeW + settle.colGap

                            settleFont.color.set(fade(deltaColor, expandedAlpha))
                            settleFont.draw(
                                batch,
                                ScoreBreakdownText.formatSignedDelta(delta),
                                x,
                                baseline,
                                settle.deltaW,
                                Align.right,
                                false,
                            )
                            x += settle.deltaW + settle.colGap

                            settleFont.color.set(fade(AppPalette.inkSoft, expandedAlpha))
                            settleFont.draw(
                                batch,
                                after.toString(),
                                x,
                                baseline,
                                settle.afterW,
                                Align.right,
                                false,
                            )
                            settleFont.color = Color.WHITE
                        }
                    }
                    yTop -= settle.rowH + settle.rowGap
                }
            }

            if (ratingLines.isNotEmpty()) {
                // The rating rows share the settlement's scaled columns and the
                // body's face shrink. The caption uses [totalFont], which is untouched.
                yTop = Fonts.withFaceScale(settleFont, bodyScale) {
                    ratingTable.draw(
                        batch = batch,
                        px = px,
                        lines = ratingLines,
                        contentLeft = contentLeft,
                        contentRight = contentRight,
                        startYTop = yTop,
                        viewTop = viewTop,
                        viewBottom = viewBottom,
                        nameW = settle.nameW,
                        beforeW = settle.beforeW,
                        deltaW = settle.deltaW,
                        afterW = settle.afterW,
                        colGap = settle.colGap,
                        rowH = settle.rowH,
                        rowGap = settle.rowGap,
                        dividerH = pattern.dividerH,
                        sectionGap = settle.ratingSectionGap,
                        titleGapTop = settle.ratingTitleGapTop,
                        titleGapBottom = settle.ratingTitleGapBottom,
                        font = settleFont,
                        captionFont = totalFont,
                        // The place, when the match is finished. The table paints it
                        // above its rows. With no place, the table keeps its own header.
                        caption = if (result.slot == ScoreBreakdownLayout.ResultSlot.RatingCaption) {
                            result.line
                        } else {
                            L10n.get("hand.rating_changes")
                        },
                        alpha = expandedAlpha,
                        drawDivider = true,
                    )
                }
            }

            val comparisonMetrics = layout.comparison
            if (comparison != null && comparisonMetrics != null) {
                comparisonTable.draw(
                    batch = batch,
                    px = px,
                    comparison = comparison,
                    metrics = comparisonMetrics,
                    contentLeft = contentLeft,
                    contentRight = contentRight,
                    startYTop = yTop,
                    viewTop = viewTop,
                    viewBottom = viewBottom,
                    captionFont = totalFont,
                    rowFont = settleFont,
                    alpha = expandedAlpha,
                    fontScale = bodyScale,
                )
            }
        }
    }

    private fun drawFooter(
        batch: SpriteBatch,
        px: Texture,
        layout: ScoreBreakdownLayout,
        pointerWorldX: Float,
        pointerWorldY: Float,
        buttonHits: ArrayList<HandEndActionHit>,
    ) {
        val actions = layout.actions
        if (!actions.show || actions.chips.isEmpty()) return
        for ((action, label, enabled, left, bottom, w, h, scale) in actions.chips) {
            val hit = HandEndActionHit(action, left, bottom, w, h, enabled)
            buttonHits += hit
            val hovered = enabled &&
                !pointerWorldX.isNaN() &&
                !pointerWorldY.isNaN() &&
                hit.contains(pointerWorldX, pointerWorldY)
            HandEndActionChrome.drawRectChip(
                batch = batch,
                px = px,
                glyph = glyphLayout,
                label = label,
                left = left,
                bottom = bottom,
                w = w,
                h = h,
                hovered = hovered,
                primary = HandEndActionChrome.isPrimary(action),
                enabled = enabled,
                scale = scale,
            )
        }
    }

    private fun drawNameScoreRow(
        batch: SpriteBatch,
        font: BitmapFont,
        name: String,
        score: String,
        contentLeft: Float,
        contentRight: Float,
        scoreColW: Float,
        baselineY: Float,
        nameColor: Color,
        scoreColor: Color,
        alpha: Float,
        scale: Float = 1f,
    ) {
        // The row shrinks as a unit: name and score both paint at the card's body
        // scale, so a score no longer stays large while its name shrinks. The
        // fallback face is scaled in lockstep.
        Fonts.withFaceScale(font, scale) {
            Fonts.drawText(batch, font, name, contentLeft, baselineY, fade(nameColor, alpha))
            glyphLayout.setText(font, score)
            font.color.set(fade(scoreColor, alpha))
            font.draw(
                batch,
                score,
                contentRight - scoreColW,
                baselineY,
                scoreColW,
                Align.right,
                false,
            )
            font.color = Color.WHITE
        }
    }

    /** Copies [src] into [fadeTint] with multiplied alpha. Do not keep the return. */
    private fun fade(src: Color, alpha: Float): Color {
        fadeTint.set(src.r, src.g, src.b, src.a * alpha)
        return fadeTint
    }
}
