package inego.zungjunggame.render.richtext

/**
 * Parses a constrained Markdown-like dialect into a [RichDoc].
 *
 * Supported:
 * - `#` / `##` / `###` headings
 * - paragraphs (blank-line separated; soft line breaks join with a space)
 * - `<br>` / `<br/>` / `<br />` hard line break within a paragraph
 * - `**bold**`, `*italic*`, `__underline__`
 * - `[label](href)` markdown hyperlinks (label may contain emphasis / colors)
 * - `<href>` autolinks (http(s), mailto, or internal page refs)
 * - `[name]…[/]` / `[name]…[/name]` named roles (see [RichColorPalette])
 * - `[#RRGGBB]…[/]` / `[#RRGGBBAA]…[/]` raw hex colors
 * - `{tile notation}` inline tiles (see [TileNotation])
 * - `:::center` … `:::` centered blocks
 * - `-` / `*` bullet lists
 * - `1.` numbered lists
 * - pipe tables (`| a | b |` header, `|:--|--:|` delimiter, body rows)
 *
 * Unknown `{…}` tokens are left as literal text. Unknown `[word]` tags that are
 * not palette names or hex are left as literal text. Nested emphasis uses a
 * simple toggle stack; colors use a push/pop stack.
 *
 * Link destinations are opaque strings on [Inline.Text.linkHref]; the host
 * (reference browser, etc.) resolves them to pages or external URIs.
 */
object RichMarkupParser {

    /** Max characters inside a color `[…]` tag before we treat `[` as literal. */
    private const val MAX_TAG_LEN = 16

    /** Soft sky-blue default for link text when the label has no explicit color. */
    private val LINK_COLOR = RichColor.Named("link")

    fun parse(source: String): RichDoc {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<Block>()
        var i = 0
        var centerDepth = 0

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                i++
                continue
            }

            if (trimmed == ":::center") {
                centerDepth++
                i++
                continue
            }
            if (trimmed == ":::" && centerDepth > 0) {
                centerDepth--
                i++
                continue
            }

            val align = if (centerDepth > 0) RichAlign.Center else RichAlign.Left

            val heading = HEADING.matchEntire(trimmed)
            if (heading != null) {
                val level = heading.groupValues[1].length.coerceIn(1, 3)
                val text = heading.groupValues[2].trim()
                blocks += Block.Heading(level, parseInlines(text), align)
                i++
                continue
            }

            val bullet = BULLET.matchEntire(trimmed)
            if (bullet != null && align == RichAlign.Left) {
                val items = ArrayList<List<Inline>>()
                val itemTexts = ArrayList<String>()
                while (i < lines.size) {
                    val t = lines[i].trim()
                    if (t.isEmpty()) break
                    val m = BULLET.matchEntire(t)
                    if (m != null) {
                        itemTexts += m.groupValues[1].trim()
                        i++
                        continue
                    }
                    if (isTableStart(lines, i)) break
                    // Soft-wrapped continuation of the current list item.
                    if (itemTexts.isNotEmpty() && isListContinuation(t)) {
                        itemTexts[itemTexts.lastIndex] =
                            itemTexts.last() + " " + t
                        i++
                        continue
                    }
                    break
                }
                for (text in itemTexts) {
                    items += parseInlines(text)
                }
                blocks += Block.BulletList(items)
                continue
            }

            val numbered = NUMBERED.matchEntire(trimmed)
            if (numbered != null && align == RichAlign.Left) {
                val start = numbered.groupValues[1].toInt()
                val items = ArrayList<List<Inline>>()
                val itemTexts = ArrayList<String>()
                while (i < lines.size) {
                    val t = lines[i].trim()
                    if (t.isEmpty()) break
                    val m = NUMBERED.matchEntire(t)
                    if (m != null) {
                        itemTexts += m.groupValues[2].trim()
                        i++
                        continue
                    }
                    if (isTableStart(lines, i)) break
                    if (itemTexts.isNotEmpty() && isListContinuation(t)) {
                        itemTexts[itemTexts.lastIndex] =
                            itemTexts.last() + " " + t
                        i++
                        continue
                    }
                    break
                }
                for (text in itemTexts) {
                    items += parseInlines(text)
                }
                blocks += Block.NumberedList(items, start = start)
                continue
            }

            // Pipe table: a row line followed by a matching delimiter row.
            if (isTableStart(lines, i)) {
                val (table, next) = parseTable(lines, i)
                blocks += table
                i = next
                continue
            }

