package inego.zungjunggame.ui

/**
 * Immutable axis-aligned rectangle in world coordinates (y-up).
 *
 * [contains] is inclusive of the edges; [intersects] requires a positive-area
 * overlap, so rectangles that merely touch along an edge do not intersect.
 */
data class Rect(
    val left: Float,
    val bottom: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val top: Float get() = bottom + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = bottom + height / 2f

    /** True when `(x, y)` lies within the closed rectangle (edges included). */
    fun contains(x: Float, y: Float): Boolean =
        x in left..right && y >= bottom && y <= top

    /** True when this and [other] share more than an edge or a corner. */
    fun intersects(other: Rect): Boolean =
        left < other.right && right > other.left &&
            bottom < other.top && top > other.bottom
}
