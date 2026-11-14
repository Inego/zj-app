package inego.zungjunggame.state

/**
 * Typed, namespaced handle for one persisted value.
 *
 * [default] supplies the value when stored data is missing or unusable. [secret]
 * is recorded for a later encryption stage; this stage stores it in the clear.
 */
class StateKey<S : Any>(
    val namespace: String,
    val name: String,
    val schema: Schema<S>,
    val default: () -> S? = { null },
    val secret: Boolean = false,
) {
    val fullName: String get() = "$namespace.$name"
}
