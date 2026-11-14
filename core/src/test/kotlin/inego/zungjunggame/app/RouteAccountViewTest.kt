package inego.zungjunggame.app

import inego.zungjunggame.features.auth.AliasCardOverlay
import inego.zungjunggame.features.auth.StackAliasPrompt
import inego.zungjunggame.features.auth.StartingLevelOverlay
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.*
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.Router
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The route-backed account [inego.zungjunggame.online.AccountController.View]:
 * show/hide push/remove the modal routes and visibility reads stack membership,
 * so the account domain never touches a concrete overlay.
 */
class RouteAccountViewTest {

    private class StubComponent(override val id: ComponentId) : Component {
        override fun layout(frame: Frame): Layout = Layout()
        override fun draw(frame: Frame, canvas: Canvas) {}
    }

    private val router = Router(
        Route.Screen(RouteIds.ROOT_MENU),
        { route -> StubComponent(ComponentId(route.key.value)) },
        { route -> RouteSpecs.specFor(route) },
    )

    private var onlinePresented = 0
    private var onlineDismissed = 0

    private fun view(
        alias: AliasCardOverlay = AliasCardOverlay(),
        level: StartingLevelOverlay = StartingLevelOverlay(),
    ) = RouteAccountView(
        router = { router },
        aliasPrompt = StackAliasPrompt(router = { router }, overlay = alias),
        startingLevelOverlay = level,
        gameMenu = null,
        presentOnline = { onlinePresented++ },
        dismissOnline = { onlineDismissed++ },
    )

    private fun has(id: String) = router.stack.contains(Route.Modal(id).key)

    @Test
    fun firstLoginAliasAndStartingLevelAreRoutes() {
        val v = view()

        v.showFirstLoginAlias("bob")
        assertTrue(v.isFirstLoginAliasVisible)
        v.hideFirstLoginAlias()
        assertFalse(v.isFirstLoginAliasVisible)

        v.showAliasEdit("Alice")
        assertTrue(v.isAliasEditVisible)
        assertTrue(has(RouteIds.CHANGE_DISPLAY_NAME))
        v.cancelAliasEdit()
        assertFalse(v.isAliasEditVisible)

        v.showStartingLevel()
        assertTrue(v.isStartingLevelVisible)
        v.hideStartingLevel()
        assertFalse(v.isStartingLevelVisible)
    }

}
