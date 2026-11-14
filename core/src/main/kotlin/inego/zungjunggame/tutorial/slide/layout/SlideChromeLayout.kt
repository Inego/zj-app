package inego.zungjunggame.tutorial.slide.layout

import kotlin.math.max
import kotlin.math.min

/**
 * Where a coach slide puts its copy: the band above the content ([Stacked]) or
 * a column beside it ([Side]).
 */
enum class SlideMode {
    /** Copy across the top; the diagram or board takes the width below it. */
    Stacked,

    /** Copy in a right-hand column; the subject keeps the left pane. */
    Side,
}

/**
 * Adaptive title / body / diagram / primary-button zones for a coach slide.
 *
 * **Fit-first:** diagram gets a guaranteed share of height; the button keeps
 * its size. Type scale follows the viewport, never the beat, so paging through
 * a lesson cannot resize the title. Beats that share a continuity family also
 * share a **copy band**: the tallest title and body among them, measured by the
 * caller, is reserved so tile geometry does not jump when body length changes.
 * A live board's band spans the whole campaign, so the table has one pane in
 * every board beat of every lesson. That band is capped at
 * [BOARD_COPY_BAND_FRAC] of the window; a beat whose body is too long for the
 * cap shrinks its body instead of pushing the table down.
 *
 * **Two modes.** A landscape slide whose beat has a subject — a tile diagram or
 * the live board — puts the copy in a right-hand column ([SlideMode.Side]) and
 * gives the subject the left pane, so the copy never covers it. Everything else
 * stacks the copy above the content ([SlideMode.Stacked]); a stacked live board
 * starts where the copy band ends and runs to the window bottom, so the table is
 * the rest of the window rather than a full-height sliver behind the copy. The
 * same plan feeds [BoardArea], so the table and the chrome cannot disagree about
 * where the board is.
 */
object SlideChromeLayout {

    /**
     * Top of the band behind the Back / Next row, y-up from the window bottom.
     * The row sits on [buttonPadY] and the same pad sits above it, so the buttons
     * are centered in the band. The live table reserves this and the coach paints
     * the bottom dim to it.
     */
    fun buttonBandTop(vp: SlideViewport, buttonLineHeight: Float): Float =
        buttonPadY(vp) + buttonHeight(vp, buttonLineHeight) + buttonPadY(vp)

    /** [buttonBandTop] from a laid-out plan. Same edge as the viewport form. */
    fun buttonBandTop(plan: Plan): Float =
        plan.primaryBottom + plan.primaryH + plan.padY

    /**
     * The rectangle a live board paints and measures into, plus the pixels its
     * bottom edge keeps clear for the Back / Next row.
     *
     * The board never learns about the coach: it is handed the pane the plan
     * leaves it. A side column hands it the pane left of the copy; a stacked
     * layout hands it everything under the copy band.
     *
     * The pane is derived for the viewport it is used in — a board lesson asks
     * for it on every frame rather than reusing a rect laid out for another
     * size — so it carries no viewport of its own to validate.
     */
    data class BoardArea(
        val width: Float,
        val height: Float,
        /** Pixels kept clear at the bottom for the Back / Next row; 0 when none. */
        val bottomReserve: Float,
    )

    /**
     * The board area under a coach that paints over it, read from the laid-out
     * [plan] so the two cannot disagree.
     *
     * A side column hands the table the pane left of the copy. A stacked coach
     * hands it everything below the copy band, so the table starts under the
     * copy instead of behind it. The band is the tallest copy in the beat's
     * family — the campaign for a board overlay — so paging board beats cannot
     * move or resize the table. The stacked board still reserves the Back / Next
     * band at its bottom.
     */
    fun boardArea(vp: SlideViewport, plan: Plan): BoardArea {
        if (plan.mode == SlideMode.Side) {
            return BoardArea(
                width = plan.columnLeft,
                height = vp.height,
                bottomReserve = 0f,
            )
        }
        val navTop = buttonBandTop(plan)
        val top = (plan.copyBandBottom - plan.bodyGap)
            .coerceAtLeast(navTop + vp.px(MIN_STACKED_BOARD_DP))
        return BoardArea(
            width = vp.width,
            height = top,
            bottomReserve = navTop,
        )
    }

