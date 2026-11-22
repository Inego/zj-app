package inego.zungjunggame.render.ui

import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.ui.Rect
import kotlin.math.max

/**
 * Shared layout for modal cards built as a **form**: a title band, one or more
 * sections of rows, and optionally a pair of actions.
 *
 * Every such card used to decide this for itself, and they all decided the same
 * generous way: one vertical stack, a fixed row pitch, and a scrollbar to absorb
 * the overflow. In a landscape phone window that clipped most of the card while
 * half the screen sat empty. This class owns those decisions instead:
 *
 *  - **Width** hugs the card's own content (its widest row, title and trailing
 *    control), floored by [Spec.minWidth] and capped by a fraction of the screen,
 *    so the card is never a viewport-sized slab nor too narrow to read.
 *  - **Mode** is one column, or the sections side by side, when the measured
 *    columns fit the cap. A phone landscape uses the width it has instead of
 *    stacking two short lists into one tall one.
 *  - **Density** is `Comfortable` or `Compact`. Compact shrinks padding, gaps and
 *    row pitch — never below the tappable floor — before anything is allowed to
 *    overflow. Gaps give way first; a row stays at least 36 dp.
 *  - **Actions** sit in a footer while comfortable. In compact they move into
 *    the header (title left, Cancel/Start right), so the card keeps one sticky
 *    band instead of two.
 *  - **Scroll is the fallback**: the layout is computed for each candidate from
 *    roomiest to densest, and the first that fits wins. Only pathological content
 *    (very long translations, huge fonts) scrolls.
 *
 * The form never paints a row. It hands the caller a rect for every row, label
 * and control, so paint and hit-testing read one geometry and cannot disagree.
 *
 * Two chrome models are supported, because both already exist in the app:
 *  - [Chrome.StickyHeader] keeps the title band (and footer, when used) fixed
 *    while the sections scroll inside [body] — the setup card and the language
 *    picker.
 *  - [Chrome.WholeCard] scrolls the title and the sections together under the
 *    card's rim — the settings card, which is one column of mixed controls.
 */
class OverlayForm {

    enum class Mode { Column, SideBySide }

    enum class Density { Comfortable, Compact }

    /** How the title relates to the scroll viewport. */
    enum class Chrome { StickyHeader, WholeCard }

    /** Where the card's action pair ended up this frame. */
    enum class Actions { None, Footer, Header }

    /**
     * Vertical rhythm and paddings for one density. A section row's height is the
     * caller's (a checkbox and a radio row need not measure the same), but the
     * pitch floor, the gaps and the paddings come from here.
     */
    data class Rhythm(
        val padX: Float,
        val padY: Float,
        val titleGap: Float,
        val sectionLabelGap: Float,
        val blockGap: Float,
        val rowH: Float,
        val rowGap: Float,
        val groupPad: Float,
        val actionH: Float,
        val columnGap: Float,
    )

    companion object {
        /** Row pitch that is still comfortably tappable when space is tight. */
        private const val COMPACT_ROW_DP = 36f
        private const val COMFORTABLE_ROW_DP = 40f
        private const val COMPACT_ACTION_DP = 40f
        private const val COMFORTABLE_ACTION_DP = 48f

        fun rhythm(density: Density): Rhythm {
            val line = Fonts.button.lineHeight
            return when (density) {
                Density.Comfortable -> Rhythm(
                    padX = Fonts.px(28f),
                    padY = Fonts.px(20f),
                    titleGap = Fonts.px(16f),
                    sectionLabelGap = Fonts.px(10f),
                    blockGap = Fonts.px(20f),
                    rowH = max(Fonts.px(COMFORTABLE_ROW_DP), line + Fonts.px(14f)),
                    rowGap = Fonts.px(4f),
                    groupPad = Fonts.px(8f),
                    actionH = max(Fonts.px(COMFORTABLE_ACTION_DP), line + Fonts.px(22f)),
                    columnGap = Fonts.px(24f),
                )

                Density.Compact -> Rhythm(
                    padX = Fonts.px(16f),
                    padY = Fonts.px(10f),
                    titleGap = Fonts.px(8f),
                    sectionLabelGap = Fonts.px(4f),
                    blockGap = Fonts.px(10f),
                    rowH = max(Fonts.px(COMPACT_ROW_DP), line + Fonts.px(10f)),
                    rowGap = Fonts.px(3f),
                    groupPad = Fonts.px(6f),
                    actionH = max(Fonts.px(COMPACT_ACTION_DP), line + Fonts.px(16f)),
                    columnGap = Fonts.px(16f),
                )
            }
        }
    }

