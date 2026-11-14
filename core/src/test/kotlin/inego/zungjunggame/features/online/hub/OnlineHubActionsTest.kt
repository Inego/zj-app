package inego.zungjunggame.features.online.hub

import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.stack.Route
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The hub's Shadow Ranked link: the panel's verdict becomes the hub's action and
 * the feature turns that action into the intent that opens the attempts card.
 */
class OnlineHubActionsTest {

    private val route = Route.Layer(RouteIds.ONLINE_HUB)

    @Test
    fun theAttemptsLinkOpensTheAttemptsCard() {
        val action = OnlineHubActions.actionFor(
            HubVerdict.ViewAttempts,
            LiveTabPanel.PrimaryAction.Disabled,
        )
        assertEquals(action, ComponentAction(OnlineHubActions.VIEW_ATTEMPTS))

        assertEquals(
            Intent.ViewShadowRankedAttempts,
            OnlineHubActions.toIntent(route, action!!),
        )
    }
}
