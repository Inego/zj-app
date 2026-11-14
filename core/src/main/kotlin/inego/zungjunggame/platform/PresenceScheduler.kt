package inego.zungjunggame.platform

import com.badlogic.gdx.utils.Disposable

/**
 * Cross-platform scheduler for off-render-loop presence ticks and tab visibility changes.
 *
 * Platform launchers must install [factory] before the first [create] call.
 * The default is a no-op so headless test environments run without background timers.
 */
interface PresenceScheduler {
    /**
     * Query whether the application / browser tab is currently visible and active.
     */
    fun isAppVisible(): Boolean

    /**
     * Start a periodic timer ticking every [intervalMs] milliseconds, invoking [task].
     * Returns a [Disposable] that cancels the timer.
     */
    fun schedulePeriodic(intervalMs: Long, task: () -> Unit): Disposable

    /**
     * Run [task] once after [delayMs], on the application thread, and never
     * before the delay. Returns a [Disposable] that cancels the pending run.
     *
     * This is the one-shot counterpart to [schedulePeriodic]: a settled app
     * schedules no frames, so a deadline that must still be observed (a
     * transport retirement) is turned into exactly one posted wake rather than a
     * continuous render loop.
     */
    fun scheduleAfter(delayMs: Long, task: () -> Unit): Disposable

    /**
     * Hook platform visibility or focus changes to invoke [onVisible] when the app
     * becomes visible / focused again, and [onHidden] when the app is hidden / lost focus.
     * Returns a [Disposable] that removes the listener.
     */
    fun hookVisibility(onVisible: () -> Unit, onHidden: () -> Unit = {}): Disposable

    companion object {
        var factory: () -> PresenceScheduler = { NoOpPresenceScheduler }

        fun create(): PresenceScheduler = factory()
    }
}

object NoOpPresenceScheduler : PresenceScheduler {
    override fun isAppVisible(): Boolean = true

    override fun schedulePeriodic(intervalMs: Long, task: () -> Unit): Disposable =
        Disposable { }

    override fun scheduleAfter(delayMs: Long, task: () -> Unit): Disposable =
        Disposable { }

    override fun hookVisibility(onVisible: () -> Unit, onHidden: () -> Unit): Disposable =
        Disposable { }
}
