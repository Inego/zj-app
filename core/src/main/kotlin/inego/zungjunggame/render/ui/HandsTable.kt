package inego.zungjunggame.render.ui

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack
import com.badlogic.gdx.utils.Align
import inego.zungjunggame.engine.HandScoreComponent
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.ui.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * One player column of a hands table: its resolved label and whether it is a bot
 * (dimmed). Order is the caller's — the in-game table orders relative to the
 * human, Shadow Ranked orders by the attempt's own seats.
 */
data class HandsTablePlayer(val label: String, val dim: Boolean = false)

/**
 * One hand row, already resolved to display strings and with [deltas]/[totals]
 * permuted into the doc's column order.
 */
data class HandsTableRow(
    val number: Int,
    val winner: String,
    val from: String,
    val score: String,
    val patterns: String,
    val deltas: IntArray,
    val totals: IntArray,
)

/** One attempt's table: its four columns, its rows, and the running totals. */
data class HandsTableDoc(
    val players: List<HandsTablePlayer>,
    val rows: List<HandsTableRow>,
    val totals: IntArray,
    /** Column index to lift with a brighter plate, or null for none. */
    val highlight: Int? = null,
)

/**
 * A hands table over one or more documents. Column widths and the row grid are
 * measured across **all** documents, so the Shadow Ranked Who control can switch
 * the shown attempt without the card resizing; the in-game table passes a single
 * document.
 */
data class HandsTableModel(
    val docs: List<HandsTableDoc>,
    val selected: Int = 0,
) {
    val shown: HandsTableDoc? get() = docs.getOrNull(selected)

    companion object {
        val EMPTY = HandsTableModel(emptyList())
    }
}

/**
 * The hands table's formatters, shared by callers that build a [HandsTableRow].
 *
 * Colours are deliberately absent: the renderer reads its roles from
 * [AppPalette], the UI's only colour authority.
 */
object HandsTableText {
    /** Plus only for positive deltas; absolute scores stay unsigned. */
    fun formatSignedDelta(v: Int): String = if (v > 0) "+$v" else v.toString()

    /** The Patterns cell: each component's localized name and unsigned points. */
    fun patternsText(components: List<HandScoreComponent>, won: Boolean): String {
        if (components.isEmpty()) return if (won) L10n.get("scores.no_patterns") else ""
        // A lone pattern's points are already the Score cell, so drop the redundant parenthetical.
        if (components.size == 1) return componentName(components[0])
        return components.joinToString(", ") { c ->
            componentName(c) + " (" + c.points + ")"
        }
    }

    /** The localized name of one scoring component. */
    fun componentName(c: HandScoreComponent): String =
        if (c.formatArgs.isEmpty()) L10n.get(c.id) else L10n.format(c.id, *c.formatArgs.toTypedArray())

    /** Winner cell: the winner's label, or the localized draw marker. */
    fun winnerText(handValue: Int, winnerSeat: Int?, labelAt: (Int) -> String): String =
        if (handValue <= 0 || winnerSeat == null) L10n.get("handsTable.draw") else labelAt(winnerSeat)

    /** From cell: the discarder's label, or the self-draw marker. */
    fun fromText(winnerSeat: Int?, discarderSeat: Int?, labelAt: (Int) -> String): String = when {
        discarderSeat == null || discarderSeat == winnerSeat -> "—"
        else -> labelAt(discarderSeat)
    }

    /** Score cell: the winning value, unsigned, or the draw marker. */
    fun scoreText(handValue: Int, winnerSeat: Int?): String =
        if (handValue <= 0 || winnerSeat == null) "—" else handValue.toString()
}

/**
 * Paints a [HandsTableModel] as the single hands table used by both the in-game
 * Scores card and the Shadow Ranked hand-tables card.
 *
 * The column set is `# · Winner · From · Score · Patterns · four players`. Every
 * column is measured across all docs; Patterns — the one long, wrapping column —
 * shrinks from its longest line down to its longest token, and only when even
 * that is too wide do all table faces zoom down by one shared factor, so nothing
 * clips and the table never pans sideways. The player cell is the Game Score
 * style: the delta on top (larger, green/red/neutral) over the running total
 * (smaller, muted).
 *
 * The renderer is stateless apart from its memoized measurement; the owner keeps
 * the scroll offset and supplies the bands' geometry.
 */
class HandsTableRenderer {
    private val glyphLayout = GlyphLayout()
    private val clipBounds = Rectangle()
    private val scissors = Rectangle()

