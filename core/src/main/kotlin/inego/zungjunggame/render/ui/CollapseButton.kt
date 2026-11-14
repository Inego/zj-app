package inego.zungjunggame.render.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.render.fonts.Fonts
import kotlin.math.max

/**
 * Square corner chrome that folds a panel body closed or open.
 *
 * Same plate language as [CloseButton] (fill + rim + Lucide glyph). Expanded
 * shows chevron-up (collapse); collapsed shows chevron-down (expand).
 *
 * Call a layout method each frame, then [render] with the pointer for desktop
 * hover. Hit-test via [contains].
 */
class CollapseButton {
    private var left = 0f
    private var bottom = 0f
    private var size = 0f

    /** Bottom edge after the last layout call. */
    val bottomY: Float
        get() = bottom

    /** Side length of the hit/draw square after the last layout call. */
    val side: Float
        get() = size

    /**
     * Place at an absolute world rect (same size as [CloseButton.defaultSide]
     * unless [side] is passed). Use to vertically center on a thin collapsed bar.
     */
    fun layoutAt(left: Float, bottom: Float, side: Float = CloseButton.defaultSide()) {
        this.left = left
        this.bottom = bottom
        this.size = side
    }

    fun contains(worldX: Float, worldY: Float): Boolean =
        size > 0f &&
            worldX >= left && worldX <= left + size &&
            worldY >= bottom && worldY <= bottom + size

    /**
     * @param expanded true when the panel body is showing (glyph = chevron-up)
     * @param enabled when false, draws dimmed; hosts should ignore activation
     * @param pressed host-driven grab/press (whole collapsed bar held) — same
     *   plate as pointer hover so the chrome matches the movable card
     */
    fun render(
        batch: SpriteBatch,
        whitePixel: Texture,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        expanded: Boolean = true,
        enabled: Boolean = true,
        pressed: Boolean = false,
    ) {
        if (size <= 1f) return

        val hovered =
            enabled &&
                (
                    pressed ||
                        (
                            !pointerWorldX.isNaN() &&
                                !pointerWorldY.isNaN() &&
                                contains(pointerWorldX, pointerWorldY)
                            )
                    )

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
        val icon = if (expanded) UiIcons.chevronUp else UiIcons.chevronDown
        val pad = size * 0.20f
        val iconSize = (size - pad * 2f).coerceAtLeast(1f)
        batch.setColor(ink)
        batch.draw(icon, left + pad, bottom + pad, iconSize, iconSize)
        batch.setColor(Color.WHITE)
    }
}
