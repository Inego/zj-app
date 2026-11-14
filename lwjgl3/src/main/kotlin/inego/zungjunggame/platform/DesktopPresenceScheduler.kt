package inego.zungjunggame.platform

import com.badlogic.gdx.utils.Disposable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Desktop scheduler for presence.
 *
 * Uses a single-threaded daemon executor to post periodic ticks onto the
 * libGDX render thread via [postToAppThread].
 */
class DesktopPresenceScheduler : PresenceScheduler {
    override fun isAppVisible(): Boolean = true

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "desktop-presence-scheduler").apply {
            isDaemon = true
        }
    }

    override fun schedulePeriodic(intervalMs: Long, task: () -> Unit): Disposable {
        val future = executor.scheduleAtFixedRate({
            postToAppThread {
                task()
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
        return Disposable {
            future.cancel(false)
        }
    }

    override fun scheduleAfter(delayMs: Long, task: () -> Unit): Disposable {
        val future = executor.schedule({
            postToAppThread {
                task()
            }
        }, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        return Disposable {
            future.cancel(false)
        }
    }

    override fun hookVisibility(onVisible: () -> Unit, onHidden: () -> Unit): Disposable {
        // Desktop window focus/unminimize is handled via LibGDX ApplicationListener pause/resume.
        return Disposable { }
    }
}
