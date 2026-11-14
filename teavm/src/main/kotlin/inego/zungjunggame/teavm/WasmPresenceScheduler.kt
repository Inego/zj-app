package inego.zungjunggame.teavm

import com.badlogic.gdx.utils.Disposable
import com.github.xpenatan.gdx.teavm.backends.web.BrowserFiber
import inego.zungjunggame.platform.PresenceScheduler
import inego.zungjunggame.platform.postToAppThread

/**
 * Browser scheduler for presence on TeaVM.
 *
 * Uses window.setInterval for periodic off-render ticks, window.setTimeout for
 * one-shot deadline wakes, and listens to `visibilitychange` to flush immediately
 * when a tab becomes visible. All hop through [postToAppThread]: a timer callback
 * is not an animation frame, and an idle page will not present the tick unless
 * that post asks for one.
 */
class WasmPresenceScheduler : PresenceScheduler {
    override fun isAppVisible(): Boolean = NativeScheduler.isVisible()

    override fun schedulePeriodic(intervalMs: Long, task: () -> Unit): Disposable {
        val timerId = NativeScheduler.setInterval(
            { BrowserFiber.enter { postToAppThread { task() } } },
            intervalMs.toInt(),
        )
        return Disposable {
            NativeScheduler.clearInterval(timerId)
        }
    }

    override fun hookVisibility(onVisible: () -> Unit, onHidden: () -> Unit): Disposable {
        val listener = NativeScheduler.onVisibilityChange(
            { BrowserFiber.enter { postToAppThread { onVisible() } } },
            { BrowserFiber.enter { postToAppThread { onHidden() } } },
        )
        return Disposable {
            NativeScheduler.removeVisibilityListener(listener)
        }
    }

    /**
     * One `window.setTimeout`, hopped through [postToAppThread] like the periodic
     * tick: the timer callback is not an animation frame, and an idle page will
     * not present the wake unless that post asks for one. This is what lets a
     * chart with an open request stop asking for frames between now and the
     * request's deadline while still retiring it on time.
     */
    override fun scheduleAfter(delayMs: Long, task: () -> Unit): Disposable {
        val timerId = NativeScheduler.setTimeout(
            { BrowserFiber.enter { postToAppThread { task() } } },
            delayMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
        return Disposable {
            NativeScheduler.clearTimeout(timerId)
        }
    }
}
