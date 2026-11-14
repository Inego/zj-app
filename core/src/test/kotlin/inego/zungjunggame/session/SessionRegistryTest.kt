package inego.zungjunggame.session

import inego.zungjunggame.engine.GameState
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.session.hosts.ConnectionStatus
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionRegistryTest {

    private class StubComponent(override val id: ComponentId) : Component {
        override fun layout(frame: Frame): Layout = Layout()
        override fun draw(frame: Frame, canvas: Canvas) {}
    }

    private val components = ComponentFactory { route -> StubComponent(ComponentId(route.key.value)) }

    /** The real declarations: the context a layer establishes is what [SessionKind] reads. */
    private val specs = LayerSpecProvider { route -> RouteSpecs.specFor(route) }

    /** Mutable host/hand sources the registry reads through its providers. */
    private class Sources {
        var local: PlayHost? = null
        var tutorial: PlayHost? = null
        var online: PlayHost? = null
        var hand: LiveHand? = null
        var onlineTableId: String? = null
    }

    private val root = Route.Screen(RouteIds.ROOT_MENU)
    private val localMenu = Route.Layer(RouteIds.LOCAL_MENU)
    private val localBoard = Route.Layer(RouteIds.LOCAL_BOARD)
    private val tutorialShell = Route.Layer(RouteIds.TUTORIAL_SHELL)

    private fun router(vararg entries: Route): Router {
        val router = Router(entries.first(), components, specs)
        entries.drop(1).forEach { router.push(it) }
        return router
    }

    private fun registry(
        vararg entries: Route,
        sources: Sources = Sources(),
    ): Pair<SessionRegistry, Sources> {
        val router = router(*entries)
        val registry = SessionRegistry(
            stack = { router.stack },
            localHostSource = { sources.local },
            tutorialHostSource = { sources.tutorial },
            onlineHostSource = { sources.online },
            handSource = { sources.hand },
            onlineTableIdSource = { sources.onlineTableId },
        )
        return registry to sources
    }

    private fun hand(active: Boolean, state: GameState? = if (active) GameState() else null): LiveHand =
        FakeHand().apply {
            this.active = active
            this.state = state
        }

    @Test
    fun onlineHostAttachedIsOnlineAndBoardHosted() {
        val (registry, sources) = registry(root)
        val online = FakeHost()
        sources.online = online

        assertEquals(SessionKind.Online, registry.kind)
        assertTrue(registry.boardHosted)
        assertEquals(online, registry.onlineHost)
        assertEquals(online, registry.activeHost)
    }

    @Test
    fun tutorialShellIsTutorial() {
        val (registry, sources) = registry(root, tutorialShell)
        sources.hand = hand(active = true)

        assertEquals(SessionKind.Tutorial, registry.kind)
        assertTrue(registry.boardHosted)
    }

    @Test
    fun tutorialShellWithoutHandIsStillTutorial() {
        // The shell declares the context; a lesson that has not started yet does
        // not turn the app back into the menu.
        val (registry, _) = registry(root, tutorialShell)

        assertEquals(SessionKind.Tutorial, registry.kind)
        assertFalse(registry.boardHosted)
    }

    @Test
    fun tutorialHandInactiveIsNotBoardHosted() {
        val (registry, sources) = registry(root, tutorialShell)
        sources.hand = hand(active = false)

        assertEquals(SessionKind.Tutorial, registry.kind)
        assertFalse(registry.boardHosted)
    }

    @Test
    fun localMenuIsFreePlay() {
        val (registry, sources) = registry(root, localMenu)
        sources.hand = hand(active = true)

        assertEquals(SessionKind.FreePlay, registry.kind)
        assertTrue(registry.boardHosted)
    }

    @Test
    fun freePlayHiddenHandIsNotBoardHosted() {
        val (registry, sources) = registry(root, localMenu, localBoard)
        sources.hand = hand(active = true, state = null)

        assertEquals(SessionKind.FreePlay, registry.kind)
        assertFalse(registry.boardHosted)
    }

    @Test
    fun rootMenuIsMenuEvenWithLocalHostAndHand() {
        val (registry, sources) = registry(root)
        sources.local = FakeHost()
        sources.hand = hand(active = true)

        assertEquals(SessionKind.Menu, registry.kind)
        assertFalse(registry.boardHosted)
        assertFalse(registry.sessionOwnsDisplay)
    }

    @Test
    fun activeHostPrefersOnlineThenTutorialThenLocal() {
        val (registry, sources) = registry(root)
        val local = FakeHost()
        val tutorial = FakeHost()
        val online = FakeHost()

        assertNull(registry.activeHost)
        sources.local = local
        assertEquals(local, registry.activeHost)
        sources.tutorial = tutorial
        assertEquals(tutorial, registry.activeHost)
        sources.online = online
        assertEquals(online, registry.activeHost)

        sources.online = null
        assertEquals(tutorial, registry.activeHost)
        sources.tutorial = null
        assertEquals(local, registry.activeHost)
    }

    @Test
    fun stackChangeChangesKind() {
        val router = router(root)
        val live = hand(active = true)
        val registry = SessionRegistry(
            stack = { router.stack },
            localHostSource = { null },
            tutorialHostSource = { null },
            onlineHostSource = { null },
            handSource = { live },
        )

        assertEquals(SessionKind.Menu, registry.kind)

        router.push(localMenu)
        assertEquals(SessionKind.FreePlay, registry.kind)
        assertTrue(registry.sessionOwnsDisplay)

        // Leaving the local context and entering the tutorial one: the kind follows
        // the topmost context layer, and the floor never changes.
        router.popTo(root.key)
        router.push(tutorialShell)
        assertEquals(SessionKind.Tutorial, registry.kind)
    }

    @Test
    fun snapshotCarriesDerivedState() {
        val (registry, sources) = registry(root, Route.Layer(RouteIds.ONLINE_TABLE))
        sources.online = FakeHost().apply { connectionStatus = ConnectionStatus.Connected }
        sources.hand = hand(active = true)
        sources.onlineTableId = "table-7"

        val snapshot = registry.snapshot()

        assertEquals(SessionKind.Online, snapshot.kind)
        assertTrue(snapshot.boardHosted)
        assertEquals(ConnectionStatus.Connected, snapshot.table?.connectionStatus)
        assertEquals(true, snapshot.hand?.active)
        assertEquals("table-7", snapshot.onlineTableId)
    }

    @Test
    fun snapshotOmitsUnattachedSources() {
        val (registry, _) = registry(root)

        val snapshot = registry.snapshot()

        assertEquals(SessionKind.Menu, snapshot.kind)
        assertFalse(snapshot.boardHosted)
        assertNull(snapshot.table)
        assertNull(snapshot.hand)
        assertNull(snapshot.onlineTableId)
    }

    @Test
    fun frameCarriesActiveHostAndLiveHand() {
        val (registry, sources) = registry(root, localMenu, localBoard)
        val host = FakeHost()
        val live = hand(active = true)
        sources.local = host
        sources.hand = live

        val frame = registry.frame()

        assertEquals(SessionKind.FreePlay, frame.kind)
        assertEquals(host, frame.playHost)
        assertEquals(live, frame.liveHand)
        assertNull(frame.onlineHost)
        assertTrue(frame.boardHosted)
    }
}
