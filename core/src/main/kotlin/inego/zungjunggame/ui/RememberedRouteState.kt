package inego.zungjunggame.ui

import inego.zungjunggame.ui.stack.RouteKey

/**
 * Durability for the route UI state a route **remembers** past its own removal.
 *
 * Most route state dies with its entry: [RouteUiState.remove] clears it and the
 * stack snapshot only carries entries still on the stack. A route whose spec marks
 * [inego.zungjunggame.ui.stack.LayerSpec.remembersState] opts out of that — its last
 * value is kept here, keyed by route, and [RouteUiState.read] falls back to it when
 * the entry is gone. The implementation owns storage (a `ui.remembered` key); the
 * store stays pure and only sees this port.
 */
interface RememberedRouteState {
    /** The value last remembered for [key], or null when none was ever kept. */
    fun recall(key: RouteKey): RouteUiValue?

    /** Keep [value] as the remembered value for [key]. */
    fun store(key: RouteKey, value: RouteUiValue)
}