            // Paragraph: collect non-empty, non-structural lines.
            val paraLines = ArrayList<String>()
            while (i < lines.size) {
                val t = lines[i].trimEnd()
                val tr = t.trim()
                if (tr.isEmpty()) break
                if (tr == ":::center" || tr == ":::") break
                if (HEADING.matches(tr)) break
                if (isTableStart(lines, i)) break
                if (align == RichAlign.Left && (BULLET.matches(tr) || NUMBERED.matches(tr))) break
                paraLines += tr
                i++
            }
            if (paraLines.isNotEmpty()) {
                val joined = paraLines.joinToString(" ")
                blocks += Block.Paragraph(parseInlines(joined), align)
            }
        }

        return RichDoc(blocks)
    }

    /**
     * Parse inline emphasis, color tags, and tile tokens from a single logical
     * line/paragraph.
     */
    fun parseInlines(text: String): List<Inline> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Inline>()
        val buf = StringBuilder()
        var bold = false
        var italic = false
        var underline = false
        val colorStack = ArrayDeque<RichColor>()
        var i = 0

        fun style() = TextStyle(
            bold = bold,
            italic = italic,
            underline = underline,
            color = colorStack.lastOrNull(),
        )

        fun flush() {
            if (buf.isEmpty()) return
            out += Inline.Text(buf.toString(), style())
            buf.clear()
        }

        while (i < text.length) {
            val c = text[i]
            // Tile: {…}
            if (c == '{') {
                val close = text.indexOf('}', i + 1)
                if (close >= 0) {
                    val inner = text.substring(i + 1, close)
                    val tiles = TileNotation.tryParseGroup(inner)
                    if (tiles != null) {
                        flush()
                        if (tiles.isNotEmpty()) {
                            out += Inline.TileRun(tiles)
                        }
                        i = close + 1
                        continue
                    }
                }
                // Literal '{'
                buf.append(c)
                i++
                continue
            }

            // ** bold **
            if (c == '*' && i + 1 < text.length && text[i + 1] == '*') {
                flush()
                bold = !bold
                i += 2
                continue
            }

            // __ underline __
            if (c == '_' && i + 1 < text.length && text[i + 1] == '_') {
                flush()
                underline = !underline
                i += 2
                continue
            }

            // Markdown link [label](href) — before color tags (both start with '[').
            if (c == '[') {
                val link = tryReadMdLink(text, i)
                if (link != null) {
                    flush()
                    emitLink(out, link.label, link.href)
                    i = link.endIndex
                    continue
                }
                // Color tags: [name]…[/] / [#RRGGBB]…[/]
                val tag = tryReadBracketTag(text, i)
                if (tag != null) {
                    val handled = applyColorTag(tag.body, colorStack) {
                        flush()
                    }
                    if (handled) {
                        i = tag.endIndex
                        continue
                    }
                }
                // Literal '[' (unknown / malformed tag)
                buf.append(c)
                i++
                continue
            }

            // Hard line break <br> / <br/> / <br /> — before autolink (else "br" is a page id).
            if (c == '<') {
                val brEnd = tryReadBr(text, i)
                if (brEnd != null) {
                    flush()
                    out += Inline.LineBreak
                    i = brEnd
                    continue
                }
                // Autolink <http://…> / <page-id>
                val auto = tryReadAutolink(text, i)
                if (auto != null) {
                    flush()
                    emitLink(out, auto.display, auto.href)
                    i = auto.endIndex
                    continue
                }
            }

            // * italic * (single asterisk)
            if (c == '*') {
                flush()
                italic = !italic
                i++
                continue
            }

            buf.append(c)
            i++
        }
        flush()
        return mergeAdjacent(out)
    }

    private data class BracketTag(val body: String, val endIndex: Int)

    private data class MdLink(val label: String, val href: String, val endIndex: Int)

    private data class AutoLink(val display: String, val href: String, val endIndex: Int)

    /**
     * Read idiomatic `[label](href)` starting at [start] (`text[start] == '['`).
     * Label may contain nested `[]` (balanced). Href may contain nested `()` (balanced).
     */
    private fun tryReadMdLink(text: String, start: Int): MdLink? {
        if (start >= text.length || text[start] != '[') return null
        var i = start + 1
        var depth = 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '[' -> depth++
                ']' -> depth--
            }
            if (depth > 0) i++
        }
        if (depth != 0 || text[i] != ']') return null
        val labelEnd = i
        if (labelEnd + 1 >= text.length || text[labelEnd + 1] != '(') return null
        var j = labelEnd + 2
        var pDepth = 1
        while (j < text.length && pDepth > 0) {
            when (text[j]) {
                '(' -> pDepth++
                ')' -> pDepth--
            }
            if (pDepth > 0) j++
        }
        if (pDepth != 0 || text[j] != ')') return null
        val label = text.substring(start + 1, labelEnd)
        val href = text.substring(labelEnd + 2, j).trim()
        if (href.isEmpty()) return null
        // Reject accidental "color-looking" empty labels with huge noise — keep open.
        return MdLink(label = label, href = href, endIndex = j + 1)
    }

    /**
     * Read `<…>` autolink. Accepts http(s)/mailto URLs and simple internal refs
     * (page ids, `#page-id`, dotted pattern numbers, relative `.md` paths).
     */
    private fun tryReadAutolink(text: String, start: Int): AutoLink? {
        if (start >= text.length || text[start] != '<') return null
        val close = text.indexOf('>', start + 1)
        if (close < 0) return null
        val inner = text.substring(start + 1, close).trim()
        if (inner.isEmpty() || inner.length > 200) return null
        if (inner.any { it.isWhitespace() }) return null
        val ok = when {
            inner.startsWith("http://", ignoreCase = true) -> true
            inner.startsWith("https://", ignoreCase = true) -> true
            inner.startsWith("mailto:", ignoreCase = true) -> true
            inner.startsWith('#') && inner.length > 1 -> true
            inner.endsWith(".md", ignoreCase = true) -> true
            // page id / pattern number: no angle-bracket HTML tags
            inner.matches(Regex("""[A-Za-z0-9][A-Za-z0-9._/#\-]*""")) -> true
            else -> false
        }
        if (!ok) return null
        return AutoLink(display = inner, href = inner, endIndex = close + 1)
    }

    /**
     * Parse [label] with nested emphasis, stamp [linkHref] on text spans, and
     * apply default link color when unspecified. Links are not underlined by
     * default (explicit `__underline__` in the label is preserved).
     */
    private fun emitLink(out: MutableList<Inline>, label: String, href: String) {
        val display = label.ifEmpty { href }
        val children = parseInlines(display)
        if (children.isEmpty()) {
            out += Inline.Text(
                text = display,
                style = TextStyle(color = LINK_COLOR),
                linkHref = href,
            )
            return
        }
        for (child in children) {
            out += when (child) {
                is Inline.Text -> child.copy(
                    linkHref = href,
                    style = child.style.copy(
                        color = child.style.color ?: LINK_COLOR,
                    ),
                )
                // Tiles inside a link stay non-clickable (no href on TileRun).
                is Inline.TileRun -> child
                // Hard breaks inside a link label are preserved as structure only.
                is Inline.LineBreak -> child
            }
        }
    }

    /**
     * Match `<br>`, `<br/>`, or `<br />` (case-insensitive). Returns index after
     * the closing `>`, or null if [start] is not a br tag.
     */
    private fun tryReadBr(text: String, start: Int): Int? {
        if (start >= text.length || text[start] != '<') return null
        val close = text.indexOf('>', start + 1)
        if (close < 0 || close - start > 8) return null
        val inner = text.substring(start + 1, close).trim()
        // "br" or "br/" (optional slash for XHTML); no attributes.
        val body = if (inner.endsWith('/')) inner.dropLast(1).trimEnd() else inner
        if (!body.equals("br", ignoreCase = true)) return null
        return close + 1
    }

    /**
     * Read color `[…]` starting at [start] (`text[start] == '['`).
     * Returns null if no closing `]` within [MAX_TAG_LEN].
     */
    private fun tryReadBracketTag(text: String, start: Int): BracketTag? {
        if (start >= text.length || text[start] != '[') return null
        val close = text.indexOf(']', start + 1)
        if (close < 0) return null
        val body = text.substring(start + 1, close)
        if (body.length > MAX_TAG_LEN) return null
        return BracketTag(body, endIndex = close + 1)
    }

    /**
     * @return true if the tag was consumed as a color open/close.
     */
    private fun applyColorTag(
        body: String,
        colorStack: ArrayDeque<RichColor>,
        onChange: () -> Unit,
    ): Boolean {
        // Close: [/] or [/name] or [/#] or [/#RRGGBB]
        // Only consume when a color is open — orphan closes stay literal so
        // unknown tags like [notacolor]x[/] remain visible for content QA.
        if (body.startsWith('/')) {
            if (colorStack.isEmpty()) return false
            val rest = body.substring(1)
            if (rest.isEmpty() || isValidCloseLabel(rest)) {
                onChange()
                colorStack.removeLast()
                return true
            }
            return false
        }

        // Open hex: #RRGGBB / #RRGGBBAA
        if (body.startsWith('#')) {
            val hex = RichColorPalette.parseHexTag(body) ?: return false
            onChange()
            colorStack.addLast(hex)
            return true
        }

        // Open named palette color
        if (isValidColorName(body) && RichColorPalette.KNOWN_NAMES.contains(body.lowercase())) {
            onChange()
            colorStack.addLast(RichColor.Named(body.lowercase()))
            return true
        }

        return false
    }

    private fun isValidColorName(s: String): Boolean {
        if (s.isEmpty() || s.length > MAX_TAG_LEN) return false
        val first = s[0]
        return !(first !in 'A'..'Z' && first !in 'a'..'z') && s.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
    }

    private fun isValidCloseLabel(s: String): Boolean {
        if (s.startsWith('#')) {
            // [/#] or [/#RRGGBB] — accept any hex-shaped close
            return s == "#" || RichColorPalette.parseHexTag(s) != null
        }
        return isValidColorName(s)
    }

    /** Collapse consecutive text spans that share the same style and link. */
    private fun mergeAdjacent(spans: List<Inline>): List<Inline> {
        if (spans.size <= 1) return spans
        val out = ArrayList<Inline>(spans.size)
        for (span in spans) {
            val last = out.lastOrNull()
            if (span is Inline.Text &&
                last is Inline.Text &&
                last.style == span.style &&
                last.linkHref == span.linkHref
            ) {
                out[out.lastIndex] = Inline.Text(last.text + span.text, last.style, last.linkHref)
            } else {
                out += span
            }
        }
        return out
    }

    /**
     * Soft-wrapped continuation of a list item: not a new structural block.
     * (Headings, list markers, and fence tokens start a new block instead.)
     */
    private fun isListContinuation(trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        if (trimmed == ":::center" || trimmed == ":::") return false
        if (HEADING.matches(trimmed)) return false
        if (BULLET.matches(trimmed)) return false
        if (NUMBERED.matches(trimmed)) return false
        return true
    }

    /**
     * A pipe table starts when a row line with at least two cells is followed
     * by a delimiter row of the same cell count whose cells are only `-`/`:`.
     */
    private fun isTableStart(lines: List<String>, index: Int): Boolean {
        if (index + 1 >= lines.size) return false
        val header = splitTableRow(lines[index].trim()) ?: return false
        if (header.size < 2) return false
        val delimiter = splitTableRow(lines[index + 1].trim()) ?: return false
        return delimiter.size == header.size && delimiter.all(::isDelimiterCell)
    }

    /** Parse a table whose header and delimiter rows were validated by [isTableStart]. */
    private fun parseTable(lines: List<String>, start: Int): Pair<Block.Table, Int> {
        val headerCells = splitTableRow(lines[start].trim()).orEmpty()
        val header = headerCells.map(::parseInlines)
        val aligns = splitTableRow(lines[start + 1].trim()).orEmpty().map(::delimiterAlign)
        val columns = header.size

        val rows = ArrayList<List<List<Inline>>>()
        var i = start + 2
        while (i < lines.size) {
            val cells = splitTableRow(lines[i].trim()) ?: break
            rows += List(columns) { c -> parseInlines(cells.getOrElse(c) { "" }) }
            i++
        }
        return Block.Table(header, rows, aligns) to i
    }

    /**
     * Split one pipe row into trimmed cell sources: one optional leading and
     * trailing pipe are dropped and `\|` is an escaped literal pipe. Returns
     * null when the line contains no pipe at all (not a row).
     */
    private fun splitTableRow(line: String): List<String>? {
        if (!line.contains('|')) return null
        val cells = ArrayList<String>()
        val sb = StringBuilder()
        var i = if (line.startsWith("|")) 1 else 0
        val trailing = line.endsWith("|") &&
            (line.length < 2 || line[line.length - 2] != '\\')
        val end = if (trailing) line.length - 1 else line.length
        while (i < end) {
            val c = line[i]
            if (c == '\\' && i + 1 < end && line[i + 1] == '|') {
                sb.append('|')
                i += 2
                continue
            }
            if (c == '|') {
                cells += sb.toString().trim()
                sb.setLength(0)
                i++
                continue
            }
            sb.append(c)
            i++
        }
        cells += sb.toString().trim()
        return cells
    }

    private fun isDelimiterCell(cell: String): Boolean = DELIMITER_CELL.matches(cell.trim())

    private fun delimiterAlign(cell: String): RichAlign {
        val c = cell.trim()
        val left = c.startsWith(":")
        val right = c.endsWith(":")
        return when {
            left && right -> RichAlign.Center
            right -> RichAlign.Right
            else -> RichAlign.Left
        }
    }

    private val HEADING = Regex("^(#{1,3})\\s+(.+)$")
    private val BULLET = Regex("^[-*]\\s+(.+)$")
    private val NUMBERED = Regex("^(\\d+)\\.\\s+(.+)$")
    private val DELIMITER_CELL = Regex(":?-+:?")
}
