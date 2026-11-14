package inego.zungjunggame.app

import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.tutorial.TutorialRoutesPort
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.Router
import inego.zungjunggame.ui.stack.StackState

/**
 * [TutorialRoutesPort] over the router: each call pushes/removes the matching
 * tutorial route, so the shell context and the lesson/quiz/coach content layers
 * are stack-owned, and the paint range owns what is up.
 *
 * **Every content layer lands inside the shell's local space, never above
 * chrome.** The lesson is the shell's own content, so it is anchored to the shell
 * itself ([Router.pushAbove]); the quiz and the coach are cards *in* that space and
 * go through [Router.pushContent], which finds the provider and its content group
 * from the specs. A restored stack that already carried chrome over the shell — a
 * Settings card, a confirm, the Reference — keeps that chrome on top. Appending put
 * a beat *over* the chrome, and an input-owning modal above the content clears the
 * local-space scope for everything above it, so the beat was laid out across the
 * whole screen while the shell painted it inside the content zone: the slide drawn
 * shifted right by the ToC rail's width, and back reaching the chrome through the
 * beat.
 *
 * The router is resolved lazily because the composition root builds the
 * tutorial host before the router.
 */
internal fun tutorialRoutesPort(router: () -> Router?): TutorialRoutesPort {
    val shellRoute = Route.Layer(RouteIds.TUTORIAL_SHELL)
    val lessonRoute = Route.Overlay(RouteIds.TUTORIAL_LESSON)
    val quizRoute = Route.Overlay(RouteIds.TUTORIAL_QUIZ)
    val coachRoute = Route.Overlay(RouteIds.TUTORIAL_COACH)
    return object : TutorialRoutesPort {
        override fun showShell() {
            val current = router() ?: return
            // Already up: this is a re-entry or a restored lesson, and the chrome
            // that came back with it stays.
            if (current.isOpen(shellRoute.key)) return
            current.pushAboveFloor(shellRoute)
            clearChrome(current)
        }

        override fun hideShell() {
            router()?.remove(shellRoute.key)
        }

        override fun showLesson() {
            val current = router() ?: return
            if (!current.isOpen(lessonRoute.key)) current.pushAbove(shellRoute.key, lessonRoute)
        }

        override fun hideLesson() {
            router()?.remove(lessonRoute.key)
        }

        override fun showQuiz() {
            val current = router() ?: return
            if (!current.isOpen(quizRoute.key)) current.pushContent(quizRoute)
        }

        override fun hideQuiz() {
            router()?.remove(quizRoute.key)
        }

        override fun showCoach() {
            val current = router() ?: return
            if (!current.isOpen(coachRoute.key)) current.pushContent(coachRoute)
        }

        override fun hideCoach() {
            router()?.remove(coachRoute.key)
        }
    }
}

/**
 * The chrome a lesson entered from the menu never shows: the shell is inserted
 * below whatever is already up, so what the menu left open is dropped here.
 */
private fun clearChrome(current: Router) {
    current.remove(Route.Modal(RouteIds.CONFIRM).key)
    current.remove(Route.Modal(RouteIds.SETTINGS).key)
    current.remove(Route.Modal(RouteIds.LANGUAGE_PICKER).key)
    current.remove(Route.Modal(RouteIds.GAME_MENU).key)
    current.remove(Route.Modal(RouteIds.MATCH_HISTORY).key)
    // The browser is dismissed, not torn out from under whatever sits above it.
    val referenceKey = Route.Reference(StackState.EMPTY).key
    if (current.stack.top.route.key == referenceKey) current.pop()
}
