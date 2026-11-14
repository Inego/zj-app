package inego.zungjunggame.render.ui

import com.badlogic.gdx.Input
import inego.zungjunggame.platform.MemoryOverlayClipboard
import inego.zungjunggame.platform.MemoryOverlayTextPrompt
import inego.zungjunggame.platform.OverlayClipboard
import inego.zungjunggame.platform.OverlayTextPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayTextFieldTest {

    private fun field(
        clipboard: OverlayClipboard = MemoryOverlayClipboard(),
        textPrompt: OverlayTextPrompt = MemoryOverlayTextPrompt(),
        maxLength: Int = 64,
        rejectWhitespace: Boolean = false,
        clock: () -> Long = { System.currentTimeMillis() },
    ): OverlayTextField {
        val field = OverlayTextField(
            maxLength = maxLength,
            rejectWhitespace = rejectWhitespace,
            clipboard = clipboard,
            textPrompt = textPrompt,
            clock = clock,
        )
        field.focus()
        return field
    }

    @Test
    fun keyDownBackspaceAndTypedControlDoesNotDoubleDelete() {
        val field = field()
        field.replaceAll("ab")
        assertTrue(field.keyDown(Input.Keys.BACKSPACE, command = false, shift = false))
        assertEquals("a", field.text)
        assertTrue(field.keyTyped('\b', command = false))
        assertEquals("a", field.text)
        assertTrue(field.keyTyped('\u007f', command = false))
        assertEquals("a", field.text)
    }

    @Test
    fun keyDownCtrlBackspaceDeletesPreviousWord() {
        val field = field()
        field.replaceAll("hello world")
        assertTrue(field.keyDown(Input.Keys.BACKSPACE, command = true, shift = false))
        assertEquals("hello ", field.text)
    }

    @Test
    fun keyDownArrowsHomeEndSelectAll() {
        val field = field()
        field.replaceAll("abcd")
        field.keyDown(Input.Keys.HOME, command = false, shift = false)
        assertEquals(0, field.buffer.caret)
        field.keyDown(Input.Keys.RIGHT, command = false, shift = true)
        field.keyDown(Input.Keys.RIGHT, command = false, shift = true)
        assertEquals("ab", field.buffer.selectedText)
        field.keyDown(Input.Keys.A, command = true, shift = false)
        assertEquals("abcd", field.buffer.selectedText)
        field.keyDown(Input.Keys.END, command = false, shift = false)
        assertEquals(4, field.buffer.caret)
        assertFalse(field.buffer.hasSelection)
    }

    @Test
    fun copyCutPasteViaClipboard() {
        val clip = MemoryOverlayClipboard()
        val field = field(clipboard = clip)
        field.replaceAll("abcd")
        field.buffer.setCaret(1, extend = false)
        field.buffer.setCaret(3, extend = true)
        field.keyDown(Input.Keys.C, command = true, shift = false)
        assertEquals("bc", clip.contents)
        assertEquals("abcd", field.text)
        field.keyDown(Input.Keys.X, command = true, shift = false)
        assertEquals("bc", clip.contents)
        assertEquals("ad", field.text)
        field.keyDown(Input.Keys.V, command = true, shift = false)
        assertEquals("abcd", field.text)
    }

    @Test
    fun pasteTakesFirstLineAndHonoursMaxLength() {
        val clip = MemoryOverlayClipboard("one\ntwo")
        val field = field(clipboard = clip, maxLength = 2)
        field.keyDown(Input.Keys.V, command = true, shift = false)
        assertEquals("on", field.text)
    }

    @Test
    fun pasteAfterBlurIsIgnored() {
        val clip = object : OverlayClipboard {
            var pending: ((String?) -> Unit)? = null
            override fun copy(text: String) {}
            override fun paste(onDone: (String?) -> Unit) {
                pending = onDone
            }
        }
        val field = field(clipboard = clip)
        field.keyDown(Input.Keys.V, command = true, shift = false)
        field.blur()
        clip.pending?.invoke("stolen")
        assertEquals("", field.text)
    }

    @Test
    fun commandKeyTypedDoesNotInsert() {
        val field = field()
        assertTrue(field.keyTyped('c', command = true))
        assertTrue(field.keyTyped('v', command = true))
        assertEquals("", field.text)
    }

    @Test
    fun unfocusedFieldIgnoresKeys() {
        val field = field()
        field.blur()
        assertFalse(field.keyDown(Input.Keys.BACKSPACE, command = false, shift = false))
        assertFalse(field.keyTyped('x', command = false))
        field.replaceAll("a")
        assertEquals("a", field.text)
    }

    @Test
    fun caretBlinksWhenIdle() {
        val simTime = 10_000L
        val field = field(clock = { simTime })
        field.resetBlink()

        // First 500ms: visible
        assertTrue(field.isCaretVisible(simTime))
        assertTrue(field.isCaretVisible(simTime + 499L))

        // 500ms - 999ms: invisible
        assertFalse(field.isCaretVisible(simTime + 500L))
        assertFalse(field.isCaretVisible(simTime + 999L))

        // 1000ms+: visible again
        assertTrue(field.isCaretVisible(simTime + 1000L))
    }

    @Test
    fun caretBlinkResetsOnBackspaceAndArrowKeys() {
        var simTime = 10_000L
        val field = field(clock = { simTime })
        field.replaceAll("hello world")
        field.resetBlink()

        // At +600ms, caret would be off if idle
        simTime += 600L
        assertFalse(field.isCaretVisible())

        // User presses Backspace at +600ms: caret immediately turns on
        assertTrue(field.keyDown(Input.Keys.BACKSPACE, command = false, shift = false))
        assertTrue(field.isCaretVisible())
        assertEquals("hello worl", field.text)

        // Rapid repeat: pressing Left every 40ms keeps caret continuously visible
        for (i in 1..15) {
            simTime += 40L
            assertTrue(field.keyDown(Input.Keys.LEFT, command = false, shift = false))
            assertTrue(field.isCaretVisible(), "caret should stay visible while running left at t=$simTime")
        }

        // After stopping and waiting 500ms, caret blinks off normally
        simTime += 500L
        assertFalse(field.isCaretVisible())
    }

    @Test
    fun pointerDownAsksOsWhenPromptReplacesKeyboard() {
        val os = MemoryOverlayTextPrompt(replacesKeyboard = true)
        val osField = field(textPrompt = os)
        osField.promptTitle = { "Display name" }
        osField.replaceAll("user-k7m4p9q2")
        osField.layout(0f, 0f, 120f, 40f)

        assertTrue(osField.pointerDown(10f, 10f, extend = false))
        assertEquals(1, os.requestCount)
        assertEquals("Display name", os.lastRequest?.title)
        assertEquals("user-k7m4p9q2", os.lastRequest?.text)
        os.complete("Alice")
        assertEquals("Alice", osField.text)

        assertTrue(osField.pointerDown(10f, 10f, extend = false))
        osField.blur()
        os.complete("stolen")
        assertEquals("Alice", osField.text)

        val keys = MemoryOverlayTextPrompt(replacesKeyboard = false)
        val keysField = field(textPrompt = keys)
        keysField.replaceAll("abcd")
        keysField.layout(0f, 0f, 200f, 40f)
        assertTrue(keysField.pointerDown(10f, 10f, extend = false))
        assertEquals(0, keys.requestCount)
        assertEquals("abcd", keysField.text)
    }

    @Test
    fun caretBlinkResetsOnTyping() {
        var simTime = 10_000L
        val field = field(clock = { simTime })
        field.resetBlink()

        // At +700ms, caret would be off if idle
        simTime += 700L
        assertFalse(field.isCaretVisible())

        // Typing a character resets blink and makes caret immediately visible
        assertTrue(field.keyTyped('a', command = false))
        assertTrue(field.isCaretVisible())
        assertEquals("a", field.text)
    }
}
