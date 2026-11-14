package inego.zungjunggame.render.handend

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.render.fonts.Fonts
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import inego.zungjunggame.render.ui.AppPalette

/** One hand-end chip (Scores / primary CTA / online leave) before it is placed. */
data class HandEndChipSpec(
    val action: HandEndAction,
    val label: String,
    val enabled: Boolean,
)

/** Measured chip: [HandEndChipSpec] plus density-aware size. */
data class HandEndSizedAction(
    val action: HandEndAction,
    val label: String,
    val w: Float,
    val h: Float,
    val enabled: Boolean,
)

/** Horizontal row of measured hand-end chips. */
data class HandEndChipRow(
    val chips: List<HandEndSizedAction>,
    val maxH: Float,
    val gap: Float,
    val rowW: Float,
    /** Uniform shrink applied so the row fits its max width; 1 when it fits raw. */
    val scale: Float = 1f,
)

/**
 * The hand-end control block: the [primary] Scores/CTA [row][HandEndChipRow], plus
 * an optional [under] row on its own line below it (the online *Back to Online home*).
 *
 * Stacking beats widening: the under-row never pushes the CTA row against the
 * screen edge, and both lines share one [HandEndChipRow.maxH] and one shrink.
 */
data class HandEndChipBlock(
    val primary: HandEndChipRow,
    val under: HandEndChipRow?,
    val rowGap: Float,
    val width: Float,
    val height: Float,
)

/** A measured chip placed in world space, ready to hit-test or draw in any chrome. */
data class HandEndPlacedChip(
    val action: HandEndAction,
    val label: String,
    val enabled: Boolean,
    val left: Float,
    val bottom: Float,
    val w: Float,
    val h: Float,
    val scale: Float,
)

/** Hit rect for a placed hand-end control. */
data class HandEndActionHit(
    val action: HandEndAction,
    val left: Float,
    val bottom: Float,
    val w: Float,
    val h: Float,
    val enabled: Boolean = true,
) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= left + w && y >= bottom && y <= bottom + h
}

/**
 * Shared measure + chrome for the hand-end card's terminal controls.
 *
 * [ScoreBreakdownOverlay] (win or exhaustive-draw card) uses the same padding,
 * min sizes, countdown suffix, and chip roles. Its chips are drawn as
 * axis-aligned rects by [inego.zungjunggame.features.score.ScoreBreakdownPainter].
 */
object HandEndActionChrome {
    fun isPrimary(action: HandEndAction): Boolean =
        action == HandEndAction.NextHand ||
            action == HandEndAction.NewGame ||
            action == HandEndAction.PlayAgain

    /** Appends `  N` seconds when [remainingMs] is a live countdown. */
    fun withCountdown(base: String, remainingMs: Long?): String {
        val remaining = remainingMs ?: return base
        val seconds = ceil(remaining.coerceAtLeast(0L) / 1000.0).toInt()
        return if (seconds > 0) "$base  $seconds" else base
    }

    fun actionAt(hits: List<HandEndActionHit>, worldX: Float, worldY: Float): HandEndAction? {
        for (i in hits.lastIndex downTo 0) {
            val hit = hits[i]
            if (hit.contains(worldX, worldY)) {
                return if (hit.enabled) hit.action else null
            }
        }
        return null
    }

    /**
     * Row contents shared by the draw banner and the win card: Scores, then the
     * primary CTA. [showComparison] adds the Shadow Ranked **Comparison** chip
     * between them, and is set only when the card carries a comparison.
     * GL-free on purpose (key lookup only) so tests can assert the row without a
     * backend.
     */
    fun chipSpecs(
        primaryAction: HandEndAction,
        primaryLabel: String,
        primaryEnabled: Boolean,
        showScoreTable: Boolean = true,
        showComparison: Boolean = false,
    ): List<HandEndChipSpec> = buildList {
        if (showScoreTable) add(HandEndChipSpec(HandEndAction.ScoreTable, L10n.get("hand.scores"), enabled = true))
        if (showComparison) {
            add(HandEndChipSpec(HandEndAction.Comparison, L10n.get("shadowRanked.comparison.open"), enabled = true))
        }
        add(HandEndChipSpec(primaryAction, primaryLabel, enabled = primaryEnabled))
    }

