package inego.zungjunggame.tutorial.slide

/**
 * Named draw targets for lesson-slide motion. Authors and presets address
 * channels; renderers map them to alpha / scale.
 *
 * The copy, the felt and the dock are deliberately absent: a beat's title,
 * body and Back / Next row are the beat — they appear with it, instantly, and
 * the felt under them never fades. Only the subject of a beat moves: its
 * diagram, a focus rim, a callout.
 */
enum class SlideChannel {
    Diagram,
    /** Per-item weights (diagram tiles, later quiz options). */
    DiagramItems,
    Focus,
    Callout,
}
