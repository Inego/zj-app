package inego.zungjunggame.ui

/**
 * Optional behavior a component exposes when its [Layout] declares a
 * gesture-owned region ([Layout.gestureRegions]) or a scrollable body
 * ([Layout.scrollable]).
 *
 * The input pipeline owns the press/drag/release lifecycle and only forwards it
 * here once the component has accepted the press, so a component never has to
 * re-implement slop or the "press-down recipient keeps the release" invariant.
 * Every callback carries the owning entry's [Frame]: a verdict emitted from a
 * gesture goes out through the channel of the entry that received it.
 */
interface GestureHost {
    /**
     * Pointer pressed inside a region this host declared.
     *
     * @return true to take ownership of the gesture.
     */
    fun onGesturePress(x: Float, y: Float, frame: Frame): Boolean = false

    /** Pointer moved while owned; [dragging] flips true once travel passed the slop. */
    fun onGestureDrag(x: Float, y: Float, dragging: Boolean, frame: Frame) {}

    /**
     * Pointer released while owned.
     *
     * @return true when the gesture consumed the release (a drag), false to let
     *   the pipeline re-dispatch it as an ordinary tap.
     */
    fun onGestureRelease(x: Float, y: Float, frame: Frame): Boolean = false

    /** Abort an in-flight gesture (modal opened, OS cancel). */
    fun onGestureCancel(frame: Frame) {}

    /**
     * Scroll wheel over this layer; true when consumed. [amount] is the sample's
     * vertical delta — the only axis this form has ever carried.
     */
    fun onWheel(amount: Float, x: Float, y: Float, frame: Frame): Boolean = false

    /**
     * Both axes of one wheel sample, in the order [InputEvent.Scroll] declares the
     * sample; the default is the vertical-only form, so a host that reads one axis
     * is unchanged and one that reads both — a chart zooming on [amountY] and
     * panning on [amountX] — overrides this. The pipeline calls this form.
     */
    fun onWheel(amountY: Float, x: Float, y: Float, amountX: Float, frame: Frame): Boolean =
        onWheel(amountY, x, y, frame)
}
