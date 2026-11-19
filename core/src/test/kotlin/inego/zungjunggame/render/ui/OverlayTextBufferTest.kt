package inego.zungjunggame.render.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayTextBufferTest {

    private fun buffer(
        text: String = "",
        maxLength: Int = 64,
        rejectWhitespace: Boolean = false,
    ): OverlayTextBuffer {
        val b = OverlayTextBuffer(maxLength, rejectWhitespace)
        if (text.isNotEmpty()) b.replaceAll(text)
        return b
    }

    @Test
    fun insertAtEndAndInMiddle() {
        val b = buffer()
        b.insert("helo")
        b.setCaret(3, extend = false)
        b.insert("l")
        assertEquals("hello", b.text)
        assertEquals(4, b.caret)
    }

    @Test
    fun typeCharReplacesSelection() {
        val b = buffer("user-tag")
        b.setCaret(0, extend = false)
        b.moveEnd(extend = true)
        b.typeChar('x')
        assertEquals("x", b.text)
        assertEquals(1, b.caret)
        assertFalse(b.hasSelection)
    }

    @Test
    fun backspaceDeletesOneCharOrSelection() {
        val b = buffer("ab")
        b.backspace()
        assertEquals("a", b.text)
        b.setCaret(0, extend = false)
        b.backspace()
        assertEquals("a", b.text)
        b.replaceAll("abcd")
        b.setCaret(1, extend = false)
        b.setCaret(3, extend = true)
        b.backspace()
        assertEquals("ad", b.text)
        assertEquals(1, b.caret)
    }

    @Test
    fun deleteForwardDeletesOneCharOrSelection() {
        val b = buffer("ab")
        b.setCaret(0, extend = false)
        b.deleteForward()
        assertEquals("b", b.text)
        b.setCaret(1, extend = false)
        b.deleteForward()
        assertEquals("b", b.text)
        b.replaceAll("abcd")
        b.setCaret(1, extend = false)
        b.setCaret(3, extend = true)
        b.deleteForward()
        assertEquals("ad", b.text)
    }

    @Test
    fun deletePrevWordRemovesPreviousWord() {
        val b = buffer("hello world")
        b.deletePrevWord()
        assertEquals("hello ", b.text)
        b.deletePrevWord()
        assertEquals("", b.text)
    }

    @Test
    fun deletePrevWordUsesPunctuationBoundary() {
        val b = buffer("user-k7m4p9q2")
        b.deletePrevWord()
        assertEquals("user-", b.text)
        b.deletePrevWord()
        assertEquals("user", b.text)
    }

    @Test
    fun deletePrevWordWithSelectionDeletesSelection() {
        val b = buffer("hello world")
        b.setCaret(0, extend = false)
        b.moveEnd(extend = true)
        b.deletePrevWord()
        assertEquals("", b.text)
    }

    @Test
    fun arrowsHomeEndAndCollapseSelection() {
        val b = buffer("abcd")
        b.setCaret(2, extend = false)
        b.moveLeft(extend = false)
        assertEquals(1, b.caret)
        b.moveRight(extend = false)
        assertEquals(2, b.caret)
        b.moveHome(extend = false)
        assertEquals(0, b.caret)
        b.moveEnd(extend = false)
        assertEquals(4, b.caret)
        b.setCaret(1, extend = false)
        b.setCaret(3, extend = true)
        assertTrue(b.hasSelection)
        b.moveLeft(extend = false)
        assertEquals(1, b.caret)
        assertFalse(b.hasSelection)
        b.setCaret(1, extend = false)
        b.setCaret(3, extend = true)
        b.moveRight(extend = false)
        assertEquals(3, b.caret)
        assertFalse(b.hasSelection)
    }

    @Test
    fun shiftArrowsExtendSelection() {
        val b = buffer("abcd")
        b.setCaret(2, extend = false)
        b.moveLeft(extend = true)
        assertEquals("b", b.selectedText)
        b.moveRight(extend = true)
        assertFalse(b.hasSelection)
        b.moveHome(extend = true)
        assertEquals("ab", b.selectedText)
        b.moveEnd(extend = true)
        assertEquals("cd", b.selectedText)
    }

    @Test
    fun wordJumps() {
        val b = buffer("hello world")
        b.movePrevWord(extend = false)
        assertEquals(6, b.caret)
        b.movePrevWord(extend = false)
        assertEquals(0, b.caret)
        b.moveNextWord(extend = false)
        assertEquals(6, b.caret)
        b.moveNextWord(extend = false)
        assertEquals(11, b.caret)
    }

    @Test
    fun shiftWordJumpSelects() {
        val b = buffer("hello world")
        b.movePrevWord(extend = true)
        assertEquals("world", b.selectedText)
    }

    @Test
    fun selectAll() {
        val b = buffer("ab")
        b.selectAll()
        assertEquals("ab", b.selectedText)
        assertEquals(2, b.caret)
        val empty = buffer()
        empty.selectAll()
        assertFalse(empty.hasSelection)
        assertEquals(0, empty.caret)
    }

    @Test
    fun maxLengthTruncatesInsertAndPaste() {
        val b = buffer(maxLength = 5)
        b.insert("abcdef")
        assertEquals("abcde", b.text)
        b.replaceAll("ab")
        b.paste("wxyz")
        assertEquals("abwxy", b.text)
    }

    @Test
    fun maxLengthAccountsForSelection() {
        val b = buffer("abcde", maxLength = 5)
        b.selectAll()
        b.insert("xyz")
        assertEquals("xyz", b.text)
    }

    @Test
    fun rejectWhitespaceOnInsertAndPaste() {
        val b = buffer(rejectWhitespace = true)
        b.insert("a b")
        assertEquals("ab", b.text)
        b.typeChar(' ')
        assertEquals("ab", b.text)
        b.paste("c d")
        assertEquals("abcd", b.text)
    }

    @Test
    fun pasteTakesFirstLineOnly() {
        val b = buffer()
        b.paste("one\ntwo")
        assertEquals("one", b.text)
        b.replaceAll("")
        b.paste("one\r\ntwo")
        assertEquals("one", b.text)
        b.replaceAll("")
        b.paste("keep\rrest")
        assertEquals("keep", b.text)
    }

    @Test
    fun pasteReplacesSelection() {
        val b = buffer("user-tag")
        b.selectAll()
        b.paste("Ada")
        assertEquals("Ada", b.text)
    }

    @Test
    fun cutRemovesSelection() {
        val b = buffer("abcd")
        b.setCaret(1, extend = false)
        b.setCaret(3, extend = true)
        assertEquals("bc", b.cutText())
        assertEquals("ad", b.text)
        assertEquals("", b.cutText())
        assertEquals("ad", b.text)
    }

    @Test
    fun controlCharsNeverInsert() {
        val b = buffer("a")
        b.typeChar('\b')
        b.typeChar('\u007f')
        b.typeChar('\n')
        b.insert("\t")
        assertEquals("a", b.text)
    }

    @Test
    fun backspaceDeletesAWholeSurrogatePair() {
        val b = buffer("A\uD83D\uDE00")
        b.moveEnd(extend = false)

        b.backspace()

        assertEquals("A", b.text)
        assertEquals(1, b.caret)
    }

    @Test
    fun pasteThatDoesNotFitNeverInsertsHalfACodePoint() {
        val emoji = "\uD83D\uDE00"
        val b = buffer("A", maxLength = 2)
        b.moveEnd(extend = false)

        b.paste(emoji)
        assertEquals("A", b.text)
        assertEquals(1, b.caret)

        b.setMaxLength(3)
        b.paste(emoji)
        assertEquals("A$emoji", b.text)
    }

    @Test
    fun combiningMarkMovesSelectsAndDeletesAsOneGrapheme() {
        val cluster = "e\u0301"
        val b = buffer(cluster + "x")
        b.moveEnd(extend = false)

        b.moveLeft(extend = false)
        assertEquals(2, b.caret)
        b.moveLeft(extend = false)
        assertEquals(0, b.caret, "the base and its mark are one caret step")
        b.moveRight(extend = false)
        assertEquals(2, b.caret)

        // A caret inside the cluster snaps back to its start.
        b.setCaret(1, extend = false)
        assertEquals(0, b.caret)

        b.moveRight(extend = true)
        assertEquals(cluster, b.selectedText)
        b.backspace()
        assertEquals("x", b.text)
    }

    @Test
    fun combiningMarkDeletesAsOneGrapheme() {
        val cluster = "e\u0301"
        val b = buffer(cluster + "x")
        b.moveEnd(extend = false)

        b.backspace()
        assertEquals(cluster, b.text)
        b.backspace()
        assertEquals("", b.text)

        val forward = buffer(cluster + "x")
        forward.setCaret(0, extend = false)
        forward.deleteForward()
        assertEquals("x", forward.text)
    }

    @Test
    fun aSurrogatePairTypedAsTwoKeysBecomesOneCodePoint() {
        val b = buffer()

        b.typeChar('\uD83D')
        assertEquals("", b.text, "half a code point is not stored")
        b.typeChar('\uDE00')

        assertEquals("\uD83D\uDE00", b.text)
        assertEquals(2, b.caret)
    }

    @Test
    fun anInterruptedSurrogateIsNeverStored() {
        val b = buffer("A")
        b.moveEnd(extend = false)

        b.typeChar('\uD83D')
        b.backspace()
        assertEquals("A", b.text)
        assertEquals(1, b.caret)

        b.typeChar('\uD83D')
        b.typeChar('x')
        assertEquals("Ax", b.text)
        b.typeChar('\uDE00')
        assertEquals("Ax", b.text)
        assertTrue(b.text.none { Character.isSurrogate(it) })
    }
}
