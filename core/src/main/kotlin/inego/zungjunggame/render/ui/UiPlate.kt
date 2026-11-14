package inego.zungjunggame.render.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import kotlin.math.min

/**
 * The app's one rectangle idiom: a solid fill and an axis-aligned frame, both
 * drawn as white-pixel quads.
 *
 * Every panel, button, checkbox, chip, track and rim in the app is this pair of
 * shapes, so this object — not each caller — owns the decomposition.
 *
 * **The frame is mitred.** The two horizontal edges span the full width and the
 * two vertical edges span the inner height, so the `t` x `t` square at each
 * corner is covered exactly once. Emitting four full-span edges instead — which
 * is what this replaced, at nineteen call sites — overlaps that corner square,
 * and a translucent colour composited twice reads as `1 - (1 - a)^2` rather than
 * `a`. At the overlay panel rim's `a = 0.55` that is 0.80, which is why card
 * corners read brighter than the edges around them. The same mistake made with
 * the two spans pulled in by the rim thickness instead leaves the corners
 * unpainted; both are the same missing ownership.
 *
 * [drawFrame] clamps [t] to half the shorter side. A quad with a negative extent
 * is emitted flipped and spills outside the requested rect, so an over-thick rim
 * has to shrink rather than invert.
 *
 * Neither function leaves the batch colour set: each ends on [Color.WHITE].
 */
object UiPlate {
    /** Fills the rect with [fill], then mitres a [rimT]-thick frame in [rim] around it. */
    fun drawPlate(
        batch: SpriteBatch,
        px: Texture,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        fill: Color,
        rim: Color,
        rimT: Float,
    ) {
        if (w <= 0f || h <= 0f) return
        batch.setColor(fill)
        batch.draw(px, left, bottom, w, h)
        drawFrame(batch, px, left, bottom, w, h, rim, rimT)
    }

    /** Mitres a [rimT]-thick frame in [color] around the rect, painting no interior. */
    fun drawFrame(
        batch: SpriteBatch,
        px: Texture,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        color: Color,
        rimT: Float,
    ) = drawFrame(batch, px, left, bottom, w, h, color.r, color.g, color.b, color.a, rimT)

    /** [drawFrame] taking components, for palettes that are already unpacked. */
    fun drawFrame(
        batch: SpriteBatch,
        px: Texture,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        rimT: Float,
    ) {
        if (w <= 0f || h <= 0f) return
        val t = rimT.coerceIn(0f, min(w, h) * 0.5f)
        if (t <= 0f) return
        batch.setColor(r, g, b, a)
        val right = left + w
        val top = bottom + h
        // Horizontals take the corners; the verticals start below the bottom edge
        // so no pixel is painted twice.
        batch.draw(px, left, bottom, w, t)
        batch.draw(px, left, top - t, w, t)
        val innerH = h - t * 2f
        if (innerH > 0f) {
            batch.draw(px, left, bottom + t, t, innerH)
            batch.draw(px, right - t, bottom + t, t, innerH)
        }
        batch.setColor(Color.WHITE)
    }
}