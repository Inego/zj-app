package inego.zungjunggame.input

import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.stack.RouteInstance
import inego.zungjunggame.ui.stack.RouteKey

/**
 * Per-frame cache: each route's [Frame], the [Component] the host drove it
 * through, and the [Layout] that component published, reused for motion output,
 * draw, and hit-testing.
 *
 * Keyed by [RouteKey] (each route appears once per stack). The frame is built
 * once and the component laid out from it immediately, so [frameFor] and
 * [layoutFor] always hand back the same frame the component's `draw` must
 * consume. The [build] seam is where the host supplies the entry's constraints,
 * emit channel, and the frame's model.
 */
class FrameModel(
    private val build: (RouteInstance) -> Entry,
) {
    /** One entry's frame, component, and the layout it produced, computed together. */
    class Entry(val frame: Frame, val component: Component, val layout: Layout)

    private val cache = HashMap<RouteKey, Entry>()

    /** The frame for [instance], built once per key until [clear]. */
    fun frameFor(instance: RouteInstance): Frame = entry(instance).frame

    /** The component for [instance] this frame was built from. */
    fun componentFor(instance: RouteInstance): Component = entry(instance).component

    /** The layout [instance] published from its frame, computed once per key. */
    fun layoutFor(instance: RouteInstance): Layout = entry(instance).layout

    /** The layout the route with [key] published this frame, or null when it is absent. */
    fun layoutOf(key: RouteKey): Layout? = cache[key]?.layout

    private fun entry(instance: RouteInstance): Entry =
        cache.getOrPut(instance.route.key) { build(instance) }

    /** Drop every cached entry; forces recomputation on the next lookup. */
    fun clear() {
        cache.clear()
    }
}
