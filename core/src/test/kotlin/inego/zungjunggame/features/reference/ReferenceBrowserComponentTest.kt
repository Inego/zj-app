package inego.zungjunggame.features.reference

import inego.zungjunggame.input.Intent
import inego.zungjunggame.render.reference.ReferenceCatalog
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.StackState
import inego.zungjunggame.ui.testFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferenceBrowserComponentTest {

    private val route = Route.Reference(StackState.EMPTY)

    private fun component(): ReferenceBrowserComponent =
        ReferenceBrowserComponent(
            route = route,
            overlay = { error("layout and action mapping must not construct ReferenceBrowserOverlay") },
        )

    private fun constraints() = Constraints(800f, 600f, 0f, 0f, 0f)

    @Test
    fun layoutPublishesTheFullScreenScrollablePanel() {
        val layout = component().layout(testFrame(constraints()))

        // The browser replaces the table: the panel is the whole viewport and
        // the layer opts into wheel/drag routing. Taps are resolved by the
        // overlay, so no hit regions are duplicated here.
        assertEquals(Rect(0f, 0f, 800f, 600f), layout.panel)
        assertTrue(layout.scrollable)
        assertEquals(0, layout.hitRegions.size)
    }

    @Test
    fun actionForMapsTheResolvedControl() {
        assertEquals(
            ComponentAction(ReferenceActions.DISMISS),
            ReferenceActions.actionFor(ReferenceVerdict(close = true)),
        )
        assertEquals(
            ComponentAction(ReferenceActions.HISTORY_BACK),
            ReferenceActions.actionFor(ReferenceVerdict(historyBack = true)),
        )
        assertEquals(
            ComponentAction(
                ReferenceActions.TOC_PAGE,
                mapOf(ReferenceActions.KEY_PAGE_ID to "pattern-2.1.1"),
            ),
            ReferenceActions.actionFor(ReferenceVerdict(tocPageId = "pattern-2.1.1")),
        )
        assertEquals(
            ComponentAction(
                ReferenceActions.LINK,
                mapOf(ReferenceActions.KEY_HREF to "about"),
            ),
            ReferenceActions.actionFor(ReferenceVerdict(linkHref = "about")),
        )
        assertEquals(
            ComponentAction(
                ReferenceActions.TAB_SECTION,
                mapOf(ReferenceActions.KEY_SECTION to ReferenceCatalog.Section.Scoring.id),
            ),
            ReferenceActions.actionFor(
                ReferenceVerdict(section = ReferenceCatalog.Section.Scoring),
            ),
        )
    }

    @Test
    fun actionForIgnoresScrollsAndEmptyResolutions() {
        // A drag or a miss resolves no control, so there is no action to emit.
        assertNull(ReferenceActions.actionFor(ReferenceVerdict()))
    }

    @Test
    fun actionsMapControlsAndResolveArticleLinks() {
        assertEquals(Intent.Back, ReferenceActions.toIntent(route, ComponentAction(ReferenceActions.DISMISS)))
        assertEquals(
            Intent.ReferenceBack,
            ReferenceActions.toIntent(route, ComponentAction(ReferenceActions.HISTORY_BACK)),
        )
        assertEquals(
            Intent.OpenReference("pattern-2.1.1"),
            ReferenceActions.toIntent(
                route,
                ComponentAction(
                    ReferenceActions.TOC_PAGE,
                    mapOf(ReferenceActions.KEY_PAGE_ID to "pattern-2.1.1"),
                ),
            ),
        )
        assertEquals(
            Intent.OpenReference(ReferenceCatalog.ABOUT_PAGE_ID),
            ReferenceActions.toIntent(
                route,
                ComponentAction(ReferenceActions.LINK, mapOf(ReferenceActions.KEY_HREF to "about")),
            ),
        )
        assertEquals(
            Intent.OpenReferenceSection(ReferenceCatalog.Section.Scoring.id),
            ReferenceActions.toIntent(
                route,
                ComponentAction(ReferenceActions.LINK, mapOf(ReferenceActions.KEY_HREF to "scoring")),
            ),
        )
        assertEquals(
            Intent.OpenReferenceSection(ReferenceCatalog.Section.Scoring.id),
            ReferenceActions.toIntent(
                route,
                ComponentAction(
                    ReferenceActions.TAB_SECTION,
                    mapOf(ReferenceActions.KEY_SECTION to ReferenceCatalog.Section.Scoring.id),
                ),
            ),
        )
        assertEquals(
            Intent.OpenReferenceExternal("https://example.com/x"),
            ReferenceActions.toIntent(
                route,
                ComponentAction(
                    ReferenceActions.LINK,
                    mapOf(ReferenceActions.KEY_HREF to "https://example.com/x"),
                ),
            ),
        )
        assertNull(ReferenceActions.toIntent(route, ComponentAction("reference.other")))
        assertNull(ReferenceActions.outside())
    }

    @Test
    fun registerWiresReferenceRoute() {
        val registry = FeatureRegistry()
        ReferenceFeature.register(registry) {
            error("a fake registry must not construct ReferenceBrowserOverlay")
        }

        assertTrue(registry.create(route) is ReferenceBrowserComponent)
        assertEquals(
            RouteSpecs.specFor(route),
            registry.specFor(route),
        )
        assertEquals(LayerRole.Overlay, registry.specFor(route).role)
        assertTrue(registry.specFor(route).ownsInput)
        assertTrue(registry.specFor(route).dismissOnBack)
        assertTrue(registry.specFor(route).policy.persists)
        assertEquals(
            Intent.Back,
            registry.actionToIntent(route, ComponentAction(ReferenceActions.DISMISS)),
        )
        assertEquals(
            Intent.ReferenceBack,
            registry.actionToIntent(route, ComponentAction(ReferenceActions.HISTORY_BACK)),
        )
        assertNull(registry.outsideIntent(route))
    }

    @Test
    fun overlayReferenceFormIsNotRegistered() {
        val registry = FeatureRegistry()
        ReferenceFeature.register(registry) {
            error("a fake registry must not construct ReferenceBrowserOverlay")
        }

        assertFailsWith<IllegalArgumentException> {
            registry.create(Route.Overlay(RouteIds.REFERENCE))
        }
    }
}
