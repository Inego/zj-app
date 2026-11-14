package inego.zungjunggame.app

import inego.zungjunggame.input.Intent

/**
 * The one intent a launch deep link asks for, held until the app can honor it.
 *
 * A deep link is a request made before the app exists: it names a screen, but
 * that screen needs an online session, and it must never displace a game the
 * player reloaded into. So the intent waits. The runtime asks once per frame;
 * [take] answers with the intent at most once — on the first frame the session
 * is live and no game owns the display — and null on every other frame.
 *
 * Render thread only; not thread-safe.
 */
internal class PendingStartupIntent(intent: Intent?) {
    private var pending: Intent? = intent

    /** The intent to emit now, or null. A non-null answer clears it: it fires once. */
    fun take(signedIn: Boolean, sessionOwnsDisplay: Boolean): Intent? {
        val next = pending ?: return null
        if (!signedIn || sessionOwnsDisplay) return null
        pending = null
        return next
    }
}
