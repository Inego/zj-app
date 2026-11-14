package inego.zungjunggame.tutorial.slide

/**
 * Named presentation presets expanded by [SlideStyles] into effect lists.
 * Prefer a style over hand-rolled timelines for most coach beats.
 */
enum class SlideStyle {
    /** No motion at all: the beat swaps instantly. */
    None,

    /** Diagram / focus / callout enter. */
    Standard,

    /** Shared diagram stays put; focus pulse + callout pop. */
    FocusBeat,

    /** Stronger tile cascade for first show of a big teaching diagram. */
    DiagramIntro,
}
