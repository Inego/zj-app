package inego.zungjunggame.tutorial.slide

/**
 * Declarative motion primitives for lesson slides.
 *
 * Enter/exit effects share a phase timeline from t=0; ambient effects run only
 * while the presenter is Idle (and freeze when exit starts).
 */
sealed class SlideEffect {
    /** Channel opacity from [from] → [to]. */
    data class Fade(
        val channels: Set<SlideChannel>,
        val from: Float = 0f,
        val to: Float = 1f,
        val durationSec: Float = 0.22f,
        val delaySec: Float = 0f,
        val ease: SlideEase = SlideEase.OutCubic,
    ) : SlideEffect() {
        init {
            require(channels.isNotEmpty()) { "Fade needs channels" }
            require(durationSec >= 0f) { "Fade durationSec >= 0" }
            require(delaySec >= 0f) { "Fade delaySec >= 0" }
        }
    }

    /**
     * Diagram (or quiz) items appear with index × [delayPerItemSec].
     * Presenter exposes [SlideFrame.itemWeight]; renderer supplies item count.
     */
    data class StaggerItems(
        val channel: SlideChannel = SlideChannel.DiagramItems,
        val itemDurationSec: Float = 0.18f,
        val delayPerItemSec: Float = 0.028f,
        val ease: SlideEase = SlideEase.OutCubic,
    ) : SlideEffect() {
        init {
            require(itemDurationSec >= 0f) { "StaggerItems itemDurationSec >= 0" }
            require(delayPerItemSec >= 0f) { "StaggerItems delayPerItemSec >= 0" }
        }
    }

    /** Looping scale (and mild alpha) on a channel while Idle. */
    data class Pulse(
        val channel: SlideChannel = SlideChannel.Focus,
        val periodSec: Float = 1.15f,
        val amplitude: Float = 0.06f,
    ) : SlideEffect() {
        init {
            require(periodSec > 0f) { "Pulse periodSec > 0" }
            require(amplitude >= 0f) { "Pulse amplitude >= 0" }
        }
    }

    /** Scale 0.85→1 with fade-in (callout pop). */
    data class Pop(
        val channel: SlideChannel = SlideChannel.Callout,
        val durationSec: Float = 0.2f,
        val delaySec: Float = 0.05f,
        val ease: SlideEase = SlideEase.OutCubic,
    ) : SlideEffect() {
        init {
            require(durationSec >= 0f) { "Pop durationSec >= 0" }
            require(delaySec >= 0f) { "Pop delaySec >= 0" }
        }
    }
}
