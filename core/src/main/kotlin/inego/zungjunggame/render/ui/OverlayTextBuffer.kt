package inego.zungjunggame.render.ui

import inego.zungjunggame.text.Graphemes
import inego.zungjunggame.text.TextEdit

/**
 * Single-line text buffer for overlay fields: caret, selection, insert/replace,
 * word jumps, and clipboard-shaped mutations. No GL, no libGDX input.
 *
 * Indices are Kotlin [Char] offsets (UTF-16), matching [String.substring], but
 * a caret, selection edge or deletion always sits on a grapheme boundary: a
 * surrogate pair or a base with its combining marks moves and deletes as one
 * step. Storage limits still count UTF-16 units, so a sticky value is not
 * rewritten just because the cap changed.
 */
class OverlayTextBuffer(
    maxLength: Int,
    val rejectWhitespace: Boolean = false,
) {
    /**
     * UTF-16 units an edit may hold. A value set with [setExact] may be longer;
     * [setMaxLength] never truncates the value already in the buffer.
     */
    var maxLength: Int = maxLength
        private set

    /**
     * Whether [after] may replace [before]. The default accepts anything that
     * fits [maxLength]. Alias editing installs the grapheme and column limits
     * here; a stored name longer than those limits is set with [setExact] and
     * can still be shortened.
     */
    var acceptEdit: (before: String, after: String) -> Boolean = { _, _ -> true }

    var text: String = ""
        private set

    var caret: Int = 0
        private set

    /** Inclusive selection origin, or null when there is no selection. */
    var selectionAnchor: Int? = null
        private set

    /**
     * The high half of a surrogate pair typed as two [typeChar] events. libGDX
     * delivers one [Char] per event, so the pair is only inserted when the low
     * half arrives; any other action abandons it instead of storing half a code
     * point.
     */
    private var pendingHighSurrogate: Char? = null

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
        pendingHighSurrogate = null
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
        pendingHighSurrogate = null
        text = value
        caret = value.length
        selectionAnchor = null
    }

    /**
     * Change the storage cap without touching [text]. Lowering it below the
     * current value keeps the value whole; later edits may only shorten it
     * until it is back under the cap.
     */
    fun setMaxLength(value: Int) {
        maxLength = value.coerceAtLeast(0)
    }

    fun typeChar(ch: Char) {
        if (ch.isHighSurrogate()) {
            pendingHighSurrogate = ch
            return
        }
        if (ch.isLowSurrogate()) {
            val high = pendingHighSurrogate ?: return
            pendingHighSurrogate = null
            insert("$high$ch")
            return
        }
        pendingHighSurrogate = null
        if (!isInsertable(ch)) return
        insert(ch.toString())
    }

    fun insert(raw: String) {
        pendingHighSurrogate = null
        val filtered = filterInsert(raw)
        if (filtered.isEmpty()) return
        val start = selectionStart
        val end = selectionEnd
        val take = TextEdit.replacement(text, start, end, filtered, maxLength, acceptEdit) ?: return
        if (take.isEmpty()) return
        text = text.substring(0, start) + take + text.substring(end)
        caret = start + take.length
        selectionAnchor = null
    }

    fun backspace() {
        if (pendingHighSurrogate != null) {
            pendingHighSurrogate = null
            return
        }
        if (hasSelection) {
            deleteSelection()
            return
        }
        if (caret <= 0) return
        val from = Graphemes.prev(text, caret)
        text = text.removeRange(from, caret)
        caret = from
    }

    fun deleteForward() {
        if (pendingHighSurrogate != null) {
            pendingHighSurrogate = null
            return
        }
        if (hasSelection) {
            deleteSelection()
            return
        }
        if (caret >= text.length) return
        text = text.removeRange(caret, Graphemes.next(text, caret))
    }

    /** Selection, else from the previous word boundary to the caret. */
    fun deletePrevWord() {
        pendingHighSurrogate = null
        if (hasSelection) {
            deleteSelection()
            return
        }
        val from = Graphemes.atOrBefore(text, prevWordIndex(text, caret))
        if (from >= caret) return
        text = text.removeRange(from, caret)
        caret = from
    }

    fun moveLeft(extend: Boolean) {
        pendingHighSurrogate = null
        if (!extend && hasSelection) {
            caret = selectionStart
            selectionAnchor = null
            return
        }
        setCaret(Graphemes.prev(text, caret), extend)
    }

    fun moveRight(extend: Boolean) {
        pendingHighSurrogate = null
        if (!extend && hasSelection) {
            caret = selectionEnd
            selectionAnchor = null
            return
        }
        setCaret(Graphemes.next(text, caret), extend)
    }

    fun moveHome(extend: Boolean) = setCaret(0, extend)

    fun moveEnd(extend: Boolean) = setCaret(text.length, extend)

    fun movePrevWord(extend: Boolean) =
        setCaret(Graphemes.atOrBefore(text, prevWordIndex(text, caret)), extend)

    fun moveNextWord(extend: Boolean) =
        setCaret(Graphemes.atOrAfter(text, nextWordIndex(text, caret)), extend)

    fun selectAll() {
        pendingHighSurrogate = null
        if (text.isEmpty()) {
            caret = 0
            selectionAnchor = null
            return
        }
        selectionAnchor = 0
        caret = text.length
    }

    fun setCaret(index: Int, extend: Boolean) {
        pendingHighSurrogate = null
        val clamped = Graphemes.atOrBefore(text, index.coerceIn(0, text.length))
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
        pendingHighSurrogate = null
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
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val units = Character.charCount(cp)
            // A high or low surrogate on its own is not a character; pasting
            // one must not put half a code point into the stored value.
            val paired = units == 2 || !Character.isSurrogate(cp.toChar())
            if (paired && isInsertable(cp)) out.appendCodePoint(cp)
            i += units
        }
        return out.toString()
    }

    private fun isInsertable(cp: Int): Boolean {
        if (cp < 32 || cp == 0x7F) return false
        if (rejectWhitespace && Character.isWhitespace(cp)) return false
        return true
    }

    private fun isInsertable(ch: Char): Boolean = isInsertable(ch.code)

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
