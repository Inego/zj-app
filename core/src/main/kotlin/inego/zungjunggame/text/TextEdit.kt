package inego.zungjunggame.text

/**
 * One proposed single-line edit, limited by storage units and then by the
 * field kind's own admission rule.
 *
 * Storage stays in UTF-16 units: [maxLength] bounds a held value. A value
 * already above that cap - a name stored before the limit changed - is not
 * truncated on open, and may still be shortened in place. While the value is
 * over the cap the edit may not make it longer; it may replace as many units
 * as it removes, so a shorter replacement is never rejected merely because
 * the result is still above the cap.
 *
 * The replacement is cut at a [Graphemes] boundary, never between the halves
 * of a surrogate pair or between a base and its combining marks. It is then
 * offered to [acceptEdit] one whole cluster at a time. A cluster holding a
 * private-use code point is dropped: no face can draw one, and a truncated
 * typed code point is the only way it arrives.
 */
object TextEdit {
    /**
     * The text that replaces `[start, end)` in [text], or null when the edit
     * must be refused. An empty result is a valid deletion.
     */
    fun replacement(
        text: String,
        start: Int,
        end: Int,
        incoming: String,
        maxLength: Int,
        acceptEdit: (before: String, after: String) -> Boolean,
    ): String? {
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        // A replacement range that ends inside a cluster would leave half a
        // code point or a detached mark behind, whatever the incoming text is.
        // The in-game buffer always passes boundaries; this guards a native
        // field that reports a deletion in UTF-16 units.
        if (from != Graphemes.atOrBefore(text, from) || to != Graphemes.atOrBefore(text, to)) {
            return null
        }
        val prefix = text.substring(0, from)
        val suffix = text.substring(to)
        if (incoming.isEmpty()) {
            return if (acceptEdit(text, prefix + suffix)) "" else null
        }
        // Private-use code points are never text: no face has a glyph for them,
        // so one would enter the value as an invisible character. The desktop
        // input stack used to hand them over whenever a typed code point was
        // truncated; a paste can carry one too. Drop the whole cluster that
        // holds one rather than store something no surface can draw.
        val clean = withoutPrivateUse(incoming)
        if (clean.isEmpty()) return null
        // A value already past the cap may shrink, never grow.
        val budget = maxOf(maxLength, text.length)
        val room = (budget - prefix.length - suffix.length).coerceAtLeast(0)
        var take = Graphemes.take(clean, room)
        while (take.isNotEmpty() && !acceptEdit(text, prefix + take + suffix)) {
            take = Graphemes.dropLast(take)
        }
        return take.ifEmpty { null }
    }

    /**
     * [incoming] without any cluster that contains a private-use code point.
     * Drops whole clusters, so a mark that rides on the removed character goes
     * with it instead of being left attached to the neighbour.
     */
    private fun withoutPrivateUse(incoming: String): String {
        var clean = incoming
        var i = 0
        while (i < clean.length) {
            val end = Graphemes.next(clean, i)
            var j = i
            var privateUse = false
            while (j < end) {
                val cp = clean.codePointAt(j)
                if (isPrivateUse(cp)) {
                    privateUse = true
                    break
                }
                j += Character.charCount(cp)
            }
            if (privateUse) {
                // Do not advance: the next cluster now starts at [i].
                clean = clean.removeRange(i, end)
            } else {
                i = end
            }
        }
        return clean
    }

    private fun isPrivateUse(cp: Int): Boolean =
        cp in 0xE000..0xF8FF ||
            cp in 0xF0000..0xFFFFD ||
            cp in 0x100000..0x10FFFD
}
