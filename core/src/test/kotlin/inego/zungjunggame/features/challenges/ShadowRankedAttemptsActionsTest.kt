package inego.zungjunggame.features.challenges

import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.ModalVerdict
import inego.zungjunggame.ui.stack.Route
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The attempts card's tap path: the overlay's close verdict becomes the dismiss action.
 */
class ShadowRankedAttemptsActionsTest {

    private val route = Route.Modal(RouteIds.SHADOW_RANKED_ATTEMPTS)

    @Test
    fun theCloseControlDismissesTheCard() {
        val action = ShadowRankedAttemptsActions.actionFor(ModalVerdict(close = true))
        assertEquals(ComponentAction(ShadowRankedAttemptsActions.DISMISS), action)
        assertEquals(Intent.Back, ShadowRankedAttemptsActions.toIntent(route, action!!))
    }

    @Test
    fun selectingLoadMoreEmitsLoadMoreIntent() {
        val action = ShadowRankedAttemptsActions.actionFor(
            ModalVerdict(selectedId = inego.zungjunggame.net.ShadowRankedAttemptsClient.LOAD_MORE_ID),
        )
        assertEquals(ComponentAction(ShadowRankedAttemptsActions.LOAD_MORE), action)
        assertEquals(Intent.LoadMoreShadowRankedAttempts, ShadowRankedAttemptsActions.toIntent(route, action!!))
    }
}