    /** The copy column and the subject pane it leaves on its left. */
    private data class SidePane(val left: Float, val right: Float)

    /**
     * The copy column for a landscape viewport whose beat has a subject to show
     * beside it, or null when the stacked layout still fits.
     *
     * The pane keeps at least [MIN_BOARD_ASPECT]× the height. Hand size is solved
     * from the short edge, so a pane narrower than that would start shrinking
     * tiles rather than leaving the table a table; a viewport that cannot give
     * both falls back to stacked.
     */
    private fun sidePane(vp: SlideViewport, hasSubject: Boolean): SidePane? {
        if (!hasSubject || vp.orientation != SlideOrientation.Landscape) return null
        val columnW = min(vp.width * SIDE_COLUMN_FRAC, vp.px(SIDE_COLUMN_MAX_DP))
        val left = vp.width - columnW
        if (left < vp.height * MIN_BOARD_ASPECT) return null
        return SidePane(left, vp.width)
    }

    private const val SIDE_COLUMN_FRAC = 0.38f
    private const val SIDE_COLUMN_MAX_DP = 360f
    private const val MIN_BOARD_ASPECT = 1.1f

    /**
     * Floor under a stacked board's top edge, above the Back / Next band. A
     * pathological copy band can never squeeze the table to nothing; the floor
     * only binds when the copy itself would already overflow the window.
     */
    private const val MIN_STACKED_BOARD_DP = 160f

    /**
     * Share of the window a stacked live board may give up to the coach copy.
     *
     * The copy band is campaign-wide so the table's pane never changes; without
     * a cap it is the tallest body in every board lesson, which on a phone can
     * take a third of the window for copy that is usually much shorter. The
     * cap is a viewport fact, not a beat fact, so the pane stays put. A body
     * that does not fit the cap is scaled down for that beat
     * ([SlideChromeLayout.plan]'s `fitBodyScale`).
     */
    private const val BOARD_COPY_BAND_FRAC = 0.25f

    /** Padding under the Back / Next row. Same value [Plan.padY] uses. */
    fun buttonPadY(vp: SlideViewport): Float = vp.px(
        when {
            vp.isCompact && vp.touch -> 8f
            vp.touch -> 12f
            vp.isCompact -> 12f
            else -> 18f
        },
    )

    /** Height of one Back / Next button. */
    fun buttonHeight(vp: SlideViewport, buttonLineHeight: Float): Float = max(
        vp.px(if (vp.touch) 48f else 44f),
        buttonLineHeight + vp.px(if (vp.touch) 18f else 16f),
    )

    /**
     * Air a fitted action label needs inside its plate, in design px. A button
     * is as wide as its label plus this, never a share of the panel.
     */
    private const val ACTION_LABEL_PAD_PX = 48f

    /** Narrowest action button. Back is secondary, so it takes a narrower floor. */
    const val BACK_BUTTON_MIN_PX = 120f
    const val ACTION_BUTTON_MIN_PX = 140f

    /**
     * Width one Back / Next / Confirm button takes from its label. The coach and
     * the quiz dock both read this, so the same control family has one size on
     * every beat instead of each presenter inventing its own.
     */
    fun actionButtonWidth(
        vp: SlideViewport,
        labelW: Float,
        minDesignPx: Float = ACTION_BUTTON_MIN_PX,
    ): Float = max(vp.px(minDesignPx), labelW + vp.px(ACTION_LABEL_PAD_PX))

