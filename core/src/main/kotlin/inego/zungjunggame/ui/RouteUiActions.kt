package inego.zungjunggame.ui

/**
 * The one action a widget emits when the player sets a route's own state.
 *
 * A new stateful surface therefore adds a [RouteStateCodec] and a [StatePort],
 * not an intent family: the framework maps the write to `Intent.WriteRouteUi`
 * and the effect stores it. [valueOf] does not check the action id; the registry
 * guards on [WRITE] before reading it.
 */
object RouteUiActions {
    const val WRITE = "routeUi.write"
    const val KEY_VALUE = "value"

    fun <T> write(codec: RouteStateCodec<T>, value: T): ComponentAction =
        ComponentAction(WRITE, mapOf(KEY_VALUE to RouteUiValue(codec.type, codec.encode(value))))

    /** The shared write for a body scroll offset. */
    fun scroll(offset: Float): ComponentAction = write(RouteScrollCodec, offset)

    /** The value [action] carries, or null when it carries none. */
    fun valueOf(action: ComponentAction): RouteUiValue? = action.payload[KEY_VALUE] as? RouteUiValue
}