    private fun whitePixel(): Texture = WhitePixel.get()

    /** Column x offsets and widths, relative to the table's left edge. */
    data class Grid(
        val numW: Float,
        val winnerW: Float,
        val fromW: Float,
        val scoreW: Float,
        val patternsW: Float,
        val seatW: Float,
        val gap: Float,
        /** Narrowest Patterns can wrap to without cutting a token. */
        val patternsMinW: Float = patternsW,
    ) {
        val numX: Float get() = 0f
        val winnerX: Float get() = numW + gap
        val fromX: Float get() = winnerX + winnerW + gap
        val scoreX: Float get() = fromX + fromW + gap
        val patternsX: Float get() = scoreX + scoreW + gap
        fun seatX(seat: Int): Float = patternsX + patternsW + gap + seat * (seatW + gap)
        val width: Float get() = seatX(3) + seatW

        fun scaled(factor: Float): Grid = copy(
            numW = numW * factor,
            winnerW = winnerW * factor,
            fromW = fromW * factor,
            scoreW = scoreW * factor,
            patternsW = patternsW * factor,
            seatW = seatW * factor,
            gap = gap * factor,
            patternsMinW = patternsMinW * factor,
        )
    }

    /** The measured table: its grid, zoom, row heights, panel and body sizes. */
    data class Frame(
        val panelW: Float,
        val contentW: Float,
        val grid: Grid,
        val zoom: Float,
        val rowHeights: List<Float>,
        val bodyContentH: Float,
        val headerH: Float,
        val footerH: Float,
    )

    private var cachedDocs: List<HandsTableDoc>? = null
    private var cachedAvailW = Float.NaN
    private var cachedPad = Float.NaN
    private var cachedScale = Float.NaN
    private var cachedFrame: Frame? = null

    /**
     * Measure [model] into the available viewport [avail] with content [pad].
     * Memoized on the document list, the width and the UI scale.
     */
    fun frame(model: HandsTableModel, avail: Rect, pad: Float): Frame {
        val current = cachedFrame
        if (current != null &&
            cachedDocs === model.docs &&
            cachedAvailW == avail.width &&
            cachedPad == pad &&
            cachedScale == Fonts.uiScale
        ) {
            return current
        }
        val measured = measure(model, avail, pad)
        cachedDocs = model.docs
        cachedAvailW = avail.width
        cachedPad = pad
        cachedScale = Fonts.uiScale
        cachedFrame = measured
        return measured
    }

    private fun measure(model: HandsTableModel, avail: Rect, pad: Float): Frame {
        val headerFont = Fonts.button
        val cellFont = Fonts.button
        val contentAvailW = (avail.width - pad * 2f).coerceAtLeast(1f)
        val base = measureColumns(model, headerFont, cellFont)

        // Patterns is the only column allowed to wrap; fit it first, then zoom.
        val fixedContentW = (base.width - base.patternsW).coerceAtLeast(0f)
        val patternsAvail = contentAvailW - fixedContentW
        val zoom: Float
        val patternsNatural: Float
        when {
            patternsAvail >= base.patternsW -> {
                zoom = 1f
                patternsNatural = base.patternsW
            }
            patternsAvail >= base.patternsMinW -> {
                zoom = 1f
                patternsNatural = patternsAvail
            }
            else -> {
                val minContentW = (fixedContentW + base.patternsMinW).coerceAtLeast(1f)
                zoom = (contentAvailW / minContentW).coerceAtMost(1f)
                patternsNatural = base.patternsMinW
            }
        }
        val scaled = base.scaled(zoom).copy(patternsW = patternsNatural * zoom)
        val minPanelW = min(avail.width, Fonts.px(320f))
        val placedW = (scaled.width + pad * 2f).coerceIn(minPanelW, avail.width)
        val contentW = (placedW - pad * 2f).coerceAtLeast(1f)
        // A small table can be narrower than the panel floor; let Patterns take the slack.
        val grid = if (contentW > scaled.width) {
            scaled.copy(patternsW = scaled.patternsW + (contentW - scaled.width))
        } else {
            scaled
        }
        val rowHeights = measureRowHeights(model, cellFont, patternsNatural).map { it * zoom }
        val bodyContentH = if (rowHeights.isNotEmpty()) rowHeights.sum() else Fonts.px(120f)
        val headerH = max(Fonts.px(28f), headerFont.lineHeight + Fonts.px(12f))
        val footerH = max(Fonts.px(34f), Fonts.labelActive.lineHeight + Fonts.px(14f))
        return Frame(placedW, contentW, grid, zoom, rowHeights, bodyContentH, headerH, footerH)
    }

