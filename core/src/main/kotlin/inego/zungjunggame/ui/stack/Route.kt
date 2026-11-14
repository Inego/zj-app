package inego.zungjunggame.ui.stack

/**
 * Unified navigation vocabulary; each kind carries a stable [key] and optional
 * string args. Concrete screens/layers/modals/overlays are identified by [id].
 */
sealed interface Route {
    val key: RouteKey
    val args: Map<String, String> get() = emptyMap()

    /** The root menu. The one screen, and the stack floor. */
    data class Screen(val id: String, override val args: Map<String, String> = emptyMap()) : Route {
        override val key get() = RouteKey("screen:$id")
    }

    /**
     * A context above the floor: the local play menu and board, the online hub
     * and table, the tutorial shell.
     *
     * [policy] is what *this* push chose. The same surface can present differently
     * depending on how we got here, so the transition that pushes it may override
     * the spec's declared policy; the entry is then the one source of truth for how
     * it is drawn and whether it is persisted (see [Router.snapshot]). Null means
     * the declaration stands.
     */
    data class Layer(
        val id: String,
        override val args: Map<String, String> = emptyMap(),
        val policy: LayerPolicy? = null,
    ) : Route {
        override val key get() = RouteKey("layer:$id")
    }

    data class Modal(val id: String, override val args: Map<String, String> = emptyMap()) : Route {
        override val key get() = RouteKey("modal:$id")
    }

    data class Overlay(val id: String, override val args: Map<String, String> = emptyMap()) : Route {
        override val key get() = RouteKey("overlay:$id")
    }

    data class Reference(val returnTo: StackState) : Route {
        override val key get() = RouteKey("reference")
    }

    data class Tooltip(val id: String) : Route {
        override val key get() = RouteKey("tooltip:$id")
    }
}

/** The catalog id this route names, or null for a route that names none. */
val Route.idOrNull: String?
    get() = when (this) {
        is Route.Screen -> id
        is Route.Layer -> id
        is Route.Modal -> id
        is Route.Overlay -> id
        is Route.Reference -> null
        is Route.Tooltip -> id
    }
