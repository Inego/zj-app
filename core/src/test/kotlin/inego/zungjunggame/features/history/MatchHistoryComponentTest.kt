package inego.zungjunggame.features.history

import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.testFrame
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchHistoryComponentTest {

    private val route = Route.Modal(RouteIds.MATCH_HISTORY)
    private val panel = Rect(10f, 20f, 300f, 400f)

    private fun component(): MatchHistoryComponent = MatchHistoryComponent(
        route = route,
        measure = { _, _ -> MatchHistoryGeometry(panel) },
    )

    private fun constraints() = Constraints(400f, 500f, 0f, 0f, 0f)

    @Test
    fun layoutPublishesTheCardAsItsOwnGestureRegion() {
        val layout = component().layout(testFrame(constraints()))

        // The card owns the wheel and the drag over its own bounds; its close
        // control and load-more row are reported by the overlay on release.
        assertEquals(panel, layout.panel)
        assertEquals(listOf(panel), layout.gestureRegions)
        assertTrue(layout.scrollable)
        assertEquals(0, layout.hitRegions.size)
    }

    @Test
    fun mapsDismissAndLoadMore() {
        assertEquals(Intent.Back, MatchHistoryActions.toIntent(route, ComponentAction(MatchHistoryActions.DISMISS)))
        assertEquals(
            Intent.LoadMoreMatchHistory,
            MatchHistoryActions.toIntent(route, ComponentAction(MatchHistoryActions.LOAD_MORE)),
        )
        assertNull(MatchHistoryActions.toIntent(route, ComponentAction("matchHistory.other")))
        assertEquals(Intent.Back, MatchHistoryActions.outside())
    }

    @Test
    fun registerWiresMatchHistoryRoute() {
        val registry = FeatureRegistry()
        MatchHistoryFeature.register(registry)

        assertTrue(registry.create(route) is MatchHistoryComponent)
        assertEquals(LayerRole.Modal, registry.specFor(route).role)
        assertEquals(Intent.LoadMoreMatchHistory, registry.actionToIntent(route, ComponentAction(MatchHistoryActions.LOAD_MORE)))
        assertEquals(Intent.Back, registry.outsideIntent(route))
    }
}
