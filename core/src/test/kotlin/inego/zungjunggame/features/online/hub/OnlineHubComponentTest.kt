package inego.zungjunggame.features.online.hub

import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.StatePort
import inego.zungjunggame.ui.testFrame
import inego.zungjunggame.ui.stack.OnlineTab
import inego.zungjunggame.ui.stack.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Geometry and routing tests for the hub shell. The shell's `measure` is pure
 * arithmetic, so these run on a plain JVM with no GL context.
 */
class OnlineHubComponentTest {

    private val route = Route.Layer(RouteIds.ONLINE_HUB)

    private fun frame(w: Float = 800f, h: Float = 600f) = testFrame(Constraints(w, h, 0f, 0f, 0f))

    private fun component(tab: OnlineTab) =
        OnlineHubComponent(route, state = StatePort { OnlineHubState(tab) })

    /**
     * The tab strip and close button are discrete taps: `layout()` publishes
     * exactly the rects [OnlineHubOverlay.measure] hands the painter, and the body
     * is the one gesture region. The loop also proves the regions are published by
     * `layout()` itself — `draw()` is never called.
     */
    @Test
    fun layoutPublishesTheTabAndCloseRectsTheShellPaints() {
        for (tab in OnlineTab.entries) {
            val layout = component(tab).layout(frame())
            val geometry = OnlineHubOverlay.measure(800f, 600f, OnlineHubState(tab))

            assertEquals(geometry.panel, layout.panel)
            assertEquals(listOf(geometry.body), layout.gestureRegions)
            assertTrue(layout.scrollable)
            assertEquals(
                listOf(geometry.liveTab),
                layout.hitRegions.rectsFor(ComponentAction(OnlineHubActions.SELECT_LIVE_TAB)),
            )
            assertEquals(
                listOf(geometry.shadowTab),
                layout.hitRegions.rectsFor(ComponentAction(OnlineHubActions.SELECT_SHADOW_TAB)),
            )
            assertEquals(
                listOf(geometry.close),
                layout.hitRegions.rectsFor(ComponentAction(OnlineHubActions.DISMISS)),
            )
        }
    }

    /**
     * The Live tab carries an in-progress-search dot exactly while the model says
     * a search is running, and the dot nests inside the Live tab's own rect so
     * its label and its tab strip stay one geometry. It is drawn for either open
     * tab: a search the player left running behind Shadow Ranked stays visible.
     */
    @Test
    fun theLiveTabShowsTheSearchIndicatorOnlyWhileSearching() {
        val state = OnlineHubState(OnlineTab.ShadowRanked)

        assertNull(OnlineHubOverlay.measure(800f, 600f, state, searching = false).liveSearchIndicator)
        val dot = OnlineHubOverlay.measure(800f, 600f, state, searching = true).liveSearchIndicator
        assertNotNull(dot)
        val geometry = OnlineHubOverlay.measure(800f, 600f, state, searching = true)
        assertTrue(geometry.liveTab.contains(dot.centerX, dot.centerY), "dot on the Live tab")
    }

    @Test
    fun panelsRouteByTab() {
        val overlay = OnlineHubOverlay()
        assertSame(overlay.livePanel, overlay.panelFor(OnlineTab.Live))
        assertSame(overlay.shadowPanel, overlay.panelFor(OnlineTab.ShadowRanked))

        for (tab in OnlineTab.entries) {
            assertEquals(tab, OnlineHubOverlay.measure(800f, 600f, OnlineHubState(tab)).activeTab)
        }
    }
}
