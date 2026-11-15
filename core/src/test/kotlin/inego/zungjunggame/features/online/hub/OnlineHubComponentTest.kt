package inego.zungjunggame.features.online.hub

import inego.zungjunggame.ui.stack.OnlineTab
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
