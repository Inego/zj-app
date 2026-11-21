package inego.zungjunggame.render

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.AppVersion
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.fonts.LineBreaker
import inego.zungjunggame.render.ui.AppPalette
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.ui.Rect
import kotlin.math.max

/**
 * The felt's bottom-right stamp: [AppVersion.LABEL] over the rules/scoring
 * attribution, right-aligned in the safe corner of the felt.
 *
 * The block has **one home** — [rect] — and never moves for a card. It is a pure
 * function of the viewport and the locale: right-aligned to the felt's safe right
 * edge, sitting on its safe bottom, one line per stamp unless the felt itself is
 * narrower than the attribution. A card that shares the corner reads that rect and
 * yields to it ([OverlayViewport.placeBeside]); the block never reads a card, so
 * no frame's paint depends on another frame's measurement.
 *
 * Painted with the felt whenever the felt is on screen, behind every overlay card:
 * a card may cover the block, while the menu card yields to it. Not a hit target.
 */
internal object FeltStamps {
    private const val CREDIT_KEY = "menu.rules_credit"

    /**
     * Space between the version and the attribution under it, and between the
     * block and a card standing beside or above it.
     */
    const val GAP_DP = 6f

    private val ink = AppPalette.inkMuted

    /**
     * The block's one rect: natural width capped to the felt, right-aligned to the
     * safe right edge and sitting on the safe bottom.
     *
     * Pure and card-free. The menu calls this to place itself; [render] calls it
     * to paint. One definition, so the card and the stamps cannot disagree.
     */
    fun rect(screenW: Float, screenH: Float): Rect {
        Fonts.ensureCreated()
        val avail = OverlayViewport.available(screenW, screenH)
        val width = blockWidth(avail.width)
        return Rect(avail.right - width, avail.bottom, width, blockHeight(width))
    }

    fun render(batch: SpriteBatch, screenW: Float, screenH: Float) {
        if (screenW <= 0f || screenH <= 0f) return
        val rect = rect(screenW, screenH)
        val gap = Fonts.px(GAP_DP)
        val lines = wrapCredit(rect.width)
        val creditH = creditHeight(lines)
        var lineTop = rect.bottom + creditH
        for (line in lines) {
            drawLine(batch, Fonts.small, line, rect, lineTop)
            lineTop -= Fonts.textHeight(Fonts.small, line)
        }
        drawLine(
            batch = batch,
            font = Fonts.body,
            text = AppVersion.LABEL,
            rect = rect,
            top = rect.bottom + (if (lines.isEmpty()) 0f else creditH + gap) +
                Fonts.textHeight(Fonts.body, AppVersion.LABEL),
        )
    }

    /** Width of the block written on one line, capped by the felt's [availWidth]. */
    private fun blockWidth(availWidth: Float): Float {
        val versionW = Fonts.textWidth(Fonts.body, AppVersion.LABEL)
        val creditW = Fonts.textWidth(Fonts.small, L10n.get(CREDIT_KEY))
        return max(versionW, creditW).coerceAtMost(availWidth)
    }

    /** Height of the block written [width] wide (the attribution may wrap there). */
    private fun blockHeight(width: Float): Float {
        val creditH = creditHeight(wrapCredit(width))
        val versionH = Fonts.textHeight(Fonts.body, AppVersion.LABEL)
        return versionH + (if (creditH > 0f) Fonts.px(GAP_DP) + creditH else 0f)
    }

    private fun wrapCredit(width: Float): List<String> {
        val text = L10n.get(CREDIT_KEY)
        if (text.isEmpty() || width <= 0f) return emptyList()
        // Measure the whole line first: the room is the question, not the sum of
        // word advances, so a line that fits is never broken by a rounding or
        // kerning delta between the two measurements.
        if (Fonts.textWidth(Fonts.small, text) <= width) return listOf(text)
        return LineBreaker.wrap(text, width) { Fonts.textWidth(Fonts.small, it) }
            .split('\n')
            .filter { it.isNotEmpty() }
    }

    private fun creditHeight(lines: List<String>): Float =
        lines.fold(0f) { height, line -> height + Fonts.textHeight(Fonts.small, line) }

    /** One stamp line, ending at the block's right edge. */
    private fun drawLine(
        batch: SpriteBatch,
        font: BitmapFont,
        text: String,
        rect: Rect,
        top: Float,
    ) {
        val left = max(rect.left, rect.right - Fonts.textWidth(font, text))
        Fonts.drawText(batch, font, text, left, top, ink)
    }
}
