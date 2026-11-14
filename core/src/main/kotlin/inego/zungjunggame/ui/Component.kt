package inego.zungjunggame.ui

/**
 * One unit of UI: a screen, modal, overlay, or tooltip.
 *
 * A component declares its identity and publishes a pure [Layout]; paint and
 * input consume that value rather than reaching into the component. It renders
 * from the frame's model-derived snapshot ([Frame.model]) rather than a per-entry
 * view handed in: the snapshot is assembled once per layout by the composition
 * root, so a component never keeps a copy.
 *
 * Every phase receives the entry's [Frame], so the upward channel is per frame
 * and nothing is stored.
 */
interface Component {
    val id: ComponentId

    /**
     * Frame-scoped motion advance, run every frame for the painted entries before
     * [layout]. May emit through the frame: this is where inertia coasts, because
     * a fling outlives the gesture that started it and [layout]/[draw] must stay
     * side-effect-free. It is not a lifecycle event.
     */
    fun motion(frame: Frame) {}

    /** Pure: no SpriteBatch/GL. Safe in headless tests. */
    fun layout(frame: Frame): Layout

    fun draw(frame: Frame, canvas: Canvas)
}
