package inego.zungjunggame.render.richtext

import inego.zungjunggame.ui.Rect

/**
 * Pure hit-testing for markdown hyperlinks in laid-out rich text.
 *
 * Multi-word labels are split into per-word [DrawOp.Text] runs with separate
 * space runs between them. Those blank runs carry [DrawOp.Text.linkHref] and
 * must be hit-tested (including [wordSpaceExtra] advance) so the pointer stays
 * "over" the link between words — hand cursor and hover highlight included.
 */
object RichTextLinkHit {
    /**
     * @param contentX X in laid-out content space (origin at content left).
     * @param contentY Y in laid-out content space (y-up, same as [DrawOp]).
     * @param wordSpaceExtra extra advance applied to blank words at layout time
     *   ([RichTextLayoutParams.wordSpaceExtra]); must match the layout that
     *   produced [ops].
     * @param yPad vertical slack around the line box for easier targeting.
     */
    fun hrefAt(
        ops: List<DrawOp>,
        contentX: Float,
        contentY: Float,
        measurer: TextMeasurer,
        wordSpaceExtra: Float = 0f,
        yPad: Float = 0f,
    ): String? {
        // Prefer the last matching op (later = typically more nested / rightmost).
        var hit: String? = null
        for (op in ops) {
            if (op !is DrawOp.Text || op.linkHref == null) continue
            val glyphW = measurer.width(op.text, op.style, op.role)
            // Spaces between linked words keep linkHref; their laid-out advance
            // is glyph width + wordSpaceExtra (see RichTextLayout.atomWidth).
            val w = if (op.text.isBlank()) glyphW + wordSpaceExtra else glyphW
            if (w <= 0f) continue
            val lh = measurer.lineHeight(op.role)
            val baselineFromTop = measurer.baselineFromTop(op.role)
            // Content y-up: [baselineY] is BitmapFont draw Y (cap top).
            // Line top sits [baselineFromTop] above that draw Y.
            val lineTop = op.baselineY + baselineFromTop
            val lineBottom = lineTop - lh
            val y0 = lineBottom - yPad
            val y1 = lineTop + yPad
            if (contentX >= op.x && contentX <= op.x + w &&
                contentY >= y0 && contentY <= y1
            ) {
                hit = op.linkHref
            }
        }
        return hit
    }

    /**
     * Bounds of every laid-out hyperlink run, translated into [originX]/[originY]
     * world space. Multi-word labels yield one box per run, so callers publish
     * each box as its own hit region. Same math as [hrefAt].
     */
    fun rects(
        ops: List<DrawOp>,
        originX: Float,
        originY: Float,
        measurer: TextMeasurer,
        wordSpaceExtra: Float = 0f,
        yPad: Float = 0f,
    ): List<Pair<String, Rect>> {
        val out = ArrayList<Pair<String, Rect>>()
        for (op in ops) {
            if (op !is DrawOp.Text || op.linkHref == null) continue
            val glyphW = measurer.width(op.text, op.style, op.role)
            val w = if (op.text.isBlank()) glyphW + wordSpaceExtra else glyphW
            if (w <= 0f) continue
            val lh = measurer.lineHeight(op.role)
            val lineTop = op.baselineY + measurer.baselineFromTop(op.role)
            val lineBottom = lineTop - lh
            out += op.linkHref to Rect(
                left = originX + op.x,
                bottom = originY + lineBottom - yPad,
                width = w,
                height = lh + yPad * 2f,
            )
        }
        return out
    }
}
