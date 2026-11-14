package inego.zungjunggame.ui

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.graphics.g2d.TextureRegion

/** Lightweight handle to a bitmap font for [DrawOp.Text]. */
@JvmInline
value class FontRef(val font: BitmapFont)

/** Lightweight handle to a nine-patch for [DrawOp.NinePatch]. */
@JvmInline
value class PatchRef(val patch: NinePatch)

/** Lightweight handle to a texture region for [DrawOp.Texture]. */
@JvmInline
value class TextureRef(val region: TextureRegion)

/**
 * Minimal draw vocabulary a component's [Layout] publishes for [Canvas] to execute.
 *
 * Keeping paint as data lets `draw` and `hit` read the same frame model, so a
 * highlight and its clickable rectangle cannot disagree.
 */
sealed interface DrawOp {
    /** A single line of text; [color] null keeps the font's own color. */
    data class Text(
        val font: FontRef,
        val text: String,
        val x: Float,
        val y: Float,
        val color: Color? = null,
    ) : DrawOp

    /** A stretchable nine-patch drawn into [rect]. */
    data class NinePatch(val patch: PatchRef, val rect: Rect) : DrawOp

    /** A texture region drawn into [rect]; [color] null uses white tint. */
    data class Texture(val texture: TextureRef, val rect: Rect, val color: Color? = null) : DrawOp

    /** A solid rectangle in [color]. */
    data class FilledRect(val rect: Rect, val color: Color) : DrawOp

    /** Start clipping subsequent ops to [rect]. */
    data class PushClip(val rect: Rect) : DrawOp

    /** End the most recent [PushClip]. */
    data object PopClip : DrawOp
}
