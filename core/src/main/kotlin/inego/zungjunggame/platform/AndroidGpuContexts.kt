package inego.zungjunggame.platform

import com.badlogic.gdx.utils.Disposable

/**
 * Android's platform fact: its GL objects survive backgrounding, and a real loss
 * announces itself by re-entering `onSurfaceCreated` on the *same* graphics object.
 *
 * This is the web's shape ([inego.zungjunggame.teavm.WasmGpuContexts]) on the
 * Android launcher, and the two halves land together for the same reason:
 * **durable with no event is blind to a real loss**, while an
 * event on a platform still declared volatile pays the pause rebuild anyway.
 *
 * ### Why `contextsAreDurable = true` is defensible here
 *
 * libGDX's `AndroidGraphics` constructor calls
 * `view.setPreserveEGLContextOnPause(true)`, so a Home press suspends the GL
 * thread *without* destroying the EGL context (read off the
 * backend source and confirmed on the attached Honor: PID and `ActivityRecord`
 * unchanged, and `always_finish_activities=1` does not change that on this app).
 * The rebuild a `pause`/`resume` pair was paying — 438–493 ms of glyph
 * regeneration per cycle, A5's measurement — therefore bought nothing.
 *
 * A pause also cannot destroy the context when the *activity* is destroyed
 * either: the manifest lists `orientation|screenSize` on `configChanges`, so a
 * rotation is a `onSurfaceChanged`, not a new `AndroidGraphics`.
 *
 * ### Why the two halves still cannot be separated
 *
 * `onSurfaceCreated` is re-entered whenever the surface is recreated, and that
 * is the only authoritative loss signal this platform has. Note what libGDX does
 * inside its own `onSurfaceCreated` before the notify arrives: it invalidates
 * every managed mesh, texture, cubemap, texture array, shader program and frame
 * buffer, and calls `setupGL` again. So the meshes and managed textures are
 * already repaired by `super`, and the rebuild that follows is still needed for
 * what libGDX does not cover:
 *
 *  - **font pages** — `glIsTexture` stays true after the driver wipes a page, so
 *    a re-upload is not safe here; they have to be rasterized again
 *    ([inego.zungjunggame.render.fonts.Fonts]);
 *  - **unmanaged textures** — every `new Texture(...)` in the app, plus the
 *    shared `WhitePixel`, which libGDX never reloads
 *    ([inego.zungjunggame.render.assets.TextureRecovery]);
 *  - **the batch shader** — a `ShaderProgram` keeps its program name and
 *    `isCompiled` stays true across a loss, so libGDX will bind a dead program
 *    forever ([inego.zungjunggame.render.assets.ManagedGpuResources]).
 *
 * ### Threading
 *
 * [onSurfaceRecreated] is called from `onSurfaceCreated`, which libGDX delivers
 * on the GL thread — the same thread the subscriber
 * ([inego.zungjunggame.app.AppRuntime]) is installed from, since it subscribes
 * during `create()` and `create()` itself runs from `onSurfaceChanged` on that
 * thread. The list is nevertheless synchronized, because a stale subscription
 * outliving its `scope` and racing a re-entry would resurrect a cleared flag.
 *
 * A subscriber must only *request* recovery and ask for a frame; the rebuild
 * itself is spent at most once per frame by the render loop. That is
 * `AppRuntime`'s subscriber, and it is why this class holds no rendering code.
 */
class AndroidGpuContexts : GpuContextPolicy {
    /**
     * Durable, and licensed by the *preserve-on* run in C2's second half: a Home
     * press and return that logs no rebuild. See the class KDoc for the evidence
     * and for what the flag is not claiming.
     */
    override val contextsAreDurable: Boolean = true

    private val listeners = ArrayList<() -> Unit>()

    /**
     * Report that the GL context behind the existing graphics object is a *new*
     * one. Called by the launcher from a `createGraphics` subclass, and only from
     * the **second and later** `onSurfaceCreated` of that object.
     *
     * GL thread. Only requests are made of the subscribers; nothing here touches
     * GL, so it is safe to call from inside `onSurfaceCreated` after `super`.
     */
    fun onSurfaceRecreated() {
        // Snapshot: a subscriber is allowed to dispose its own subscription from
        // inside the callback, and iterating the live list would then fail
        // fast. It cannot do that today (the subscriber only sets a flag), but
        // the cost of being wrong here is a crash on a context loss.
        val snapshot = synchronized(listeners) { listeners.toList() }
        for (listener in snapshot) {
            try {
                listener()
            } catch (t: Throwable) {
                // One bad subscriber must not strand the rest, and a context loss
                // must never be able to kill the GL thread — that would leave the
                // app drawing nothing, which is the failure Stage G removed.
                System.err.println("AndroidGpuContexts: subscriber failed: ${t.message}")
            }
        }
    }

    /**
     * Subscribe to a real context recreation. Returns a handle that removes the
     * subscriber; the render loop disposes it with the rest of its scope.
     */
    override fun onContextRecreated(onRecover: () -> Unit): Disposable {
        synchronized(listeners) { listeners.add(onRecover) }
        return Disposable { synchronized(listeners) { listeners.remove(onRecover) } }
    }

    /** Subscriber count; test hook. */
    val subscriberCount: Int get() = synchronized(listeners) { listeners.size }
}
