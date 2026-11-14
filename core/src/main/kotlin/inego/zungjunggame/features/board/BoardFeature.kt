package inego.zungjunggame.features.board

import inego.zungjunggame.persist.UiPreferences
import inego.zungjunggame.render.table.PlayTableView
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.idOrNull

/**
 * Registers the board components — one per board route. Which session is
 * attached, not the route, decides what the table shows. The local and online
 * boards are contexts above the floor, and the tutorial lesson is a content layer
 * inside the shell's local space.
 *
 * **[PlayTableView] is deliberately one instance, owned by the runtime and shared
 * across these mutually-exclusive contexts.** The app assembles the board's
 * model-derived render inputs once per frame and the presenter hands them to the
 * table in `paint`; only one board context is ever up, so one live table is
 * correct. A [BoardComponent] only **adapts** it (measure its hit boxes, hand the
 * frame's model in) and keeps **no per-entry widget state** for it — `persist()` is
 * `null` and `restore()` an empty no-op. [BoardChrome] is not cloned either: its
 * provider is stateless, so every component's `chrome()` wraps the same behavior
 * with nothing per entry to persist.
 */
object BoardFeature {
    private val CONTEXT_IDS = listOf(
        RouteIds.LOCAL_BOARD,
        RouteIds.ONLINE_TABLE,
    )
    private val OVERLAY_IDS = listOf(
        RouteIds.TUTORIAL_LESSON,
    )

    fun register(
        registry: FeatureRegistry,
        table: () -> PlayTableView,
        preferences: StateSlot<UiPreferences>,
        chrome: () -> BoardChrome = { BoardChrome { _, _, _, _ -> } },
        /**
         * The tutorial board lesson's pane. Every other board context gets the
         * whole content space ([BoardPane.Plain]).
         */
        pane: BoardPane = BoardPane.Plain,
    ) {
        val routes = CONTEXT_IDS.map { Route.Layer(it) } +
            OVERLAY_IDS.map { Route.Overlay(it) }
        for (route in routes) {
            val routePane = if (route.idOrNull == RouteIds.TUTORIAL_LESSON) {
                pane
            } else {
                BoardPane.Plain
            }
            registry.register(
                routeKey = route.key,
                factory = { registered ->
                    BoardComponent(
                        registered,
                        table,
                        preferences,
                        chrome = chrome(),
                        pane = routePane,
                    )
                },
                spec = RouteSpecs.specFor(route),
                actions = BoardActions,
            )
        }
    }
}
