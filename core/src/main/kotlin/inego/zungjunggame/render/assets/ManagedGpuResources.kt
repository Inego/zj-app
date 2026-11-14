package inego.zungjunggame.render.assets

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.invalidateMeshesAfterContextLoss

/**
 * Puts libGDX's *own* GL resources back after a context loss — the half
 * [TextureRecovery] cannot cover.
 *
 * ### The bug this exists for
 *
 * A forced `loseContext` / `restoreContext` left the app showing felt only, and
 * the log said why, ~256 times:
 *
 * ```
 * INVALID_OPERATION: useProgram: object does not belong to this context
 *     SpriteBatch::begin -> AppRuntime::paint
 * INVALID_OPERATION: drawElements / bindBuffer / bindVertexArray / bufferData ...
 * ```
 *
 * [TextureRecovery] rebuilds the font atlas and the textures the app *owns*, and it
 * did its job — fonts came back, the tile faces came back. What it cannot touch is
 * the batch's internal state: `SpriteBatch` holds a `Mesh` (a VBO and a VAO) and a
 * `ShaderProgram`, both plain GL names that died with the old context. Every draw
 * after the restore therefore references a name the new context has never issued,
 * **so nothing draws at all** and the felt is the whole screen.
 *
 * Replacing those names is the first half. The second is libGDX's own
 * `VertexBufferObjectWithVAO.invalidate`: it allocates a new VAO and keeps the
 * previous attribute-location cache, so the next bind skips
 * `glVertexAttribPointer`. The draw calls then succeed and rasterize nothing.
 * [invalidateManagedMeshes] drops that cache. Both halves are required; either
 * one alone still leaves felt.
 *
 * ### libGDX already has most of this; nothing on the web called it
 *
 * Worth stating, because it makes this a wiring job rather than a rewrite:
 *
 *  - **Meshes are managed.** A `Mesh` built over a `VertexBufferObject` registers
 *    itself with `Gdx.app`, and `Mesh.invalidateAllMeshes(app)` drops the cached
 *    handles so each mesh transparently allocates fresh ones on next use. The GL
 *    names are cached in the mesh's *data* object, not in the mesh — which is why
 *    invalidation suffices and **no new `Mesh` is needed**. The identity every view
 *    holds stays valid, which matters because they are constructor parameters in
 *    ~70 files.
 *  - **Textures** reload only through `Texture.invalidateAllTextures(app)`, and only
 *    for `isManaged()` data. The app's own textures are deliberately *unmanaged*
 *    (see [TextureRecovery]) and are rebuilt by build-then-swap, which is right
 *    there — but it means the per-component 1×1 white pixels must be dropped
 *    explicitly, since nothing sweeps unmanaged textures.
 *  - **Shaders have no managed path at all.** A `ShaderProgram` keeps its program
 *    name and `isCompiled` stays true across a loss, so libGDX will bind a dead
 *    program forever. This one is genuinely ours to handle.
 *
 * ### Why the shader is replaced, not the batch
 *
 * The obvious alternative is to build a new `SpriteBatch` and thread it through
 * everything that draws — every overlay, presenter and view, by constructor
 * parameter, across ~70 files. That is a large diff for a fault libGDX repairs in
 * place, and it would churn files with nothing to do with GPU recovery.
 * [Batch.setShader] exists for this, and the app only ever calls `flush()` on the
 * batch, so nothing holds a reference to the old program except the batch itself.
 *
 * Render thread only, like [TextureRecovery] and the coordinator that calls it.
 */
object ManagedGpuResources {
    private const val TAG = "GpuRecovery"

    /**
     * Keyed by owner so re-registration replaces rather than piles up, and disposal
     * can unregister. Insertion-ordered so the shader exists before anything tries
     * to draw with it.
     */
    private val steps = LinkedHashMap<String, () -> Unit>()

    /**
     * Register a recovery step, replacing any previous one under the same [key].
     *
     * Not run here: registration happens while the object is being built, and its GL
     * state is valid by then. Running it would upload and immediately discard a
     * second copy.
     */
    fun register(key: String, recover: () -> Unit) {
        steps[key] = recover
    }

    /** Forget an owner, so a disposed object is never recovered. */
    fun unregister(key: String) {
        steps.remove(key)
    }

    /**
     * Rebuild every registered resource. Render thread, before the frame that will
     * draw with them.
     *
     * Fail-soft by design: one step throwing must not strand the rest, because the
     * alternative is a frame of nothing drawn over a single bad step — which is the
     * exact symptom this class exists to remove.
     */
    fun recoverAll(reason: String) {
        var done = 0
        for ((key, step) in steps) {
            try {
                step()
                done++
            } catch (t: Throwable) {
                Gdx.app?.error(TAG, "$key recovery failed ($reason): ${t.message}", t)
            }
        }
        Gdx.app?.log(TAG, "recovered $done/${steps.size} managed resource(s) after $reason")
    }

    /**
     * Drop every managed mesh's cached GL handles so each allocates fresh ones on its
     * next draw, and drop the VAO attribute-location cache libGDX leaves behind.
     *
     * New buffer names fix `bindBuffer` / `bindVertexArray` / `bufferData` /
     * `drawElements` returning `INVALID_OPERATION`. They are not sufficient on
     * their own: [com.badlogic.gdx.graphics.glutils.VertexBufferObjectWithVAO.invalidate]
     * keeps the previous attribute locations, the next bind skips
     * `glVertexAttribPointer`, and the draw succeeds against an empty VAO. That
     * is a cleared framebuffer and zero GL errors — the felt-only frame after a
     * recovery that logged success. No mesh is allocated or disposed, so no view
     * has to learn about a new one.
     */
    fun invalidateManagedMeshes() {
        val app = Gdx.app ?: return
        invalidateMeshesAfterContextLoss(app)
    }

    /**
     * Replace [batch]'s shader with a freshly compiled default.
     *
     * The dead program is deliberately **not** disposed first: it is a GL name from a
     * context that no longer exists, so disposing it is a silent no-op that can only
     * log errors. Leaking one program per context loss is a far cheaper trade than a
     * window where nothing draws.
     *
     * The replacement is published through [Batch.setShader], which flushes first if a
     * draw is in flight — so this is safe to call from inside a frame, and is called
     * *before* the frame that will draw.
     */
    fun rebuildBatchShader(batch: SpriteBatch) {
        batch.setShader(SpriteBatch.createDefaultShader())
    }

    /**
     * Register a lazily-created **unmanaged** texture for recovery.
     *
     * [drop] should discard the cached instance so the next frame builds a new one
     * against the live context. Used for the per-component 1×1 white pixel, of which
     * there is one per component that draws a filled rect.
     */
    fun registerLazyTexture(key: String, drop: () -> Unit) {
        register(key) { drop() }
    }

    /** Registered step count; test hook. */
    val size: Int get() = steps.size

    /** Test hook: forget every step. */
    fun resetForTests() {
        steps.clear()
    }
}
