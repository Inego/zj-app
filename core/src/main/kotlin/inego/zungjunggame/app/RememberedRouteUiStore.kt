package inego.zungjunggame.app

import inego.zungjunggame.persist.RememberedRouteUi
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.ui.RememberedRouteState
import inego.zungjunggame.ui.RouteUiValue
import inego.zungjunggame.ui.stack.RouteKey

/**
 * [RememberedRouteState] over the `ui.remembered` key: a write-through map of the
 * last value of each remembering route, keyed by route. The route UI store owns
 * when to recall and store; this only moves the value to and from the key.
 */
internal class RememberedRouteUiStore(
    private val slot: StateSlot<RememberedRouteUi>,
) : RememberedRouteState {
    override fun recall(key: RouteKey): RouteUiValue? = slot.requireValue().entries[key.value]

    override fun store(key: RouteKey, value: RouteUiValue) {
        val current = slot.requireValue()
        if (current.entries[key.value] == value) return
        slot.set(current.copy(entries = current.entries + (key.value to value)))
    }
}