    /**
     * Fit one action row into [maxW]: every button keeps the width its label
     * asked for while the row fits; a row that cannot fit shares the width
     * equally rather than spilling past the content zone. In practice only a
     * three-button row needs the fallback, so a lone Back / Next keeps the size
     * its own label earned.
     */
    fun fitActionRow(widths: List<Float>, gap: Float, maxW: Float): List<Float> {
        if (widths.isEmpty()) return widths
        val natural = widths.sum() + gap * (widths.size - 1)
        if (natural <= maxW) return widths
        val share = ((maxW - gap * (widths.size - 1)) / widths.size).coerceAtLeast(0f)
        return List(widths.size) { share }
    }

    /**
     * Bottom edge, y-up, of the dim behind the coach title and body.
     * It sits [Plan.bodyGap] under whichever block renders lower this frame,
     * so the scrim follows the copy instead of a fixed fraction of the screen.
     * Both heights zero: there is no copy, and the edge is the title slot.
     */
    fun copyScrimBottom(plan: Plan): Float {
        val titleInk = if (plan.titleH > 0f) {
            plan.titleTop - plan.titleH
        } else {
            Float.POSITIVE_INFINITY
        }
        val bodyInk = if (plan.bodyH > 0f) {
            plan.bodyTop - plan.bodyH
        } else {
            Float.POSITIVE_INFINITY
        }
        val ink = min(titleInk, bodyInk)
        if (ink == Float.POSITIVE_INFINITY) return plan.titleTop
        return ink - plan.bodyGap
    }

    data class Plan(
        val padX: Float,
        val padY: Float,
        val maxTextW: Float,
        val textLeft: Float,
        val mode: SlideMode,
        /** Left edge of the copy column; 0 when [mode] is stacked. */
        val columnLeft: Float,
        /** Right edge of the copy column; the viewport width when stacked. */
        val columnRight: Float,
        /** Inner zone a Back / Next row is centered in. */
        val rowLeft: Float,
        val rowW: Float,
        /** BitmapFont baseline top for title. */
        val titleTop: Float,
        /** Laid-out title height at [titleScale]. Zero when the title is empty. */
        val titleH: Float,
        val titleScale: Float,
        val bodyTop: Float,
        /** Laid-out body height at [bodyScale]. Zero when the body is empty. */
        val bodyH: Float,
        val bodyScale: Float,
        /**
         * When true, body should use the compact face ([inego.zungjunggame.render.fonts.Fonts.body]
         * / button), not the large labelActive face used for titles.
         */
        val compactBodyFace: Boolean,
        val titleGap: Float,
        val bodyGap: Float,
        val diagramLeft: Float,
        val diagramBottom: Float,
        val diagramW: Float,
        val diagramH: Float,
        val primaryLeft: Float,
        val primaryBottom: Float,
        val primaryW: Float,
        val primaryH: Float,
        /** True when the copy band is fixed so the subject below it cannot move. */
        val fixedCopyBand: Boolean,
        /**
         * Bottom of the reserved copy band, y-up. A stacked live board reserves
         * a fixed share of the window — at most [BOARD_COPY_BAND_FRAC] — the
         * same in every board beat, so the table's pane cannot move. A diagram
         * band is the tallest title and body in its consecutive run; paging the
         * run cannot move the diagram.
         */
        val copyBandBottom: Float,
    )

