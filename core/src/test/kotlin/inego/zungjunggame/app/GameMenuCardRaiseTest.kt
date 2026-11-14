package inego.zungjunggame.app

import inego.zungjunggame.runtime.RouteController
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.session.SessionRegistry
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.stack.FakeComponent
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.Router
import kotlin.test.Test
import kotlin.test.assertEquals

private val gameMenuKey = Route.Modal(RouteIds.GAME_MENU).key

/**
 * The game-menu card has one owner: `showGameMenuCard` raises it only over a
 * context. A menu surface — the root floor, the local play menu — renders the card
 * as its own layer, so a raise there is a no-op and the card can never be drawn
 * twice.
 *
 * Drives the production chain `AppChromeEffects.showMenu()` → `showGameMenuCard`
 * over a real [Router] built from the production [RouteSpecs], with the kind
 * derived from the stack by a real [SessionRegistry], so both guards (`kind` and
 * `stack.menuSurface`) are exercised.
 */
class GameMenuCardRaiseTest {

    private class Env {
        val router = Router(
            Route.Screen(RouteIds.ROOT_MENU),
            { FakeComponent(ComponentId(it.key.value)) },
            { RouteSpecs.specFor(it) },
        )
        val routes = RouteController(router)
        val registry = SessionRegistry(
            stack = { router.stack },
            localHostSource = { null },
            tutorialHostSource = { null },
            onlineHostSource = { null },
            handSource = { null },
        )
        val chrome = AppChromeEffects(
            sessionRegistry = registry,
            routes = routes,
            toggleFullscreen = {},
            exitApp = {},
        )

        fun cards(): Int = router.stack.entries.count { it.route.key == gameMenuKey }
    }

    @Test
    fun raisingOverAMenuSurfaceIsANoOp() {
        // The root floor: the card is the screen's own content.
        val root = Env()
        root.chrome.showMenu()
        assertEquals(0, root.cards(), "root menu")

        // The local play menu: a context whose content is the card.
        val localMenu = Env()
        localMenu.routes.pushAboveFloor(Route.Layer(RouteIds.LOCAL_MENU))
        localMenu.chrome.showMenu()
        assertEquals(0, localMenu.cards(), "local play menu")
    }

    @Test
    fun raisingOverAContextPushesExactlyOneCard() {
        val contexts = listOf(
            listOf(RouteIds.LOCAL_MENU, RouteIds.LOCAL_BOARD),
            listOf(RouteIds.ONLINE_HUB, RouteIds.ONLINE_TABLE),
            listOf(RouteIds.TUTORIAL_SHELL),
        )
        for (ids in contexts) {
            val env = Env()
            env.routes.pushAboveFloor(Route.Layer(ids.first()))
            for (id in ids.drop(1)) env.routes.push(Route.Layer(id))

            env.chrome.showMenu()
            assertEquals(1, env.cards(), "${ids.last()}: one card")

            // Raising again is idempotent: never two cards in range.
            env.chrome.showMenu()
            assertEquals(1, env.cards(), "${ids.last()}: still one card")
        }
    }
}
