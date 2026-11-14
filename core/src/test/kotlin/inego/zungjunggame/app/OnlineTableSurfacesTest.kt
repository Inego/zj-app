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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The table's stack landing and its origin tab.
 *
 * The specs and the router are the production ones — the origin arg and the hub
 * parent are the subject — and [OnlineTableSurfaces] is driven through the real
 * `OnlineTablePort` seam, so a restored `[root, hub, table]` is a real restore,
 * not a fixture. The hub's tab owner is a recording seam.
 */
class OnlineTableSurfacesTest {

    private class StubComponent(override val id: ComponentId) : Component {
        override fun layout(frame: Frame): Layout = Layout()
        override fun draw(frame: Frame, canvas: Canvas) {}
    }

    private class Env {
        val router = Router(Route.Screen(RouteIds.ROOT_MENU), COMPONENTS, SPECS)
        val selected = mutableListOf<OnlineTab>()
        private val transitions = ScreenTransitions(RouteController(router))
        val surfaces = OnlineTableSurfaces(
            transitions = { transitions },
            selectHubTab = { selected += it },
        )

        val keys: List<String> get() = router.stack.entries.map { it.route.key.value }
    }

    @Test
    fun aLiveTableReturnsToTheLiveTab() {
        val env = Env()

        env.surfaces.tableJoined(OnlineTab.Live)
        assertEquals(
            listOf(
                "screen:${RouteIds.ROOT_MENU}",
                "layer:${RouteIds.ONLINE_HUB}",
                "layer:${RouteIds.ONLINE_TABLE}",
            ),
            env.keys,
        )

        env.surfaces.tableExited()

        assertEquals(listOf(OnlineTab.Live), env.selected)
        assertEquals(
            listOf("screen:${RouteIds.ROOT_MENU}", "layer:${RouteIds.ONLINE_HUB}"),
            env.keys,
        )
    }

    @Test
    fun aShadowRankedTableReturnsToTheShadowRankedTab() {
        val env = Env()

        env.surfaces.tableJoined(OnlineTab.ShadowRanked)
        env.surfaces.tableExited()

        assertEquals(listOf(OnlineTab.ShadowRanked), env.selected)
        assertEquals(
            listOf("screen:${RouteIds.ROOT_MENU}", "layer:${RouteIds.ONLINE_HUB}"),
            env.keys,
        )
    }

    @Test
    fun aRestoredTableKeepsTheOriginItWasLeftWith() {
        val first = Env()
        first.surfaces.tableJoined(OnlineTab.ShadowRanked)
        val persisted = first.router.snapshot { null }

        val restored = Env()
        restored.router.restore(persisted)
        // The boot/reconcile rejoin path announces a live table with no origin of its
        // own; the restored route's args are the record and must win.
        restored.surfaces.tableJoined(OnlineTab.Live)
        restored.surfaces.tableExited()

        assertEquals(listOf(OnlineTab.ShadowRanked), restored.selected)
    }

    /**
     * **Return home** from a table (the hand-end chip, the connect card) leaves the
     * table through the same [OnlineTablePort.tableExited], so the hub it reveals is
     * on the tab the table was entered from — never the old lobby.
     */
    @Test
    fun returningHomeLeavesTheHubOnTheTablesOriginTab() {
        for (origin in OnlineTab.entries) {
            val env = Env()
            env.surfaces.tableJoined(origin)
            env.surfaces.tableExited()

            assertEquals(
                listOf("screen:${RouteIds.ROOT_MENU}", "layer:${RouteIds.ONLINE_HUB}"),
                env.keys,
                origin.name,
            )
            assertEquals(listOf(origin), env.selected, origin.name)
        }
    }

    private companion object {
        val COMPONENTS = ComponentFactory { route -> StubComponent(ComponentId(route.key.value)) }
        val SPECS = LayerSpecProvider { route -> RouteSpecs.specFor(route) }
    }
}
