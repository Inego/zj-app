package inego.zungjunggame.tutorial.slide

/**
 * Expands [SlideStyle] presets into enter / exit / ambient effect lists.
 * Single place to tune default timings.
 *
 * **No preset animates the copy, the felt or the dock.** A beat's title, body,
 * Back / Next row and the felt under them are the beat: they change with it, in
 * one frame. Only the subject — diagram shell and items, a focus rim, a callout
 * — has enter motion, and nothing fades out on leave, so the next beat lands
 * the moment Next (or Back) is pressed.
 */
object SlideStyles {

    data class Expanded(
        val enter: List<SlideEffect>,
        val exit: List<SlideEffect>,
        val ambient: List<SlideEffect>,
    )

    fun expand(style: SlideStyle): Expanded = when (style) {
        SlideStyle.None -> Expanded(emptyList(), emptyList(), emptyList())

        SlideStyle.Standard -> Expanded(
            enter = listOf(
                SlideEffect.Fade(
                    channels = setOf(
                        SlideChannel.Diagram,
                        SlideChannel.DiagramItems,
                        SlideChannel.Focus,
                        SlideChannel.Callout,
                    ),
                    durationSec = 0.22f,
                ),
            ),
            exit = emptyList(),
            ambient = emptyList(),
        )

        SlideStyle.FocusBeat -> Expanded(
            enter = listOf(
                SlideEffect.Fade(
                    channels = setOf(SlideChannel.Focus),
                    durationSec = 0.2f,
                    delaySec = 0.04f,
                ),
                SlideEffect.Pop(
                    channel = SlideChannel.Callout,
                    durationSec = 0.2f,
                    delaySec = 0.08f,
                ),
            ),
            exit = emptyList(),
            ambient = listOf(
                SlideEffect.Pulse(
                    channel = SlideChannel.Focus,
                    periodSec = 1.15f,
                    amplitude = 0.06f,
                ),
            ),
        )

        SlideStyle.DiagramIntro -> Expanded(
            enter = listOf(
                SlideEffect.Fade(
                    channels = setOf(SlideChannel.Diagram),
                    durationSec = 0.16f,
                    delaySec = 0.04f,
                ),
                SlideEffect.StaggerItems(
                    itemDurationSec = 0.16f,
                    delayPerItemSec = 0.022f,
                ),
            ),
            exit = emptyList(),
            ambient = emptyList(),
        )
    }

    /**
     * Merge style template with author extras. Continuity may drop diagram
     * re-enter effects — see [SlidePresenter].
     */
    fun compose(presentation: SlidePresentation): Expanded {
        val base = expand(presentation.style)
        return Expanded(
            enter = base.enter + presentation.enter,
            exit = base.exit + presentation.exit,
            ambient = base.ambient + presentation.ambient,
        )
    }
}
