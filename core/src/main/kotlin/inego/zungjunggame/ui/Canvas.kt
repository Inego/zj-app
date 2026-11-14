package inego.zungjunggame.ui

import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack
import com.badlogic.gdx.utils.Disposable

/**
 * Thin libGDX-backed executor for [DrawOp] plus a transform helper.
 *
 * Owns no feature logic and owns no GL resource; the batch is supplied by the
 * renderer and remains owned by it, and the 1×1 white texture for
 * [DrawOp.FilledRect] is likewise borrowed. Injecting it rather than importing it
 * is what keeps `ui/` free of a `render/` dependency (see
 * [inego.zungjunggame.arch.Rule.UI_PURE]).
 *
 * [whitePixel] is a **provider, not a value**, and deliberately so: the renderer's
 * recovery drops the shared texture after a context loss and the next
 * [WhitePixel.get] builds a new one. A [Canvas] holding the instance would keep
 * drawing through a dead GL name forever — which is precisely the bug
 * the managed-GPU recovery stage exists to remove, reintroduced one layer up.
 *
 * Clip rects are assumed to already be in the batch's screen coordinate space;
 * nested clips intersect against the current top.
 */
class Canvas(
    val batch: SpriteBatch,
    private val whitePixel: () -> Texture,
) : Disposable {
    private var clipDepth: Int = 0

    /** Execute a single draw op. */
    fun run(op: DrawOp) {
        when (op) {
            is DrawOp.Text -> runText(op)
            is DrawOp.NinePatch -> op.patch.patch.draw(
                batch,
                op.rect.left,
                op.rect.bottom,
                op.rect.width,
                op.rect.height,
            )
            is DrawOp.Texture -> runTexture(op)
            is DrawOp.FilledRect -> runFilledRect(op)
            is DrawOp.PushClip -> runPushClip(op)
            DrawOp.PopClip -> runPopClip()
        }
    }

    /** Apply a translation for the duration of [block] (push/pop the batch transform). */
    fun at(x: Float, y: Float, block: () -> Unit) {
        batch.flush()
        val original = Matrix4(batch.transformMatrix)
        // SpriteBatch rebuilds its combined matrix only on setTransformMatrix.
        batch.transformMatrix = Matrix4(original).translate(x, y, 0f)
        try {
            block()
        } finally {
            batch.flush()
            batch.transformMatrix = original
        }
    }

    /**
     * Run [block] inside [transform]'s local space, or in place when it is null.
     * The local origin is the transform rect's bottom-left.
     */
    fun withTransform(transform: Transform?, block: () -> Unit) {
        if (transform == null) {
            block()
            return
        }
        at(transform.rect.left, transform.rect.bottom, block)
    }

    override fun dispose() {
    }

    private fun runText(op: DrawOp.Text) {
        val font = op.font.font
        val color = op.color
        if (color == null) {
            font.draw(batch, op.text, op.x, op.y)
            return
        }
        val previous = font.color.cpy()
        font.color = color
        font.draw(batch, op.text, op.x, op.y)
        font.color = previous
    }

    private fun runTexture(op: DrawOp.Texture) {
        val color = op.color
        if (color == null) {
            batch.draw(op.texture.region, op.rect.left, op.rect.bottom, op.rect.width, op.rect.height)
            return
        }
        val previous = batch.color.cpy()
        batch.color = color
        batch.draw(op.texture.region, op.rect.left, op.rect.bottom, op.rect.width, op.rect.height)
        batch.color = previous
    }

    private fun runFilledRect(op: DrawOp.FilledRect) {
        val previous = batch.color.cpy()
        batch.color = op.color
        batch.draw(whitePixel(), op.rect.left, op.rect.bottom, op.rect.width, op.rect.height)
        batch.color = previous
    }

    private fun runPushClip(op: DrawOp.PushClip) {
        batch.flush()
        ScissorStack.pushScissors(Rectangle(op.rect.left, op.rect.bottom, op.rect.width, op.rect.height))
        clipDepth++
    }

    private fun runPopClip() {
        if (clipDepth <= 0) return
        batch.flush()
        ScissorStack.popScissors()
        clipDepth--
    }
}
