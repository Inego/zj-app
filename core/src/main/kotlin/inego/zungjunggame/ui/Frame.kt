package inego.zungjunggame.ui

/**
 * One entry's frame-scoped inputs and its upward channel.
 *
 * A frame is a value the host builds for the entry being modeled: the entry's
 * [constraints], [env] and [space] come in, and the action it resolves goes out
 * through [emit], which the host maps through the same route-action table the tap
 * path uses. Nothing is stored on the component, so a value a widget reports
 * belongs to the frame that produced it and cannot reach another entry.
 */
class Frame(
    val constraints: Constraints,
    val env: UiEnvironment,
    /** The local space this entry is laid out in, or null in world space. */
    val space: Transform?,
    val emit: (ComponentAction) -> Unit,
    /**
     * The frame's cached layouts, read-only. A presenter derives chrome from a
     * lower sibling's geometry through this channel, never from the sibling's
     * live state.
     */
    val layouts: LayoutLookup = LayoutLookup { null },
    /**
     * The composition root's immutable per-frame snapshot — one
     * `AppModel` assembled after input and host ticks. Typed as [Any] so `ui/`
     * stays free of the runtime/session/feature packages it carries; a
     * presenter reads it through `inego.zungjunggame.runtime.appModel`. Presenters
     * that derive nothing from the model ignore it.
     */
    val model: Any = Unit,
    /**
     * Asks the application frame for another present. A fling, a fade, or a
     * pulse that is still moving calls this; a settled frame does not.
     */
    private val requestPaint: () -> Unit = {},
    private val requestWake: (Long) -> Unit = {},
) {
    /** The picture is still changing; present again. */
    fun keepPainting() {
        requestPaint()
    }

    /**
     * Ask for **one** present after [delayMs], and none meanwhile. This is the
     * discrete counterpart to [keepPainting]: a settled surface that must observe
     * a wall-clock deadline (a transport retirement, a retry window) schedules the
     * single frame it needs instead of asking for every frame until then.
     *
     * The delay is a minimum: a platform with coarse timer resolution may fire
     * later. The default host ignores it, exactly as a headless frame ignores
     * [keepPainting].
     */
    fun wakeAfter(delayMs: Long) {
        if (delayMs >= 0L) requestWake(delayMs)
    }
}