    /**
     * Contents of the row *under* the chips: the online **Back to Online home**.
     *
     * It is a real chip — as a plain text link it read as a caption rather than
     * a control — but it keeps its own line, so it never crowds the CTA row.
     * Empty when [action] is null. GL-free on purpose (key lookup only).
     */
    fun underRowSpecs(action: HandEndAction?, label: String): List<HandEndChipSpec> =
        if (action == null) emptyList()
        else listOf(HandEndChipSpec(action, label, enabled = true))

    /**
     * Uniform shrink factor that makes a [rowW]-wide row fit [maxRowW].
     *
     * Returns 1 for a row that already fits, and for an unbounded (non-finite,
     * zero, or negative) [maxRowW] — callers without a width to respect must not
     * collapse the row. GL-free on purpose so tests can assert the fit rule.
     */
    fun rowFitScale(rowW: Float, maxRowW: Float): Float {
        if (!maxRowW.isFinite() || maxRowW <= 0f) return 1f
        if (rowW <= 0f || rowW <= maxRowW) return 1f
        return maxRowW / rowW
    }

    /**
     * Measures the hand-end control block: the [primarySpecs] row, plus an
     * optional [underSpecs] row on its own line underneath.
     *
     * @param maxRowW width each row must fit (portrait phones are narrow). One
     *   shrink is computed for the whole block — stacked lines must not disagree
     *   on size — and applied uniformly, labels included, instead of clipping.
     */
    fun measureChipBlock(
        glyph: GlyphLayout,
        primarySpecs: List<HandEndChipSpec>,
        underSpecs: List<HandEndChipSpec> = emptyList(),
        touch: Boolean = InputProfile.detect() == InputProfile.Touch,
        maxRowW: Float = Float.POSITIVE_INFINITY,
    ): HandEndChipBlock {
        val rowGap = if (touch) Fonts.px(10f) else Fonts.px(8f)
        val primaryRaw = measureRow(glyph, primarySpecs, touch)
        val underRaw = if (underSpecs.isEmpty()) null else measureRow(glyph, underSpecs, touch)
        // One chip height for the whole block, so the stacked rows line up.
        val rowH = max(primaryRaw.maxH, underRaw?.maxH ?: 0f)
        val primary = primaryRaw.equalized(rowH)
        val under = underRaw?.equalized(rowH)
        val scale = min(
            rowFitScale(primary.rowW, maxRowW),
            rowFitScale(under?.rowW ?: 0f, maxRowW),
        )
        val primaryFitted = primary.shrunkBy(scale)
        val underFitted = under?.shrunkBy(scale)
        val gap = rowGap * scale
        return HandEndChipBlock(
            primary = primaryFitted,
            under = underFitted,
            rowGap = gap,
            width = max(primaryFitted.rowW, underFitted?.rowW ?: 0f),
            height = primaryFitted.maxH + if (underFitted == null) 0f else gap + underFitted.maxH,
        )
    }

    /**
     * Places [block] centred on [centerX] with the block's bottom edge at
     * [bottom]: the primary row on top, the under-row on the line below it.
     *
     * GL-free arithmetic on an already-measured block, so the one rule that keeps
     * **Back to Online home** off the CTA line stays unit testable.
     */
    fun placeChipBlock(
        block: HandEndChipBlock,
        centerX: Float,
        bottom: Float,
    ): List<HandEndPlacedChip> {
        val placed = ArrayList<HandEndPlacedChip>(
            block.primary.chips.size + (block.under?.chips?.size ?: 0),
        )
        val primaryBottom = bottom + block.height - block.primary.maxH
        placeRow(placed, block.primary, primaryBottom, centerX)
        val under = block.under
        if (under != null) {
            placeRow(placed, under, primaryBottom - block.rowGap - under.maxH, centerX)
        }
        return placed
    }

    private fun placeRow(
        into: MutableList<HandEndPlacedChip>,
        row: HandEndChipRow,
        bottom: Float,
        centerX: Float,
    ) {
        var x = centerX - row.rowW * 0.5f
        for ((action, label, w, _, enabled) in row.chips) {
            into += HandEndPlacedChip(
                action = action,
                label = label,
                enabled = enabled,
                left = x,
                bottom = bottom,
                w = w,
                h = row.maxH,
                scale = row.scale,
            )
            x += w + row.gap
        }
    }

