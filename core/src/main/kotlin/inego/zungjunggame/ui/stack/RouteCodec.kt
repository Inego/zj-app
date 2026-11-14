package inego.zungjunggame.ui.stack

/** Snapshot of this route as a persistable [RouteEntryState], with its [policy]. */
fun Route.toEntryState(
    policy: LayerPolicy,
    componentState: String? = null,
): RouteEntryState = RouteEntryState(
    routeKey = key.value,
    kind = when (this) {
        is Route.Screen -> RouteKind.Screen
        is Route.Layer -> RouteKind.Layer
        is Route.Modal -> RouteKind.Modal
        is Route.Overlay -> RouteKind.Overlay
        is Route.Reference -> RouteKind.Reference
        is Route.Tooltip -> RouteKind.Tooltip
    },
    id = idOrNull,
    args = args,
    componentState = componentState,
    policy = policy,
)

/**
 * Rebuilds the route described by this entry, or null when its id is unusable.
 *
 * The stored [RouteEntryState.routeKey] is informational: keys are derived from
 * kind + id, so a stale key cannot smuggle in a mismatched route. A [RouteKind.Layer]
 * comes back with the policy it was pushed with, so the restored entry presents as
 * it left. Reference returns with an empty return stack; its return is volatile by
 * design.
 */
fun RouteEntryState.toRoute(): Route? = when (kind) {
    RouteKind.Screen -> id?.let { Route.Screen(it, args) }
    RouteKind.Layer -> id?.let { Route.Layer(it, args, policy) }
    RouteKind.Modal -> id?.let { Route.Modal(it, args) }
    RouteKind.Overlay -> id?.let { Route.Overlay(it, args) }
    RouteKind.Reference -> Route.Reference(StackState.EMPTY)
    RouteKind.Tooltip -> id?.let { Route.Tooltip(it) }
}
