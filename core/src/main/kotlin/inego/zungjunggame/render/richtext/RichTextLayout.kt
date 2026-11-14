package inego.zungjunggame.render.richtext

import inego.zungjunggame.render.fonts.LineBreaker
import kotlin.math.max

/**
 * Measures text without depending on libGDX types — keeps layout unit-testable.
 */
interface TextMeasurer {
    fun width(text: String, style: TextStyle, role: TextRole): Float
    fun lineHeight(role: TextRole): Float
    /**
     * Distance from the top of the line box down to the Y that should be passed
     * to [com.badlogic.gdx.graphics.g2d.BitmapFont.draw] (cap-height top —
     * top of typical capitals — *not* the typographic baseline).
     */
    fun baselineFromTop(role: TextRole): Float
}

/** Layout knobs (already density-scaled by the caller when needed). */
data class RichTextLayoutParams(
    val maxWidth: Float,
    val paragraphGap: Float,
    val headingGapAfter: Float,
    val listItemGap: Float,
    val listIndent: Float,
    val tileAspect: Float = 3f / 4f,
    /** Tile height as a multiple of the body line height. */
    val tileHeightFactor: Float = 1.2f,
    val tileGapFraction: Float = 0.08f,
    /**
     * Extra advance added to each inter-word space beyond the font's space glyph.
     * Does not change the drawn space character — only how far the next word sits.
     */
    val wordSpaceExtra: Float = 0f,
    /** Horizontal padding inside a table cell. */
    val tableCellPadX: Float = 6f,
    /** Vertical padding above and below a table cell's text. */
    val tableCellPadY: Float = 4f,
    /** Space between adjacent table columns (each cell also has its own padding). */
    val tableColumnGap: Float = 12f,
    /** Thickness of a table rule. */
    val tableBorderWidth: Float = 1f,
    /**
     * Font role for paragraphs, list items, and table cells — everything that
     * is not a heading. [TextRole.Body] is the document default and what every
     * article, tutorial slide, and notice has always rendered at; a surface whose
     * body text must read smaller than a document's (a chrome caption, say) sets
     * [TextRole.Small] here rather than scaling glyphs. Headings keep their own
     * roles, so shrinking the body of a document does not shrink its titles.
     */
    val bodyRole: TextRole = TextRole.Body,
)

/** Positioned draw commands; y increases upward (libGDX world space). */
sealed interface DrawOp {
    data class Text(
        val x: Float,
        /**
         * Y for [com.badlogic.gdx.graphics.g2d.BitmapFont.draw]: top of typical
         * capitals (cap-height), in y-up content space. True baseline is
         * [baselineY] − font.capHeight.
         */
        val baselineY: Float,
        val text: String,
        val style: TextStyle,
        val role: TextRole,
        /** Non-null when this run is part of a markdown hyperlink. */
        val linkHref: String? = null,
    ) : DrawOp

    data class Tile(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val type: Int,
    ) : DrawOp

    data class Underline(
        val x0: Float,
        val x1: Float,
        val y: Float,
        val thickness: Float,
    ) : DrawOp

    /**
     * Filled rectangle (table rules). In a laid-out [LaidOutDoc], [x]/[y] are the
     * bottom-left in y-up content space, matching the other ops. Layout builds it
     * with [y] as the top edge measured downward; [RichTextLayout.layout] converts
     * it like the rest.
     */
    data class Fill(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val color: RichColor? = null,
    ) : DrawOp
}

/**
 * Laid-out document in a y-up content box of size [width] × [height].
 * Content top is at y = [height]; first line sits just under that.
 */
data class LaidOutDoc(
    val width: Float,
    val height: Float,
    val ops: List<DrawOp>,
)

/**
 * Word-wraps a [RichDoc] into positioned [DrawOp]s.
 *
 * Tile runs never break mid-group. Soft wrap is on word and CJK break boundaries via [LineBreaker].
 * [Inline.LineBreak] (`<br>`) forces a new line within the same block.
 */
