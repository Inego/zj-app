package inego.zungjunggame.render.ui

/**
 * Single-line text buffer for overlay fields: caret, selection, insert/replace,
 * word jumps, and clipboard-shaped mutations. No GL, no libGDX input.
 *
 * Indices are Kotlin [Char] offsets (UTF-16), matching [String.substring].
 */
class OverlayTextBuffer(
    val maxLength: Int,
    val rejectWhitespace: Boolean = false,
) {
    /**
     * Whether [after] may replace [before]. The default accepts anything that
     * fits [maxLength]. Alias editing installs the grapheme, column, and paint
     * limits here; a stored name longer than those limits is set with [setExact]
     * and can still be shortened.
     */
    var acceptEdit: (before: String, after: String) -> Boolean = { _, _ -> true }

    var text: String = ""
        private set

    var caret: Int = 0
        private set

    /** Inclusive selection origin, or null when there is no selection. */
    var selectionAnchor: Int? = null
        private set

    val hasSelection: Boolean
        get() {
            val anchor = selectionAnchor ?: return false
            return anchor != caret
        }

    val selectionStart: Int
        get() {
            val anchor = selectionAnchor ?: return caret
            return minOf(anchor, caret)
        }

    val selectionEnd: Int
        get() {
            val anchor = selectionAnchor ?: return caret
            return maxOf(anchor, caret)
        }

    val selectedText: String
        get() = if (hasSelection) text.substring(selectionStart, selectionEnd) else ""

    fun replaceAll(value: String) {
        text = ""
        caret = 0
        selectionAnchor = null
        insert(value)
    }

    /**
     * Replace the buffer with [value] exactly, including a value longer than
     * [maxLength]. Used to show a stored display name that predates the current
     * write limits. Later keystrokes still go through [insert].
     */
    fun setExact(value: String) {
        text = value
        caret = value.length
        selectionAnchor = null
    }

    fun typeChar(ch: Char) {
        if (!isInsertable(ch)) return
        insert(ch.toString())
    }

    fun insert(raw: String) {
        val filtered = filterInsert(raw)
        if (filtered.isEmpty()) return
        val start = selectionStart
        val end = selectionEnd
        val prefix = text.substring(0, start)
        val suffix = text.substring(end)
        val room = (maxLength - prefix.length - suffix.length).coerceAtLeast(0)
        val limited = if (filtered.length > room) filtered.substring(0, room) else filtered
        if (limited.isEmpty()) return
        val take = longestAccepted(text, prefix, limited, suffix)
        if (take.isEmpty()) return
        text = prefix + take + suffix
        caret = prefix.length + take.length
        selectionAnchor = null
    }

    /** Longest prefix of [incoming] that [acceptEdit] allows. Empty rejects the edit. */
    private fun longestAccepted(before: String, prefix: String, incoming: String, suffix: String): String {
        var take = incoming
        while (take.isNotEmpty() && !acceptEdit(before, prefix + take + suffix)) {
            take = dropLastCodePoint(take)
        }
        return take
    }

    private fun dropLastCodePoint(value: String): String {
        if (value.isEmpty()) return value
        val last = value.length - 1
        val end = if (last > 0 && value[last].isLowSurrogate() && value[last - 1].isHighSurrogate()) {
            last - 1
        } else {
            last
        }
        return value.substring(0, end)
    }

    fun backspace() {
        if (hasSelection) {
            deleteSelection()
            return
        }
        if (caret <= 0) return
        text = text.removeRange(caret - 1, caret)
        caret -= 1
    }

    fun deleteForward() {
        if (hasSelection) {
            deleteSelection()
            return
        }
        if (caret >= text.length) return
        text = text.removeRange(caret, caret + 1)
    }

    /** Selection, else from the previous word boundary to the caret. */
    fun deletePrevWord() {
        if (hasSelection) {
            deleteSelection()
            return
        }
        val from = prevWordIndex(text, caret)
        if (from >= caret) return
        text = text.removeRange(from, caret)
        caret = from
    }

    fun moveLeft(extend: Boolean) {
        if (!extend && hasSelection) {
            caret = selectionStart
            selectionAnchor = null
            return
        }
        setCaret(caret - 1, extend)
    }

    fun moveRight(extend: Boolean) {
        if (!extend && hasSelection) {
            caret = selectionEnd
            selectionAnchor = null
            return
        }
        setCaret(caret + 1, extend)
    }

    fun moveHome(extend: Boolean) = setCaret(0, extend)

    fun moveEnd(extend: Boolean) = setCaret(text.length, extend)

    fun movePrevWord(extend: Boolean) = setCaret(prevWordIndex(text, caret), extend)

    fun moveNextWord(extend: Boolean) = setCaret(nextWordIndex(text, caret), extend)

    fun selectAll() {
        if (text.isEmpty()) {
            caret = 0
            selectionAnchor = null
            return
        }
        selectionAnchor = 0
        caret = text.length
    }

    fun setCaret(index: Int, extend: Boolean) {
        val clamped = index.coerceIn(0, text.length)
        if (extend) {
            if (selectionAnchor == null) selectionAnchor = caret
        } else {
            selectionAnchor = null
        }
        caret = clamped
        val anchor = selectionAnchor
        if (anchor != null && anchor == caret) selectionAnchor = null
    }

    fun cutText(): String {
        if (!hasSelection) return ""
        val selected = selectedText
        deleteSelection()
        return selected
    }

    /**
     * Insert [raw] at the caret (replacing the selection). Only the first line
     * is kept; later lines and CR/LF are dropped.
     */
    fun paste(raw: String) {
        insert(firstLine(raw))
    }

    private fun deleteSelection() {
        val start = selectionStart
        val end = selectionEnd
        if (start == end) {
            selectionAnchor = null
            return
        }
        text = text.removeRange(start, end)
        caret = start
        selectionAnchor = null
    }

    private fun filterInsert(raw: String): String {
        if (raw.isEmpty()) return ""
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            if (isInsertable(ch)) out.append(ch)
        }
        return out.toString()
    }

    private fun isInsertable(ch: Char): Boolean {
        if (ch.code < 32 || ch == '\u007f') return false
        if (rejectWhitespace && ch.isWhitespace()) return false
        return true
    }

    companion object {
        fun firstLine(raw: String): String {
            val end = raw.indexOfFirst { it == '\n' || it == '\r' }
            return if (end < 0) raw else raw.substring(0, end)
        }

        fun prevWordIndex(text: String, caret: Int): Int {
            var i = caret.coerceIn(0, text.length)
            if (i == 0) return 0
            while (i > 0 && charClass(text[i - 1]) == CharClass.Space) i--
            if (i == 0) return 0
            val kind = charClass(text[i - 1])
            while (i > 0 && charClass(text[i - 1]) == kind) i--
            return i
        }

        fun nextWordIndex(text: String, caret: Int): Int {
            var i = caret.coerceIn(0, text.length)
            if (i >= text.length) return text.length
            val kind = charClass(text[i])
            while (i < text.length && charClass(text[i]) == kind) i++
            while (i < text.length && charClass(text[i]) == CharClass.Space) i++
            return i
        }

        private fun charClass(ch: Char): CharClass = when {
            ch.isWhitespace() -> CharClass.Space
            ch.isLetterOrDigit() || ch == '_' -> CharClass.Word
            else -> CharClass.Other
        }

        private enum class CharClass { Space, Word, Other }
    }
}
