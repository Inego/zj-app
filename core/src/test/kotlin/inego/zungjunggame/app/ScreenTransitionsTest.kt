package inego.zungjunggame.app

import inego.zungjunggame.runtime.RouteController
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.stack.ComponentFactory
import inego.zungjunggame.ui.stack.LayerSpecProvider
import inego.zungjunggame.ui.stack.OnlineTab
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.Router
import inego.zungjunggame.ui.stack.StackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Router/stack-level coverage for the transitions: a context goes *above* the
 * floor and is left by popping back to it, and the chrome layers are stack
 * routes. The tutorial shell is a context like the others — its own port raises
 * it (`TutorialRoutesTest`), so here it is pushed directly.
 */
class ScreenTransitionsTest {

    private class StubComponent(override val id: ComponentId) : Component {
        override fun layout(frame: Frame): Layout = Layout()
        override fun draw(frame: Frame, canvas: Canvas) {}
    }

    private class TestEnv {
        val router = Router(Route.Screen(RouteIds.ROOT_MENU), COMPONENTS, SPECS)
        val transitions = ScreenTransitions(RouteController(router))

        val floorId: String get() = (router.stack.floor.route as Route.Screen).id

        val keys: List<String> get() = router.stack.entries.map { it.route.key.value }

        val referenceRouteKey = Route.Reference(StackState.EMPTY).key

        /** The tutorial context as its own port raises it: directly above the floor. */
        fun enterTutorial() {
            router.pushAboveFloor(Route.Layer(RouteIds.TUTORIAL_SHELL))
        }

        fun hasReference(): Boolean = router.stack.contains(referenceRouteKey)

        fun hasMatchHistory(): Boolean = has(Route.Modal(RouteIds.MATCH_HISTORY))

        fun has(route: Route): Boolean = router.stack.contains(route.key)

        /** The game-menu card is stack-owned now: its presence is a route, not a flag. */
        fun hasGameMenu(): Boolean = has(Route.Modal(RouteIds.GAME_MENU))
    }

    @Test
    fun localMenuPushesTheContextMenuLayerAboveTheFloor() {
        val env = TestEnv()

        env.transitions.localMenu()

        assertEquals(listOf("screen:${RouteIds.ROOT_MENU}", "layer:${RouteIds.LOCAL_MENU}"), env.keys)
        assertEquals(RouteIds.ROOT_MENU, env.floorId)
        // The context menu *is* the card, so no card is pushed over it.
        assertFalse(env.hasGameMenu())
    }

    @Test
    fun localBoardPushesTheBoardLayerAboveTheLocalMenu() {
        val env = TestEnv()
        env.transitions.localMenu()

        env.transitions.localBoard()

        assertEquals(
            listOf(
                "screen:${RouteIds.ROOT_MENU}",
                "layer:${RouteIds.LOCAL_MENU}",
                "layer:${RouteIds.LOCAL_BOARD}",
            ),
            env.keys,
        )
        assertEquals(RouteIds.ROOT_MENU, env.floorId)
    }

    @Test
    fun popToRootPopsEveryContextAndCardAboveTheFloor() {
        val env = TestEnv()
        env.transitions.localMenu()
        env.transitions.localBoard()
        env.router.push(Route.Modal(RouteIds.GAME_MENU))

        env.transitions.popToRoot()

        assertEquals(listOf("screen:${RouteIds.ROOT_MENU}"), env.keys)
        assertEquals(RouteIds.ROOT_MENU, env.floorId)
        assertFalse(env.hasGameMenu())
    }

    @Test
    fun popToRootTakesAnOwnerAnnouncedCardWithIt() {
        val env = TestEnv()
        env.router.push(Route.Modal(RouteIds.FIRST_LOGIN_ALIAS))

        env.transitions.popToRoot()

        // The card is a layer above the floor; returning to the floor removes it.
        assertEquals(listOf("screen:${RouteIds.ROOT_MENU}"), env.keys)
        assertFalse(env.has(Route.Modal(RouteIds.FIRST_LOGIN_ALIAS)))
    }

    @Test
    fun dismissHubLeavesALiveTableAlone() {
        val env = TestEnv()
        env.transitions.onlineTable(OnlineTab.Live)

        env.transitions.dismissHub()

        // A session expiry during a match must not eject the player to the menu.
        assertEquals(
            listOf(
                "screen:${RouteIds.ROOT_MENU}",
                "layer:${RouteIds.ONLINE_HUB}",
                "layer:${RouteIds.ONLINE_TABLE}",
            ),
            env.keys,
        )
    }

    @Test
    fun onlineMatchHistoryKeepsTheHubUpAndShowsHistory() {
        val env = TestEnv()

        env.transitions.onlineMatchHistory()

        assertEquals(
            listOf(
                "screen:${RouteIds.ROOT_MENU}",
                "layer:${RouteIds.ONLINE_HUB}",
                "modal:${RouteIds.MATCH_HISTORY}",
            ),
            env.keys,
        )
        assertTrue(env.hasMatchHistory())
    }