class RichTextLayout(
    private val measurer: TextMeasurer,
    private val params: RichTextLayoutParams,
) {
    fun layout(doc: RichDoc): LaidOutDoc {
        val ops = ArrayList<DrawOp>()
        // Working coordinates: y increases downward from the content top.
        var y = 0f
        var first = true

        fun gapBefore(gap: Float) {
            if (!first) y += gap
            first = false
        }

        for (block in doc.blocks) {
            when (block) {
                is Block.Heading -> {
                    gapBefore(params.headingGapAfter)
                    y += layoutSpans(
                        spans = block.spans,
                        role = headingRole(block.level),
                        align = block.align,
                        left = 0f,
                        width = params.maxWidth,
                        yTop = y,
                        ops = ops,
                    )
                }
                is Block.Paragraph -> {
                    gapBefore(params.paragraphGap)
                    y += layoutSpans(
                        spans = block.spans,
                        role = params.bodyRole,
                        align = block.align,
                        left = 0f,
                        width = params.maxWidth,
                        yTop = y,
                        ops = ops,
                    )
                }
                is Block.BulletList -> {
                    gapBefore(params.paragraphGap)
                    for ((idx, item) in block.items.withIndex()) {
                        if (idx > 0) y += params.listItemGap
                        y += layoutListItem("•", item, y, ops)
                    }
                }
                is Block.NumberedList -> {
                    gapBefore(params.paragraphGap)
                    for ((idx, item) in block.items.withIndex()) {
                        if (idx > 0) y += params.listItemGap
                        y += layoutListItem("${block.start + idx}.", item, y, ops)
                    }
                }
                is Block.Table -> {
                    gapBefore(params.paragraphGap)
                    y += layoutTable(block, y, ops)
                }
            }
        }

        val height = y
        val converted = ops.map { op ->
            when (op) {
                is DrawOp.Text -> op.copy(baselineY = height - op.baselineY)
                is DrawOp.Tile -> op.copy(cy = height - op.cy)
                is DrawOp.Underline -> op.copy(y = height - op.y)
                is DrawOp.Fill -> op.copy(y = height - op.y - op.h)
            }
        }
        return LaidOutDoc(width = params.maxWidth, height = height, ops = converted)
    }

    private fun layoutListItem(
        marker: String,
        spans: List<Inline>,
        yTop: Float,
        ops: MutableList<DrawOp>,
    ): Float {
        val role = params.bodyRole
        val lh = lineBoxHeight(role)
        val drawYFromTop = measurer.baselineFromTop(role)
        ops += DrawOp.Text(
            x = 0f,
            baselineY = yTop + drawYFromTop,
            text = marker,
            style = TextStyle.Plain,
            role = role,
        )
        val contentLeft = params.listIndent
        val contentW = (params.maxWidth - contentLeft).coerceAtLeast(1f)
        val used = layoutSpans(
            spans = spans,
            role = role,
            align = RichAlign.Left,
            left = contentLeft,
            width = contentW,
            yTop = yTop,
            ops = ops,
        )
        return max(used, lh)
    }

    /**
     * Lay out a pipe table: size columns to [RichTextLayoutParams.maxWidth] from
     * their intrinsic widths, wrap each cell inside its column, and emit one
     * horizontal rule above the header, below the header, and below each body row.
     *
     * @return height consumed (y-down)
     */
    private fun layoutTable(
        table: Block.Table,
        yTop: Float,
        ops: MutableList<DrawOp>,
    ): Float {
        val columns = table.header.size
        if (columns == 0) return 0f
        val role = params.bodyRole
        val tileH = measurer.lineHeight(params.bodyRole) * params.tileHeightFactor
        val tileW = tileH * params.tileAspect
        val tileGap = tileW * params.tileGapFraction

        // The header is always bold, matching how markdown denotes it.
        val header = table.header.map(::bolden)
        val rows = table.rows.map { row ->
            List(columns) { c -> row.getOrElse(c) { emptyList() } }
        }

        val intrinsic = FloatArray(columns) { c ->
            var w = intrinsicWidth(header[c], role, tileW, tileGap)
            for (row in rows) w = max(w, intrinsicWidth(row[c], role, tileW, tileGap))
            w
        }

        val padX = params.tableCellPadX
        val padY = params.tableCellPadY
        val gap = params.tableColumnGap
        val border = params.tableBorderWidth
        val gaps = padX * 2f + gap * (columns - 1).coerceAtLeast(0)
        val available = (params.maxWidth - gaps).coerceAtLeast(1f)

        // Distribute the column band in proportion to intrinsic width, so a long
        // cell keeps more room; equal shares when every cell is empty.
        val weights = if (intrinsic.any { it > 0f }) intrinsic else FloatArray(columns) { 1f }
        val weightSum = weights.sum()
        val widths = FloatArray(columns) { c ->
            if (weightSum <= 0f) available / columns else available * weights[c] / weightSum
        }
        val tableWidth = padX * 2f + widths.sum() + gap * (columns - 1).coerceAtLeast(0)
        val alignAt = { c: Int -> table.aligns.getOrElse(c) { RichAlign.Left } }

        val x = FloatArray(columns)
        var cursorX = padX
        for (c in 0 until columns) {
            x[c] = cursorX
            cursorX += widths[c] + gap
        }

        fun rule(top: Float) {
            ops += DrawOp.Fill(x = 0f, y = top, w = tableWidth, h = border, color = RULE_COLOR)
        }

        fun rowHeight(cells: List<List<Inline>>, textTop: Float): Float {
            var h = 0f
            for (c in 0 until columns) {
                h = max(
                    h,
                    layoutSpans(
                        spans = cells[c],
                        role = role,
                        align = alignAt(c),
                        left = x[c],
                        width = widths[c],
                        yTop = textTop,
                        ops = ops,
                    ),
                )
            }
            return h
        }

        var cursor = yTop
        rule(cursor)
        cursor += border

        cursor += padY
        val headerHeight = rowHeight(header, cursor)
        cursor += headerHeight + padY
        rule(cursor)
        cursor += border

        for (row in rows) {
            cursor += padY
            val height = rowHeight(row, cursor)
            cursor += height + padY
            rule(cursor)
            cursor += border
        }
        return cursor - yTop
    }

    /** Intrinsic (unwrapped) width of a cell, taking the widest hard-broken line. */
    private fun intrinsicWidth(
        spans: List<Inline>,
        role: TextRole,
        tileW: Float,
        tileGap: Float,
    ): Float {
        var widest = 0f
        var line = 0f
        for (atom in flattenAtoms(spans)) {
            if (atom is Atom.LineBreak) {
                widest = max(widest, line)
                line = 0f
                continue
            }
            line += atomWidth(atom, role, tileW, tileGap)
        }
        return max(widest, line)
    }

    /** Copy text spans with bold forced on (table headers). */
    private fun bolden(spans: List<Inline>): List<Inline> = spans.map { span ->
        if (span is Inline.Text && !span.style.bold) {
            span.copy(style = span.style.copy(bold = true))
        } else {
            span
        }
    }

    /**
     * @return height consumed (y-down)
     */
    private fun layoutSpans(
        spans: List<Inline>,
        role: TextRole,
        align: RichAlign,
        left: Float,
        width: Float,
        yTop: Float,
        ops: MutableList<DrawOp>,
    ): Float {
        val atoms = flattenAtoms(spans)
        val lineBoxH = lineBoxHeight(role)
        if (atoms.isEmpty()) return lineBoxH

        // Tiles scale with the text they sit beside, so a small body's tiles are
        // small too — [role], not the document default.
        val tileH = measurer.lineHeight(role) * params.tileHeightFactor
        val tileW = tileH * params.tileAspect
        val tileGap = tileW * params.tileGapFraction
        val baselineFromTop = measurer.baselineFromTop(role)

        data class Placed(val atom: Atom, val x: Float, val w: Float)

        val lines = ArrayList<List<Placed>>()
        var line = ArrayList<Placed>()
        var x = 0f

        fun commitLine(allowEmpty: Boolean = false) {
            while (line.isNotEmpty()) {
                val last = line.last().atom
                if (last is Atom.Word && last.text.isBlank()) {
                    line.removeAt(line.lastIndex)
                } else break
            }
            if (line.isNotEmpty()) {
                lines.add(line.toList())
            } else if (allowEmpty) {
                lines.add(emptyList())
            }
            line = ArrayList()
            x = 0f
        }

        for (atom in atoms) {
            if (atom is Atom.LineBreak) {
                // Always end the current line; empty → blank line box only when
                // forcing a break with nothing pending (e.g. consecutive <br>).
                commitLine(allowEmpty = line.isEmpty() && lines.isNotEmpty())
                continue
            }
            val w = atomWidth(atom, role, tileW, tileGap)
            val isSpace = atom is Atom.Word && atom.text.isBlank()
            if (isSpace && line.isEmpty()) continue
            if (line.isNotEmpty() && x + w > width + 0.01f && !isSpace) {
                commitLine()
            }
            if (isSpace && line.isEmpty()) continue
            line += Placed(atom, x, w)
            x += w
        }
        commitLine()
        if (lines.isEmpty()) return lineBoxH

        var cursorTop = yTop
        for (placedLine in lines) {
            val lineW = if (placedLine.isEmpty()) 0f else placedLine.last().let { it.x + it.w }
            val xOff = when (align) {
                RichAlign.Left -> left
                RichAlign.Center -> left + ((width - lineW) * 0.5f)
                RichAlign.Right -> left + (width - lineW)
            }
            // Vertically center the text line box inside taller tile rows.
            val extra = (lineBoxH - measurer.lineHeight(role)).coerceAtLeast(0f) * 0.5f
            // Y for BitmapFont.draw (cap-height top), not the typographic baseline.
            val textDrawY = cursorTop + baselineFromTop + extra
            val tileCy = cursorTop + lineBoxH * 0.5f

            for ((atom, x1) in placedLine) {
                when (atom) {
                    is Atom.Word -> {
                        if (atom.text.isEmpty()) continue
                        val drawX = xOff + x1
                        ops += DrawOp.Text(
                            x = drawX,
                            baselineY = textDrawY,
                            text = atom.text,
                            style = atom.style,
                            role = role,
                            linkHref = atom.linkHref,
                        )
                        // Underline is painted by [RichTextRenderer] under the true
                        // baseline (drawY − capHeight); no separate op here.
                    }
                    is Atom.Tiles -> {
                        var tx = xOff + x1
                        for ((ti, type) in atom.tiles.withIndex()) {
                            ops += DrawOp.Tile(
                                cx = tx + tileW * 0.5f,
                                cy = tileCy,
                                w = tileW,
                                h = tileH,
                                type = type,
                            )
                            tx += tileW
                            if (ti < atom.tiles.lastIndex) tx += tileGap
                        }
                    }
                    is Atom.LineBreak -> Unit // never placed on a line
                }
            }
            cursorTop += lineBoxH
        }
        return cursorTop - yTop
    }

    private fun lineBoxHeight(role: TextRole): Float {
        val textH = measurer.lineHeight(role)
        val tileH = measurer.lineHeight(role) * params.tileHeightFactor
        return max(textH, tileH)
    }

    private sealed interface Atom {
        data class Word(
            val text: String,
            val style: TextStyle,
            val linkHref: String? = null,
        ) : Atom

        data class Tiles(val tiles: IntArray) : Atom {
            override fun equals(other: Any?): Boolean =
                other is Tiles && tiles.contentEquals(other.tiles)

            override fun hashCode(): Int = tiles.contentHashCode()
        }

        data object LineBreak : Atom
    }

    private fun flattenAtoms(spans: List<Inline>): List<Atom> {
        val out = ArrayList<Atom>()
        for (span in spans) {
            when (span) {
                is Inline.TileRun -> if (span.tiles.isNotEmpty()) out += Atom.Tiles(span.tiles)
                is Inline.Text -> out += splitWords(span.text, span.style, span.linkHref)
                is Inline.LineBreak -> out += Atom.LineBreak
            }
        }
        return out
    }

    private fun splitWords(text: String, style: TextStyle, linkHref: String?): List<Atom> {
        if (text.isEmpty()) return emptyList()
        val tokens = LineBreaker.tokenize(text)
        val out = ArrayList<Atom>(tokens.size)
        for (token in tokens) {
            val isSpace = token == " " || token.isBlank()
            out += Atom.Word(if (isSpace) " " else token, style, linkHref)
        }
        return out
    }

    private fun atomWidth(
        atom: Atom,
        role: TextRole,
        tileW: Float,
        tileGap: Float,
    ): Float = when (atom) {
        is Atom.Word -> {
            val w = measurer.width(atom.text, atom.style, role)
            if (atom.text.isBlank()) w + params.wordSpaceExtra else w
        }
        is Atom.Tiles -> {
            val n = atom.tiles.size
            if (n <= 0) 0f else n * tileW + (n - 1) * tileGap
        }
        is Atom.LineBreak -> 0f
    }

    private fun headingRole(level: Int): TextRole = when (level) {
        1 -> TextRole.H1
        2 -> TextRole.H2
        else -> TextRole.H3
    }

    private companion object {
        /** Subtle divider ink for table rules; resolved through the palette. */
        val RULE_COLOR = RichColor.Named("muted")
    }
}
