package inego.zungjunggame.ui.stack

import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterStackPersistenceTest {

    private val home = Route.Screen("home")
    private val settings = Route.Modal("settings")
    private val banner = Route.Overlay("banner")
    private val reference = Route.Reference(StackState.EMPTY)

    private val knownIds = setOf("home", "settings", "banner", "local_menu")

    private fun routeId(route: Route): String? = route.idOrNull

    /** The entry the production snapshot would write for this route. */
    private fun Route.entry(componentState: String? = null): RouteEntryState =
        toEntryState(RouteSpecs.specFor(this).policy, componentState)

    private val strictComponents = ComponentFactory { route ->
        val id = routeId(route)
        require(id == null || id in knownIds) { "unknown route id: $id" }
        FakeComponent(ComponentId(route.key.value))
    }

    private val routeSpecs = LayerSpecProvider { route -> RouteSpecs.specFor(route) }

    private fun router(
        components: ComponentFactory = strictComponents,
        specs: LayerSpecProvider = routeSpecs,
        changes: MutableList<Stack> = mutableListOf(),
        removed: MutableList<RouteKey> = mutableListOf(),
    ): Router = Router(home, components, specs, onEntryRemoved = { removed += it })
        .apply { addObserver { changes += it } }

    @Test
    fun snapshotKeepsTheWholeStackExceptAnnouncedLayers() {
        val router = Router(
            home,
            { route -> FakeComponent(ComponentId(route.key.value)) },
            { RouteSpecs.specFor(it) },
        )
        router.push(Route.Modal(RouteIds.GAME_MENU))
        router.push(Route.Overlay(RouteIds.AWAY))
        router.push(reference)

        val snapshot = router.snapshot { null }

        assertEquals(
            listOf("screen:home", "modal:${RouteIds.GAME_MENU}", "reference"),
            snapshot.entries.map { it.routeKey },
        )
    }

    @Test
    fun restoreRebuildsOrderedStackAndNotifiesOnce() {
        val changes = mutableListOf<Stack>()
        val router = router(changes = changes)
        val state = StackState(
            listOf(
                home.entry(),
                settings.entry(),
                banner.entry(),
            ),
        )

        assertTrue(router.restore(state))
        assertEquals(
            listOf(home.key, settings.key, banner.key),
            router.stack.entries.map { it.route.key },
        )
        assertEquals(LayerRole.Screen, router.stack.floor.spec.role)
        assertEquals(1, changes.size)
    }

    @Test
    fun snapshotSchemaRestoreRoundTrip() {
        // Presentation travels with how a layer was pushed, so the entry — not the
        // declaration — is what the snapshot carries and what restore reads back.
        val localMenu = Route.Layer("local_menu", policy = LayerPolicy(Presentation.Below, persists = true))
        val source = router()
        source.push(settings)
        source.push(banner)
        source.push(localMenu)

        val decoded = StackStateSchema.decode(StackStateSchema.encode(source.snapshot { null }))!!

        val target = router()
        assertTrue(target.restore(decoded))
        assertEquals(
            source.stack.entries.map { it.route.key },
            target.stack.entries.map { it.route.key },
        )
        assertEquals(localMenu.policy, target.stack.top.spec.policy)
    }

    @Test
    fun restoreRejectsUnknownRouteId() {
        val changes = mutableListOf<Stack>()
        val router = router(changes = changes)
        val state = StackState(listOf(Route.Screen("mystery").entry()))

        assertFalse(router.restore(state))
        assertEquals(listOf(home.key), router.stack.entries.map { it.route.key })
        assertTrue(changes.isEmpty())
    }

    @Test
    fun restoreRejectsNonScreenFloor() {
        val router = router()
        val state = StackState(listOf(settings.entry()))

        assertFalse(router.restore(state))
        assertEquals(listOf(home.key), router.stack.entries.map { it.route.key })
    }

    @Test
    fun restoreRejectsDuplicateKeys() {
        val router = router()
        val state = StackState(
            listOf(home.entry(), settings.entry(), settings.entry()),
        )

        assertFalse(router.restore(state))
        assertEquals(listOf(home.key), router.stack.entries.map { it.route.key })
    }

    @Test
    fun restoreEmptyReturnsFalse() {
        val router = router()
        assertFalse(router.restore(StackState.EMPTY))
        assertEquals(listOf(home.key), router.stack.entries.map { it.route.key })
    }

    /**
     * The snapshot's state text is a projection of the route UI store: the caller
     * supplies every entry's text, and an entry whose store has none carries none.
     * The snapshot never reads a component to learn a value.
     */
    @Test
    fun snapshotUsesTheStateProjection() {
        val source = router()

        assertEquals("projected", source.snapshot { "projected" }.entries.single().componentState)
        assertNull(source.snapshot { null }.entries.single().componentState)
    }

    /**
     * Restore rebuilds entries only; seeding the route UI store from the incoming
     * stack is the composition root's job, so an entry's state text neither breaks
     * the rebuild nor reaches a component.
     */
    @Test
    fun restoreIgnoresEntryStateText() {
        val router = router()
        val state = StackState(listOf(home.entry(componentState = "junk")))

        assertTrue(router.restore(state))
        assertEquals(listOf(home.key), router.stack.entries.map { it.route.key })
        assertTrue(router.stack.floor.component is FakeComponent)
    }

    @Test
    fun entryRemovalNotifiesExactlyTheLeavingKeys() {
        val removed = mutableListOf<RouteKey>()
        val router = router(removed = removed)
        router.push(settings)
        router.push(banner)
        assertTrue(removed.isEmpty(), "a push removes nothing")

        assertTrue(router.pop())
        assertEquals(listOf(banner.key), removed.toList())

        assertTrue(router.remove(settings.key))
        assertEquals(listOf(banner.key, settings.key), removed.toList())
    }

    @Test
    fun restoreReplacingTheStackReportsTheEntriesItDrops() {
        val removed = mutableListOf<RouteKey>()
        val router = router(removed = removed)
        router.push(settings)

        assertTrue(router.restore(StackState(listOf(home.entry(), banner.entry()))))

        // Restore materializes fresh instances for every entry, the floor included,
        // so both old keys clear; the composition root re-seeds from the incoming
        // stack right after the rebuild.
        assertEquals(listOf(home.key, settings.key), removed.toList())
    }

    @Test
    fun failedRestoreDoesNotReportRemovals() {
        val removed = mutableListOf<RouteKey>()
        val router = router(removed = removed)
        router.push(settings)

        assertFalse(router.restore(StackState(listOf(Route.Screen("mystery").entry()))))

        assertTrue(removed.isEmpty(), "a rejected restore never reached the stack")
    }
}
