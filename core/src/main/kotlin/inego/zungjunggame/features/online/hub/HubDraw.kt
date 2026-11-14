package inego.zungjunggame.features.online.hub

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.Hyperlink
import inego.zungjunggame.render.ui.OverlayChrome
import inego.zungjunggame.ui.Rect
import kotlin.math.max
import inego.zungjunggame.render.ui.AppPalette

/**
 * The drawing primitives both hub tab panels share: the filled button plate, the
 * labelled cycle/toggle row, a standalone body link, and wrapped text.
 *
 * One implementation each, because [LiveTabPanel] and [ShadowRankedTabPanel] are
 * two bodies of one card and their controls must be pixel-identical. A panel that
 * needs a new control shape adds it here rather than growing a private variant.
 */
internal object HubDraw {
    private val glyph = GlyphLayout()

    fun borderPx(): Float = OverlayChrome.borderPx()

    /**
     * The ink of a body's own prose: a status line and a note.
     *
     * One value, because [LiveTabPanel] and [ShadowRankedTabPanel] are two bodies
     * of one card: a sentence that appears in both must not arrive in two
     * colours, and a body that wants "normal text" has exactly one answer. Error
     * and pending states are the exception and keep their own inks — they are
     * saying something the prose is not.
     */
    val bodyInk = AppPalette.inkMuted

    /**
     * The hub's go-green ink: the label of a control that returns the player to a
     * live table they are already seated at.
     *
     * Rejoin is the one control that leaves the hub for something the player
     * already owns, so it reads as the affirmative — the felt's own green, one
     * step brighter than the hover plate tint it sits on. The hover ink is the
     * same hue lifted, so the label stays green across the hover state instead of
     * reverting to ivory and looking like a different control.
     */
    private val goGreen = AppPalette.onlineInk
    private val goGreenHovered = AppPalette.onlineInkHover

    /**
     * The go-green label ink, for a control that leaves the hub for something the
     * player already owns. Handed to [drawButtonRow] as its `accent`.
     */
    val goGreenInk: (hovered: Boolean) -> Color = { hovered ->
        if (hovered) goGreenHovered else goGreen
    }

    /**
     * A filled, rimmed, centred-label button.
     *
     * [accent] overrides the label ink for a control that carries its own emphasis
     * (see [goGreen]): it is handed the hover state so the accent can answer it,
     * and null keeps the state-driven default, so every existing caller is
     * unchanged. It is consulted for the enabled states only — a disabled control
     * keeps its dimmed ink, because "you cannot press this" is the message.
     */
    fun drawButtonRow(
        batch: SpriteBatch,
        px: Texture,
        font: BitmapFont,
        label: String,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        border: Float,
        pointerX: Float,
        pointerY: Float,
        enabled: Boolean = true,
        selected: Boolean = false,
        accent: ((hovered: Boolean) -> Color)? = null,
    ) {
        val hovered = enabled && !pointerX.isNaN() && !pointerY.isNaN() &&
            pointerX >= left && pointerX <= left + w &&
            pointerY >= bottom && pointerY <= bottom + h
        val bg = when {
            !enabled -> AppPalette.tint(AppPalette.panel, 0.85f)
            selected -> AppPalette.plateSelected
            hovered -> AppPalette.plateHover
            else -> AppPalette.plate
        }
        val rim = when {
            !enabled -> AppPalette.rimInert
            selected -> AppPalette.tint(AppPalette.accent, 0.85f)
            else -> OverlayChrome.rowRim()
        }
        val ink = when {
            !enabled -> AppPalette.tint(AppPalette.inkInert, 0.6f)
            accent != null -> accent(hovered)
            selected -> AppPalette.inkStrong
            hovered -> AppPalette.inkStrong
            else -> AppPalette.ink
        }
        OverlayChrome.drawButton(
            batch, px, font, label,
            left, bottom, w, h, bg, rim, ink, border,
        )
    }

    /**
     * Width of a filled button that hugs its label: the ink plus
     * [BUTTON_SIDE_PAD_PX] on each side, capped to [maxW].
     *
     * Measured on the face the plate draws with ([font]) and through the same
     * [glyph] layout [OverlayChrome.drawButton] fits, so a plate this wide never
     * shrinks its own label — the air at each end is the design's, not what is
     * left after a fit.
     */
    fun buttonWidth(font: BitmapFont, label: String, maxW: Float): Float {
        glyph.setText(font, label)
        return (glyph.width + Fonts.px(BUTTON_SIDE_PAD_PX) * 2f).coerceAtMost(maxW)
    }

    /** Air on each side of a hugging button's label, in design px. */
    private const val BUTTON_SIDE_PAD_PX = 24f

