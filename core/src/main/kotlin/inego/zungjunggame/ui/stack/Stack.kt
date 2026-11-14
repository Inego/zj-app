package inego.zungjunggame.ui.stack

/**
 * Immutable ordered set of route instances, bottom to top.
 *
 * Order is paint order (bottom→top); the reverse is input order (top→down).
 * Invariants: non-empty, exactly one [LayerRole.Screen] at the floor (the root
 * menu, which is never replaced or removed), and no duplicate [RouteKey].
 */
class Stack(instances: List<RouteInstance>) {
    val entries: List<RouteInstance> = instances.toList()

    init {
        require(entries.isNotEmpty()) { "Stack must contain at least one entry" }
        require(entries[0].spec.role == LayerRole.Screen) { "Stack floor must be a Screen" }
        require(entries.count { it.spec.role == LayerRole.Screen } == 1) {
            "Stack must contain exactly one Screen"
        }
        val keys = entries.map { it.route.key }
        require(keys.size == keys.toSet().size) { "Stack must not contain duplicate route keys" }
    }

    /** The root menu at index 0. */
    val floor: RouteInstance get() = entries.first()

    /** The topmost entry. */
    val top: RouteInstance get() = entries.last()

    /**
     * The entries a frame paints, lays out, and routes input to: the topmost
     * [Presentation.Opaque] entry and everything above it. What is below the
     * range is covered — not painted, not laid out, not interactive — so there
     * is no visibility to track outside the stack.
     */
    val paintRange: List<RouteInstance>
        get() {
            val first = entries
                .indexOfLast { it.spec.policy.presentation == Presentation.Opaque }
                .coerceAtLeast(0)
            return entries.subList(first, entries.size)
        }

    val anyBlocksAutomation: Boolean get() = entries.any { it.spec.blocksAutomation }

    /**
     * True when nothing on the stack supersedes the fundamental overlay, so the
     * felt and its version stamp are painted behind it. A board, a lesson shell,
     * or the reference browser paints a surface of its own and turns this off.
     */
    val onFelt: Boolean get() = entries.all { it.spec.onFelt }

    /** True when the top layer's own content is the game-menu card. */
    val menuSurface: Boolean get() = top.spec.menuSurface

    fun find(key: RouteKey): RouteInstance? = entries.firstOrNull { it.route.key == key }

    fun contains(key: RouteKey): Boolean = find(key) != null

    /** The topmost entry whose spec satisfies [predicate], or null. */
    fun topWhere(predicate: (LayerSpec) -> Boolean): RouteInstance? =
        entries.lastOrNull { predicate(it.spec) }
}
