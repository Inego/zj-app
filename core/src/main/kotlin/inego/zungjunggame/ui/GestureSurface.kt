package inego.zungjunggame.ui

/**
 * The **widget-side** half of the gesture contract: a view that owns its own
 * press → drag → release state machine and reports what a release resolved.
 *
 * [GestureHost] is the component-side half and [GestureBridge] joins the two, so
 * a component wrapping a surface forwards every gesture by inheriting the bridge
 * instead of retyping the forwarding. The split of authority is:
 *
 * - the **pipeline** decides *whether* a press belongs to the surface, from the
 *   layer's [Layout.gestureRegions] and [Layout.scrollable];
 * - the **surface** decides what the press then means, and its verdict says which
 *   control (if any) the release resolved.
 *
 * [V] is that verdict, in the surface's own feature vocabulary — a card reports the
 * row or close control it resolved ([ModalVerdict]), the reference browser the link
 * or history step it resolved, the tutorial shell the lesson it selected.
 */
interface GestureSurface<V> {
    /**
     * Wheel / trackpad over the surface. libGDX [amountY] is positive when the
     * wheel scrolls down.
     *
     * @return true when the surface scrolled something; false when it has nothing
     *   to scroll, which leaves the press to the layer (an input-owning layer still
     *   swallows it).
     */
    fun onScroll(amountY: Float, x: Float, y: Float): Boolean = false

    /**
     * Both axes of one wheel / trackpad sample, the shape a surface needs when it
     * reads the horizontal delta too — the same order [InputEvent.Scroll] declares
     * the sample in, so a call site reads against one shape. [amountX] follows
     * libGDX's convention (positive when the content slides right, so a wheel
     * turned right reports a negative).
     *
     * The default forwards [amountY] to the vertical-only form, so a surface that
     * scrolls one axis keeps overriding that one and a surface that wants both
     * overrides this; the pipeline calls this form. `(x, y)` is the cursor in the
     * same space the surface laid out in.
     */
    fun onScroll(amountY: Float, x: Float, y: Float, amountX: Float): Boolean =
        onScroll(amountY, x, y)

    /**
     * Pointer pressed inside a region this layer claims.
     *
     * @return true to own the gesture; false to hand the press back to the
     *   hit-region tap path, for a control the surface deliberately leaves
     *   click-only.
     */
    fun onPointerDown(x: Float, y: Float): Boolean = true

    /**
     * The value a claimed press resolved, drained by the [GestureBridge]; null
     * (the default) when the press only claimed the gesture. Symmetric with the
     * drag verdict and the scroll offset: a slider sets a value on press, so
     * that first value must travel the same action channel as the drag steps
     * that follow.
     */
    fun pressVerdict(): V? = null

    /**
     * Pointer moved while owned. May resolve a per-step verdict the drag itself
     * reports — a parked block's offset, a slider value. Null (the default) means
     * the drag has nothing to say beyond its own transient state, which the
     * [GestureBridge] reads as "no action"; a non-null verdict is emitted through
     * the bridge's `actionFor`, exactly like a release verdict.
     */
    fun onPointerDrag(x: Float, y: Float): V? = null

    /** Pointer released while owned. */
    fun onPointerUp(x: Float, y: Float): V

    /**
     * The offset a consumed scroll ([onScroll] returned true) settled at, for a
     * surface that reports one. Null (the default) means the surface's scroll is
     * self-contained and needs no action — a live drag reads its own position.
     *
     * A wheel ease has not settled when this is asked: it only armed a target, so
     * the offset it reports is unchanged and the surface's own per-frame
     * `advance` carries the motion from here.
     */
    fun scrollOffset(): Float? = null

    /**
     * True while the offset is still moving on its own — a released drag's fling
     * coast, or a wheel ease between the notch and its landing point. The offset
     * does not move on the release itself, and [Component.motion] has already run
     * by then, so the motion has to be visible to the present decision on its
     * own.
     *
     * Answer the *movement*, not the gesture: a surface that eases its wheel
     * reports coasting for the whole ease, or the frame loop stops one frame in.
     */
    fun scrollCoasting(): Boolean = false

    /** Abort an in-flight gesture (a modal opened, an OS cancel). */
    fun cancelGesture() {}
}
