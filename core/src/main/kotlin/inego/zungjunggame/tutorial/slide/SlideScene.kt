package inego.zungjunggame.tutorial.slide

import inego.zungjunggame.tutorial.TutorialDiagram
import inego.zungjunggame.tutorial.TutorialFocus

/**
 * Logical content of one teaching beat — enough to diff continuity between
 * consecutive coaches without caring about layout pixels.
 */
data class SlideScene(
    val titleKey: String,
    val bodyKey: String,
    val diagram: TutorialDiagram = TutorialDiagram.None,
    val focus: TutorialFocus? = null,
    /**
     * Optional stable family id. When null, continuity uses [diagram]
     * (non-[TutorialDiagram.None] only).
     */
    val continuityKey: String? = null,
) {
    fun resolvedContinuityKey(): String? =
        continuityKey?.takeIf { it.isNotBlank() }
            ?: diagram.takeIf { it != TutorialDiagram.None }?.name
}