    /**
     * One group of rows, optionally under a heading.
     *
     * [contentWidth] is the width the section's widest row needs — the column
     * width when the sections stand side by side. It must not depend on the row
     * width the form passes back; it is read once to size the card, before the
     * final width exists.
     *
     * [rows] are heights in top-to-bottom order, measured by the caller at the
     * row width it was handed. [groupPad] and [rowGap] default to the density's;
     * pass 0 for a section that is a plain stack (no framed group), and a larger
     * gap for rows that should breathe.
     */
    data class Section(
        val label: String?,
        val contentWidth: Float,
        val rows: List<Float>,
        val groupPad: Float? = null,
        val rowGap: Float? = null,
    )

    /** An unlabelled full-width block after the sections (a checkbox row). */
    data class Block(val height: Float)

    /**
     * The card's content and the policy it wants. The form turns this into a
     * [Result] for the space it is given.
     *
     * [sections] and [tail] are called more than once: first with the maximum row
     * width to discover each section's [Section.contentWidth] and count, then at
     * the final row width to obtain row heights. Section widths must therefore be
     * independent of the row width (they are text measurements).
     */
    class Spec(
        val title: String,
        val minWidth: Float,
        /** Fraction of the screen width the card may take, by orientation. */
        val landscapeWidthFraction: Float = 0.72f,
        val portraitWidthFraction: Float = 0.88f,
        val chrome: Chrome = Chrome.StickyHeader,
        /**
         * The actions the card wants. [Actions.Footer] may be promoted to
         * [Actions.Header] in compact density; [Actions.None] never is.
         */
        val actions: Actions = Actions.None,
        /** Width of the action pair, for the header promotion and the footer slot. */
        val actionWidth: Float = 0f,
        /** A persistent top-right control (the ✕), present in every density. */
        val closeWidth: Float = 0f,
        val closeHeight: Float = 0f,
        /** Widest block under [tail]; 0 when the tail is narrower than the sections. */
        val tailWidth: Float = 0f,
        val sections: (Rhythm, rowWidth: Float) -> List<Section>,
        val tail: (Rhythm, rowWidth: Float) -> List<Block> = { _, _ -> emptyList() },
    )

    /** Where one section landed. Rows are in top-to-bottom order. */
    data class SectionPlacement(
        val label: Rect?,
        val group: Rect,
        val rows: List<Rect>,
    )

    data class Result(
        val panel: Rect,
        val mode: Mode,
        val density: Density,
        val chrome: Chrome,
        val actions: Actions,
        val rhythm: Rhythm,
        /** The title band: draw the title at its top, inside [title] horizontally. */
        val header: Rect,
        /** The title's own slot, clear of a trailing control. */
        val title: Rect,
        /** The ✕ or the promoted action pair, top-right of the header. */
        val trailing: Rect?,
        /** The action pair's band, present only while [actions] is `Footer`. */
        val footer: Rect?,
        val sections: List<SectionPlacement>,
        val tail: List<Rect>,
        val overflowing: Boolean,
    )

    /** The scroll viewport, installed by [layout] and read by the card's gestures. */
    val body = OverlayBodyScroll()

    /**
     * Size and place the card for [screenW]×[screenH], install the viewport, and
     * return every rect this frame paints or hit-tests.
     *
     * [scrollY] is the owner's offset, clamped to the measured range.
     */
    fun layout(screenW: Float, screenH: Float, spec: Spec, scrollY: Float): Result {
        Fonts.ensureCreated()
        val avail = OverlayViewport.available(screenW, screenH)
        val portrait = screenH > screenW
        val cap = OverlayViewport.maxPanelWidth(
            screenW * (if (portrait) spec.portraitWidthFraction else spec.landscapeWidthFraction),
            avail,
            screenW,
            Fonts.px(24f),
        )
        // Content widths do not depend on the width the form hands back, so probe
        // at the widest row width the screen allows.
        val comfortable = rhythm(Density.Comfortable)
        val compact = rhythm(Density.Compact)

        // Prefer the card the player already knows: keep one column and shrink
        // before changing the structure, and use comfortable targets whenever the
        // room exists. A wide-but-short window still reaches two columns before it
        // is allowed to scroll.
        val order = ArrayList<Pair<Density, Mode>>(4)
        order += Density.Comfortable to Mode.Column
        order += Density.Compact to Mode.Column
        if (canSplit(spec, comfortable, avail, cap)) {
            order += Density.Comfortable to Mode.SideBySide
        }
        if (canSplit(spec, compact, avail, cap)) {
            order += Density.Compact to Mode.SideBySide
        }

        var last: Result? = null
        for ((density, mode) in order) {
            val candidate = place(avail, cap, spec, density, mode, scrollY)
            last = candidate
            if (!candidate.overflowing) return candidate
        }
        return last ?: error("OverlayForm has no candidate")
    }

