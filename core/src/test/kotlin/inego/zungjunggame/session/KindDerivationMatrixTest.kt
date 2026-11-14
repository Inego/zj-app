package inego.zungjunggame.session

import inego.zungjunggame.engine.GameState
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.session.hosts.PlayHost
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.stack.ComponentFactory
import inego.zungjunggame.ui.stack.LayerSpecProvider
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.Router
import inego.zungjunggame.ui.stack.SessionKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Derived [SessionKind] and [SessionRegistry.activeHost] across the stacks the app
 * puts up and the sources that can be attached to them.
 *
 * The kind is the topmost context layer's own declaration ([RouteSpecs]), or the
 * attached online host when there is one. The floor decides nothing by itself, and
 * neither does an attached hand: a table is online because the stack says so.
 */
class KindDerivationMatrixTest {

    private class StubComponent(override val id: ComponentId) : Component {
        override fun layout(frame: Frame): Layout = Layout()
        override fun draw(frame: Frame, canvas: Canvas) {}
    }

    private val components = ComponentFactory { route -> StubComponent(ComponentId(route.key.value)) }
    private val specs = LayerSpecProvider { route -> RouteSpecs.specFor(route) }

    private class Sources(
        val name: String,
        val hand: LiveHand? = null,
        val local: PlayHost? = null,
        val tutorial: PlayHost? = null,
        val online: PlayHost? = null,
    )

    private fun hand() = FakeHand().apply {
        active = true
        state = GameState()
    }

    private val combos = listOf(
        Sources("none"),
        Sources("hand", hand = hand()),
        Sources("local", local = FakeHost()),
        Sources("tutorial", tutorial = FakeHost()),
        Sources("online", online = FakeHost()),
        Sources("hand+local", hand = hand(), local = FakeHost()),
        Sources("hand+tutorial", hand = hand(), tutorial = FakeHost()),
        Sources("hand+online", hand = hand(), online = FakeHost()),
        Sources("local+tutorial+online", local = FakeHost(), tutorial = FakeHost(), online = FakeHost()),
    )

    /** A stack the app can stand on, and the kind its context declares. */
    private class StackCase(val name: String, val entries: List<Route>, val kind: SessionKind)

    private val root = Route.Screen(RouteIds.ROOT_MENU)
    private val localMenu = Route.Layer(RouteIds.LOCAL_MENU)
    private val localBoard = Route.Layer(RouteIds.LOCAL_BOARD)
    private val hub = Route.Layer(RouteIds.ONLINE_HUB)
    private val onlineTable = Route.Layer(RouteIds.ONLINE_TABLE)
    private val tutorialShell = Route.Layer(RouteIds.TUTORIAL_SHELL)

    private val stacks = listOf(
        StackCase("root", listOf(root), SessionKind.Menu),
        StackCase("root+local menu", listOf(root, localMenu), SessionKind.FreePlay),
        StackCase("root+local menu+board", listOf(root, localMenu, localBoard), SessionKind.FreePlay),
        StackCase("root+tutorial shell", listOf(root, tutorialShell), SessionKind.Tutorial),
        StackCase("root+hub", listOf(root, hub), SessionKind.Menu),
        StackCase("root+hub+table", listOf(root, hub, onlineTable), SessionKind.Online),
    )

    private fun expectedActiveHost(s: Sources): PlayHost? = s.online ?: s.tutorial ?: s.local

    @Test
    fun kindAndActiveHostAcrossStackAndSourceMatrix() {
        for (case in stacks) {
            val router = Router(case.entries.first(), components, specs)
            case.entries.drop(1).forEach { router.push(it) }
            for (s in combos) {
                val registry = SessionRegistry(
                    stack = { router.stack },
                    localHostSource = { s.local },
                    tutorialHostSource = { s.tutorial },
                    onlineHostSource = { s.online },
                    handSource = { s.hand },
                )

                // An attached table is a live online session whatever the stack says.
                val kind = if (s.online != null) SessionKind.Online else case.kind
                assertEquals(kind, registry.kind, "kind for stack=${case.name} sources=${s.name}")
                assertEquals(
                    kind != SessionKind.Menu,
                    registry.sessionOwnsDisplay,
                    "display ownership for stack=${case.name} sources=${s.name}",
                )
                assertEquals(
                    expectedActiveHost(s),
                    registry.activeHost,
                    "activeHost for stack=${case.name} sources=${s.name}",
                )
            }
        }
    }
}
