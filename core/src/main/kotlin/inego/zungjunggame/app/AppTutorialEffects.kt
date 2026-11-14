package inego.zungjunggame.app

import inego.zungjunggame.ui.stack.SessionKind
import inego.zungjunggame.render.wallbuild.WallBuildDeal
import inego.zungjunggame.render.wallbuild.WallBuildHandoffSnapshot
import inego.zungjunggame.session.SessionRegistry
import inego.zungjunggame.tutorial.QuizAnswer
import inego.zungjunggame.tutorial.QuizInteractionState
import inego.zungjunggame.tutorial.TutorialHost

/**
 * Interactive tutorial host commands.
 *
 * The tutorial host owns the runner, the coach/quiz chrome, the mid-lesson save,
 * and the stack membership of the shell context they are hosted in; this effect is
 * the app's call surface onto it. The only rule it owns is that Tutorial opens
 * from the root menu.
 */
internal class AppTutorialEffects(
    private val tutorialHost: TutorialHost,
    private val sessionRegistry: SessionRegistry,
) : TutorialEffects {

    override fun applyPrimary() {
        tutorialHost.applyCoachPrimary()
    }

    override fun applyBack() {
        tutorialHost.applyBack()
    }

    override fun selectLesson(lessonId: String): Boolean = tutorialHost.onNavSelect(lessonId)

    override fun quizSubmit(answer: QuizAnswer) {
        tutorialHost.applyQuizSubmit(answer)
    }

    override fun quizNext() {
        tutorialHost.applyQuizNext()
    }

    override fun quizTryAgain() {
        tutorialHost.applyQuizTryAgain()
    }

    override fun quizStateChanged(attempt: QuizInteractionState) {
        tutorialHost.applyQuizStateChanged(attempt)
    }

    override fun openHref(href: String) {
        tutorialHost.openCatalogHref(href)
    }

    override fun openFromRoot() {
        if (sessionRegistry.kind != SessionKind.Menu) return
        // The host raises the shell itself (through its routes port) once it has a
        // lesson to put in it, so an entry that never loads a lesson leaves no
        // empty shell on the stack. A parked local game is not disturbed.
        tutorialHost.requestOpen()
    }

    override fun slideLeaveComplete() {
        tutorialHost.applySlideLeaveComplete()
    }

    override fun wallDealCommitted(deal: WallBuildDeal) {
        tutorialHost.onWallDealCommitted(deal)
    }

    override fun wallHandoffCommitted(handoff: WallBuildHandoffSnapshot) {
        tutorialHost.onWallHandoffCommitted(handoff)
    }
}