    private fun canSplit(
        spec: Spec,
        rhythm: Rhythm,
        avail: Rect,
        cap: Float,
    ): Boolean {
        val probe = spec.sections(rhythm, avail.width)
        if (probe.size < 2) return false
        return splitWidth(probe, rhythm) + rhythm.padX * 2f +
            VerticalScrollbar.gutterWidth() <= cap
    }

    private fun place(
        avail: Rect,
        cap: Float,
        spec: Spec,
        density: Density,
        mode: Mode,
        scrollY: Float,
    ): Result {
        val rhythm = rhythm(density)
        val resolved = if (density == Density.Compact && spec.actions == Actions.Footer) {
            Actions.Header
        } else {
            spec.actions
        }
        val trailingW = when (resolved) {
            Actions.Header -> spec.actionWidth
            else -> spec.closeWidth
        }
        val trailingH = when (resolved) {
            Actions.Header -> rhythm.actionH
            else -> spec.closeHeight
        }

        val probe = spec.sections(rhythm, avail.width)
        val contentW = if (mode == Mode.SideBySide) {
            splitWidth(probe, rhythm)
        } else {
            columnWidth(probe, rhythm)
        }
        val titleW = Fonts.textWidth(Fonts.labelActive, spec.title)
        val ownedW = max(
            max(contentW, spec.tailWidth) + rhythm.padX * 2f,
            titleW + rhythm.padX * 2f + trailingW,
        )
        val desiredW = max(spec.minWidth, ownedW) + VerticalScrollbar.gutterWidth()
        val panelW = desiredW.coerceAtMost(cap).coerceAtMost(avail.width)
        val rowWidth = body.measureWidth(panelW, rhythm.padX * 2f)

        val sections = spec.sections(rhythm, rowWidth)
        val tail = spec.tail(rhythm, rowWidth)

        val labelH = Fonts.button.lineHeight
        val sectionInner = { s: Section ->
            s.rows.sum() + s.rowGapOr(rhythm) * (s.rows.size - 1).coerceAtLeast(0)
        }
        val groupH = { s: Section -> s.groupPadOr(rhythm) * 2f + sectionInner(s) }
        val sectionH = { s: Section ->
            (if (s.label != null) labelH + rhythm.sectionLabelGap else 0f) + groupH(s)
        }

        val sectionsH = if (mode == Mode.SideBySide && sections.isNotEmpty()) {
            sections.maxOf(sectionH)
        } else {
            sections.map(sectionH).sum() + rhythm.blockGap * (sections.size - 1).coerceAtLeast(0)
        }
        val tailH = if (tail.isEmpty()) {
            0f
        } else {
            rhythm.blockGap + tail.map { it.height }.sum() + rhythm.rowGap * (tail.size - 1).coerceAtLeast(0)
        }
        val bodyContentH = sectionsH + tailH

        val titleLineH = Fonts.labelActive.lineHeight
        val headerH = rhythm.padY + max(titleLineH, trailingH) + rhythm.titleGap
        val footerBand = if (resolved == Actions.Footer) {
            rhythm.blockGap + rhythm.actionH + rhythm.padY
        } else {
            rhythm.padY
        }

        val panel: Rect
        val contentTop: Float
        if (spec.chrome == Chrome.WholeCard) {
            // The title is part of the column: it scrolls with the rows and only
            // the card's rim stays fixed.
            val fit = body.fitWholeCard(
                avail = avail,
                panelW = panelW,
                chromeH = headerH + rhythm.padY,
                bodyContentH = bodyContentH,
                scrollY = scrollY,
            )
            panel = Rect(fit.left, fit.bottom, panelW, fit.cardH)
            contentTop = body.contentTop
        } else {
            val bodyInset = if (resolved == Actions.Footer) {
                rhythm.padY + rhythm.actionH + rhythm.blockGap
            } else {
                rhythm.padY
            }
            val fit = body.fitAndPlace(
                avail = avail,
                panelW = panelW,
                chromeH = headerH + footerBand,
                bodyContentH = bodyContentH,
                bodyInset = bodyInset,
                scrollY = scrollY,
            )
            panel = Rect(fit.left, fit.bottom, panelW, fit.cardH)
            contentTop = body.contentTop
        }

        val rowsTop = contentTop - if (spec.chrome == Chrome.WholeCard) headerH else 0f
        val headerTop = if (spec.chrome == Chrome.WholeCard) {
            contentTop - rhythm.padY
        } else {
            panel.top - rhythm.padY
        }
        val trailing = if (trailingW > 0f) {
            Rect(
                left = panel.right - rhythm.padX - trailingW,
                bottom = headerTop - trailingH,
                width = trailingW,
                height = trailingH,
            )
        } else {
            null
        }
        val titleRight = if (trailing != null) trailing.left - rhythm.padX else panel.right - rhythm.padX
        val titleSlotW = (titleRight - (panel.left + rhythm.padX)).coerceAtLeast(1f)
        val title = Rect(
            left = panel.left + rhythm.padX,
            bottom = headerTop - titleLineH,
            width = titleSlotW,
            height = titleLineH,
        )
        val header = Rect(
            left = panel.left + rhythm.padX,
            bottom = headerTop - max(titleLineH, trailingH),
            width = (panelW - rhythm.padX * 2f).coerceAtLeast(1f),
            height = max(titleLineH, trailingH),
        )
        val footer = if (resolved == Actions.Footer) {
            Rect(
                left = panel.left + rhythm.padX,
                bottom = panel.bottom + rhythm.padY,
                width = rowWidth,
                height = rhythm.actionH,
            )
        } else {
            null
        }

        val columns = if (mode == Mode.SideBySide) {
            columnRects(sections, rhythm, panel, rowWidth)
        } else {
            emptyList()
        }
        var top = rowsTop
        val placed = ArrayList<SectionPlacement>(sections.size)
        for ((index, section) in sections.withIndex()) {
            val column = if (mode == Mode.SideBySide) {
                columns[index]
            } else {
                ColumnRect(panel.left + rhythm.padX, rowWidth)
            }
            val labelRect: Rect?
            if (section.label != null) {
                labelRect = Rect(column.left, top - labelH, column.width, labelH)
                top -= labelH + rhythm.sectionLabelGap
            } else {
                labelRect = null
            }
            val groupBottom = top - groupH(section)
            val group = Rect(column.left, groupBottom, column.width, groupH(section))
            val rows = ArrayList<Rect>(section.rows.size)
            var rowTop = top - section.groupPadOr(rhythm)
            for (height in section.rows) {
                rows += Rect(column.left + section.groupPadOr(rhythm), rowTop - height, rowWidthOf(column, section, rhythm), height)
                rowTop -= height + section.rowGapOr(rhythm)
            }
            placed += SectionPlacement(labelRect, group, rows)
            if (mode == Mode.Column) top = groupBottom - rhythm.blockGap
        }
        if (mode == Mode.SideBySide && sections.isNotEmpty()) {
            top = rowsTop - sectionsH - rhythm.blockGap
        }

        val tailRects = ArrayList<Rect>(tail.size)
        var tailTop = top
        for (block in tail) {
            tailRects += Rect(panel.left + rhythm.padX, tailTop - block.height, rowWidth, block.height)
            tailTop -= block.height + rhythm.rowGap
        }

        return Result(
            panel = panel,
            mode = mode,
            density = density,
            chrome = spec.chrome,
            actions = resolved,
            rhythm = rhythm,
            header = header,
            title = title,
            trailing = trailing,
            footer = footer,
            sections = placed,
            tail = tailRects,
            overflowing = body.overflowing,
        )
    }

