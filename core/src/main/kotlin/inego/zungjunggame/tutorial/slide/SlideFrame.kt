package inego.zungjunggame.tutorial.slide

/**
 * Per-frame draw parameters produced by [SlidePresenter].
 * Geometry stays fixed in the overlay; only these multipliers change.
 */
data class SlideFrame(
    val alpha: Map<SlideChannel, Float>,
    val scale: Map<SlideChannel, Float>,
    /**
     * Visibility 0..1 for diagram/quiz item [index] (stagger).
     * Settled / non-stagger ⇒ always 1.
     */
    val itemWeight: (Int) -> Float,
) {
    fun alphaOf(channel: SlideChannel, default: Float = 1f): Float =
        alpha[channel] ?: default

    fun scaleOf(channel: SlideChannel, default: Float = 1f): Float =
        scale[channel] ?: default

    companion object {
        /** Fully settled, no motion. */
        val SETTLED: SlideFrame = SlideFrame(
            alpha = SlideChannel.entries.associateWith { 1f },
            scale = SlideChannel.entries.associateWith { 1f },
            itemWeight = { 1f },
        )
    }
}
