package inego.zungjunggame.app

import inego.zungjunggame.input.Intent
import inego.zungjunggame.ui.stack.OnlineTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A launch deep link waits for a live session and fires once; it never jumps the
 * player out of a game they reloaded into.
 */
class PendingStartupIntentTest {

    @Test
    fun holdsUntilTheSessionIsLiveThenFiresOnce() {
        val pending = PendingStartupIntent(Intent.OpenOnline(OnlineTab.ShadowRanked))

        assertNull(pending.take(signedIn = false, sessionOwnsDisplay = false), "before sign-in")
        assertEquals(
            Intent.OpenOnline(OnlineTab.ShadowRanked),
            pending.take(signedIn = true, sessionOwnsDisplay = false),
        )
        assertNull(pending.take(signedIn = true, sessionOwnsDisplay = false), "only once")
    }

    @Test
    fun waitsOutAGameThePlayerReloadedInto() {
        val pending = PendingStartupIntent(Intent.OpenOnline(OnlineTab.ShadowRanked))

        assertNull(pending.take(signedIn = true, sessionOwnsDisplay = true))
        assertEquals(
            Intent.OpenOnline(OnlineTab.ShadowRanked),
            pending.take(signedIn = true, sessionOwnsDisplay = false),
            "fires once the game is left",
        )
    }

    @Test
    fun noDeepLinkFiresNothing() {
        val pending = PendingStartupIntent(null)

        assertNull(pending.take(signedIn = true, sessionOwnsDisplay = false))
    }
}
