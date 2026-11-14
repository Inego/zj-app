package inego.zungjunggame.app

import inego.zungjunggame.ui.stack.RouteKey
import inego.zungjunggame.ui.stack.Router
import inego.zungjunggame.ui.stack.Stack

/**
 * Runs a route's enter work whenever that route appears on the stack.
 *
 * The surface is the trigger, not a tap command: [key] crossing from absent to
 * present fires [enter] once, so a card raised over the surface does not reload it
 * and a second tap on the entry that raised it does not either. The [Router]
 * notification is the one signal, and a surface that is already up when this seam
 * is installed — a stack the boot restored — is loaded by the install itself.
 */
internal class RouteSurfaceLoad(
    router: Router,
    private val key: RouteKey,
    private val enter: () -> Unit,
) {
    private var up = false

    init {
        router.addObserver { sync(it) }
        sync(router.stack)
    }

    private fun sync(stack: Stack) {
        val present = stack.contains(key)
        if (present && !up) enter()
        up = present
    }
}
