package inego.zungjunggame.render.assets

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture

/**
 * The one 1×1 white pixel every filled-rect draw uses.
 *
 * ### Why this is shared
 *
 * It used to be built per component: 28 private `whitePixel()` helpers, each with
 * its own `Pixmap(1, 1)` and its own `Texture`, each cached in a field and disposed
 * by hand. That was already duplication, but it became a **correctness** problem with the managed-GPU recovery stage: a `Texture(Pixmap)` is unmanaged, so after a context loss
 * every one of those 28 instances is a dead GL name. Recovering them would mean
 * 28 registration sites and 28 drop callbacks.
 *
 * One shared instance is one thing to recover, and it is one thing to get right.
 *
 * ### Why it has to exist at all
 *
 * `batch.draw(whitePixel, ...)` is how the whole UI draws untextured rectangles,
 * so this 4-byte texture is load-bearing for every panel, border, divider and
 * highlight. Losing it silently removes all of them.
 *
 * ### Recovery
 *
 * The cached instance is dropped on a context loss and rebuilt on the next draw.
 * Dropping rather than re-uploading is deliberate: an unmanaged texture cannot be
 * re-uploaded in place, and a stale one must never be handed out. The old instance
 * is not disposed — its GL name belongs to a dead context, so disposing it is a
 * silent no-op that can only log errors.
 */
object WhitePixel {
    private var texture: Texture? = null

    /**
     * The shared pixel, created on first use.
     *
     * Render thread only, like every other GL resource here.
     */
    fun get(): Texture {
        texture?.let { return it }
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pixmap.setColor(Color.WHITE)
        pixmap.fill()
        val created = Texture(pixmap)
        pixmap.dispose()
        texture = created
        return created
    }

    /** Drop the cached instance so the next [get] rebuilds it. Recovery only. */
    fun invalidate() {
        texture = null
    }

    /** Release for real, at the end of the app's life. */
    fun dispose() {
        texture?.dispose()
        texture = null
    }
}

/**
 * Install the white pixel's recovery step.
 *
 * Called from the composition root so the whole registration story lives in one
 * place: [TextureRecovery] for the app's own textures, this for the shared pixel,
 * [ManagedGpuResources] for the batch and meshes.
 */
fun registerWhitePixelRecovery() {
    ManagedGpuResources.registerLazyTexture("whitePixel") { WhitePixel.invalidate() }
}
