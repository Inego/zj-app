package inego.zungjunggame.runtime

import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.stack.FakeComponent
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.RouteInstance
import inego.zungjunggame.ui.stack.Stack
import inego.zungjunggame.ui.stack.StackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Headless parity for the stack-derived replacements of the legacy overlay
 * policy queries. Specs come from the real [RouteSpecs], so the tests pin the
 * behavior the old flag lists encoded.
 */
class StackPolicyTest {

    private val floor = Route.Screen(RouteIds.LOCAL_BOARD)

    private fun instance(route: Route): RouteInstance =
        RouteInstance(route, FakeComponent(ComponentId(route.key.value)), RouteSpecs.specFor(route))

    private fun stackOf(vararg above: Route): Stack =
        Stack(listOf(instance(floor)) + above.map(::instance))

    @Test
    fun floorOnlyDoesNotBlockAutomation() {
        assertFalse(stackOf().anyBlocksAutomation)
        assertEquals(1, stackOf().entries.size)
    }

    @Test
    fun blockingRoutesBlockAutomation() {
        for (route in blockingRoutes) {
            assertTrue(stackOf(route).anyBlocksAutomation, route.key.value)
        }
    }

    @Test
    fun transparentRoutesDoNotBlockAutomation() {
        for (route in transparentRoutes) {
            assertFalse(stackOf(route).anyBlocksAutomation, route.key.value)
        }
    }

    /**
     * S17: the online connect card is occluded by the legacy `otherModal` set,
     * now declared per-route on [inego.zungjunggame.ui.stack.LayerSpec].
     */
    @Test
    fun connectCardIsSuppressedByTheLegacyOtherModalSet() {
        for (route in otherModalRoutes) {
            assertTrue(RouteSpecs.specFor(route).suppressesConnectCard, route.key.value)
        }
    }

    @Test
    fun connectCardIsNotSuppressedByTheTableBreakdownCoachOrQuiz() {
        for (route in nonSuppressingRoutes) {
            assertFalse(RouteSpecs.specFor(route).suppressesConnectCard, route.key.value)
        }
        assertFalse(RouteSpecs.specFor(Route.Modal(RouteIds.ONLINE_CONNECT)).suppressesConnectCard)
        assertFalse(RouteSpecs.specFor(floor).suppressesConnectCard)
    }

    private companion object {
        val blockingRoutes = listOf(
            Route.Overlay(RouteIds.AWAY),
            Route.Modal(RouteIds.SCORE_TABLE),
            Route.Modal(RouteIds.CONFIRM),
            Route.Modal(RouteIds.GAME_MENU),
            Route.Reference(StackState.EMPTY),
            Route.Overlay(RouteIds.TUTORIAL_COACH),
            Route.Overlay(RouteIds.TUTORIAL_QUIZ),
        )

        val transparentRoutes = listOf(
            Route.Overlay(RouteIds.SCORE_BREAKDOWN),
            Route.Overlay(RouteIds.TUTORIAL_LESSON),
            // The connecting card is not part of the legacy automation-blocking set.
            Route.Modal(RouteIds.ONLINE_CONNECT),
            // The shell hosts the lesson; the lesson and shell are all transparent.
            Route.Layer(RouteIds.TUTORIAL_SHELL),
        )

        val otherModalRoutes = listOf(
            Route.Overlay(RouteIds.AWAY),
            Route.Modal(RouteIds.GAME_MENU),
            Route.Modal(RouteIds.LANGUAGE_PICKER),
            Route.Modal(RouteIds.SETTINGS),
            Route.Modal(RouteIds.FIRST_LOGIN_ALIAS),
            Route.Modal(RouteIds.STARTING_LEVEL),
            Route.Modal(RouteIds.MATCH_HISTORY),
            Route.Reference(StackState.EMPTY),
            Route.Modal(RouteIds.CONFIRM),
            Route.Modal(RouteIds.NEW_GAME_SETUP),
        )

        val nonSuppressingRoutes = listOf(
            Route.Modal(RouteIds.SCORE_TABLE),
            Route.Overlay(RouteIds.SCORE_BREAKDOWN),
            Route.Overlay(RouteIds.TUTORIAL_COACH),
            Route.Overlay(RouteIds.TUTORIAL_QUIZ),
            Route.Overlay(RouteIds.TUTORIAL_LESSON),
        )
    }
}
