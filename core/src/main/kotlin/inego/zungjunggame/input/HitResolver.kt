package inego.zungjunggame.input

import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.localPoint
import inego.zungjunggame.ui.stack.RouteInstance
import inego.zungjunggame.ui.stack.Stack

/** Outcome of hit-testing a stack at one point. */
sealed interface ResolvedHit {
    /** A control under the point. */
    data class On(val instance: RouteInstance, val action: ComponentAction) : ResolvedHit

    /** Point is inside a layer that owns input but did not hit a control (e.g. a scrim). */
    data class Outside(val instance: RouteInstance) : ResolvedHit
}

/**
 * Walks a [Stack]'s paint range top-down against cached layouts to resolve a
 * point.
 *
 * The first layer with a hit region wins; an input-owning layer with no hit
 * consumes the point as [ResolvedHit.Outside] and stops the walk. Non-owning
 * layers without a hit are transparent and let the walk continue downward.
 * Covered entries are not in the range, so input cannot reach what a frame
 * does not paint.
 */
class HitResolver(private val frames: FrameModel) {
    /** Resolves `(x, y)` against [stack], or null when no layer claims it. */
    fun resolve(stack: Stack, x: Float, y: Float): ResolvedHit? {
        for (instance in stack.paintRange.asReversed()) {
            val layout = frames.layoutFor(instance)
            val local = layout.localPoint(x, y) ?: continue
            val action = layout.hitRegions.hitAt(local.first, local.second)
            if (action != null) return ResolvedHit.On(instance, action)
            if (instance.spec.ownsInput) return ResolvedHit.Outside(instance)
        }
        return null
    }
}
