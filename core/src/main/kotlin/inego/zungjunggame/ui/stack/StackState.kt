package inego.zungjunggame.ui.stack

/** Persistable kind of a [Route]. */
enum class RouteKind { Screen, Layer, Modal, Overlay, Reference, Tooltip }

/** Serialized form of one route entry; [componentState] is feature-owned. */
data class RouteEntryState(
    val routeKey: String,
    val kind: RouteKind,
    val id: String?,
    val args: Map<String, String> = emptyMap(),
    val componentState: String? = null,
    /**
     * The policy this entry was pushed with. Presentation travels with *how* we
     * got here, so it is part of the entry rather than a lookup on restore: a
     * context reopens presenting exactly as it was left.
     */
    val policy: LayerPolicy,
)

/** Serialized stack: entries bottom to top. */
data class StackState(val entries: List<RouteEntryState>) {
    companion object { val EMPTY = StackState(emptyList()) }
}
