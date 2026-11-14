package inego.zungjunggame.runtime

import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.RouteKey
import inego.zungjunggame.ui.stack.Router
import inego.zungjunggame.ui.stack.Stack

/** Thin, delegating facade over [Router] for feature and host code. */
class RouteController(private val router: Router) {
    val stack: Stack get() = router.stack
    val current: Route get() = router.current

    fun push(route: Route) = router.push(route)

    /** Insert [route] directly above the floor, below every layer already up. */
    fun pushAboveFloor(route: Route) = router.pushAboveFloor(route)

    fun pop(): Boolean = router.pop()

    fun popTo(key: RouteKey): Boolean = router.popTo(key)
    fun isOpen(key: RouteKey): Boolean = router.isOpen(key)

    /** Push [route] unless a layer with the same key is already on the stack. */
    fun pushIfAbsent(route: Route) {
        if (!router.isOpen(route.key)) router.push(route)
    }

    /** Pop the top layer when [key] is the top; false when it is absent or covered. */
    fun popIfPresent(key: RouteKey): Boolean {
        return router.stack.top.route.key == key && router.pop()
    }

    /** Remove [key] and everything above it; false when absent or the floor. */
    fun remove(key: RouteKey): Boolean = router.remove(key)
}
