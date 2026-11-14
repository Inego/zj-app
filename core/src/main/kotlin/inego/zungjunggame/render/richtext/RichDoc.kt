package inego.zungjunggame.render.richtext

/** Horizontal alignment for a block or table column. */
enum class RichAlign {
    Left,
    Center,
    Right,
}

/**
 * Unresolved text color on a span. Resolved at draw time via [RichColorPalette]
 * so content stays themeable (named roles) with a raw-hex escape hatch.
 */
sealed interface RichColor {
    /** Palette role, lowercased (e.g. `"hl"`, `"danger"`). */
    data class Named(val name: String) : RichColor

    /** Packed ARGB `0xAARRGGBB`. */
    data class Hex(val argb: Int) : RichColor
}

/** Inline text emphasis + optional color (combinable). */
data class TextStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val color: RichColor? = null,
) {
    companion object {
        val Plain = TextStyle()
    }
}

/** Which font role a text span uses (size / weight base). */
enum class TextRole {
    /** Document body text. The default for every article, slide, and notice. */
    Body,

    /**
     * Body text that must read smaller than a document's — chrome captions and
     * other small print rendered through the same markup pipeline.
     *
     * Separate from [Body] rather than a scale of it: the pipeline sizes a line
     * from its font, so a smaller role is the honest way to say "small print"
     * and keeps emphasis (`**…**`) resolving to a real bold face at that size.
     */
    Small,

    H1,
    H2,
    H3,
}

/** Inline content inside a paragraph, heading, or list item. */
sealed interface Inline {
    /**
     * @param linkHref optional markdown link destination (`[text](href)`).
     *   When set, the span is clickable; the parser applies default link color
     *   unless [style] already specifies a color (no default underline).
     */
    data class Text(
        val text: String,
        val style: TextStyle = TextStyle.Plain,
        val linkHref: String? = null,
    ) : Inline

    /** One or more tiles drawn side-by-side as a single unbreakable run. */
    data class TileRun(val tiles: IntArray) : Inline {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TileRun) return false
            return tiles.contentEquals(other.tiles)
        }

        override fun hashCode(): Int = tiles.contentHashCode()

        override fun toString(): String = "TileRun(${tiles.joinToString()})"
    }

    /**
     * Hard line break within the same block (`<br>`, `<br/>`, `<br />`).
     * Not a new paragraph — only advances to the next line box.
     */
    data object LineBreak : Inline
}

/** Block-level document node. */
sealed interface Block {
    data class Heading(
        val level: Int,
        val spans: List<Inline>,
        val align: RichAlign = RichAlign.Left,
    ) : Block {
        init {
            require(level in 1..3) { "heading level must be 1..3, got $level" }
        }
    }

    data class Paragraph(
        val spans: List<Inline>,
        val align: RichAlign = RichAlign.Left,
    ) : Block

    data class BulletList(
        val items: List<List<Inline>>,
    ) : Block

    data class NumberedList(
        val items: List<List<Inline>>,
        val start: Int = 1,
    ) : Block

    /**
     * Pipe table: a header row (`| a | b |`), a delimiter row (`|---|---|`),
     * then body rows.
     *
     * Every row has the same cell count as [header]; the parser pads or drops
     * cells to match. Cells hold ordinary [Inline] content (emphasis, colors,
     * tiles, links). [aligns] is one [RichAlign] per column, taken from the
     * delimiter row's colons (`:--` left, `:-:` center, `--:` right); a missing
     * entry falls back to [RichAlign.Left].
     */
    data class Table(
        val header: List<List<Inline>>,
        val rows: List<List<List<Inline>>>,
        val aligns: List<RichAlign> = emptyList(),
    ) : Block
}

/** Parsed rich document (markup → AST). */
data class RichDoc(val blocks: List<Block>)
