package inego.zungjunggame.features.tutorial

import inego.zungjunggame.input.Intent
import inego.zungjunggame.render.wallbuild.WallBuildDeal
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
import kotlin.random.Random

class TutorialCoachComponentTest {

    private val route = Route.Overlay(RouteIds.TUTORIAL_COACH)

    private fun component(): TutorialCoachComponent = TutorialCoachComponent(
        route = route,
        overlay = { TutorialCoachOverlay() },
    )

    private fun constraints() = Constraints(800f, 600f, 0f, 0f, 0f)

    @Test
    fun layoutPublishesTheSurfacesOwnBoundsAndLeavesControlsToTheWidget() {
        val layout = component().layout(testFrame(constraints()))

        // The panel fills the shell's content zone and resolves its own controls,
        // so the one rect is both the panel and the region the pipeline consults
        // it for — the felt it declines is what leaves the board below playable.
        assertEquals(Rect(0f, 0f, 800f, 600f), layout.panel)
        assertEquals(listOf(Rect(0f, 0f, 800f, 600f)), layout.gestureRegions)
        assertEquals(0, layout.hitRegions.size)
    }

    @Test
    fun actionForMapsEveryResolution() {
        assertEquals(
            ComponentAction(TutorialCoachActions.PRIMARY),
            TutorialCoachActions.actionFor(TutorialCoachOverlay.PointerUpResult(primary = true)),
        )
        assertEquals(
            ComponentAction(TutorialCoachActions.BACK),
            TutorialCoachActions.actionFor(TutorialCoachOverlay.PointerUpResult(back = true)),
        )
        assertEquals(
            ComponentAction(
                TutorialCoachActions.LINK,
                mapOf(TutorialCoachActions.KEY_HREF to "scoring/1.1"),
            ),
            TutorialCoachActions.actionFor(
                TutorialCoachOverlay.PointerUpResult(linkHref = "scoring/1.1"),
            ),
        )
        // The felt, or unlinked copy: the bridge emits nothing but still consumed
        // the lift, so it is not re-read as a tap.
        assertNull(TutorialCoachActions.actionFor(TutorialCoachOverlay.PointerUpResult()))
    }

    @Test
    fun controlsMapToTutorialIntents() {
        assertEquals(
            Intent.TutorialPrimary,
            TutorialCoachActions.toIntent(route, ComponentAction(TutorialCoachActions.PRIMARY)),
        )
        assertEquals(
            Intent.TutorialBack,
            TutorialCoachActions.toIntent(route, ComponentAction(TutorialCoachActions.BACK)),
        )
        assertEquals(
            Intent.TutorialOpenHref("scoring/1.1"),
            TutorialCoachActions.toIntent(
                route,
                ComponentAction(
                    TutorialCoachActions.LINK,
                    mapOf(TutorialCoachActions.KEY_HREF to "scoring/1.1"),
                ),
            ),
        )
        assertEquals(
            Intent.TutorialCoachLeaveComplete,
            TutorialCoachActions.toIntent(route, ComponentAction(TutorialCoachActions.LEAVE_COMPLETE)),
        )
        val deal = WallBuildDeal.invent(Random(0))
        assertEquals(
            Intent.TutorialWallDealCommitted(deal),
            TutorialCoachActions.toIntent(
                route,
                ComponentAction(TutorialCoachActions.WALL_DEAL, mapOf(TutorialCoachActions.KEY_DEAL to deal)),
            ),
        )
        assertNull(TutorialCoachActions.toIntent(route, ComponentAction("tutorialCoach.other")))
        assertNull(TutorialCoachActions.outside())
    }

    @Test
    fun registerWiresCoachRoute() {
        val registry = FeatureRegistry()
        TutorialCoachFeature.register(
            registry,
            overlay = { error("a fake registry must not construct TutorialCoachOverlay") },
        )

        assertTrue(registry.create(route) is TutorialCoachComponent)
        assertEquals(LayerRole.Overlay, registry.specFor(route).role)
        assertEquals(
            Intent.TutorialPrimary,
            registry.actionToIntent(route, ComponentAction(TutorialCoachActions.PRIMARY)),
        )
        assertNull(registry.outsideIntent(route))
    }
}