    private data class ColumnRect(val left: Float, val width: Float)

    private fun columnWidth(sections: List<Section>, rhythm: Rhythm): Float =
        sections.maxOfOrNull {
            max(it.contentWidth, labelWidth(it)) + it.groupPadOr(rhythm) * 2f
        } ?: 0f

    private fun splitWidth(sections: List<Section>, rhythm: Rhythm): Float {
        if (sections.isEmpty()) return 0f
        val columns = sections.map {
            max(it.contentWidth, labelWidth(it)) + it.groupPadOr(rhythm) * 2f
        }.sum()
        return columns + rhythm.columnGap * (sections.size - 1)
    }

    private fun labelWidth(section: Section): Float =
        section.label?.let { Fonts.textWidth(Fonts.button, it) } ?: 0f

    private fun columnRects(
        sections: List<Section>,
        rhythm: Rhythm,
        panel: Rect,
        rowWidth: Float,
    ): List<ColumnRect> {
        val widths = sections.map {
            max(it.contentWidth, labelWidth(it)) + it.groupPadOr(rhythm) * 2f
        }
        val total = widths.sum() + rhythm.columnGap * (sections.size - 1)
        var left = panel.left + rhythm.padX + (rowWidth - total) * 0.5f
        return widths.map { width ->
            val column = ColumnRect(left, width)
            left += width + rhythm.columnGap
            column
        }
    }

    /** A row's width inside its column: the column minus the section's own padding. */
    private fun rowWidthOf(column: ColumnRect, section: Section, rhythm: Rhythm): Float =
        (column.width - section.groupPadOr(rhythm) * 2f).coerceAtLeast(1f)

    private fun Section.groupPadOr(rhythm: Rhythm): Float = groupPad ?: rhythm.groupPad

    private fun Section.rowGapOr(rhythm: Rhythm): Float = rowGap ?: rhythm.rowGap
}
