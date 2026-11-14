package inego.zungjunggame.features.tutorial

import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.RememberedRouteState
import inego.zungjunggame.ui.RouteUiActions
import inego.zungjunggame.ui.RouteUiState
import inego.zungjunggame.ui.RouteUiValue
import inego.zungjunggame.ui.testFrame
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.Transform
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.RouteKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TutorialShellComponentTest {

    private val route = Route.Layer(RouteIds.TUTORIAL_SHELL)
    private val body = Rect(0f, 0f, 800f, 600f)
    private val content = Rect(200f, 20f, 580f, 560f)
    private val reserved = Rect(200f, 200f, 40f, 48f)

    private fun geometry(): TutorialShellGeometry = TutorialShellGeometry(
        body = body,
        content = content,
        reserved = listOf(reserved),
    )

    private fun component(): TutorialShellComponent = TutorialShellComponent(
        route = route,
        shell = { error("a fake measure must not construct TutorialShell") },
        measure = { _, _ -> geometry() },
    )

    private fun constraints() = Constraints(800f, 600f, 0f, 0f, 0f)

    @Test
    fun layoutPublishesTheBodyItOwnsAndTheContentZone() {
        val layout = component().layout(testFrame(constraints()))

        // The shell's own bounds carry the wheel, the content zone is what the
        // lesson layers above it are laid out in: two rects, not one. No nav row
        // is a hit region — the shell claims its rail and reports the row a
        // release selected, so a drag ending over a row cannot select it.
        assertEquals(body, layout.panel)
        assertEquals(Transform(content, listOf(reserved)), layout.space)
        assertTrue(layout.scrollable)
        assertEquals(0, layout.hitRegions.size)
    }

    @Test
    fun navSelectMapsToTutorialSelectAndOutsideBacks() {
        assertEquals(
            Intent.TutorialSelect("lesson.a"),
            TutorialShellActions.toIntent(
                route,
                ComponentAction(
                    TutorialShellActions.NAV_SELECT,
                    mapOf(TutorialShellActions.KEY_LESSON_ID to "lesson.a"),
                ),
            ),
        )
        assertNull(TutorialShellActions.toIntent(route, ComponentAction("tutorialShell.other")))
        assertEquals(Intent.Back, TutorialShellActions.toIntent(route, ComponentAction(TutorialShellActions.BACK)))
        assertEquals(Intent.Back, TutorialShellActions.outside())
    }

    @Test
    fun registerWiresShellContextRoute() {
        val registry = FeatureRegistry()
        TutorialShellFeature.register(registry) {
            error("a fake registry must not construct TutorialShell")
        }

        assertTrue(registry.create(route) is TutorialShellComponent)
        assertEquals(LayerRole.Context, registry.specFor(route).role)
        assertEquals(
            Intent.TutorialSelect("lesson.a"),
            registry.actionToIntent(
                route,
                ComponentAction(
                    TutorialShellActions.NAV_SELECT,
                    mapOf(TutorialShellActions.KEY_LESSON_ID to "lesson.a"),
                ),
            ),
        )
        assertEquals(Intent.Back, registry.outsideIntent(route))
    }

    @Test
    fun closedDrawerSurvivesLeavingTheShell() {
        val remembered = FakeRememberedRouteState()
        val routeUi = RouteUiState(remembered)
        val registry = FeatureRegistry()
        TutorialShellFeature.register(registry, routeUi) {
            error("a fake registry must not construct TutorialShell")
        }
        val closed = TutorialShellState(tocScrollY = 40f, drawerOpen = false, pullTabYFrac = 0.25f)
        val intent = registry.actionToIntent(route, RouteUiActions.write(TutorialShellCodec, closed))
        val write = intent as Intent.WriteRouteUi

        assertTrue(write.remember)
        routeUi.put(route.key, write.value, remember = true)
        // Exit to the main menu removes the shell entry and clears its live value.
        routeUi.remove(route.key)

        assertEquals(closed, routeUi.port(route.key, TutorialShellCodec).read())
    }
}

/** Stands in for the persisted `ui.remembered` key. */
private class FakeRememberedRouteState : RememberedRouteState {
    private val values = mutableMapOf<RouteKey, RouteUiValue>()

    override fun recall(key: RouteKey): RouteUiValue? = values[key]

    override fun store(key: RouteKey, value: RouteUiValue) {
        values[key] = value
    }
}
