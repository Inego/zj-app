package inego.zungjunggame.ui

import inego.zungjunggame.ui.stack.RouteKey

/**
 * One frame's read-only geometry: the [Layout] a route published, looked up by
 * its route key, or null when that route is not in the frame's paint range.
 *
 * A presenter reads a lower sibling's layout in its own `layout` phase (the host
 * lays out bottom→top) and derives its chrome from it. The lookup never mutates
 * the sibling; it is the geometry twin of the model channel.
 */
fun interface LayoutLookup {
    fun of(route: RouteKey): Layout?
}
