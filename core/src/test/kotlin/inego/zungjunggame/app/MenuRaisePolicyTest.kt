package inego.zungjunggame.app

import inego.zungjunggame.features.auth.AliasCardOverlay
import inego.zungjunggame.features.auth.AliasPrompt
import inego.zungjunggame.features.auth.StackAliasPrompt
import inego.zungjunggame.features.confirm.ConfirmPrompt
import inego.zungjunggame.runtime.RouteController
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.stack.FakeComponent
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.Router
import inego.zungjunggame.ui.stack.StackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The game-menu raise policy, swept over the classification declared in
 * `RouteModals.kt`.
 *
 * A row raises its surface in one of two shapes. A REPLACE surface takes the
 * `game_menu` card down as it is raised, so dismissing it reveals the context
 * whose own back re-raises the menu. A CHILD surface stands over the card and
 * one back reveals it.
 *
 * The sweep drives the production helpers (`replaceMenuCardWith` /
 * `raiseCardOverMenu`) and the production transitions over a real [Router] built
 * from the production [RouteSpecs], so it exercises the seam the app uses rather
 * than a fake of it. The straggler case is pinned directly: the online score
 * table replaces.
 */
class MenuRaisePolicyTest {

    private val menuRoute = Route.Modal(RouteIds.GAME_MENU)

    private class Env(online: Boolean) {
        val router = Router(
            Route.Screen(RouteIds.ROOT_MENU),
            { FakeComponent(ComponentId(it.key.value)) },
            { RouteSpecs.specFor(it) },
        )
        val routes = RouteController(router)
        val transitions = ScreenTransitions(routes)

        init {
            if (online) {
                routes.pushAboveFloor(Route.Layer(RouteIds.ONLINE_HUB))
                routes.push(Route.Layer(RouteIds.ONLINE_TABLE))
            } else {
                routes.pushAboveFloor(Route.Layer(RouteIds.LOCAL_MENU))
                routes.push(Route.Layer(RouteIds.LOCAL_BOARD))
            }
            routes.push(Route.Modal(RouteIds.GAME_MENU))
        }

        fun has(route: Route): Boolean = router.stack.contains(route.key)
        fun topKey() = router.stack.top.route.key
    }

    private class Surface(
        val name: String,
        val route: Route,
        val raise: (Env) -> Unit,
    )

    private val replaceSurfaces: List<Surface> = buildList {
        for (prompt in ConfirmPrompt.entries) {
            add(
                Surface("confirm:$prompt", prompt.route()) { env ->
                    env.routes.replaceMenuCardWith(prompt.route())
                },
            )
        }
        add(Surface("score table", Route.Modal(RouteIds.SCORE_TABLE)) { it.transitions.openScoreTable() })
        add(
            Surface("new game setup", Route.Modal(RouteIds.NEW_GAME_SETUP)) {
                it.transitions.openNewGameSetup()
            },
        )
    }

    private val childSurfaces: List<Surface> = listOf(
        Surface("reference", Route.Reference(StackState.EMPTY)) { it.transitions.openReference() },
        Surface("settings", Route.Modal(RouteIds.SETTINGS)) { it.transitions.openSettings() },
        Surface("language picker", Route.Modal(RouteIds.LANGUAGE_PICKER)) {
            it.transitions.openLanguagePicker()
        },
        Surface("display-name editor", Route.Modal(RouteIds.CHANGE_DISPLAY_NAME)) { env ->
            // The editor's push is the prompt's, below `app/`: the card is a
            // keyboard-profile surface, so only the prompt knows whether to raise
            // one at all (touch uses the OS dialog instead). Driving it here keeps
            // the check on the production raise site.
            StackAliasPrompt(router = { env.router }, overlay = AliasCardOverlay())
                .show(AliasPrompt.Kind.ChangeName, seed = "alice")
        },
    )

    @Test
    fun everyReplaceSurfaceTakesTheMenuCardDown() {
        for (surface in replaceSurfaces) {
            for (online in listOf(false, true)) {
                val env = Env(online = online)
                surface.raise(env)

                assertFalse(
                    env.has(menuRoute),
                    "${surface.name} (online=$online): the menu card must not survive the raise",
                )
                assertTrue(env.has(surface.route), "${surface.name}: the surface must be up")
            }
        }
    }

    @Test
    fun everyChildSurfaceLeavesTheMenuCardAndOneBackRevealsIt() {
        for (surface in childSurfaces) {
            val env = Env(online = false)
            surface.raise(env)

            assertTrue(env.has(menuRoute), "${surface.name}: the menu card stays under the child")
            assertTrue(env.has(surface.route), "${surface.name}: the child is up")

            assertTrue(env.router.popDismissible(), "${surface.name}: one generic dismissal removes the child")
            assertEquals(menuRoute.key, env.topKey(), "${surface.name}: the menu is revealed")
        }
    }

    /** The row exists online too (`render/GameMenuFlags.showScoreTable`), so it replaces there. */
    @Test
    fun onlineScoreTableReplacesTheMenuCard() {
        val env = Env(online = true)

        env.transitions.openScoreTable()

        assertFalse(env.has(menuRoute), "the online menu card must not survive the ledger")
        assertTrue(env.has(Route.Modal(RouteIds.SCORE_TABLE)))
    }
}