    /**
     * A labelled row whose value is a single cycled/toggled choice.
     *
     * The label is left-aligned and the value right-aligned in the same rect, so
     * the two compete for one measure. They are budgeted before either is drawn:
     * the label takes what it needs, the value is given what is left, and the
     * label is shrunk ([Fonts.fitScale]) so a pair that cannot both fit at full
     * size still separates by [CYCLE_GAP_PX] instead of overprinting. Without
     * that, a row is legible only in the locale the layout was written in — the
     * label keeps its width and the value is drawn straight through it.
     */
    fun drawCycleRow(
        batch: SpriteBatch,
        px: Texture,
        font: BitmapFont,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        t: Float,
        label: String,
        value: String,
        hovered: Boolean,
        muted: Boolean = false,
    ) {
        val bg = when {
            muted -> AppPalette.plateMuted
            hovered -> AppPalette.tint(AppPalette.plateSelected, 0.90f)
            else -> AppPalette.tint(AppPalette.plateDeep, 0.75f)
        }
        val rim = when {
            muted -> AppPalette.tint(AppPalette.inkInert, 0.22f)
            hovered -> AppPalette.tint(AppPalette.rim, 0.60f)
            else -> AppPalette.tint(AppPalette.rim, 0.30f)
        }
        OverlayChrome.drawPanel(batch, px, left, bottom, w, h, bg, rim, t)

        val textY = bottom + h * 0.5f + font.capHeight * 0.5f
        val inset = Fonts.px(12f)
        val inner = (w - inset * 2f).coerceAtLeast(1f)
        val gap = Fonts.px(CYCLE_GAP_PX)

        glyph.setText(font, value)
        val valueW = glyph.width

        // A row with no label is a segmented option: the value is the whole
        // content and owns the plate, so it is centred and never fitted.
        if (label.isEmpty()) {
            font.color = when {
                muted -> AppPalette.inkMuted
                hovered -> AppPalette.inkStrong
                else -> AppPalette.inkSoft
            }
            font.draw(batch, value, left + (w - valueW) * 0.5f, textY)
            font.color = Color.WHITE
            return
        }

        // Both strings are measured at the natural face, and the value's width is
        // subtracted from the measure to leave the label a budget.
        glyph.setText(font, label)
        val labelW = glyph.width
        val labelScale =
            if (valueW >= inner) 1f
            else Fonts.fitScale(labelW, (inner - valueW - gap).coerceAtLeast(1f))

        font.color = if (muted) AppPalette.inkInert else AppPalette.inkMuted
        Fonts.withFaceScale(font, labelScale) {
            glyph.setText(font, label)
            font.draw(batch, label, left + inset, textY)
        }
        font.color = when {
            muted -> AppPalette.inkMuted
            hovered -> AppPalette.inkStrong
            else -> AppPalette.inkSoft
        }
        // Measured at the natural scale: the value is never shrunk, because doing
        // so would hide the choice the row exists to show.
        font.draw(batch, value, left + w - inset - valueW, textY)
        font.color = Color.WHITE
    }

    /** Air between a cycle row's label and its value, in design px. */
    private const val CYCLE_GAP_PX = 16f

    /**
     * Target width of a standalone body link: its ink plus the inset
     * [Hyperlink.drawCentered] reserves on both sides, so the face is never
     * shrunk by its own target.
     */
    fun linkWidth(label: String): Float = Hyperlink.width(label) + Fonts.px(16f)

    /** Height of a standalone body link's row; at least finger-sized. */
    fun linkRowHeight(font: BitmapFont): Float =
        max(Fonts.px(40f), font.lineHeight + Fonts.px(12f))

    /**
     * Paint a standalone body link into the rect its panel published: the ink,
     * the hover lift and the rule are [Hyperlink]'s, so a body link and the
     * header's Leaderboard link are the same affordance.
     */
    fun drawLink(
        batch: SpriteBatch,
        px: Texture,
        label: String,
        rect: Rect,
        hovered: Boolean,
    ) {
        Hyperlink.drawCentered(
            batch = batch,
            whitePixel = px,
            label = label,
            rect = rect,
            hovered = hovered,
        )
    }

    /** Height [text] occupies when wrapped to [width]. */
    fun measureWrapped(font: BitmapFont, text: String, width: Float): Float {
        if (text.isEmpty()) return 0f
        glyph.setText(font, text, Color.WHITE, width, Align.left, true)
        return glyph.height
    }

    /** Paints [text] wrapped to [width], left-aligned at [left]; returns its height. */
    fun drawWrapped(
        batch: SpriteBatch,
        font: BitmapFont,
        text: String,
        left: Float,
        baseline: Float,
        width: Float,
        color: Color,
    ): Float {
        if (text.isEmpty()) return 0f
        glyph.setText(font, text, color, width, Align.left, true)
        font.color = color
        font.draw(batch, text, left, baseline, width, Align.left, true)
        font.color = Color.WHITE
        return glyph.height
    }
}
