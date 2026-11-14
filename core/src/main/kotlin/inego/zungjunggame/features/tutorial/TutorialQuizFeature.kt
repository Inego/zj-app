package inego.zungjunggame.features.tutorial

import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.tutorial.QuizAnswer
import inego.zungjunggame.tutorial.QuizInteractionState
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.stack.Route

/**
 * Registers the [RouteIds.TUTORIAL_QUIZ] overlay above the tutorial lesson
 * screen with the [inego.zungjunggame.ui.stack.LayerRole.Overlay] defaults.
 */
object TutorialQuizFeature {
    fun register(registry: FeatureRegistry, overlay: () -> TutorialQuizOverlay) {
        val route = Route.Overlay(RouteIds.TUTORIAL_QUIZ)
        registry.register(
            routeKey = route.key,
            factory = { registered -> TutorialQuizComponent(registered, overlay) },
            spec = RouteSpecs.specFor(route),
            actions = TutorialQuizActions,
        )
    }
}

/** Maps the quiz surface's resolutions and prompt links to tutorial intents. */
object TutorialQuizActions : RouteActions {
    const val SUBMIT = "tutorialQuiz.submit"
    const val NEXT = "tutorialQuiz.next"
    const val TRY_AGAIN = "tutorialQuiz.tryAgain"
    const val STATE_CHANGED = "tutorialQuiz.stateChanged"
    const val BACK = "tutorialQuiz.back"
    const val LINK = "tutorialQuiz.link"
    const val KEY_HREF = "href"
    const val KEY_ANSWER = "answer"
    const val KEY_ATTEMPT = "attempt"

    /**
     * The action a completed press emits: the prompt link it landed on, or the
     * control it resolved plus the attempt edit / answer it carried. A release
     * that resolved nothing emits no action — the bridge still consumed the lift,
     * so it is not re-read as a tap. A submit / state-change with no payload is a
     * live getter by another name, so it emits nothing either.
     */
    fun actionFor(verdict: TutorialQuizOverlay.PointerUpResult): ComponentAction? {
        verdict.linkHref?.let { href ->
            return ComponentAction(LINK, mapOf(KEY_HREF to href))
        }
        return when {
            verdict.selectionChanged -> verdict.attempt?.let {
                ComponentAction(STATE_CHANGED, mapOf(KEY_ATTEMPT to it))
            }
            verdict.submit -> verdict.answer?.let {
                ComponentAction(SUBMIT, mapOf(KEY_ANSWER to it))
            }
            verdict.next -> ComponentAction(NEXT)
            verdict.tryAgain -> ComponentAction(TRY_AGAIN)
            verdict.back -> ComponentAction(BACK)
            else -> null
        }
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        SUBMIT -> (action.payload[KEY_ANSWER] as? QuizAnswer)
            ?.let { Intent.TutorialQuizSubmit(it) }
        STATE_CHANGED -> (action.payload[KEY_ATTEMPT] as? QuizInteractionState)
            ?.let { Intent.TutorialQuizStateChanged(it) }
        NEXT -> Intent.TutorialQuizNext
        TRY_AGAIN -> Intent.TutorialQuizTryAgain
        BACK -> Intent.TutorialBack
        LINK -> (action.payload[KEY_HREF] as? String)?.let { Intent.TutorialOpenHref(it) }
        else -> null
    }
}
