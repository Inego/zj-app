package inego.zungjunggame.android

import android.os.Handler
import android.os.Looper
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.LifecycleListener
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.platform.PresenceScheduler
import inego.zungjunggame.platform.postToAppThread

/**
 * Android implementation of [PresenceScheduler].
 *
 * `Main.create()` turns continuous rendering off, so a settled screen presents no
 * frame. Before this class existed the runtime's one-second timer was the no-op
 * default on Android, which meant a signed-in screen left still stopped driving
 * [inego.zungjunggame.online.OnlineCoordinator.tick]: presence stopped being
 * refreshed, the idle-screen account refresh stopped, and the server expired the
 * presence after its TTL. This is the missing driver, matching
 * `DesktopPresenceScheduler` and `WasmPresenceScheduler`.
 *
 * A main-looper [Handler] owns the clock; the task itself is hopped onto the
 * libGDX application thread with [postToAppThread]. That hop is also the wake:
 * `AndroidApplication.postRunnable` calls `requestRendering()`, so the tick gets
 * the one frame it needs without continuous rendering.
 *
 * The timer is stopped while the activity is paused, so background time does not
 * queue ticks that would all run in one burst on return.
 *
 * [hookVisibility] stays a no-op on purpose. `Main.pause()` / `Main.resume()`
 * already deliver `onAppPaused()` / `onAppResumed()`; wiring the visibility hook
 * as well would run that lifecycle twice per transition.
 */
class AndroidPresenceScheduler : PresenceScheduler {

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val timers = ArrayList<Periodic>()
    private var foreground = true
    private var released = false

    private val lifecycle = object : LifecycleListener {
        override fun pause() = stopTimers()

        override fun resume() = startTimers()

        override fun dispose() = release()
    }

    init {
        (Gdx.app as? AndroidApplication)?.addLifecycleListener(lifecycle)
    }

    override fun isAppVisible(): Boolean = synchronized(lock) { foreground }

    override fun schedulePeriodic(intervalMs: Long, task: () -> Unit): Disposable {
        val timer = Periodic(intervalMs.coerceAtLeast(1L), task)
        synchronized(lock) {
            if (released) return Disposable { }
            timers += timer
            armLocked(timer)
        }
        return Disposable {
            synchronized(lock) {
                timer.cancelled = true
                timer.armed = false
                timers.remove(timer)
                handler.removeCallbacks(timer)
            }
        }
    }

    override fun scheduleAfter(delayMs: Long, task: () -> Unit): Disposable {
        synchronized(lock) {
            if (released) return Disposable { }
        }
        val runnable = Runnable { postToAppThread(task) }
        handler.postDelayed(runnable, delayMs.coerceAtLeast(0L))
        return Disposable { handler.removeCallbacks(runnable) }
    }

    override fun hookVisibility(onVisible: () -> Unit, onHidden: () -> Unit): Disposable =
        Disposable { }

    /** Caller holds [lock]. */
    private fun armLocked(timer: Periodic) {
        if (released || !foreground || timer.cancelled || timer.armed) return
        timer.armed = true
        handler.postDelayed(timer, timer.intervalMs)
    }

    private fun stopTimers() {
        synchronized(lock) {
            foreground = false
            for (timer in timers) {
                timer.armed = false
                handler.removeCallbacks(timer)
            }
        }
    }

    private fun startTimers() {
        synchronized(lock) {
            if (released) return
            foreground = true
            for (timer in timers) armLocked(timer)
        }
    }

    private fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            foreground = false
            timers.clear()
            handler.removeCallbacksAndMessages(null)
        }
        (Gdx.app as? AndroidApplication)?.removeLifecycleListener(lifecycle)
    }

    private inner class Periodic(
        val intervalMs: Long,
        private val task: () -> Unit,
    ) : Runnable {

        /** Guarded by [lock]. */
        var armed = false

        /** Guarded by [lock]. */
        var cancelled = false

        override fun run() {
            synchronized(lock) {
                armed = false
                if (cancelled || released || !foreground) return
            }
            postToAppThread(task)
            synchronized(lock) {
                armLocked(this)
            }
        }
    }
}
