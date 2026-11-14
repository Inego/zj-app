package inego.zungjunggame.platform

/**
 * Whether HINT runs UCB flat Monte Carlo playouts on this platform.
 *
 * MC playouts need a preemptive background thread: a single hand playout costs
 * ~3 ms on desktop (see `hints.McPlayoutRunner`) and the search only stops when
 * the turn changes, so it cannot share the render loop.
 *
 * - Desktop (LWJGL3) opts in: one daemon worker, avgΔ / n labels on the options.
 * - Android leaves it off. The worker competes with the GL thread on a phone and
 *   the search is far slower there than on desktop, so HINT is just the classic
 *   [inego.zungjunggame.engines.PlayingEngine.rank] co-best highlight.
 * - WasmGC (TeaVM) must leave it off. `Thread.start()` there is not a thread: it
 *   defers onto the TeaVM event queue as a cooperative fiber, and a fiber yields
 *   only at a suspension point. The playout loop has none, so it never returns to
 *   the browser: the tab freezes, and the frame loop that would set the cancel
 *   flag never runs again. Classic rank-only HINT is the whole feature there.
 *
 * Default is off, and only launchers turn it on, like the other `platform`
 * installation points. A launcher that forgets costs the MC labels; it can never
 * cost the tab.
 */
object McHints {
    var enabled: Boolean = false
}
