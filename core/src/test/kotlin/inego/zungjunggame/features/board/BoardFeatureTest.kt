package inego.zungjunggame.features.board

import inego.zungjunggame.input.Intent
import inego.zungjunggame.persist.UiPreferences
import inego.zungjunggame.persist.UiPreferencesKey
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.stack.SessionKind
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.stack.ContentPolicy
import inego.zungjunggame.ui.stack.LayerPolicy
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.LayerSpec
import inego.zungjunggame.ui.stack.Presentation
import inego.zungjunggame.ui.stack.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardFeatureTest {

    private fun preferences(): StateSlot<UiPreferences> =
        StateSlot(DefaultStateStore(InMemoryStateBackend()), UiPreferencesKey)

    private fun registry(): FeatureRegistry = FeatureRegistry().apply {
        BoardFeature.register(this, { error("no GL in tests") }, preferences())
    }

    private val contextIds = listOf(
        RouteIds.LOCAL_BOARD,
        RouteIds.ONLINE_TABLE,
    )

    /** The session each context declares; the shared board paints whichever is live. */
    private val contextKinds = mapOf(
        RouteIds.LOCAL_BOARD to SessionKind.FreePlay,
        RouteIds.ONLINE_TABLE to SessionKind.Online,
    )
    private val overlayIds = listOf(
        RouteIds.TUTORIAL_LESSON,
    )

    @Test
    fun registersEveryBoardContextAndLessonOverlay() {
        val registry = registry()

        for (id in contextIds) {
            val route = Route.Layer(id)

            assertTrue(registry.create(route) is BoardComponent, "create $id")
            assertEquals(LayerRole.Context, registry.specFor(route).role, "spec role $id")
            assertEquals(
                LayerSpec.defaultsFor(LayerRole.Context).copy(contextKind = contextKinds.getValue(id)),
                registry.specFor(route),
                "spec $id",
            )
        }
        for (id in overlayIds) {
            val route = Route.Overlay(id)

            assertTrue(registry.create(route) is BoardComponent, "create $id")
            assertEquals(LayerRole.Overlay, registry.specFor(route).role, "spec role $id")
            // The lesson board is a tutorial local-space content layer, announced
            // by the tutorial host rather than opened by the player, so it stays
            // out of the persisted stack.
            assertEquals(
                LayerSpec.defaultsFor(LayerRole.Overlay).copy(
                    content = ContentPolicy.InSpace(0),
                    policy = LayerPolicy(Presentation.Below, persists = false),
                ),
                registry.specFor(route),
                "spec $id",
            )
        }
    }

    @Test
    fun routesShareBoardActions() {
        val registry = registry()

        val routes = contextIds.map { Route.Layer(it) } +
            overlayIds.map { Route.Overlay(it) }
        for (route in routes) {
            assertEquals(Intent.Hint, registry.actionToIntent(route, ComponentAction(BoardActions.HINT)))
        }
    }
}
