package inego.zungjunggame.render.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.render.fonts.Fonts
import kotlin.math.max

/**
 * Square corner chrome for panels and full-screen modes.
 *
 * Typical uses:
 * - **×** close ([Style.Close]) via [layoutTopRight] — exit / dismiss
 * - **←** back ([Style.Back]) — history back (place with [layoutAt] beside close)
 * - **☰** menu ([Style.Menu]) — open the game menu from board chrome
 *
 * Icons are Lucide outline glyphs from [UiIcons] (white PNGs, tinted for state).
 * Button plate (fill + rim) stays procedural so size scales with density without
 * a nine-patch.
 *
 * Call a layout method each frame (or when the host resizes), then [render]
 * with the pointer for desktop hover. Hit-test via [contains].
 *
 * All hosts should use [cornerInset] so the button sits at the same distance
 * from the panel corner.
 */
class CloseButton(
    private val style: Style = Style.Close,
) {
    private var left = 0f
    private var bottom = 0f
    private var size = 0f

    /** Left edge after the last layout call. */
    val leftX: Float
        get() = left

    /** Bottom edge after the last layout call. */
    val bottomY: Float
        get() = bottom

    /** Side length of the hit/draw square after the last layout call. */
    val side: Float
        get() = size

    /**
     * Place the button in the top-right of a panel.
     * Defaults to the shared [cornerInset] so every overlay matches.
     */
    fun layoutTopRight(
        panelLeft: Float,
        panelBottom: Float,
        panelW: Float,
        panelH: Float,
        pad: Float = cornerInset(),
    ) {
        Fonts.ensureCreated()
        size = defaultSide()
        left = panelLeft + panelW - pad - size
        bottom = panelBottom + panelH - pad - size
    }

    /**
     * Place at an absolute world rect (same size as [defaultSide] unless [side]
     * is passed). Use to put a second chrome control beside [layoutTopRight].
     */
    fun layoutAt(left: Float, bottom: Float, side: Float = defaultSide()) {
        this.left = left
        this.bottom = bottom
        this.size = side
    }

    fun contains(worldX: Float, worldY: Float): Boolean =
        size > 0f &&
            worldX >= left && worldX <= left + size &&
            worldY >= bottom && worldY <= bottom + size

    /**
     * @param enabled when false, draws dimmed; hosts should ignore activation
     */
    fun render(
        batch: SpriteBatch,
        whitePixel: Texture,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        enabled: Boolean = true,
    ) {
        if (size <= 1f) return

        val hovered =
            enabled &&
                !pointerWorldX.isNaN() &&
                !pointerWorldY.isNaN() &&
                contains(pointerWorldX, pointerWorldY)

        val bg = when {
            !enabled -> AppPalette.tint(AppPalette.plateDarker, 0.55f)
            else -> OverlayChrome.rowFill(hovered)
        }
        val rim = when {
            !enabled -> AppPalette.tint(AppPalette.inkInert, 0.22f)
            else -> OverlayChrome.rowRim()
        }
        UiPlate.drawPlate(batch, whitePixel, left, bottom, size, size, bg, rim, max(1.2f, Fonts.px(1.5f)))

        val ink = when {
            !enabled -> AppPalette.tint(AppPalette.inkInert, 0.45f)
            hovered -> AppPalette.inkStrong
            else -> AppPalette.inkSoft
        }
        val icon = when (style) {
            Style.Close -> UiIcons.close
            Style.Back -> UiIcons.back
            Style.Menu -> UiIcons.menu
        }
        drawTintedIcon(batch, icon, ink)
    }

    private fun drawTintedIcon(batch: SpriteBatch, icon: Texture, ink: Color) {
        // Lucide 24px glyphs already include a small margin; extra pad keeps the
        // stroke clear of the rim at density-scaled sizes.
        val pad = size * 0.20f
        val iconSize = (size - pad * 2f).coerceAtLeast(1f)
        batch.setColor(ink)
        batch.draw(icon, left + pad, bottom + pad, iconSize, iconSize)
        batch.setColor(Color.WHITE)
    }

    enum class Style {
        /** Lucide `x` — dismiss / close. */
        Close,

        /** Lucide `arrow-left` — history back / return. */
        Back,

        /** Lucide `menu` — open the game menu (Esc / system-back equivalent). */
        Menu,
    }

    companion object {
        /**
         * Fixed density inset from panel edges.
         * Do not derive this from panel width — that makes wide windows look
         * like the button is floating mid-header (score table regression).
         */
        fun cornerInset(): Float {
            Fonts.ensureCreated()
            return Fonts.px(14f)
        }

        /** Shared side length for corner chrome controls. */
        fun defaultSide(): Float {
            Fonts.ensureCreated()
            return max(Fonts.px(36f), Fonts.button.lineHeight + Fonts.px(14f))
        }

        /** Gap between two adjacent corner controls (history back + exit). */
        fun siblingGap(): Float {
            Fonts.ensureCreated()
            return Fonts.px(8f)
        }
    }
}
