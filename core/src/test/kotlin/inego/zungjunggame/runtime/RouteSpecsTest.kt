package inego.zungjunggame.runtime

import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.stack.ContentPolicy
import inego.zungjunggame.ui.stack.FakeComponent
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.Router
import inego.zungjunggame.ui.stack.SessionKind
import inego.zungjunggame.ui.stack.StackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteSpecsTest {

    /**
     * The floor and the contexts above it declare their own presentation: what
     * owns input, what establishes a session context, and what leaves the
     * fundamental felt overlay visible behind it.
     */
    @Test
    fun floorAndContextsDeclareTheirOwnPresentation() {
        val root = RouteSpecs.specFor(Route.Screen(RouteIds.ROOT_MENU))
        assertEquals(LayerRole.Screen, root.role)
        assertFalse(root.ownsInput)
        assertFalse(root.blocksAutomation)
        assertNull(root.contextKind)
        assertTrue(root.onFelt)
        assertTrue(root.menuSurface)
        assertTrue(root.policy.persists)

        val localMenu = RouteSpecs.specFor(Route.Layer(RouteIds.LOCAL_MENU))
        assertEquals(LayerRole.Context, localMenu.role)
        assertTrue(localMenu.ownsInput)
        assertEquals(SessionKind.FreePlay, localMenu.contextKind)
        assertTrue(localMenu.onFelt, "a context menu stands on the felt")
        assertTrue(localMenu.menuSurface)
        assertTrue(localMenu.policy.persists)

        val localBoard = RouteSpecs.specFor(Route.Layer(RouteIds.LOCAL_BOARD))
        assertEquals(LayerRole.Context, localBoard.role)
        assertTrue(localBoard.ownsInput)
        assertEquals(SessionKind.FreePlay, localBoard.contextKind)
        assertFalse(localBoard.onFelt, "a board paints its own surface")
        assertFalse(localBoard.menuSurface)

        // The hub is the unified online home: a context that owns its input and
        // stands on the felt, with no live session of its own.
        val hub = RouteSpecs.specFor(Route.Layer(RouteIds.ONLINE_HUB))
        assertEquals(LayerRole.Context, hub.role)
        assertTrue(hub.ownsInput)
        assertNull(hub.contextKind, "the hub is the online home, not a live session")
        assertTrue(hub.onFelt, "the hub stands on the felt")
        assertFalse(hub.menuSurface)
        assertTrue(hub.policy.persists)

        val table = RouteSpecs.specFor(Route.Layer(RouteIds.ONLINE_TABLE))
        assertEquals(LayerRole.Context, table.role)
        assertTrue(table.ownsInput)
        assertEquals(SessionKind.Online, table.contextKind)
        assertFalse(table.onFelt, "a board paints its own surface")

        // The shell is a context too, and the one that hosts the lesson: it owns
        // its input and paints its own surface, so a press over it is the shell's.
        val shell = RouteSpecs.specFor(Route.Layer(RouteIds.TUTORIAL_SHELL))
        assertEquals(LayerRole.Context, shell.role)
        assertTrue(shell.ownsInput)
        assertEquals(SessionKind.Tutorial, shell.contextKind)
        assertFalse(shell.onFelt, "the lesson shell paints its own surface")
        assertFalse(shell.menuSurface)
    }

    @Test
    fun layersPersistUnlessAnOwnerAnnouncesThem() {
        // The snapshot is the whole stack, so a layer the player opened persists...
        assertTrue(RouteSpecs.specFor(Route.Overlay("banner")).policy.persists)
        assertTrue(RouteSpecs.specFor(Route.Modal(RouteIds.GAME_MENU)).policy.persists)
        assertTrue(RouteSpecs.specFor(Route.Modal(RouteIds.NEW_GAME_SETUP)).policy.persists)
        // ...while the ones an owner fills in when it shows them do not.
        assertFalse(RouteSpecs.specFor(Route.Overlay(RouteIds.AWAY)).policy.persists)
        assertFalse(RouteSpecs.specFor(Route.Modal(RouteIds.ONLINE_CONNECT)).policy.persists)
    }

    @Test
    fun theOnlineHubRemembersItsStatePastItsEntry() {
        assertTrue(RouteSpecs.specFor(Route.Layer(RouteIds.ONLINE_HUB)).remembersState)
        assertFalse(RouteSpecs.specFor(Route.Modal(RouteIds.NEW_GAME_SETUP)).remembersState)
    }

    @Test
    fun tutorialCoachAndQuizOverlaysBlockAutomation() {
        listOf(RouteIds.TUTORIAL_COACH, RouteIds.TUTORIAL_QUIZ).forEach { id ->
            val spec = RouteSpecs.specFor(Route.Overlay(id))
            assertTrue(spec.blocksAutomation, id)
            assertFalse(spec.ownsInput, id)
        }
    }

    @Test
    fun scoreBreakdownAndLessonDoNotBlockAutomation() {
        listOf(RouteIds.SCORE_BREAKDOWN, RouteIds.TUTORIAL_LESSON).forEach { id ->
            assertFalse(RouteSpecs.specFor(Route.Overlay(id)).blocksAutomation, id)
        }
        assertFalse(RouteSpecs.specFor(Route.Layer(RouteIds.TUTORIAL_SHELL)).blocksAutomation)
    }

    /**
     * S18: the tutorial shell is the only layer that provides local space; the
     * lesson, quiz, and coach are its local-space content. Paint selects the
     * shell composition off `ContentPolicy.ProvidesSpace`, so this is the single
     * `worldToLocal` case.
     */
    @Test
    fun tutorialShellIsTheOnlySpaceProvider() {
        assertEquals(
            ContentPolicy.ProvidesSpace,
            RouteSpecs.specFor(Route.Layer(RouteIds.TUTORIAL_SHELL)).content,
        )
        listOf(
            Route.Screen(RouteIds.ROOT_MENU),
            Route.Layer(RouteIds.LOCAL_BOARD),
            Route.Layer(RouteIds.ONLINE_TABLE),
            Route.Modal(RouteIds.GAME_MENU),
            Route.Overlay(RouteIds.AWAY),
            Route.Reference(StackState.EMPTY),
        ).forEach { route ->
            assertEquals(ContentPolicy.Normal, RouteSpecs.specFor(route).content, route.key.value)
        }
        listOf(RouteIds.TUTORIAL_LESSON, RouteIds.TUTORIAL_QUIZ).forEach { id ->
            assertEquals(
                ContentPolicy.InSpace(0),
                RouteSpecs.specFor(Route.Overlay(id)).content,
                id,
            )
        }
        assertEquals(
            ContentPolicy.InSpace(1),
            RouteSpecs.specFor(Route.Overlay(RouteIds.TUTORIAL_COACH)).content,
        )
    }

    @Test
    fun listedModalsDismissOnBack() {
        listOf(
            RouteIds.SCORE_TABLE,
            RouteIds.MATCH_HISTORY,
            RouteIds.LANGUAGE_PICKER,
            RouteIds.SETTINGS,
            RouteIds.NEW_GAME_SETUP,
        ).forEach { id ->
            assertTrue(RouteSpecs.specFor(Route.Modal(id)).dismissOnBack, id)
        }
    }

    /**
     * The ordering invariant every announcement must keep: an `InSpace` layer
     * belongs to its **context** — the nearest lower layer that establishes a
     * session — and is placed above that context, below every layer standing over it.
     *
     * Swept over the production declarations rather than one case at a time, because
     * the defect this pins was found by a click-through and not by the suite: from a
     * hand end in a local game the player opened the score table, restarted, and did
     * not see it. The stack had restored `[root, local_menu, local_board,
     * modal:score_table]`; the hand-end ledger was then announced and, because a game
     * context publishes no local space, was *appended* over the table — whereupon the
     * host's covered rule hid the table while its route stayed up. A provider's local
     * space is a layout fact; where content belongs is a context fact.
     */
    @Test
    fun announcedContentLandsBelowEveryLayerStandingOverItsContext() {
        val contexts = listOf(
            Route.Layer(RouteIds.LOCAL_MENU),
            Route.Layer(RouteIds.LOCAL_BOARD),
            Route.Layer(RouteIds.ONLINE_HUB),
            Route.Layer(RouteIds.ONLINE_TABLE),
            Route.Layer(RouteIds.TUTORIAL_SHELL),
        )
        val standingOver = listOf(
            Route.Modal(RouteIds.SCORE_TABLE),
            Route.Modal(RouteIds.GAME_MENU),
            Route.Modal(RouteIds.SETTINGS),
            Route.Modal(RouteIds.LANGUAGE_PICKER),
            Route.Modal(RouteIds.MATCH_HISTORY),
            Route.Modal(RouteIds.NEW_GAME_SETUP),
            Route.Modal(RouteIds.CONFIRM),
            Route.Reference(StackState.EMPTY),
        )
        val contents = listOf(
            Route.Overlay(RouteIds.SCORE_BREAKDOWN),
            Route.Overlay(RouteIds.TUTORIAL_LESSON),
            Route.Overlay(RouteIds.TUTORIAL_QUIZ),
            Route.Overlay(RouteIds.TUTORIAL_COACH),
        )

        for (context in contexts) {
            for (content in contents) {
                assertTrue(
                    RouteSpecs.specFor(content).content is ContentPolicy.InSpace,
                    "${content.key.value} is announced content",
                )

                // Nothing over the context: the content sits directly on it.
                val alone = router()
                alone.push(context)
                alone.pushContent(content)
                assertEquals(
                    listOf(floor.key, context.key, content.key),
                    alone.stack.entries.map { it.route.key },
                    "${content.key.value} over ${context.key.value}",
                )

                // Something over the context: the content goes under it, never over.
                for (over in standingOver) {
                    val router = router()
                    router.push(context)
                    router.push(over)
                    router.pushContent(content)
                    val keys = router.stack.entries.map { it.route.key }
                    assertEquals(
                        listOf(floor.key, context.key, content.key, over.key),
                        keys,
                        "${content.key.value} announced over ${context.key.value} " +
                            "while ${over.key.value} stands over it",
                    )
                }
            }
        }

        // Siblings stack in announcement order inside that group, still under chrome.
        val router = router()
        router.push(Route.Layer(RouteIds.TUTORIAL_SHELL))
        router.pushContent(Route.Overlay(RouteIds.TUTORIAL_LESSON))
        router.push(Route.Modal(RouteIds.SETTINGS))
        router.pushContent(Route.Overlay(RouteIds.TUTORIAL_QUIZ))
        assertEquals(
            listOf(
                floor.key,
                Route.Layer(RouteIds.TUTORIAL_SHELL).key,
                Route.Overlay(RouteIds.TUTORIAL_LESSON).key,
                Route.Overlay(RouteIds.TUTORIAL_QUIZ).key,
                Route.Modal(RouteIds.SETTINGS).key,
            ),
            router.stack.entries.map { it.route.key },
        )
    }

    /**
     * Bands decide the slot, not announcement order: an `InSpace(0)` ledger
     * announced after the `InSpace(1)` coach still lands *under* the coach, so
     * input (walked top-down) and paint agree. Without this the coach handle's
     * click fell through to the ledger beneath it.
     */
    @Test
    fun higherBandContentStaysAboveLaterLowerBandContent() {
        val router = router()
        router.push(Route.Layer(RouteIds.TUTORIAL_SHELL))
        router.pushContent(Route.Overlay(RouteIds.TUTORIAL_LESSON))
        router.pushContent(Route.Overlay(RouteIds.TUTORIAL_COACH))
        router.pushContent(Route.Overlay(RouteIds.SCORE_BREAKDOWN))
        assertEquals(
            listOf(
                floor.key,
                Route.Layer(RouteIds.TUTORIAL_SHELL).key,
                Route.Overlay(RouteIds.TUTORIAL_LESSON).key,
                Route.Overlay(RouteIds.SCORE_BREAKDOWN).key,
                Route.Overlay(RouteIds.TUTORIAL_COACH).key,
            ),
            router.stack.entries.map { it.route.key },
        )

        // Hiding the lower-band ledger must not drop the higher-band coach standing over it.
        assertTrue(router.removeExact(Route.Overlay(RouteIds.SCORE_BREAKDOWN).key))
        assertEquals(
            listOf(
                floor.key,
                Route.Layer(RouteIds.TUTORIAL_SHELL).key,
                Route.Overlay(RouteIds.TUTORIAL_LESSON).key,
                Route.Overlay(RouteIds.TUTORIAL_COACH).key,
            ),
            router.stack.entries.map { it.route.key },
        )
    }

    private val floor = Route.Screen("floor")

    /** A router over the production declarations, as the app builds it. */
    private fun router(): Router = Router(
        floor,
        { route -> FakeComponent(ComponentId(route.key.value)) },
        { RouteSpecs.specFor(it) },
    )
}
