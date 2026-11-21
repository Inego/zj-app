package inego.zungjunggame.render.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.platform.UiCursor
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.ui.Rect
import kotlin.math.max

/**
 * A hyperlink: a run of text in the link ink with a rule under its true
 * baseline — the UI's one way of saying "this is a way out" instead of another
 * rimmed plate. The hub's header **Leaderboard**, the rating beside it, both
 * tabs' **Past games** / **Past attempts** links, and the leaderboard card's **Top** and **Me** are
 * this widget.
 *
 * ### The face belongs to the widget
 *
 * [Fonts.button] — baked **white**. A [BitmapFont] here is rasterised with its
 * own ink in the atlas, and setting a colour tints by *multiplying* that baked
 * ink, so a caller-chosen face cannot be trusted to reach the link ink. The
 * section links used to be drawn in 20px [Fonts.label], baked khaki, which
 * turned the sky blue into (0.55·0.72, 0.82·0.68, 1.0·0.48) — a muddy
 * grey-green beside the white-baked Leaderboard link. Owning the face is what
 * makes "a link looks like a link" true in every row.
 *
 * ### What a host supplies, and why
 *
 * A link is *text on someone else's line*, so placement stays with the host:
 * the header link is centred in the touch target it owns ([drawCentered]),
 * while a section link sits at its row's right edge on the row's own baseline
 * ([draw]). Hover is handed in for the same reason — the host's published hit
 * region is the only source of truth for what the finger is on, and two rects
 * (one for the hit test, one derived here for the ink) would drift apart.
 * [enabled] false paints [disabledInk] and ignores hover. The host still owns
 * the hit region and decides whether a press does anything.
 *
 * The widget also owns the desktop **hand cursor** for the same hover value, so
 * every host that paints a link gets the link pointer without repeating the
 * cursor policy. Touch backends no-op through [UiCursor].
 *
 * ### The rule
 *
 * `BitmapFont.draw`'s Y is a *cap-height top*, so the true baseline is one
 * [BitmapFont.capHeight] below it; the stroke hangs a quarter of the face's
 * descent under that baseline — the same derivation
 * [inego.zungjunggame.render.richtext.RichTextRenderer] uses for markdown
 * links, so a link reads identically wherever it is a link.
 */
object Hyperlink {

    /** Idle ink: the palette's soft sky-blue, distinct from every plate's ivory. */
    val ink: Color = AppPalette.linkInk

    /** Hover ink: the same hue lifted, so the lift is visible without recolouring. */
    val hoverInk: Color = AppPalette.infoInkSoft

    /**
     * Disabled ink: the idle hue, quieted. The rule stays, so a link that cannot
     * be taken still reads as a link.
     */
    val disabledInk: Color = AppPalette.tint(AppPalette.info, 0.4f)

    /**
     * Ink for a link. A disabled link ignores [hovered]: the widget refuses to
     * look live, whatever the host hands in.
     */
    fun inkFor(hovered: Boolean, enabled: Boolean = true): Color = when {
        !enabled -> disabledInk
        hovered -> hoverInk
        else -> ink
    }

    /**
     * The link face. Lazily fetched because [Fonts.button] is `lateinit`; every
     * entry point goes through here so a link can never be drawn on a face whose
     * baked ink would filter the link colour.
     */
    private fun face(): BitmapFont {
        Fonts.ensureCreated()
        return Fonts.button
    }

    /** Width of [label], through the fallback-aware measure. */
    fun width(label: String): Float = Fonts.textWidth(face(), label)

    /**
     * Paint [label] left-aligned at [x] sitting on [baseline] (the y-up true
     * baseline, *not* a draw Y).
     */
    fun draw(
        batch: SpriteBatch,
        whitePixel: Texture,
        label: String,
        x: Float,
        baseline: Float,
        hovered: Boolean,
        enabled: Boolean = true,
    ) {
        val font = face()
        // The link's hover is also its cursor: one owner, so a host cannot paint
        // the lift without raising the hand.
        UiCursor.overLink(hovered && enabled)
        val paint = inkFor(hovered, enabled)
        val textW = width(label)
        Fonts.drawText(batch, font, label, x, baseline + font.capHeight, paint)

        val thick = ruleThickness(font)
        val gap = ruleGap(font)
        batch.setColor(paint)
        batch.draw(whitePixel, x, baseline - gap - thick, textW, thick)
        batch.setColor(Color.WHITE)
    }

    /**
     * How far below a link's baseline the widget paints: the rule, or a
     * descender when the word has one that hangs lower.
     *
     * Hosts publishing a link as a tap target size it from here. A target that
     * stops at its band's bottom cuts through the middle of the word, and the
     * link then feels dead to anything arriving from below.
     */
    fun inkDepth(): Float {
        val font = face()
        return max(-font.data.descent, ruleGap(font) + ruleThickness(font))
    }

    /** Air between the baseline and the rule's upper edge. */
    private fun ruleGap(font: BitmapFont): Float = max(1f, -font.data.descent * 0.25f)

    /** The rule's own weight. */
    private fun ruleThickness(font: BitmapFont): Float = max(1f, font.lineHeight * 0.06f)

    /**
     * Paint [label] centred in [rect], fitted so a longer translation shrinks
     * its face instead of spilling out of its own target. The link is centred
     * by its cap box, so the ink — rule included — sits in the middle of the
     * rect.
     */
    fun drawCentered(
        batch: SpriteBatch,
        whitePixel: Texture,
        label: String,
        rect: Rect,
        hovered: Boolean,
        enabled: Boolean = true,
    ) {
        val font = face()
        val inset = Fonts.px(8f)
        val available = (rect.width - inset * 2f).coerceAtLeast(1f)
        Fonts.withFaceScale(font, Fonts.fitScale(width(label), available)) {
            val textW = width(label)
            draw(
                batch = batch,
                whitePixel = whitePixel,
                label = label,
                x = rect.left + (rect.width - textW) * 0.5f,
                baseline = rect.centerY - font.capHeight * 0.5f,
                hovered = hovered,
                enabled = enabled,
            )
        }
    }
}