    @Test
    fun onlineShadowRankedAttemptsKeepsTheHubUpAndShowsTheCard() {
        val env = TestEnv()

        env.transitions.onlineShadowRankedAttempts()

        assertEquals(
            listOf(
                "screen:${RouteIds.ROOT_MENU}",
                "layer:${RouteIds.ONLINE_HUB}",
                "modal:${RouteIds.SHADOW_RANKED_ATTEMPTS}",
            ),
            env.keys,
        )
        assertTrue(env.has(Route.Modal(RouteIds.SHADOW_RANKED_ATTEMPTS)))
    }

    @Test
    fun onlineTableSitsAboveTheHubAndPopsBackToIt() {
        val env = TestEnv()
        env.transitions.onlineHub()
        // A card left open over the hub: nothing sits between the hub and the table
        // it was entered from, so the entry takes it.
        env.transitions.openSettings()

        env.transitions.onlineTable(OnlineTab.Live)

        assertEquals(
            listOf(
                "screen:${RouteIds.ROOT_MENU}",
                "layer:${RouteIds.ONLINE_HUB}",
                "layer:${RouteIds.ONLINE_TABLE}",
            ),
            env.keys,
        )
        assertFalse(env.hasMatchHistory())

        // A rejoin of the same host announces the table again, and the boot probes a
        // restored stack with the table already up. Neither may disturb the stack.
        env.router.push(Route.Modal(RouteIds.GAME_MENU))
        env.transitions.onlineTable(OnlineTab.Live)
        assertEquals(
            listOf(
                "screen:${RouteIds.ROOT_MENU}",
                "layer:${RouteIds.ONLINE_HUB}",
                "layer:${RouteIds.ONLINE_TABLE}",
                "modal:${RouteIds.GAME_MENU}",
            ),
            env.keys,
        )

        // The table's session is gone, so its layer goes — revealing the hub it
        // was entered from, never the root and never a bare board.
        env.transitions.dismissTable()

        assertEquals(
            listOf("screen:${RouteIds.ROOT_MENU}", "layer:${RouteIds.ONLINE_HUB}"),
            env.keys,
        )
    }

    @Test
    fun onlineTableRecordsTheTabItWasEnteredFrom() {
        val env = TestEnv()

        env.transitions.onlineTable(OnlineTab.ShadowRanked)
        assertEquals(OnlineTab.ShadowRanked, env.transitions.tableOrigin())

        // A rejoin of an already-up table keeps the recorded origin (the route is the
        // record); it does not overwrite it with the caller's default.
        env.transitions.onlineTable(OnlineTab.Live)
        assertEquals(OnlineTab.ShadowRanked, env.transitions.tableOrigin())

        env.transitions.dismissTable()
        assertEquals(null, env.transitions.tableOrigin())
    }

    @Test
    fun referenceOpenPushesRouteRunsCallbackAndKeepsTheLayersUnderneath() {
        val env = TestEnv()
        var deepLinked = false

        env.transitions.openReference { deepLinked = true }

        assertTrue(deepLinked)
        assertTrue(env.hasReference())
        assertEquals(RouteIds.ROOT_MENU, env.floorId)
        // The root screen (which is the menu) stays under the browser; covering it
        // would land the reader on a bare felt when the browser closes.
        assertFalse(env.hasGameMenu())
    }

    @Test
    fun referenceDismissPopsAndRevealsTheLayersUnderneath() {
        val env = TestEnv()
        env.transitions.openReference()

        env.transitions.dismissReference()

        assertFalse(env.hasReference())
        assertEquals(RouteIds.ROOT_MENU, env.floorId)
        assertFalse(env.hasGameMenu())
    }

    @Test
    fun referenceOpenAndDismissKeepTheLessonLayersUnderneath() {
        val env = TestEnv()
        env.enterTutorial()
        env.router.push(Route.Overlay(RouteIds.TUTORIAL_LESSON))
        env.router.push(Route.Overlay(RouteIds.TUTORIAL_COACH))

        env.transitions.openReference()

        assertTrue(env.hasReference())
        assertTrue(env.has(Route.Overlay(RouteIds.TUTORIAL_LESSON)))
        assertTrue(env.has(Route.Overlay(RouteIds.TUTORIAL_COACH)))

        env.transitions.dismissReference()

        assertFalse(env.hasReference())
        assertTrue(env.has(Route.Layer(RouteIds.TUTORIAL_SHELL)))
        assertTrue(env.has(Route.Overlay(RouteIds.TUTORIAL_LESSON)))
        assertTrue(env.has(Route.Overlay(RouteIds.TUTORIAL_COACH)))
    }

