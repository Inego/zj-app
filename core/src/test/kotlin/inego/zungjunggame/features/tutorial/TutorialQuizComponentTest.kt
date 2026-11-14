package inego.zungjunggame.features.tutorial

import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.tutorial.QuizAnswer
import inego.zungjunggame.tutorial.QuizInteractionState
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.testFrame
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TutorialQuizComponentTest {

    private val route = Route.Overlay(RouteIds.TUTORIAL_QUIZ)

    private fun component(): TutorialQuizComponent = TutorialQuizComponent(
        route = route,
        overlay = { error("layout and action mapping must not construct TutorialQuizOverlay") },
    )

    private fun constraints() = Constraints(800f, 600f, 0f, 0f, 0f)

    @Test
    fun layoutPublishesTheSurfacesOwnBoundsAndLeavesControlsToTheWidget() {
        val layout = component().layout(testFrame(constraints()))

        // The quiz fills its layer and resolves its own controls, so the one rect
        // is both the panel and the region the pipeline consults it for: no
        // control is duplicated here as a hit region.
        assertEquals(Rect(0f, 0f, 800f, 600f), layout.panel)
        assertEquals(listOf(Rect(0f, 0f, 800f, 600f)), layout.gestureRegions)
        assertEquals(0, layout.hitRegions.size)
    }

    @Test
    fun actionForMapsEveryResolution() {
        val attempt = QuizInteractionState(answer = QuizAnswer(selectedIds = setOf("1")))
        assertEquals(
            ComponentAction(TutorialQuizActions.STATE_CHANGED, mapOf(TutorialQuizActions.KEY_ATTEMPT to attempt)),
            TutorialQuizActions.actionFor(
                TutorialQuizOverlay.PointerUpResult(selectionChanged = true, attempt = attempt),
            ),
        )
        val answer = QuizAnswer(selectedIds = setOf("1"))
        assertEquals(
            ComponentAction(TutorialQuizActions.SUBMIT, mapOf(TutorialQuizActions.KEY_ANSWER to answer)),
            TutorialQuizActions.actionFor(
                TutorialQuizOverlay.PointerUpResult(submit = true, answer = answer),
            ),
        )
        assertEquals(
            ComponentAction(TutorialQuizActions.NEXT),
            TutorialQuizActions.actionFor(TutorialQuizOverlay.PointerUpResult(next = true)),
        )
        assertEquals(
            ComponentAction(TutorialQuizActions.TRY_AGAIN),
            TutorialQuizActions.actionFor(TutorialQuizOverlay.PointerUpResult(tryAgain = true)),
        )
        assertEquals(
            ComponentAction(TutorialQuizActions.BACK),
            TutorialQuizActions.actionFor(TutorialQuizOverlay.PointerUpResult(back = true)),
        )
        assertEquals(
            ComponentAction(
                TutorialQuizActions.LINK,
                mapOf(TutorialQuizActions.KEY_HREF to "rules/hand"),
            ),
            TutorialQuizActions.actionFor(
                TutorialQuizOverlay.PointerUpResult(linkHref = "rules/hand"),
            ),
        )
        // Empty-payload submit / state-change is a live getter by another name, so
        // it emits nothing; the felt / locked attempt is the same.
        assertNull(TutorialQuizActions.actionFor(TutorialQuizOverlay.PointerUpResult(submit = true)))
        assertNull(TutorialQuizActions.actionFor(TutorialQuizOverlay.PointerUpResult(selectionChanged = true)))
        assertNull(TutorialQuizActions.actionFor(TutorialQuizOverlay.PointerUpResult()))
    }

    @Test
    fun controlsMapToQuizIntents() {
        val answer = QuizAnswer(selectedIds = setOf("1"))
        assertEquals(
            Intent.TutorialQuizSubmit(answer),
            TutorialQuizActions.toIntent(
                route,
                ComponentAction(TutorialQuizActions.SUBMIT, mapOf(TutorialQuizActions.KEY_ANSWER to answer)),
            ),
        )
        assertEquals(
            Intent.TutorialQuizNext,
            TutorialQuizActions.toIntent(route, ComponentAction(TutorialQuizActions.NEXT)),
        )
        assertEquals(
            Intent.TutorialQuizTryAgain,
            TutorialQuizActions.toIntent(route, ComponentAction(TutorialQuizActions.TRY_AGAIN)),
        )
        val attempt = QuizInteractionState(answer = answer)
        assertEquals(
            Intent.TutorialQuizStateChanged(attempt),
            TutorialQuizActions.toIntent(
                route,
                ComponentAction(TutorialQuizActions.STATE_CHANGED, mapOf(TutorialQuizActions.KEY_ATTEMPT to attempt)),
            ),
        )
        assertEquals(
            Intent.TutorialBack,
            TutorialQuizActions.toIntent(route, ComponentAction(TutorialQuizActions.BACK)),
        )
        assertEquals(
            Intent.TutorialOpenHref("rules/hand"),
            TutorialQuizActions.toIntent(
                route,
                ComponentAction(
                    TutorialQuizActions.LINK,
                    mapOf(TutorialQuizActions.KEY_HREF to "rules/hand"),
                ),
            ),
        )
        // A submit / state-change without its payload maps to nothing.
        assertNull(TutorialQuizActions.toIntent(route, ComponentAction(TutorialQuizActions.SUBMIT)))
        assertNull(TutorialQuizActions.toIntent(route, ComponentAction(TutorialQuizActions.STATE_CHANGED)))
        assertNull(TutorialQuizActions.toIntent(route, ComponentAction("tutorialQuiz.other")))
        assertNull(TutorialQuizActions.outside())
    }

    @Test
    fun registerWiresQuizRoute() {
        val registry = FeatureRegistry()
        TutorialQuizFeature.register(registry) {
            error("a fake registry must not construct TutorialQuizOverlay")
        }

        assertTrue(registry.create(route) is TutorialQuizComponent)
        assertEquals(LayerRole.Overlay, registry.specFor(route).role)
        val answer = QuizAnswer(selectedIds = setOf("1"))
        assertEquals(
            Intent.TutorialQuizSubmit(answer),
            registry.actionToIntent(
                route,
                ComponentAction(TutorialQuizActions.SUBMIT, mapOf(TutorialQuizActions.KEY_ANSWER to answer)),
            ),
        )
    }
}
