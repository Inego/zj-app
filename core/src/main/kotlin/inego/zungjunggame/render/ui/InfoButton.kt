package inego.zungjunggame.render.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.render.fonts.Fonts

/**
 * Rimless **info** control: Lucide `i` in a circle ([UiIcons.info]), no plate rim.
 *
 * Intended as a lightweight deep-link / help affordance next to labels and
 * options (e.g. New Game setup → Reference). Hosts [layoutAt] each frame,
 * [render] with the pointer for desktop hover, and hit-test via [contains].
 *
 * Visual language: glyph-first. Idle is icon only; hover uses a soft fill
 * **without** a border so it stays distinct from rimmed chrome ([CloseButton]).
 */
class InfoButton {
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
     * Place at an absolute world rect.
     * @param side defaults to [defaultSide] (same density scale as corner chrome).
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
     * @param whitePixel 1×1 white texture used only for the optional hover fill
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

        // Rimless: soft fill on hover/disabled only — never a border stroke.
        if (hovered || !enabled) {
            val bg = when {
                !enabled -> AppPalette.tint(AppPalette.plateDarker, 0.35f)
                else -> AppPalette.tint(AppPalette.controlHover, 0.55f)
            }
            batch.setColor(bg)
            batch.draw(whitePixel, left, bottom, size, size)
            batch.setColor(Color.WHITE)
        }

        val ink = when {
            !enabled -> AppPalette.tint(AppPalette.inkInert, 0.40f)
            hovered -> AppPalette.inkStrong
            else -> AppPalette.tint(AppPalette.inkWarm, 0.92f)
        }
        // Lucide 24px glyphs already include a small margin; keep a light pad so
        // the stroke does not touch the hit-box edge at density-scaled sizes.
        val pad = size * 0.18f
        val iconSize = (size - pad * 2f).coerceAtLeast(1f)
        batch.setColor(ink)
        batch.draw(UiIcons.info, left + pad, bottom + pad, iconSize, iconSize)
        batch.setColor(Color.WHITE)
    }

    companion object {
        /** Shared side length for info controls (matches [CloseButton.defaultSide]). */
        fun defaultSide(): Float {
            Fonts.ensureCreated()
            return maxOf(Fonts.px(36f), Fonts.button.lineHeight + Fonts.px(14f))
        }

        /** Gap between a label/row and an adjacent info control. */
        fun siblingGap(): Float {
            Fonts.ensureCreated()
            return Fonts.px(10f)
        }
    }
}
