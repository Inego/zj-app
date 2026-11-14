package inego.zungjunggame.input

import inego.zungjunggame.ui.GestureHost
import inego.zungjunggame.ui.stack.Stack

/**
 * Routes a scroll-wheel sample to the top stack layer that owns it.
 *
 * A layer opts in by publishing [inego.zungjunggame.ui.Layout.scrollable]; an
 * input-owning layer with no scroll host still swallows the wheel so it cannot
 * leak to the layer underneath (the legacy modal behavior). The host receives
 * the owning entry's frame, so a consumed wheel emits through the entry's own
 * channel.
 *
 * Both axes of the one sample travel together; a host that reads only the
 * vertical delta is reached through [GestureHost.onWheel]'s one-axis form, and
 * one that reads the horizontal too — a chart panning on X and zooming on Y —
 * through its two-axis form.
 */
object ScrollRouter {
    /**
     * Routes one sample, in the order [inego.zungjunggame.input.InputEvent.Scroll]
     * declares it: the vertical delta, where it happened, then the horizontal delta.
     *
     * @return true when a layer consumed the wheel.
     */
    fun route(
        stack: Stack,
        frames: FrameModel,
        amountY: Float,
        x: Float,
        y: Float,
        amountX: Float,
    ): Boolean {
        for (instance in stack.paintRange.asReversed()) {
            val layout = frames.layoutFor(instance)
            val gesture = frames.componentFor(instance) as? GestureHost
            val inPanel = layout.panel?.contains(x, y) ?: true
            if (layout.scrollable && inPanel &&
                gesture != null &&
                gesture.onWheel(amountY, x, y, amountX, frames.frameFor(instance))
            ) {
                return true
            }
            if (instance.spec.ownsInput) return true
        }
        return false
    }
}