    @Test
    fun referenceOpenedTwiceKeepsASingleRoute() {
        val env = TestEnv()
        env.transitions.localMenu()
        env.transitions.localBoard()

        env.transitions.openReference()
        env.transitions.openReference()

        assertEquals(1, env.router.stack.entries.count { it.route.key == env.referenceRouteKey })

        env.transitions.dismissReference()
        assertFalse(env.hasReference())
        assertEquals(
            listOf(
                "screen:${RouteIds.ROOT_MENU}",
                "layer:${RouteIds.LOCAL_MENU}",
                "layer:${RouteIds.LOCAL_BOARD}",
            ),
            env.keys,
        )
    }

    @Test
    fun referenceDismissWithNoRouteLeavesTheStackUntouched() {
        val env = TestEnv()
        env.enterTutorial()

        env.transitions.dismissReference()

        assertFalse(env.hasReference())
        assertTrue(env.has(Route.Layer(RouteIds.TUTORIAL_SHELL)))
    }

    @Test
    fun tutorialSettingsOpenKeepsTheShellAndLessonLayers() {
        val env = TestEnv()
        env.enterTutorial()
        env.router.push(Route.Overlay(RouteIds.TUTORIAL_LESSON))
        env.router.push(Route.Overlay(RouteIds.TUTORIAL_COACH))

        env.transitions.openSettings()

        assertTrue(env.has(Route.Layer(RouteIds.TUTORIAL_SHELL)))
        assertTrue(env.has(Route.Overlay(RouteIds.TUTORIAL_LESSON)))
        assertTrue(env.has(Route.Overlay(RouteIds.TUTORIAL_COACH)))
        assertTrue(env.has(Route.Modal(RouteIds.SETTINGS)))
        assertFalse(env.hasGameMenu())

        // The Settings modal must sit above the still-covered beat so paint and
        // input rank it over the coach.
        val keys = env.router.stack.entries.map { it.route.key }
        assertTrue(
            keys.indexOf(Route.Modal(RouteIds.SETTINGS).key) >
                keys.indexOf(Route.Overlay(RouteIds.TUTORIAL_COACH).key),
        )
    }

    @Test
    fun tutorialSettingsDismissLeavesTutorialStackIntact() {
        val env = TestEnv()
        env.enterTutorial()
        env.router.push(Route.Overlay(RouteIds.TUTORIAL_LESSON))
        env.router.push(Route.Overlay(RouteIds.TUTORIAL_COACH))
        env.transitions.openSettings()

        env.transitions.dismissSettings()

        assertTrue(env.has(Route.Layer(RouteIds.TUTORIAL_SHELL)))
        assertFalse(env.has(Route.Modal(RouteIds.SETTINGS)))
        assertTrue(env.has(Route.Overlay(RouteIds.TUTORIAL_LESSON)))
        assertTrue(env.has(Route.Overlay(RouteIds.TUTORIAL_COACH)))
        assertFalse(env.hasGameMenu())
    }

    @Test
    fun settingsPushOverTheRootMenuAndDismissRevealsIt() {
        val env = TestEnv()

        // The main menu is the root screen's own content, so there is no card to
        // push above it; a chrome layer over it is just a layer.
        assertFalse(env.hasGameMenu())

        env.transitions.openSettings()
        assertEquals(RouteIds.ROOT_MENU, env.floorId)
        assertTrue(env.has(Route.Modal(RouteIds.SETTINGS)))

        env.transitions.dismissSettings()
        assertEquals(RouteIds.ROOT_MENU, env.floorId)
        assertFalse(env.has(Route.Modal(RouteIds.SETTINGS)))
        assertFalse(env.hasGameMenu())
    }

    @Test
    fun languagePickerPushOverTheRootMenuAndDismissRevealsIt() {
        val env = TestEnv()

        env.transitions.openLanguagePicker()
        assertTrue(env.has(Route.Modal(RouteIds.LANGUAGE_PICKER)))
        assertFalse(env.hasGameMenu())

        env.transitions.dismissLanguagePicker()
        assertFalse(env.has(Route.Modal(RouteIds.LANGUAGE_PICKER)))
        assertFalse(env.hasGameMenu())
    }

    @Test
    fun chromeOpensOverTheLocalMenuAndPopsBackToIt() {
        val env = TestEnv()
        env.transitions.localMenu()

        env.transitions.openSettings()
        assertTrue(env.has(Route.Modal(RouteIds.SETTINGS)))

        env.transitions.dismissSettings()
        assertFalse(env.has(Route.Modal(RouteIds.SETTINGS)))

        env.transitions.openLanguagePicker()
        env.transitions.dismissLanguagePicker()

        // Every chrome layer is a pop away from the context it was opened over.
        assertEquals(
            listOf("screen:${RouteIds.ROOT_MENU}", "layer:${RouteIds.LOCAL_MENU}"),
            env.keys,
        )
        assertFalse(env.hasGameMenu())
    }

    private companion object {
        val COMPONENTS = ComponentFactory { route -> StubComponent(ComponentId(route.key.value)) }
        val SPECS = LayerSpecProvider { route -> RouteSpecs.specFor(route) }
    }
}