    /**
     * @param measureTitleH height of wrapped title at [titleScale] and [maxTextW]
     * @param measureBodyH height of wrapped body at [bodyScale] and [maxTextW]
     * @param measureCopyBandH height of the tallest body in this beat's copy
     *   band at [bodyScale] and [maxTextW]. The band is a measured fact about
     *   the beats that share this diagram, not a guessed line count; callers
     *   that have no band pass nothing (the band is the current body).
     * @param measureCopyBandTitleH height of the tallest title in this beat's
     *   copy band at [titleScale] and [maxTextW]. A board band spans every
     *   board lesson, whose titles differ, so the band reserves the tallest one;
     *   otherwise the table's top edge would move with the lesson's title.
     * @param fitBodyScale largest scale at or below the base body scale at which
     *   the current body fits [maxTextW] × [maxHeight]. A stacked live board
     *   uses it when its fixed copy band is shorter than the body; a side
     *   column uses it when the copy would spill over the Back / Next row. The
     *   subject below or beside the copy keeps its pane, and the beat's body
     *   gives up size instead.
     * @param measurePrimaryLabelW unwrapped primary button label width at base button face
     * @param titleLineHeight base line height of the title face (scale 1)
     * @param bodyLineHeight height one body line advances at scale 1 — the rich
     *   layout's line box (tile-aware), not the face's line height
     * @param buttonLineHeight base line height of the button face
     */
    fun plan(
        vp: SlideViewport,
        hasTileDiagram: Boolean,
        titleEmpty: Boolean,
        bodyEmpty: Boolean,
        titleLineHeight: Float,
        bodyLineHeight: Float,
        buttonLineHeight: Float,
        measureTitleH: (titleScale: Float, maxTextW: Float) -> Float,
        measureBodyH: (bodyScale: Float, maxTextW: Float) -> Float,
        measureCopyBandH: (bodyScale: Float, maxTextW: Float) -> Float = { _, _ -> 0f },
        measureCopyBandTitleH: (titleScale: Float, maxTextW: Float) -> Float = { _, _ -> 0f },
        fitBodyScale: (baseScale: Float, maxTextW: Float, maxHeight: Float) -> Float =
            { base, _, _ -> base },
        measurePrimaryLabelW: () -> Float,
        /**
         * True when this beat shows the live board instead of a tile diagram.
         * Together with [hasTileDiagram] it decides whether a landscape slide
         * has a subject worth a side column.
         */
        boardOverlay: Boolean = false,
    ): Plan {
        val touch = vp.touch
        val padX = vp.px(
            when {
                vp.isCompact && touch -> 12f
                touch -> 16f
                vp.isCompact -> 18f
                else -> 28f
            },
        )
        val padY = buttonPadY(vp)
        val bodyGap = vp.px(
            when {
                hasTileDiagram && vp.isCompact -> 10f
                hasTileDiagram -> 14f
                else -> 12f
            },
        )

        val side = sidePane(vp, hasSubject = hasTileDiagram || boardOverlay)
        val columnLeft = side?.left ?: 0f
        val columnRight = side?.right ?: vp.width
        val columnW = columnRight - columnLeft
        val rowLeft = columnLeft + padX
        val rowW = (columnW - padX * 2f).coerceAtLeast(1f)

        val maxTextW = if (side != null) {
            rowW
        } else {
            min(
                vp.width - padX * 2f,
                vp.px(
                    when {
                        hasTileDiagram && vp.isPortrait -> 560f
                        hasTileDiagram -> 720f
                        vp.isPortrait -> 520f
                        else -> 680f
                    },
                ),
            )
        }
        val textLeft = if (side != null) rowLeft else (vp.width - maxTextW) * 0.5f

        val primaryH = buttonHeight(vp, buttonLineHeight)
        val primaryLabelW = measurePrimaryLabelW()
        val primaryW = actionButtonWidth(vp, primaryLabelW).coerceAtMost(rowW)
        val primaryLeft = rowLeft + (rowW - primaryW) * 0.5f
        val primaryBottom = buttonPadY(vp)

        val titleTop = vp.height - padY
        val contentBottom = primaryBottom + primaryH + bodyGap
        val availableH = (titleTop - contentBottom).coerceAtLeast(vp.px(64f))

        // Diagram slides: never use the oversized title face for body copy.
        val compactBodyFace = hasTileDiagram

        // Soft max scales — grow only when the viewport is comfortable; never the old 1.4×.
        val titleScaleMax = when {
            !hasTileDiagram -> 1f
            vp.sizeClass == SlideSizeClass.Comfortable && !vp.isPortrait -> 1.05f
            vp.sizeClass == SlideSizeClass.Compact -> 0.92f
            else -> 1f
        }
        val bodyScaleMax = when {
            !hasTileDiagram -> 1f
            vp.sizeClass == SlideSizeClass.Comfortable -> 1.05f
            vp.sizeClass == SlideSizeClass.Compact -> 0.95f
            else -> 1f
        }

        // Height shares: diagram is first-class; copy is secondary.
        val copyMaxFrac = when {
            !hasTileDiagram -> 0.72f
            vp.isPortrait && vp.isCompact -> 0.26f
            vp.isPortrait -> 0.30f
            vp.isCompact -> 0.28f
            else -> 0.32f
        }
        val diagramMinFrac = when {
            !hasTileDiagram -> 0f
            vp.isPortrait && vp.isCompact -> 0.48f
            vp.isPortrait -> 0.50f
            else -> 0.46f
        }

        // Type scale belongs to the viewport, never to the beat's copy: paging
        // between two beats of one lesson must not resize the title. The body
        // may shrink to fit a stacked board's fixed band, but that band is the
        // same for every board beat, so the table never moves.
        val titleScale = titleScaleMax
        val bodyScaleMaxForBeat = bodyScaleMax
        val titleH = if (titleEmpty) 0f else measureTitleH(titleScale, maxTextW)
        val bodyBaseH = if (bodyEmpty) 0f else measureBodyH(bodyScaleMaxForBeat, maxTextW)

        fun titleGapFor(tScale: Float, hasBody: Boolean): Float {
            if (!hasBody || titleEmpty) return 0f
            val line = titleLineHeight * tScale
            return if (hasTileDiagram) {
                max(vp.px(if (touch) 12f else 16f), line * 0.45f)
            } else {
                vp.px(10f)
            }
        }

        val titleGap = titleGapFor(titleScale, hasBody = !bodyEmpty)
        // The copy band is the tallest title + body in this beat's continuity
        // family, measured by the caller. It deliberately does not depend on the
        // current beat's copy: a short beat reserves the same height as the
        // longest one, so the diagram or board below the copy cannot move when
        // paging the family. A board overlay has content below the copy too, so
        // it takes the band as well; a plain text slide has nothing to hold
        // still.
        val copyBandBodyH = if (hasTileDiagram || boardOverlay) {
            max(bodyBaseH, measureCopyBandH(bodyScaleMaxForBeat, maxTextW))
        } else {
            bodyBaseH
        }
        val copyBandTitleH = if (hasTileDiagram || boardOverlay) {
            max(titleH, measureCopyBandTitleH(titleScale, maxTextW))
        } else {
            titleH
        }
        val bandHasBody = !bodyEmpty || copyBandBodyH > 0f
        val bandGap = titleGapFor(titleScaleMax, hasBody = bandHasBody)
        val copyNatural = copyBandTitleH + bandGap + copyBandBodyH

        // A stacked live board gives the coach at most a quarter of the window;
        // the table owns the rest, and the pane must not move between beats.
        // The campaign band is the tallest copy in every board lesson, so on a
        // phone it can be far more than a quarter. The cap is a viewport fact,
        // not a beat fact, so every board beat reserves the same band. The floor
        // keeps the tallest title and one body line readable on a window too
        // short for the cap; it is still band-wide, so the table does not move.
        val copyBandH = if (boardOverlay && side == null) {
            val cap = (vp.height * BOARD_COPY_BAND_FRAC - padY - bodyGap)
                .coerceAtLeast(0f)
            val floor = copyBandTitleH + bandGap + bodyLineHeight * bodyScaleMaxForBeat
            min(copyNatural, max(cap, floor))
        } else {
            copyNatural
        }

        // A body too long for its reserved area shrinks for this beat instead
        // of pushing the table down or spilling over the buttons. A stacked
        // board has a fixed band; a side column has the whole column above the
        // Back / Next row. The title keeps its band-wide size, so paging cannot
        // resize it.
        var bodyScale = bodyScaleMaxForBeat
        var bodyH = bodyBaseH
        val bodyAvail = when {
            boardOverlay && side == null -> copyBandH - titleH - titleGap
            side != null -> availableH - titleH - titleGap
            else -> null
        }?.coerceAtLeast(0f)
        if (bodyAvail != null && !bodyEmpty && bodyH > bodyAvail) {
            bodyScale = fitBodyScale(bodyScaleMaxForBeat, maxTextW, bodyAvail)
            bodyH = measureBodyH(bodyScale, maxTextW)
        }

        val copyBudget = if (hasTileDiagram) {
            // The band is measured, not guessed: [copyBandBodyH] is the tallest
            // body among the beats that share this diagram, so every beat in
            // the family reserves the same height. The viewport share is only a
            // last-resort cap; if it binds, the diagram gives up the overflow
            // once for the whole family instead of per beat.
            val fixedBand = titleH + bandGap + copyBandBodyH + bodyGap * 0.25f
            min(availableH * copyMaxFrac, fixedBand)
                .coerceAtLeast(titleLineHeight + bodyLineHeight)
        } else {
            availableH * copyMaxFrac
        }

        val effectiveTitleGap = if (bodyEmpty || titleEmpty) 0f else titleGap
        val bodyTop = titleTop - titleH - effectiveTitleGap

        // A side column gives the subject the whole left pane; the stacked
        // layout keeps solving the diagram band under the copy.
        val diagramLeft: Float
        val diagramBottom: Float
        val diagramW: Float
        val diagramH: Float
        if (side != null) {
            diagramLeft = padX
            diagramBottom = padY
            diagramW = (columnLeft - padX * 2f).coerceAtLeast(0f)
            diagramH = if (hasTileDiagram) (vp.height - padY * 2f).coerceAtLeast(0f) else 0f
        } else {
            diagramLeft = padX
            diagramBottom = contentBottom
            diagramW = vp.width - padX * 2f
            diagramH = if (hasTileDiagram) {
                // The band already covers every body in the family, so [copyNatural]
                // is the same for all of its beats. Subtracting the overflow (and
                // not jumping to the current copy's own bottom) keeps the diagram
                // constant across the family — a one-pixel band overrun costs one
                // pixel of diagram, never a snap that re-centres a width-limited
                // square inside the rect.
                val rawH = (titleTop - copyBudget - contentBottom).coerceAtLeast(0f)
                val minDiag = availableH * diagramMinFrac
                val overflow = (copyNatural - copyBudget).coerceAtLeast(0f)
                max(rawH, minDiag)
                    .coerceAtLeast(vp.px(72f))
                    .coerceAtMost((titleTop - contentBottom - vp.px(4f)).coerceAtLeast(vp.px(72f)))
                    .coerceAtMost((rawH - overflow).coerceAtLeast(vp.px(72f)))
            } else {
                val textBottom = if (bodyEmpty) bodyTop else bodyTop - bodyH
                (textBottom - bodyGap - contentBottom).coerceAtLeast(0f)
            }
        }

        return Plan(
            padX = padX,
            padY = padY,
            maxTextW = maxTextW,
            textLeft = textLeft,
            mode = if (side != null) SlideMode.Side else SlideMode.Stacked,
            columnLeft = columnLeft,
            columnRight = columnRight,
            rowLeft = rowLeft,
            rowW = rowW,
            titleTop = titleTop,
            titleH = titleH,
            titleScale = titleScale,
            bodyTop = bodyTop,
            bodyH = bodyH,
            bodyScale = bodyScale,
            compactBodyFace = compactBodyFace,
            titleGap = effectiveTitleGap,
            bodyGap = bodyGap,
            diagramLeft = diagramLeft,
            diagramBottom = diagramBottom,
            diagramW = diagramW,
            diagramH = diagramH,
            primaryLeft = primaryLeft,
            primaryBottom = primaryBottom,
            primaryW = primaryW,
            primaryH = primaryH,
            fixedCopyBand = hasTileDiagram || (boardOverlay && side == null),
            copyBandBottom = titleTop - copyBandH,
        )
    }
}
