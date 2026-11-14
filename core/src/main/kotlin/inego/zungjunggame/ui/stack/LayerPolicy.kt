package inego.zungjunggame.ui.stack

/**
 * Whether a layer paints what is below it.
 *
 * `Opaque` layers paint a full-screen background, so the paint loop starts at the
 * topmost opaque layer and never draws what it covers. `Below` layers are painted
 * over their context; any dimming is the layer's own.
 *
 * A context — a local game, an online table, a lesson — is `Opaque`; chrome opened
 * on top of one is `Below`.
 */
enum class Presentation { Opaque, Below }

/**
 * How a layer presents and whether it belongs to the persisted stack.
 *
 * Declared per route id in [inego.zungjunggame.runtime.RouteSpecs] as the default
 * for that id. A push site may choose a different policy for the entry it creates,
 * because the same surface presents differently depending on how it was reached —
 * a context menu covers the main menu when it is entered from it, and renders the
 * board behind it when it is opened over one. The resolved policy is what the
 * entry carries, so paint and the snapshot both read one value.
 */
data class LayerPolicy(
    val presentation: Presentation,
    val persists: Boolean,
)
