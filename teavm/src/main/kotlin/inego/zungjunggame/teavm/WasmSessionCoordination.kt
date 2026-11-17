package inego.zungjunggame.teavm

import com.github.xpenatan.gdx.teavm.backends.web.BrowserFiber
import inego.zungjunggame.platform.LiveSession
import inego.zungjunggame.platform.SessionCoordination
import inego.zungjunggame.platform.postToAppThread
import org.teavm.jso.JSObject

/**
 * Browser coordination for the origin-wide session.
 *
 * A visible tab takes `navigator.locks`' exclusive refresh lock around the
 * read → exchange → write sequence. A **hidden** tab runs the same sequence
 * unlocked: its animation-frame loop is paused, so the callback that would
 * release the lock cannot run until the tab is visible again, and holding the
 * origin's lock for that long would stall every other tab. Unlocked is safe —
 * `AuthSession.reloadStored` takes the latest token and `applyRefreshed` writes
 * only if the presented token is still stored, so a concurrent exchange becomes
 * a sibling rotation (which the server recovers from) instead of a replay.
 *
 * The store watch is a wake-up, never a payload: the handler re-reads
 * `auth.session` and decides. The visibility and bfcache edges cover a tab that
 * was frozen while another wrote and therefore missed the event.
 *
 * The live-session channel is the one thing that does travel as a payload: the
 * access JWT of a rotation. It cannot be re-read from the store — the store
 * holds only the refresh token — and without it every sibling's next request
 * is refused, which is what made two visible tabs rotate each other's tokens
 * forever. `BroadcastChannel` is same-origin and memory-only; a browser
 * without it falls back to the sibling exchange.
 */
class WasmSessionCoordination(
    private val authStorageKey: String,
) : SessionCoordination {
    /** Null when this browser has no `BroadcastChannel`; both sides no-op. */
    private val liveChannel: JSObject? = NativeSessionChannel.open(LIVE_CHANNEL)

    override fun withRefreshLock(critical: (release: () -> Unit) -> Unit) {
        if (!NativeSessionLock.isSupported() || !NativeSessionLock.isDocumentVisible()) {
            critical { }
            return
        }
        NativeSessionLock.requestExclusive(LOCK_NAME) { handle ->
            // The lock callback is a browser callback, not a frame: hop into a
            // fiber, but do not post to the app thread. A post waits for the
            // next animation frame, and a tab that goes hidden must still finish
            // the exchange and release the lock.
            BrowserFiber.enter {
                var released = false
                val releaseOnce = {
                    if (!released) {
                        released = true
                        NativeSessionLock.release(handle)
                    }
                }
                try {
                    critical(releaseOnce)
                } catch (t: Throwable) {
                    // A throw would otherwise strand the origin's lock until
                    // this tab dies; release it before surfacing the bug.
                    releaseOnce()
                    throw t
                }
            }
        }
    }

    override fun watchForeignSessionChanges(onChange: () -> Unit) {
        NativeSessionStorage.watch(authStorageKey) {
            // This one *is* an app-thread hop: it mutates model state, and one
            // poke per visible frame is soon enough. A hidden tab's post waits
            // for its next frame, and the visibility edge pokes it again then.
            BrowserFiber.enter { postToAppThread { onChange() } }
        }
    }

    override fun publishLiveSession(session: LiveSession) {
        NativeSessionChannel.post(
            liveChannel,
            session.refreshToken,
            session.accessToken,
            session.userId,
        )
    }

    override fun watchLiveSessions(onSession: (LiveSession) -> Unit) {
        NativeSessionChannel.watch(liveChannel) { refreshToken, accessToken, userId ->
            // Adoption mutates the session model, so it takes the same app-thread
            // hop as the store watch. The payload is copied out of the event
            // before the hop: the event object does not survive it.
            BrowserFiber.enter {
                postToAppThread {
                    onSession(
                        LiveSession(
                            refreshToken = refreshToken,
                            accessToken = accessToken,
                            userId = userId,
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        /** One lock name per origin: every tab of this game contends for it. */
        const val LOCK_NAME = "zj-auth-refresh"

        /** One channel name per origin; tabs of this game are its only members. */
        const val LIVE_CHANNEL = "zj-auth-live-session"
    }
}