    /** The natural (unzoomed) height of one row: the two-line player stack. */
    private fun naturalRowBase(cellFont: BitmapFont): Float {
        glyphLayout.setText(Fonts.body, "+999")
        val deltaH = glyphLayout.height
        glyphLayout.setText(cellFont, "+999")
        val runH = glyphLayout.height
        return deltaH + runH + Fonts.px(4f) + Fonts.px(5f) * 2f
    }

    private fun measureColumns(
        model: HandsTableModel,
        headerFont: BitmapFont,
        cellFont: BitmapFont,
    ): Grid {
        var winner = textWidth(headerFont, L10n.get("handsTable.winner"))
        var from = textWidth(headerFont, L10n.get("handsTable.from"))
        var score = textWidth(headerFont, L10n.get("handsTable.score"))
        var patternsSample = textWidth(headerFont, L10n.get("handsTable.patterns"))
        var patternsToken = 0f
        var seatHeader = 0f
        var seatCell = 0f
        for (doc in model.docs) {
            for (player in doc.players) seatHeader = max(seatHeader, textWidth(headerFont, player.label))
            for (row in doc.rows) {
                winner = max(winner, textWidth(cellFont, row.winner))
                from = max(from, textWidth(cellFont, row.from))
                score = max(score, textWidth(cellFont, row.score))
                patternsSample = max(patternsSample, textWidth(cellFont, row.patterns))
                for (token in row.patterns.split(' ')) {
                    if (token.isNotEmpty()) patternsToken = max(patternsToken, textWidth(cellFont, token))
                }
                for (seat in 0 until 4) {
                    val delta = row.deltas.getOrElse(seat) { 0 }
                    val total = row.totals.getOrElse(seat) { 0 }
                    // The player cell draws the delta in Fonts.body, and the footer
                    // totals in the larger Fonts.labelActive; the header uses cellFont.
                    seatCell = max(seatCell, textWidth(Fonts.body, HandsTableText.formatSignedDelta(delta)))
                    seatCell = max(seatCell, textWidth(cellFont, total.toString()))
                    seatCell = max(seatCell, textWidth(Fonts.labelActive, HandsTableText.formatSignedDelta(total)))
                }
            }
        }
        val gap = Fonts.px(12f)
        val numW = max(textWidth(headerFont, "#"), textWidth(cellFont, "99"))
        val scoreW = max(score, textWidth(cellFont, "9999"))
        val seatW = max(seatHeader, max(seatCell, textWidth(cellFont, "+9999")))
        val patternsHeader = textWidth(headerFont, L10n.get("handsTable.patterns"))
        val patternsMin = max(patternsHeader, patternsToken)
        val patternsW = max(patternsHeader, patternsSample)
        return Grid(
            numW = numW,
            winnerW = winner,
            fromW = from,
            scoreW = scoreW,
            patternsW = patternsW,
            seatW = seatW,
            gap = gap,
            patternsMinW = patternsMin,
        )
    }

    /** Row `i` is as tall as the tallest hand `i` reaches across all docs at [patternsNatural]. */
    private fun measureRowHeights(
        model: HandsTableModel,
        cellFont: BitmapFont,
        patternsNatural: Float,
    ): List<Float> {
        val rowBase = naturalRowBase(cellFont)
        val rowCount = model.docs.maxOfOrNull { it.rows.size } ?: 0
        val heights = ArrayList<Float>(rowCount)
        for (index in 0 until rowCount) {
            var content = rowBase
            for (doc in model.docs) {
                val row = doc.rows.getOrNull(index) ?: continue
                content = max(content, wrappedHeight(cellFont, row.patterns, patternsNatural) + Fonts.px(8f))
            }
            heights += content
        }
        return heights
    }

    // --- painting -----------------------------------------------------------

    /**
     * The brighter plate behind the document's emphasized column, spanning the
     * whole data band `[bottom, top]` (header through footer). Drawn before the
     * header/rows/footer so their text sits on top of it.
     */
    fun drawHighlight(
        batch: SpriteBatch,
        model: HandsTableModel,
        frame: Frame,
        left: Float,
        bottom: Float,
        top: Float,
    ) {
        val shown = model.shown ?: return
        val column = shown.highlight ?: return
        if (column !in 0 until 4 || top <= bottom) return
        val px = whitePixel()
        batch.setColor(AppPalette.rowHighlight)
        batch.draw(px, left + frame.grid.seatX(column), bottom, frame.grid.seatW, top - bottom)
        batch.setColor(Color.WHITE)
    }

