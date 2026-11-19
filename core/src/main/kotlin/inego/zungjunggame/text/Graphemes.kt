package inego.zungjunggame.text

/**
 * Extended-grapheme boundaries over UTF-16 text, by code-unit index.
 *
 * The editor stores UTF-16 and caps storage in UTF-16 units, but a caret,
 * selection or deletion may only land on a cluster boundary. A surrogate pair
 * and a base with its combining marks are one step, so an edit can shorten a
 * value but cannot leave half a code point or a detached mark behind.
 *
 * The definition deliberately matches the one the alias policy counts with:
 * a code point plus its extenders, joined emoji sequences, and regional
 * indicator pairs. It is not a full UAX #29 implementation (Hangul jamo runs
 * and prepend characters are not joined), but it is the same rule on every
 * surface that edits or measures a name.
 */
object Graphemes {
    private const val ZWJ = 0x200D
    private const val FIRST_REGIONAL_INDICATOR = 0x1F1E6
    private const val LAST_REGIONAL_INDICATOR = 0x1F1FF

    /** True when [cp] is a mark that rides on the preceding cluster. */
    fun isExtend(cp: Int): Boolean {
        if (EXTEND_RANGES.containsRange(cp)) return true
        return when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            -> true
            else -> false
        }
    }

    /** The next boundary after [index], or the end of [text]. */
    fun next(text: String, index: Int): Int {
        if (index >= text.length) return text.length
        if (index <= 0) return clusterEnd(text, 0)
        return clusterEnd(text, atOrBefore(text, index))
    }

    /** The boundary before [index], or zero. */
    fun prev(text: String, index: Int): Int =
        atOrBefore(text, (index - 1).coerceAtLeast(0))

    /** The nearest boundary at or before [index]. */
    fun atOrBefore(text: String, index: Int): Int {
        val target = index.coerceIn(0, text.length)
        if (target == 0) return 0
        var start = 0
        while (start < text.length) {
            val end = clusterEnd(text, start)
            if (end >= target) return if (end == target) end else start
            start = end
        }
        return text.length
    }

    /** The nearest boundary at or after [index]. */
    fun atOrAfter(text: String, index: Int): Int {
        val target = index.coerceIn(0, text.length)
        var start = 0
        while (start < target) start = clusterEnd(text, start)
        return start
    }

    fun count(text: String): Int {
        var clusters = 0
        var i = 0
        while (i < text.length) {
            i = clusterEnd(text, i)
            clusters++
        }
        return clusters
    }

    /**
     * The longest prefix of [text] that ends on a cluster boundary and is at
     * most [maxUnits] UTF-16 units long. A cluster that does not fit whole is
     * not taken at all.
     */
    fun take(text: String, maxUnits: Int): String {
        if (maxUnits <= 0) return ""
        if (text.length <= maxUnits) return text
        var end = 0
        while (end < text.length) {
            val nextEnd = clusterEnd(text, end)
            if (nextEnd > maxUnits) break
            end = nextEnd
        }
        return text.substring(0, end)
    }

    /** [text] without its last cluster. */
    fun dropLast(text: String): String {
        if (text.isEmpty()) return text
        return text.substring(0, prev(text, text.length))
    }

    private fun clusterEnd(text: String, start: Int): Int {
        if (start >= text.length) return text.length
        var i = start
        val first = text.codePointAt(i)
        i += Character.charCount(first)
        if (isRegionalIndicator(first) && i < text.length) {
            val next = text.codePointAt(i)
            if (isRegionalIndicator(next)) i += Character.charCount(next)
        }
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                isExtend(cp) -> i += Character.charCount(cp)
                cp == ZWJ -> {
                    i += 1
                    if (i < text.length) {
                        i += Character.charCount(text.codePointAt(i))
                    } else {
                        break
                    }
                }
                else -> break
            }
        }
        return i
    }

    private fun isRegionalIndicator(cp: Int): Boolean =
        cp in FIRST_REGIONAL_INDICATOR..LAST_REGIONAL_INDICATOR

    /**
     * Ranges that are extenders but not character marks: emoji skin-tone
     * modifiers and tag characters. Everything else is read from the Unicode
     * general category, so Indic spacing marks and script-specific marks are
     * not missed.
     */
    private val EXTEND_RANGES = intArrayOf(
        0x1F3FB, 0x1F3FF,
        0xE0020, 0xE007E,
        0xE0100, 0xE01EF,
    )

    private fun IntArray.containsRange(cp: Int): Boolean {
        var lo = 0
        var hi = size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val start = this[mid * 2]
            val end = this[mid * 2 + 1]
            when {
                cp < start -> hi = mid - 1
                cp > end -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }
}
