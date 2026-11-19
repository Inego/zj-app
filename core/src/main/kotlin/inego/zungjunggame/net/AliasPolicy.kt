package inego.zungjunggame.net

import inego.zungjunggame.text.Graphemes

/**
 * Display-name write limits shared by the in-app editor, the OS name dialog,
 * and the save path.
 *
 * A new name may be at least [MIN_GRAPHEMES] and at most [MAX_GRAPHEMES]
 * grapheme clusters and at most [MAX_COLUMNS] East Asian Width columns (Wide and
 * Fullwidth count as 2, ambiguous characters such as Cyrillic and Greek count as
 * 1). The server applies the same numbers in `normalize_alias` and is the
 * authority; this copy exists so the editor can refuse the keystroke. Names already stored under the old 24-grapheme rule are
 * not rewritten: [allowsEdit] still shows them and still lets the player delete,
 * and it refuses an edit that would grow graphemes or columns while the text is
 * over the limit.
 *
 * Column width here is the sum of code-point widths. The server's `unicode-width`
 * crate also folds a few emoji sequences into one width; a sequence this table
 * counts more generously is rejected when the save reaches the server.
 */
object AliasPolicy {
    /** The write floor: a name shorter than this cannot be saved. Two graphemes
     * is a complete CJK name (surname + given, e.g. 完全), so the floor is 2. */
    const val MIN_GRAPHEMES: Int = 2
    const val MAX_GRAPHEMES: Int = 16
    const val MAX_COLUMNS: Int = 16

    /**
     * The widest name the server can still return, in graphemes: the write limit is
     * [MAX_GRAPHEMES], but a name saved under the old 24-grapheme rule is not
     * rewritten (see [allowsEdit]), so a surface that must show any stored name
     * whole measures against this, not against what a save today would accept.
     */
    const val STORED_GRAPHEMES: Int = 24

    /**
     * UTF-16 units the editor will hold. Above the write limits so a name saved
     * before those limits still appears whole. Typing past the write limits is
     * refused by [allowsEdit]; this only bounds a paste.
     */
    const val HOLD_LENGTH: Int = 96

    fun withinLimits(name: String): Boolean =
        graphemeCount(name) <= MAX_GRAPHEMES && columnWidth(name) <= MAX_COLUMNS

    /** True when [name] is long enough to save (see [MIN_GRAPHEMES]). */
    fun meetsMinimum(name: String): Boolean = graphemeCount(name) >= MIN_GRAPHEMES

    /**
     * An edit the field may apply. A result inside the write limits is always
     * allowed. A result outside them is allowed only when it does not add
     * graphemes or columns, so a stored long name can be shortened in place.
     */
    fun allowsEdit(before: String, after: String): Boolean {
        if (withinLimits(after)) return true
        return graphemeCount(after) <= graphemeCount(before) &&
            columnWidth(after) <= columnWidth(before)
    }

    fun graphemeCount(text: String): Int = Graphemes.count(text)

    fun columnWidth(text: String): Int {
        var width = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            width += columnOf(cp)
            i += Character.charCount(cp)
        }
        return width
    }

    private fun columnOf(cp: Int): Int = when {
        cp < 0x20 || cp == 0x7F -> 0
        Graphemes.isExtend(cp) -> 0
        isWide(cp) -> 2
        else -> 1
    }

    /** East Asian Width Wide or Fullwidth. Ambiguous code points stay width 1. */
    private fun isWide(cp: Int): Boolean {
        var lo = 0
        var hi = WIDE_RANGES.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val start = WIDE_RANGES[mid * 2]
            val end = WIDE_RANGES[mid * 2 + 1]
            when {
                cp < start -> hi = mid - 1
                cp > end -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    /**
     * Inclusive ranges, sorted, of code points with East Asian Width Wide or
     * Fullwidth (Unicode 16). Emoji blocks are included whole; a few non-wide
     * scalars inside them are counted wide, which only makes the editor stricter
     * than the server.
     */
    private val WIDE_RANGES: IntArray = intArrayOf(
        0x1100, 0x115F,
        0x231A, 0x231B,
        0x2329, 0x232A,
        0x23E9, 0x23EC,
        0x23F0, 0x23F0,
        0x23F3, 0x23F3,
        0x25FD, 0x25FE,
        0x2614, 0x2615,
        0x2648, 0x2653,
        0x267F, 0x267F,
        0x2693, 0x2693,
        0x26A1, 0x26A1,
        0x26AA, 0x26AB,
        0x26BD, 0x26BE,
        0x26C4, 0x26C5,
        0x26CE, 0x26CE,
        0x26D4, 0x26D4,
        0x26EA, 0x26EA,
        0x26F2, 0x26F3,
        0x26F5, 0x26F5,
        0x26FA, 0x26FA,
        0x26FD, 0x26FD,
        0x2705, 0x2705,
        0x270A, 0x270B,
        0x2728, 0x2728,
        0x274C, 0x274C,
        0x274E, 0x274E,
        0x2753, 0x2755,
        0x2757, 0x2757,
        0x2795, 0x2797,
        0x27B0, 0x27B0,
        0x27BF, 0x27BF,
        0x2B1B, 0x2B1C,
        0x2B50, 0x2B50,
        0x2B55, 0x2B55,
        0x2E80, 0x2E99,
        0x2E9B, 0x2EF3,
        0x2F00, 0x2FD5,
        0x2FF0, 0x2FFB,
        0x3000, 0x303E,
        0x3041, 0x3096,
        0x3099, 0x30FF,
        0x3105, 0x312F,
        0x3131, 0x318E,
        0x3190, 0x31E3,
        0x31F0, 0x321E,
        0x3220, 0x3247,
        0x3250, 0x4DBF,
        0x4E00, 0xA48C,
        0xA490, 0xA4C6,
        0xA960, 0xA97C,
        0xAC00, 0xD7A3,
        0xF900, 0xFAFF,
        0xFE10, 0xFE19,
        0xFE30, 0xFE52,
        0xFE54, 0xFE66,
        0xFE68, 0xFE6B,
        0xFF01, 0xFF60,
        0xFFE0, 0xFFE6,
        0x16FE0, 0x16FE4,
        0x16FF0, 0x16FF1,
        0x17000, 0x187F7,
        0x18800, 0x18CD5,
        0x18D00, 0x18D08,
        0x1B000, 0x1B122,
        0x1B150, 0x1B152,
        0x1B164, 0x1B167,
        0x1B170, 0x1B2FB,
        0x1F004, 0x1F004,
        0x1F0CF, 0x1F0CF,
        0x1F18E, 0x1F18E,
        0x1F191, 0x1F19A,
        0x1F200, 0x1F202,
        0x1F210, 0x1F23B,
        0x1F240, 0x1F248,
        0x1F250, 0x1F251,
        0x1F260, 0x1F265,
        0x1F300, 0x1FAFF,
        0x20000, 0x3FFFD,
    )
}
