package inego.zungjunggame.chart

/**
 * One move a rating chart can make: the vocabulary the device recognizer produces
 * and the explicit controls (zoom in/out, All Time) speak.
 *
 * The engine never emits [AllTime] — it exists so a component translates a button
 * press through [applying], instead of a second code path that could disagree
 * about what "all time" is. A zoom control is deliberately *not* a variant of its
 * own: it emits the [Zoom] one unit of wheel input produces, anchored at the
 * chart's centre ([ChartGestureRecognizer.zoomUnits]), so pressing + and
 * scrolling one notch over the middle of the chart ask for exactly the same
 * window. The viewport owns the semantics; this is only the funnel.
 */
sealed interface ChartGesture {
    /**
     * The chart's content moved [dxPixels] horizontally, positive moving content
     * right (earlier time) — the sign a finger and the wheel's horizontal delta
     * already share.
     */
    data class Pan(val dxPixels: Float) : ChartGesture

    /**
     * The visible span is multiplied by [factor] (below 1 zooms in) around
     * [anchorFraction] of the chart's content area, which stays where it is — unless
     * the window already sits on a bound and the anchor is within that bound's edge
     * margin, where the bound stays put instead ([ChartViewport.zoomBy]).
     */
    data class Zoom(val factor: Double, val anchorFraction: Double) : ChartGesture

    /** An explicit All Time control: the whole history at its own extent. */
    data object AllTime : ChartGesture
}

/**
 * The window [gesture] produces — the one place a gesture becomes viewport
 * state, for both the device path and the explicit controls.
 *
 * [contentWidthPx] is the width the chart's content is drawn across: a pan's
 * pixels become a fraction of it, so the ink travels exactly as far as the
 * pointer did. A zoom's anchor is already a fraction, so the width does not
 * enter it. Both callers pass the same content rect they measured the gesture
 * in.
 */
fun ChartViewport.applying(gesture: ChartGesture, contentWidthPx: Float): ChartViewport =
    when (gesture) {
        is ChartGesture.Pan -> panByPixels(gesture.dxPixels, contentWidthPx)
        is ChartGesture.Zoom -> zoomBy(gesture.factor, gesture.anchorFraction)
        ChartGesture.AllTime -> jumpToAllTime()
    }