    fun drawHeader(
        batch: SpriteBatch,
        model: HandsTableModel,
        frame: Frame,
        left: Float,
        bottom: Float,
        headerH: Float,
    ) {
        val shown = model.shown ?: return
        val grid = frame.grid
        val headerFont = Fonts.button
        withTableFaceScale(frame) {
            drawCell(batch, headerFont, "#", left + grid.numX, bottom, grid.numW, headerH, Align.right, AppPalette.headerInk)
            drawCell(batch, headerFont, L10n.get("handsTable.winner"), left + grid.winnerX, bottom, grid.winnerW, headerH, Align.left, AppPalette.headerInk)
            drawCell(batch, headerFont, L10n.get("handsTable.from"), left + grid.fromX, bottom, grid.fromW, headerH, Align.left, AppPalette.headerInk)
            drawCell(batch, headerFont, L10n.get("handsTable.score"), left + grid.scoreX, bottom, grid.scoreW, headerH, Align.right, AppPalette.headerInk)
            drawCell(batch, headerFont, L10n.get("handsTable.patterns"), left + grid.patternsX, bottom, grid.patternsW, headerH, Align.left, AppPalette.headerInk)
            for (i in 0 until 4) {
                val player = shown.players.getOrNull(i)
                val color = if (player?.dim == true) AppPalette.seatInk else AppPalette.headerInk
                drawCell(batch, headerFont, player?.label.orEmpty(), left + grid.seatX(i), bottom, grid.seatW, headerH, Align.right, color)
            }
        }
    }

    /** The rule under the header (or over the footer). */
    fun drawRule(batch: SpriteBatch, left: Float, y: Float, width: Float) {
        val px = whitePixel()
        batch.setColor(AppPalette.rule)
        batch.draw(px, left, y, width, max(1.5f, Fonts.px(1.5f)))
        batch.setColor(Color.WHITE)
    }

    /**
     * Draw the scrolling rows, clipped to the body band `[bodyBottom, bodyBottom + viewH]`.
     * [bodyTop] is the top of the body; rows hang down from it, shifted up by
     * [scrollY] (the same scrolled-down offset [VerticalScroll] produces: a larger
     * offset reveals later hands).
     */
    fun drawRows(
        batch: SpriteBatch,
        camera: Camera,
        model: HandsTableModel,
        frame: Frame,
        left: Float,
        width: Float,
        bodyTop: Float,
        bodyBottom: Float,
        viewH: Float,
        scrollY: Float,
    ) {
        val shown = model.shown ?: return
        if (viewH <= 1f || shown.rows.isEmpty()) return
        val grid = frame.grid
        val cellFont = Fonts.button
        val px = whitePixel()
        clipped(batch, camera, left, bodyBottom, width, viewH) {
            withTableFaceScale(frame) {
                val deltaH = glyphHeight(Fonts.body, "+999")
                val runH = glyphHeight(cellFont, "+999")
                val blockH = deltaH + Fonts.px(4f) + runH
                var yTop = bodyTop + scrollY
                shown.rows.forEachIndexed { index, row ->
                    val h = frame.rowHeights.getOrElse(index) { frame.rowHeights.lastOrNull() ?: 0f }
                    val rowBottom = yTop - h
                    if (rowBottom < bodyBottom + viewH && yTop > bodyBottom) {
                        if (index % 2 == 1) {
                            batch.setColor(AppPalette.rowStripe)
                            batch.draw(px, left, rowBottom, grid.width, h)
                            batch.setColor(Color.WHITE)
                        }
                        drawCell(batch, cellFont, row.number.toString(), left + grid.numX, rowBottom, grid.numW, h, Align.right, AppPalette.inkMuted)
                        drawCell(batch, cellFont, row.winner, left + grid.winnerX, rowBottom, grid.winnerW, h, Align.left, AppPalette.rowInk)
                        drawCell(batch, cellFont, row.from, left + grid.fromX, rowBottom, grid.fromW, h, Align.left, AppPalette.rowInkMuted)
                        drawCell(batch, cellFont, row.score, left + grid.scoreX, rowBottom, grid.scoreW, h, Align.right, AppPalette.ink)
                        drawWrapped(batch, cellFont, row.patterns, left + grid.patternsX, rowBottom, grid.patternsW, h, AppPalette.rowInkSoft)

                        // Player cell: delta over running total, centered as a stack.
                        val blockBottom = rowBottom + (h - blockH) * 0.5f
                        val deltaBottom = blockBottom + runH + Fonts.px(4f)
                        for (i in 0 until 4) {
                            val delta = row.deltas.getOrElse(i) { 0 }
                            val total = row.totals.getOrElse(i) { 0 }
                            val x = left + grid.seatX(i)
                            drawCell(batch, Fonts.body, HandsTableText.formatSignedDelta(delta), x, deltaBottom, grid.seatW, deltaH, Align.right, AppPalette.deltaInk(delta))
                            drawCell(batch, cellFont, total.toString(), x, blockBottom, grid.seatW, runH, Align.right, AppPalette.inkMuted)
                        }
                    }
                    yTop = rowBottom
                }
            }
        }
    }

