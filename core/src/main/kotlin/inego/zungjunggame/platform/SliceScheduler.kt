package inego.zungjunggame.platform

/**
 * Cooperative macro-task scheduler for platforms without preemptive background
 * threads (Web / WasmGC).
 *
 * Schedules short compute slices directly on the browser event loop (via
 * `MessageChannel`, avoiding both `requestAnimationFrame` VSync throttling /
 * redundant WebGL redraws and `setTimeout(0)`'s 4 ms nesting clamp) while
 * still yielding cleanly to user input and painting between slices.
 *
 * Only launchers install [startLoop] (see `teavm/TeaLauncher`). When null,
 * callers fall back to render-loop stepping.
 */
object SliceScheduler {
    /**
     * Start repeatedly invoking [step] (returning `true` while more work remains)
     * as cooperative macro-tasks. Returns a cancel callback that stops further slices.
     */
    var startLoop: ((step: () -> Boolean) -> () -> Unit)? = null
}
