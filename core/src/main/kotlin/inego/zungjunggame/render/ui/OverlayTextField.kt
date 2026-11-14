package inego.zungjunggame.render.ui

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.platform.LineRequest
import inego.zungjunggame.platform.OverlayClipboard
import inego.zungjunggame.platform.OverlayTextPrompt
import inego.zungjunggame.render.fonts.Fonts
import kotlin.math.max

/**
 * In-canvas single-line editor: buffer + caret/selection drawing + key/mouse
 * handling. Overlay chrome stays [SpriteBatch]; not Scene2D, not an HTML input.
 *
 * Keyboard commands arrive through [keyDown] / [keyTyped] (TeaVM never sets
 * `isKeyJustPressed(BACKSPACE)`). [keyTyped] ignores control characters so a
 * desktop Backspace does not double-delete after [keyDown].
 *
 * On a profile with no hardware keyboard a tap opens [OverlayTextPrompt]
 * instead of placing a caret: there is nothing to type with otherwise.
 *
 * A value wider than the plate scrolls horizontally around the caret instead of
 * painting past the field. This is what lets the special-key card hold a long
 * opaque credential in the same widget as a display name.
 */
class OverlayTextField(
    maxLength: Int,
    rejectWhitespace: Boolean = false,
    private val clipboard: OverlayClipboard = OverlayClipboard.create(),
    private val textPrompt: OverlayTextPrompt = OverlayTextPrompt.create(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    val buffer = OverlayTextBuffer(maxLength, rejectWhitespace)

    var enabled: Boolean = true
    var focused: Boolean = false
        private set

    var left: Float = 0f
        private set
    var bottom: Float = 0f
        private set
    var width: Float = 0f
        private set
    var height: Float = 0f
        private set

    val text: String
        get() = buffer.text

    /** Title of the OS dialog a touch tap opens. Read at request time so locale can change. */
    var promptTitle: () -> String = { "" }

    private var font: BitmapFont? = null
    private var dragging: Boolean = false
    private var pasteGen: Int = 0
    private var promptGen: Int = 0

    /**
     * First character the field paints. A long value scrolls horizontally so the
     * caret stays in view instead of drawing past the plate; when the value fits,
     * this is zero.
     */
    private var viewStart: Int = 0

    var lastActivityMs: Long = clock()
        private set

    fun resetBlink(now: Long = clock()) {
        lastActivityMs = now
    }

    fun isCaretVisible(now: Long = clock()): Boolean {
        if (!focused || !enabled) return false
        if (dragging) return true
        val elapsed = (now - lastActivityMs).coerceAtLeast(0L)
        return (elapsed / BLINK_INTERVAL_MS) % 2L == 0L
    }

    fun layout(left: Float, bottom: Float, width: Float, height: Float) {
        this.left = left
        this.bottom = bottom
        this.width = width
        this.height = height
    }

    fun contains(worldX: Float, worldY: Float): Boolean =
        worldX >= left && worldX <= left + width &&
            worldY >= bottom && worldY <= bottom + height

    fun focus() {
        focused = true
        resetBlink()
    }

    fun blur() {
        focused = false
        dragging = false
        pasteGen += 1
        promptGen += 1
        buffer.setCaret(buffer.caret, extend = false)
    }

    fun replaceAll(value: String) {
        buffer.replaceAll(value)
        resetBlink()
    }

    /** Show [value] whole, even when it is longer than the field's insert cap. */
    fun setExact(value: String) {
        buffer.setExact(value)
        resetBlink()
    }

    /**
     * Extra gate on typing and paste, after the length cap. The alias card
     * installs the grapheme, column, and paint limits.
     */
    var acceptEdit: (before: String, after: String) -> Boolean
        get() = buffer.acceptEdit
        set(value) {
            buffer.acceptEdit = value
        }

    fun selectAll() {
        buffer.selectAll()
        resetBlink()
    }

    /**
     * @param command Ctrl or Cmd/SYM.
     * @return true when the key was consumed.
     */
    fun keyDown(keycode: Int, command: Boolean, shift: Boolean): Boolean {
        if (!focused || !enabled) return false
        val consumed = when (keycode) {
            Input.Keys.BACKSPACE -> {
                if (command) buffer.deletePrevWord() else buffer.backspace()
                true
            }
            Input.Keys.FORWARD_DEL -> {
                buffer.deleteForward()
                true
            }
            Input.Keys.LEFT -> {
                if (command) buffer.movePrevWord(shift) else buffer.moveLeft(shift)
                true
            }
            Input.Keys.RIGHT -> {
                if (command) buffer.moveNextWord(shift) else buffer.moveRight(shift)
                true
            }
            Input.Keys.HOME -> {
                buffer.moveHome(shift)
                true
            }
            Input.Keys.END -> {
                buffer.moveEnd(shift)
                true
            }
            Input.Keys.A -> if (command) {
                buffer.selectAll()
                true
            } else false
            Input.Keys.C -> if (command) {
                copySelection()
                true
            } else false
            Input.Keys.X -> if (command) {
                cutSelection()
                true
            } else false
            Input.Keys.V -> if (command) {
                requestPaste()
                true
            } else false
            else -> false
        }
        if (consumed) resetBlink()
        return consumed
    }

    fun keyTyped(character: Char, command: Boolean): Boolean {
        if (!focused || !enabled) return false
        if (character.code < 32 || character == '\u007f') return true
        if (command) return true
        buffer.typeChar(character)
        resetBlink()
        return true
    }

    fun pointerDown(worldX: Float, worldY: Float, extend: Boolean): Boolean {
        if (!enabled || !contains(worldX, worldY)) return false
        focused = true
        resetBlink()
        if (textPrompt.replacesKeyboard) {
            requestOsText()
            return true
        }
        buffer.setCaret(indexAtX(worldX), extend)
        dragging = true
        return true
    }

    fun pointerDrag(worldX: Float) {
        if (!dragging || !enabled) return
        buffer.setCaret(indexAtX(worldX), extend = true)
        resetBlink()
    }

    fun pointerUp() {
        dragging = false
    }

    fun render(
        batch: SpriteBatch,
        px: Texture,
        font: BitmapFont,
        pointerWorldX: Float,
        pointerWorldY: Float,
    ) {
        this.font = font
        val hovered =
            !pointerWorldX.isNaN() &&
                !pointerWorldY.isNaN() &&
                contains(pointerWorldX, pointerWorldY)
        val rim = when {
            focused -> AppPalette.tint(AppPalette.accent, 0.95f)
            hovered -> AppPalette.tint(AppPalette.controlRim, 0.70f)
            else -> AppPalette.tint(AppPalette.controlRim, 0.50f)
        }
        UiPlate.drawPlate(
            batch, px, left, bottom, width, height,
            AppPalette.panelDeep, rim, max(1.2f, Fonts.px(1.5f)),
        )

        val pad = Fonts.px(12f)
        val display = buffer.text
        val textX = left + pad
        val textY = bottom + height * 0.5f
        val available = (width - pad * 2f).coerceAtLeast(1f)

        // Prefix widths once per frame: the field may hold a long opaque key, and
        // both the view window and the caret/selection need the same measurements.
        val prefix = FloatArray(display.length + 1)
        for (i in 1..display.length) {
            prefix[i] = Fonts.textWidth(font, display.substring(0, i))
        }
        val totalW = prefix[display.length]
        val caretW = prefix[buffer.caret]
        if (totalW <= available) {
            viewStart = 0
        } else {
            // Scroll just enough that the caret sits one pad inside the right edge,
            // without scrolling past the point where the remaining text fills the
            // field.
            val maxStartW = (caretW - available + pad)
                .coerceIn(0f, (totalW - available).coerceAtLeast(0f))
            var start = viewStart.coerceIn(0, display.length)
            while (start > 0 && prefix[start] > maxStartW) start--
            while (start < display.length && prefix[start + 1] <= maxStartW) start++
            viewStart = start
        }
        val start = viewStart.coerceAtMost(display.length)
        var end = start
        while (end < display.length && prefix[end + 1] - prefix[start] <= available) end++
        val visible = display.substring(start, end)
        val lineH = Fonts.textHeight(font, visible)

        if (focused && buffer.hasSelection) {
            val selStart = buffer.selectionStart.coerceIn(start, end)
            val selEnd = buffer.selectionEnd.coerceIn(start, end)
            if (selEnd > selStart) {
                val selLeft = textX + prefix[selStart] - prefix[start]
                val selRight = textX + prefix[selEnd] - prefix[start]
                val selW = (selRight - selLeft).coerceAtLeast(1f)
                val inset = Fonts.px(8f)
                batch.setColor(AppPalette.tint(AppPalette.accent, 0.35f))
                batch.draw(px, selLeft, bottom + inset, selW, height - inset * 2f)
                batch.setColor(Color.WHITE)
            }
        }
        // Primary + CJK fallback, so a name in any script the alias policy
        // admits paints here the same way the menu chip paints it.
        Fonts.drawText(batch, font, visible, textX, textY + lineH * 0.5f, AppPalette.ink)

        val caretOn = isCaretVisible()
        if (caretOn) {
            val caretX = textX + prefix[buffer.caret] - prefix[start]
            val caretWWidth = max(1.5f, Fonts.px(2f))
            val inset = Fonts.px(8f)
            batch.setColor(AppPalette.inkStrong)
            batch.draw(px, caretX, bottom + inset, caretWWidth, height - inset * 2f)
            batch.setColor(Color.WHITE)
        }
    }

    private fun prefixWidth(font: BitmapFont, display: String, index: Int): Float {
        if (index <= 0 || display.isEmpty()) return 0f
        val end = index.coerceAtMost(display.length)
        return Fonts.textWidth(font, display.substring(0, end))
    }

    private fun indexAtX(worldX: Float): Int {
        val font = this.font ?: return buffer.text.length
        val display = buffer.text
        if (display.isEmpty()) return 0
        val pad = Fonts.px(12f)
        val start = viewStart.coerceIn(0, display.length)
        val x = worldX - left - pad + prefixWidth(font, display, start)
        if (x <= 0f) return start
        var prevW = 0f
        for (i in 1..display.length) {
            val currW = Fonts.textWidth(font, display.substring(0, i))
            if (x < (prevW + currW) * 0.5f) return (i - 1).coerceAtLeast(start)
            prevW = currW
        }
        return display.length
    }

    private fun copySelection() {
        val selected = buffer.selectedText
        if (selected.isEmpty()) return
        clipboard.copy(selected)
    }

    private fun cutSelection() {
        val selected = buffer.cutText()
        if (selected.isEmpty()) return
        clipboard.copy(selected)
    }

    private fun requestOsText() {
        val gen = ++promptGen
        textPrompt.request(
            LineRequest(
                title = promptTitle(),
                text = text,
                selectAll = buffer.hasSelection,
                maxLength = buffer.maxLength,
            ),
        ) { raw ->
            if (gen != promptGen) return@request
            if (!focused || !enabled) return@request
            if (raw == null) return@request
            replaceAll(raw)
        }
    }

    private fun requestPaste() {
        val gen = ++pasteGen
        clipboard.paste { raw ->
            if (gen != pasteGen) return@paste
            if (!focused || !enabled) return@paste
            if (raw.isNullOrEmpty()) return@paste
            buffer.paste(raw)
            resetBlink()
        }
    }

    companion object {
        const val BLINK_INTERVAL_MS = 500L
    }
}

internal fun overlayCommandPressed(): Boolean {
    val input = Gdx.input ?: return false
    return input.isKeyPressed(Input.Keys.CONTROL_LEFT) ||
        input.isKeyPressed(Input.Keys.CONTROL_RIGHT) ||
        input.isKeyPressed(Input.Keys.SYM)
}

internal fun overlayShiftPressed(): Boolean {
    val input = Gdx.input ?: return false
    return input.isKeyPressed(Input.Keys.SHIFT_LEFT) ||
        input.isKeyPressed(Input.Keys.SHIFT_RIGHT)
}