    fun drawFooter(
        batch: SpriteBatch,
        model: HandsTableModel,
        frame: Frame,
        left: Float,
        bottom: Float,
        footerH: Float,
    ) {
        val shown = model.shown ?: return
        val grid = frame.grid
        val metaW = grid.patternsX + grid.patternsW
        withTableFaceScale(frame) {
            drawCell(batch, Fonts.button, L10n.get("scores.total"), left, bottom, metaW, footerH, Align.center, AppPalette.ink)
            for (i in 0 until 4) {
                val value = shown.totals.getOrElse(i) { 0 }
                drawCell(batch, Fonts.labelActive, HandsTableText.formatSignedDelta(value), left + grid.seatX(i), bottom, grid.seatW, footerH, Align.right, AppPalette.deltaInk(value))
            }
        }
    }

    // --- helpers ------------------------------------------------------------

    private fun withTableFaceScale(frame: Frame, block: () -> Unit) {
        if (frame.zoom == 1f) {
            block()
            return
        }
        Fonts.withFaceScale(Fonts.body, frame.zoom) {
            Fonts.withFaceScale(Fonts.button, frame.zoom) {
                Fonts.withFaceScale(Fonts.buttonBold, frame.zoom) {
                    Fonts.withFaceScale(Fonts.labelActive, frame.zoom) {
                        block()
                    }
                }
            }
        }
    }

    private fun glyphHeight(font: BitmapFont, sample: String): Float {
        glyphLayout.setText(font, sample)
        return glyphLayout.height
    }

    private fun clipped(
        batch: SpriteBatch,
        camera: Camera,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        block: () -> Unit,
    ) {
        if (w <= 1f || h <= 1f) return
        batch.flush()
        clipBounds.set(x, y, w, h)
        ScissorStack.calculateScissors(camera, batch.transformMatrix, clipBounds, scissors)
        val on = ScissorStack.pushScissors(scissors)
        try {
            block()
        } finally {
            batch.flush()
            if (on) ScissorStack.popScissors()
        }
    }

    private fun drawCell(
        batch: SpriteBatch,
        font: BitmapFont,
        text: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        align: Int,
        color: Color,
    ) {
        glyphLayout.setText(font, text, color, w, align, false)
        val textX = when (align) {
            Align.center -> x + (w - glyphLayout.width) * 0.5f
            Align.right -> x + w - glyphLayout.width
            else -> x
        }
        font.color = color
        font.draw(batch, text, textX, y + (h + glyphLayout.height) * 0.5f)
        font.color = Color.WHITE
    }

    private fun drawWrapped(
        batch: SpriteBatch,
        font: BitmapFont,
        text: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        color: Color,
    ) {
        if (text.isEmpty()) return
        glyphLayout.setText(font, text, color, w.coerceAtLeast(1f), Align.left, true)
        val top = y + (h + glyphLayout.height) * 0.5f
        font.color = color
        font.draw(batch, text, x, top, w.coerceAtLeast(1f), Align.left, true)
        font.color = Color.WHITE
    }

    private fun textWidth(font: BitmapFont, text: String): Float {
        glyphLayout.setText(font, text)
        return glyphLayout.width
    }

    private fun wrappedHeight(font: BitmapFont, text: String, width: Float): Float {
        if (text.isEmpty()) return font.lineHeight
        glyphLayout.setText(font, text, font.color, width.coerceAtLeast(1f), Align.left, true)
        return glyphLayout.height
    }
}
