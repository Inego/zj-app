package inego.zungjunggame.app

/**
 * Whether the runtime's opportunistic stack write is due.
 *
 * A durable write is required at the lifecycle edges (pause / exit / dispose); this
 * decides the safety net for the edges that never arrive (a crash, a kill, a
 * browser tab closed without unload). It fires when either the stack **instance**
 * changed (navigation) or the route UI store **[revision][inego.zungjunggame.ui.RouteUiState.revision]**
 * moved (a scroll, a draft, a drawer), throttled so neither writes more often than
 * [intervalMs].
 */
internal fun stackFlushDue(
    stackChanged: Boolean,
    routeUiChanged: Boolean,
    nowMs: Long,
    lastFlushMs: Long,
    intervalMs: Long,
): Boolean {
    return !(!stackChanged && !routeUiChanged) && nowMs - lastFlushMs >= intervalMs
}
