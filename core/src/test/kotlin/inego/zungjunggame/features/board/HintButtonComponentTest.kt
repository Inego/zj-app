package inego.zungjunggame.features.board

import inego.zungjunggame.input.Intent
import inego.zungjunggame.render.hint.HintButtonGeometry
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.testFrame
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.Route
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class HintButtonComponentTest {

    private val route = Route.Overlay(RouteIds.HINT_BUTTON)
    private val hit = Rect(600f, 200f, 80f, 40f)

    private fun component(): HintButtonComponent = HintButtonComponent(
        route = route,
        overlay = { error("a fake measure must not construct HintButtonOverlay") },
        measure = { _, _ -> HintButtonGeometry(hit) },
    )

    private fun constraints() = Constraints(800f, 600f, 0f, 0f, 0f)

    @Test
    fun layoutPublishesTheButtonHitBox() {
        val layout = component().layout(testFrame(constraints()))

        assertNull(layout.panel)
        assertEquals(1, layout.hitRegions.size)
        assertEquals(ComponentAction(HintButtonActions.TAP), layout.hitRegions.hitAt(620f, 220f))
        assertNull(layout.hitRegions.hitAt(10f, 10f))
    }

    @Test
    fun tapMapsToHintAndOutsideIsIgnored() {
        assertEquals(Intent.Hint, HintButtonActions.toIntent(route, ComponentAction(HintButtonActions.TAP)))
        assertNull(HintButtonActions.toIntent(route, ComponentAction("hintButton.other")))
        assertNull(HintButtonActions.outside())
    }

    @Test
    fun registerWiresHintButtonRoute() {
        val registry = FeatureRegistry()
        HintButtonFeature.register(registry) { error("a fake registry must not construct HintButtonOverlay") }

        assertTrue(registry.create(route) is HintButtonComponent)
        assertEquals(LayerRole.Overlay, registry.specFor(route).role)
        assertEquals(Intent.Hint, registry.actionToIntent(route, ComponentAction(HintButtonActions.TAP)))
    }
}
