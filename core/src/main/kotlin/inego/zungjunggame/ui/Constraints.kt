package inego.zungjunggame.ui

/**
 * Per-frame inputs a component's [Component.layout] consumes: viewport size,
 * pointer position, frame delta, and optional safe-area inset.
 */
data class Constraints(
    val worldWidth: Float,
    val worldHeight: Float,
    val pointerX: Float,
    val pointerY: Float,
    val deltaSec: Float,
    val safeArea: Rect? = null,
    /**
     * Every pointer the device reports down this frame, translated into this
     * frame's own space exactly as [pointerX]/[pointerY] are: world space for a
     * top-level layer, and the scope's local space for a component laid out
     * inside one. Empty when no pointer is down, and empty for a layer occluded
     * by an input-owning layer above it.
     *
     * This is the frame's multi-pointer snapshot and the channel a component
     * reads when one pointer is not enough (a pinch). A pointer that left the
     * frame's space — outside a scope's rect, or on chrome that scope reserves —
     * is dropped from the set, the multi-pointer reading of the `NaN` convention
     * [pointerX]/[pointerY] keep.
     */
    val pointers: FramePointers = FramePointers.EMPTY,
)