    private fun measureRow(
        glyph: GlyphLayout,
        specs: List<HandEndChipSpec>,
        touch: Boolean,
    ): HandEndChipRow {
        val padX = if (touch) Fonts.px(18f) else Fonts.px(14f)
        val padY = if (touch) Fonts.px(12f) else Fonts.px(8f)
        val minH = if (touch) Fonts.px(44f) else Fonts.px(30f)
        val gap = if (touch) Fonts.px(12f) else Fonts.px(10f)
        val minW = if (touch) Fonts.px(120f) else Fonts.px(96f)
        if (specs.isEmpty()) return HandEndChipRow(emptyList(), 0f, gap, 0f)
        val buttonFont = Fonts.button
        val chips = ArrayList<HandEndSizedAction>(specs.size)
        var maxH = minH
        for ((action, label, enabled) in specs) {
            glyph.setText(buttonFont, label)
            val w = max(glyph.width + padX * 2f, minW)
            val h = max(glyph.height + padY * 2f, minH)
            maxH = max(maxH, h)
            chips += HandEndSizedAction(action, label, w, h, enabled)
        }
        val equalized = chips.map { it.copy(h = maxH) }
        val rowW = equalized.sumOf { it.w.toDouble() }.toFloat() +
            gap * (equalized.size - 1).coerceAtLeast(0)
        return HandEndChipRow(equalized, maxH, gap, rowW)
    }

    private fun HandEndChipRow.equalized(h: Float): HandEndChipRow =
        if (maxH == h) this else copy(chips = chips.map { it.copy(h = h) }, maxH = h)

    private fun HandEndChipRow.shrunkBy(scale: Float): HandEndChipRow {
        if (scale == 1f) return this
        return HandEndChipRow(
            chips = chips.map { it.copy(w = it.w * scale, h = it.h * scale) },
            maxH = maxH * scale,
            gap = gap * scale,
            rowW = rowW * scale,
            scale = scale,
        )
    }

    fun bgColor(hovered: Boolean, primary: Boolean, enabled: Boolean): Color = when {
        !enabled -> AppPalette.buttonFillDisabled
        hovered && primary -> AppPalette.plateHover
        hovered -> AppPalette.buttonFillHover
        primary -> AppPalette.buttonFillPrimary
        else -> AppPalette.buttonFill
    }

    fun borderColor(hovered: Boolean, primary: Boolean, enabled: Boolean): Color = when {
        !enabled -> AppPalette.rimDisabled
        hovered -> AppPalette.tint(AppPalette.accent, 0.98f)
        primary -> AppPalette.tint(AppPalette.success, 0.90f)
        else -> AppPalette.tint(AppPalette.panelRim, 0.82f)
    }

    fun labelColor(enabled: Boolean): Color = if (enabled) AppPalette.ink else AppPalette.inkDisabled

    fun drawLabel(
        batch: SpriteBatch,
        glyph: GlyphLayout,
        label: String,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        enabled: Boolean,
        scale: Float = 1f,
    ) {
        val buttonFont = Fonts.button
        Fonts.withFaceScale(buttonFont, scale) {
            glyph.setText(buttonFont, label)
            buttonFont.color = labelColor(enabled)
            buttonFont.draw(
                batch,
                label,
                left + (w - glyph.width) * 0.5f,
                bottom + h * 0.5f + glyph.height * 0.5f,
            )
            buttonFont.color = Color.WHITE
        }
    }

    fun drawRectChip(
        batch: SpriteBatch,
        px: Texture,
        glyph: GlyphLayout,
        label: String,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        hovered: Boolean,
        primary: Boolean,
        enabled: Boolean,
        scale: Float = 1f,
    ) {
        val bg = bgColor(hovered, primary, enabled)
        val border = borderColor(hovered, primary, enabled)
        val borderT = max(Fonts.px(1.8f), h * 0.06f)
        val shadow = Fonts.px(2f) * scale

        batch.setColor(AppPalette.tint(AppPalette.shadow, 0.28f))
        batch.draw(px, left + shadow * 0.4f, bottom - shadow, w, h)
        batch.setColor(border)
        batch.draw(px, left, bottom, w, h)
        batch.setColor(bg)
        batch.draw(
            px,
            left + borderT,
            bottom + borderT,
            w - borderT * 2f,
            h - borderT * 2f,
        )
        batch.setColor(Color.WHITE)
        drawLabel(batch, glyph, label, left, bottom, w, h, enabled, scale)
    }
}
