package inego.zungjunggame.ui

/**
 * Pure, GL-free output of [Component.layout] for one frame: draw ops, hit
 * regions, optional panel bounds, scroll extents, gesture ownership, and a
 * world→local mapping.
 */
class Layout(
    val ops: List<DrawOp> = emptyList(),
    val hitRegions: HitRegions<ComponentAction> = HitRegions(),
    /**
     * This layer's **own bounds**: what it covers, and therefore all it may own
     * the wheel or a drag over. The input pipeline gates both on this rect, so a
     * layer whose own chrome lies outside it cannot receive them.
     *
     * Not to be confused with [space], which is what a layer *hands down* to
     * the layers above it.
     */
    val panel: Rect? = null,
    val scrollMaxY: Float = 0f,
    val scrollY: Float = 0f,
    /** Regions a [GestureHost] component claims for press/drag (park chrome, sliders). */
    val gestureRegions: List<Rect> = emptyList(),
    /** True when the layer's panel can be pressed and dragged to scroll. */
    val scrollable: Boolean = false,
    val worldToLocal: ((Float, Float) -> Pair<Float, Float>?)? = null,
    /**
     * Named geometry this layer laid out, for a layer above it to read: focus
     * anchors and sibling chrome the owner chooses to publish. Computed purely
     * in [Component.layout]; a reader never mutates the publishing layer.
     */
    val regions: Map<String, Rect> = emptyMap(),
    /**
     * The space the layers above this one are laid out and painted inside, for a
     * layer whose spec declares
     * [inego.zungjunggame.ui.stack.ContentPolicy.ProvidesSpace].
     *
     * Independent of [panel] because the two answer different questions: the
     * tutorial shell's own bounds include the TOC rail it owns input over, while
     * the lesson it hosts is laid out in the content zone beside that rail.
     */
    val space: Transform? = null,
)

/**
 * A local space: a rect in its parent's coordinates and the world→local mapping
 * it implies.
 *
 * [rect] is the layout and paint viewport. A point outside it, or inside
 * [reserved], has no local coordinate: the provider kept that point (chrome
 * drawn over the viewport), and the walk falls through to the provider.
 */
data class Transform(
    val rect: Rect,
    /** Points inside [rect] that stay with the provider. Empty for an ordinary space. */
    val reserved: List<Rect> = emptyList(),
) {
    fun toLocal(x: Float, y: Float): Pair<Float, Float>? {
        if (!rect.contains(x, y)) return null
        if (reserved.any { it.contains(x, y) }) return null
        return (x - rect.left) to (y - rect.bottom)
    }
}

/** What a hit region resolves to; the owning feature maps it to an intent. */
data class ComponentAction(
    val id: String,
    val payload: Map<String, Any?> = emptyMap(),
)

/**
 * Maps screen-space `(x, y)` into this layout's local space, or null when the
 * point lies outside it. Layers without a [Layout.worldToLocal] are identity.
 */
fun Layout.localPoint(x: Float, y: Float): Pair<Float, Float>? {
    val toLocal = worldToLocal ?: return x to y
    return toLocal(x, y)
}

/** Copy of this layout with [worldToLocal] set to [transform]'s mapping. */
fun Layout.withLocalSpace(transform: Transform): Layout = Layout(
    ops = ops,
    hitRegions = hitRegions,
    panel = panel,
    scrollMaxY = scrollMaxY,
    scrollY = scrollY,
    gestureRegions = gestureRegions,
    scrollable = scrollable,
    worldToLocal = { x, y -> transform.toLocal(x, y) },
    regions = regions,
    space = space,
)
