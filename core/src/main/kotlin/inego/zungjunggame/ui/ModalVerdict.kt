package inego.zungjunggame.ui

/**
 * Verdict of a completed press on a **modal card**: the row or entry the release
 * selected, in that feature's own vocabulary, or its close control.
 *
 * A card's surface is a `GestureSurface<ModalVerdict>`; its feature maps the
 * verdict to the action it emits (`SettingsActions.actionFor`, `GameMenuActions`
 * …), so the widget never names its feature's action ids.
 */
data class ModalVerdict(
    /** Stable id of the row/entry the release selected, in the widget's vocabulary. */
    val selectedId: String? = null,
    /** True when the card's close control was activated. */
    val close: Boolean = false,
    /**
     * Where a body drag is right now, reported from the drag steps so a
     * continuous scroll travels the same action channel as the wheel and the
     * release (the widget keeps no authoritative offset to read back).
     */
    val dragScrollOffset: Float? = null,
    /**
     * Where a body drag settled, when the release ended a scroll; the owning
     * component emits it as route state instead of the widget keeping it.
     */
    val settledScrollOffset: Float? = null,
)
