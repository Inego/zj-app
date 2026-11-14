package inego.zungjunggame.features.tutorial

import kotlin.math.max
import kotlin.math.min

/**
 * A screen-space rect a diagram drawer published for a focused tile.
 *
 * One entry per [inego.zungjunggame.tutorial.TutorialTargets] target id, so the
 * focus chrome can highlight "the 3 of characters" without knowing which diagram
 * drew it. Screen space, origin bottom-left, the same frame as the panel.
 */
internal data class AnchorRect(
    val left: Float,
    val bottom: Float,
    val w: Float,
    val h: Float,
) {
    val cx: Float get() = left + w * 0.5f
    val cy: Float get() = bottom + h * 0.5f

    /** Smallest rect covering both, so repeated faces of one type read as one target. */
    fun union(other: AnchorRect): AnchorRect {
        val l = min(left, other.left)
        val b = min(bottom, other.bottom)
        val r = max(left + w, other.left + other.w)
        val t = max(bottom + h, other.bottom + other.h)
        return AnchorRect(l, b, r - l, t - b)
    }
}