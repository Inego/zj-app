package inego.zungjunggame.render.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.render.fonts.Fonts
import kotlin.math.max

/**
 * Shared chrome for overlay cards and their controls: a filled plate with a
 * four-edge rim, optionally carrying a centred label.
 *
 * Plate geometry and thickness live here, so every overlay's rim is
 * pixel-identical. A control that is not a standard tappable row still passes
 * its own colours. The tappable-row plate is the exception: every such row is
 * the same fill and rim, so those colours live here. Hover changes the fill
 * only. The rim stays the resting sage, and accent marks a selected row.
 *
 * A control that cannot be pressed this frame is *inert*, and inert always looks
 * the same: a disabled row and a control still working are both saying "this
 * does nothing right now", and a second grey would suggest a second reason.
 *
 * The returned colours are shared instances. Callers must not mutate them.
 */
object OverlayChrome {
    private val rowRestFill = AppPalette.plate
    private val rowHoverFill = AppPalette.plateHover
    private val rowSelectedFill = AppPalette.plateSelected
    private val rowSelectedHoverFill = AppPalette.plateSelectedHover
    private val rowRestRim = AppPalette.rim
    private val rowSelectedRim = AppPalette.tint(AppPalette.accent, 0.95f)
    private val actionSecondaryRestFill = AppPalette.plateDeep
    private val actionSecondaryHoverFill = AppPalette.plateSelected
    private val inertPlate = AppPalette.plateMuted
    private val inertBorder = AppPalette.tint(AppPalette.inkInert, 0.22f)
    private val inertText = AppPalette.inkInert

    /** Plate of a control that cannot be pressed this frame. */
    fun inertFill(): Color = inertPlate

    /** Rim of a control that cannot be pressed this frame. */
    fun inertRim(): Color = inertBorder

    /** Label ink of a control that cannot be pressed this frame. */
    fun inertInk(): Color = inertText

    /**
     * Fill of a standard tappable row. [hovered] and [selected] are the only
     * states that change it.
     */
    fun rowFill(hovered: Boolean, selected: Boolean = false): Color = when {
        selected && hovered -> rowSelectedHoverFill
        selected -> rowSelectedFill
        hovered -> rowHoverFill
        else -> rowRestFill
    }

    /**
     * Rim of a standard tappable row. Hover does not change it. Accent is
     * selection only.
     */
    fun rowRim(selected: Boolean = false): Color = if (selected) rowSelectedRim else rowRestRim

    /**
     * Fill of a standard overlay action button. [primary] keeps the lighter
     * resting plate; hover is green for both and never a warm brown.
     */
    fun actionFill(hovered: Boolean, primary: Boolean = false): Color = when {
        hovered && primary -> rowHoverFill
        hovered -> actionSecondaryHoverFill
        primary -> rowRestFill
        else -> actionSecondaryRestFill
    }

    /** Rim of a standard overlay action button. Hover does not change it. */
    fun actionRim(): Color = rowRestRim

    /** Panel rim colour used by every overlay card. */
    fun panelRim(): Color = AppPalette.panelRim

    /** Standard panel border thickness. */
    fun borderPx(): Float = max(1.5f, Fonts.px(2f))

    /** Standard button rim thickness. */
    fun buttonRimPx(): Float = max(1.2f, Fonts.px(1.5f))

    /** Air between a fitted label and the button rim, in design px. */
    private const val LABEL_PAD_PX = 6f

    /** Fills [left,bottom,w,h] with [fill], strokes a four-edge rim in [rim]; leaves batch colour WHITE. */
    fun drawPanel(
        batch: SpriteBatch,
        px: Texture,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        fill: Color,
        rim: Color = panelRim(),
        t: Float = borderPx(),
    ) {
        UiPlate.drawPlate(batch, px, left, bottom, w, h, fill, rim, t)
    }

    /**
     * Fills with [fill], strokes a four-edge rim in [rim], draws [label] centered in
     * the rect using [font]/[glyph] tinted [labelColor]; leaves batch colour and
     * font colour WHITE.
     *
     * The label is **fitted to the plate**: a translation wider than the button gets
     * a uniformly smaller face ([Fonts.fitScale], floored at
     * [Fonts.MIN_FIT_SCALE]) instead of spilling past the rim. This is the fit
     * contract every button shares, so it lives here rather than at the call site —
     * a site that lays out to the source-language string cannot know how long its
     * translation will be, and there are sixteen locales to be wrong about. Peers
     * that already fit (the hub's header link) do so because they are text, not
     * plates, and draw their own.
     *
     * The fit measures [label] at the face's natural scale, then re-measures inside
     * [Fonts.withFaceScale], so the drawn width is the one the centering used. A
     * label that still overflows at the floor is left overflowing on purpose:
     * shrinking past [Fonts.MIN_FIT_SCALE] would trade legibility for nothing.
     */
    fun drawButton(
        batch: SpriteBatch,
        px: Texture,
        font: BitmapFont,
        glyph: GlyphLayout,
        label: String,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        fill: Color,
        rim: Color,
        labelColor: Color,
        t: Float = buttonRimPx(),
    ) {
        UiPlate.drawPlate(batch, px, left, bottom, w, h, fill, rim, t)

        if (label.isEmpty()) return

        // Clear the rim plus a little air, so a fitted label never sits on the edge.
        val inset = t + Fonts.px(LABEL_PAD_PX)
        val available = (w - inset * 2f).coerceAtLeast(1f)
        glyph.setText(font, label)
        val scale = Fonts.fitScale(glyph.width, available)
        Fonts.withFaceScale(font, scale) {
            glyph.setText(font, label)
            font.color = labelColor
            font.draw(
                batch,
                label,
                left + (w - glyph.width) * 0.5f,
                bottom + h * 0.5f + glyph.height * 0.5f,
            )
            font.color = Color.WHITE
        }
    }
}
