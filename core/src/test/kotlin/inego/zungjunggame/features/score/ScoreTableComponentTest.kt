package inego.zungjunggame.features.score

import inego.zungjunggame.engine.SessionScoreTable
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

class ScoreTableComponentTest {

    private val route = Route.Modal(RouteIds.SCORE_TABLE)
    private val panel = Rect(10f, 20f, 300f, 200f)

    private fun component(): ScoreTableComponent = ScoreTableComponent(
        route = route,
        table = { SessionScoreTable() },
        humanSeat = { 0 },
        measure = { _, _ -> ScoreTableGeometry(panel) },
    )

    private fun constraints() = Constraints(400f, 300f, 0f, 0f, 0f)

    @Test
    fun layoutPublishesTheCardAsItsOwnGestureRegion() {
        val layout = component().layout(testFrame(constraints()))

        // The table owns the wheel and the drag over its own bounds; the close
        // control is reported by the overlay on release, not by a hit region.
        assertEquals(panel, layout.panel)
        assertEquals(listOf(panel), layout.gestureRegions)
        assertTrue(layout.scrollable)
        assertEquals(0, layout.hitRegions.size)
    }

    @Test
    fun actionsMapDismissAndOutside() {
        assertEquals(Intent.Back, ScoreTableActions.toIntent(route, ComponentAction(ScoreTableActions.DISMISS)))
        assertNull(ScoreTableActions.toIntent(route, ComponentAction("scoreTable.other")))
        assertEquals(Intent.Back, ScoreTableActions.outside())
    }

    @Test
    fun registerWiresScoreTableRoute() {
        val registry = FeatureRegistry()
        ScoreTableFeature.register(registry, { SessionScoreTable() }, { 0 })

        assertTrue(registry.create(route) is ScoreTableComponent)
        assertEquals(LayerRole.Modal, registry.specFor(route).role)
        assertEquals(Intent.Back, registry.outsideIntent(route))
    }
}
