package inego.zungjunggame.tutorial.slide

/**
 * How a coach (later quiz) beat appears. Authors usually set [style] only;
 * [enter]/[exit]/[ambient] append after the style template.
 */
data class SlidePresentation(
    val style: SlideStyle = SlideStyle.Standard,
    val enter: List<SlideEffect> = emptyList(),
    val exit: List<SlideEffect> = emptyList(),
    val ambient: List<SlideEffect> = emptyList(),
    /**
     * When true (default), same continuity key / diagram keeps tiles settled
     * and only changed channels enter.
     */
    val preserveContinuity: Boolean = true,
) {
    companion object {
        val None = SlidePresentation(style = SlideStyle.None)
        val Standard = SlidePresentation(style = SlideStyle.Standard)
        val FocusBeat = SlidePresentation(style = SlideStyle.FocusBeat)
        val DiagramIntro = SlidePresentation(style = SlideStyle.DiagramIntro)
    }
}
