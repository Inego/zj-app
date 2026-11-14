package inego.zungjunggame.render.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.render.fonts.Fonts
import kotlin.math.hypot
import kotlin.math.max

/**
 * Stretchable white rounded-rect (tile-like corners) for HINT / hand-end chips.
 * Tint via [com.badlogic.gdx.graphics.g2d.SpriteBatch.setColor]; dispose texture with the patch.
 *
 * Texture pads are [CHIP_PATCH_PAD] px; construction scales them to a density-aware
 * world size so desktop chips (~28px tall) are not crushed by 20+20 corners.
 */
class UiChip : Disposable {
    private val patch: NinePatch = createPatch()

    init {
        Fonts.ensureCreated()
        // NinePatch left/right/top/bottom are world units (= texture px at scale 1).
        // Desktop HINT is ~28px tall; unscaled pad 20+20 leaves a negative center.
        val cornerWorld = Fonts.px(CHIP_CORNER_DESIGN_PX)
        val s = cornerWorld / CHIP_PATCH_PAD
        patch.scale(s, s)
    }

    /**
     * Shared HINT / hand-end chrome: shadow, rim, fill, optional top sheen.
     * Sheen is omitted when the strip is shorter than the nine-patch corners
     * (avoids the same collapse that broke desktop chips).
     */
    fun drawChrome(
        batch: SpriteBatch,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        bg: Color,
        border: Color,
        borderT: Float,
        shadow: Float,
        sheenAlpha: Float,
    ) {
        batch.setColor(AppPalette.tint(AppPalette.shadow, 0.28f))
        patch.draw(batch, left + shadow * 0.4f, bottom - shadow, w, h)

        batch.setColor(border)
        patch.draw(batch, left, bottom, w, h)

        batch.setColor(bg)
        patch.draw(
            batch,
            left + borderT,
            bottom + borderT,
            w - borderT * 2f,
            h - borderT * 2f,
        )

        val sheenH = h * 0.38f
        val minSheenH = patch.topHeight + patch.bottomHeight + 1f
        if (sheenH > minSheenH) {
            batch.setColor(AppPalette.tint(AppPalette.sheen, sheenAlpha))
            patch.draw(
                batch,
                left + borderT + borderT * 0.5f,
                bottom + h - borderT - sheenH,
                w - borderT * 2f - borderT,
                sheenH,
            )
        }
        batch.setColor(Color.WHITE)
    }

    override fun dispose() {
        patch.texture.dispose()
    }

    companion object {
        /** Texture-space corner / nine-patch split (see [createPatch]). */
        private const val CHIP_PATCH_PAD = 20

        /**
         * Design-time corner radius in density-1 pixels. Must stay small enough
         * that 2×corners fit inside the shortest chip (desktop ~28px) and its
         * inset fill; Android grows this with [Fonts.uiScale].
         */
        private const val CHIP_CORNER_DESIGN_PX = 7f

        /**
         * Soft AA rounded rectangle as a [NinePatch] so it scales cleanly
         * while keeping mahjong-tile-style corners.
         */
        private fun createPatch(): NinePatch {
            val size = 64
            val radius = CHIP_PATCH_PAD
            val pm = Pixmap(size, size, Pixmap.Format.RGBA8888)
            pm.setBlending(Pixmap.Blending.None)
            pm.setColor(Color.CLEAR)
            pm.fill()
            val r = radius.toFloat()
            val maxX = (size - 1).toFloat()
            val maxY = (size - 1).toFloat()
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val fx = x.toFloat()
                    val fy = y.toFloat()
                    val cx = when {
                        fx < r -> r
                        fx > maxX - r -> maxX - r
                        else -> fx
                    }
                    val cy = when {
                        fy < r -> r
                        fy > maxY - r -> maxY - r
                        else -> fy
                    }
                    val dist = hypot(fx - cx, fy - cy)
                    // Soft 1px AA rim so corners do not stair-step when scaled.
                    val a = (1f - (dist - r + 0.5f)).coerceIn(0f, 1f)
                    if (a <= 0.001f) continue
                    pm.drawPixel(x, y, Color.rgba8888(1f, 1f, 1f, a))
                }
            }
            val tex = Texture(pm)
            tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            pm.dispose()
            return NinePatch(tex, radius, radius, radius, radius)
        }

        /** Standard border thickness for chips of height [h]. */
        fun borderThickness(h: Float, emphasized: Boolean = false): Float =
            max(Fonts.px(if (emphasized) 2.4f else 1.8f), h * 0.06f)
    }
}
