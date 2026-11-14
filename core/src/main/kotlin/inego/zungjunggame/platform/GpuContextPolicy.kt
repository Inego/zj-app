package inego.zungjunggame.platform

import com.badlogic.gdx.utils.Disposable

/**
 * Whether this platform's GL objects — glyph atlases, texture faces — survive the
 * app being backgrounded, and the one event that proves a context really was
 * thrown away.
 *
 * A declared platform fact rather than a guess made at the point of use. The
 * codebase already owns this idiom in [PresenceScheduler]: a `factory` installed
 * by the platform launcher before the first `create`, with a safe default so
 * headless tests run unchanged. Do not scatter `if (Gdx.app.type == ...)` through
 * the render loop to re-derive it.
 *
 * Consumers:
 *  - [inego.zungjunggame.app.AppRuntime] asks [contextsAreDurable] to decide
 *    whether a long frame gap is worth a rebuild, and subscribes to
 *    [onContextRecreated] so a real loss repairs itself on every platform.
 *  - The policy itself never rebuilds; it only reports. Rebuilding is the
 *    coordinator's job, so that the same coalescing applies whichever platform
 *    reported the loss.
 */
interface GpuContextPolicy {
    /**
     * True when this platform's GL objects survive an app backgrounding.
     *
     * `true`  = durable  — textures and glyph atlases are still valid, so a gap
     *            in the frame clock carries no information about the context and
     *            must not trigger a rebuild. Measured on desktop and Wasm; both
     *            still keep the [onContextRecreated] signal for the rare real loss.
     * `false` = volatile — assume the objects are gone and rebuild. This is the
     *            default, and what an *uninstalled* platform gets: a headless test,
     *            or a new target nobody has measured yet. No shipping launcher
     *            declares it — Android was the last, and Stage C moved it to
     *            [AndroidGpuContexts] (durable **and** event-backed).
     *
     * **Read the polarity carefully.** "Durable" is `true`; the conservative
     * platform is `false`. Getting these backwards is the exact mistake that has
     * been made twice here, and it is silently harmful in both directions:
     * as `true` on a volatile platform it skips a rebuild that was genuinely
     * needed, and as `false` on a durable one it pays a rebuild that bought
     * nothing.
     */
    val contextsAreDurable: Boolean

    /**
     * Fired only when the platform *knows* the GL context was recreated — never
     * as a guess, and never on an ordinary pause/return.
     *
     * [onRecover] runs on the platform's GL thread, which is the render thread on
     * every supported target. It must only *request* recovery; the rebuild runs
     * at most once per frame from the render loop, so several losses in one frame
     * collapse into one rebuild.
     *
     * Returns a [Disposable] that removes the listener. Platforms with no such
     * event return an inert disposable.
     */
    fun onContextRecreated(onRecover: () -> Unit): Disposable

    companion object {
        /**
         * Volatile until a launcher says otherwise. The default is the
         * conservative one: an un-instated platform keeps today's behaviour, which
         * is to rebuild on a long frame gap.
         */
        var factory: () -> GpuContextPolicy = { VolatileGpuContexts }

        fun create(): GpuContextPolicy = factory()
    }
}

/**
 * A platform that keeps its GL context across a pause, and has no event that
 * proves a loss. Installed by desktop (lwjgl3), whose double-buffered
 * OS window cannot lose its context and which therefore has no signal to offer.
 *
 * **Wasm does not use this, and neither does Android** — both need a real loss
 * signal, and installing them here would give them durable durability with durable
 * blindness. See [WasmGpuContexts] and [AndroidGpuContexts].
 */
object DurableGpuContexts : GpuContextPolicy {
    override val contextsAreDurable: Boolean = true

    /**
     * Inert: a durable platform has no recreation event of its own. A real loss
     * on these targets is still reachable — a driver reset, an OS-reclaimed browser
     * tab — and is expected to arrive through the target's own signal
     * (`contextrestored` on the web, a second `onSurfaceCreated` on Android), which
     * is wired to the same request path.
     */
    override fun onContextRecreated(onRecover: () -> Unit): Disposable = Disposable { }
}

/**
 * A platform whose GL objects may be gone after a backgrounding, with no way to
 * ask.
 *
 * **No shipping launcher installs this any more.** Android was the last and moved
 * to [AndroidGpuContexts] when the platform policies were split out. It remains the `factory` default so
 * that a headless test, or a new target nobody has measured, keeps today's behaviour
 * — a long frame gap rebuilds — instead of silently inheriting a durable claim no
 * one has made.
 */
object VolatileGpuContexts : GpuContextPolicy {
    override val contextsAreDurable: Boolean = false

    override fun onContextRecreated(onRecover: () -> Unit): Disposable = Disposable { }
}
