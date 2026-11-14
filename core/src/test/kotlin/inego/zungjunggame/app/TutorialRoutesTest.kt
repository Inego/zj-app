package inego.zungjunggame.app

import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.stack.ComponentFactory
import inego.zungjunggame.ui.stack.LayerSpecProvider
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.Router
import inego.zungjunggame.ui.stack.StackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S18/S20 + N6: the tutorial shell context and the lesson / quiz / coach content
 * layers are stack-owned through [tutorialRoutesPort]; the host announces
 * show/hide and this port inserts or removes the route. Stack membership is
 * visibility.
 *
 * The specs here are the production ones ([RouteSpecs]): where a content layer
 * lands is decided by `ContentPolicy` (`InSpace`), so a fake spec would test the
 * fake rather than the rule.
 */
class TutorialRoutesTest {

    private class StubComponent(override val id: ComponentId) : Component {
        override fun layout(frame: Frame): Layout = Layout()
        override fun draw(frame: Frame, canvas: Canvas) {}
    }

    private val components =
        ComponentFactory { route -> StubComponent(ComponentId(route.key.value)) }
    private val specs = LayerSpecProvider { route -> RouteSpecs.specFor(route) }

    private fun router(): Router = Router(Route.Screen(RouteIds.ROOT_MENU), components, specs)

    private fun keys(router: Router) = router.stack.entries.map { it.route.key.value }

    @Test
    fun lessonIsBoundAboveTheShellAndBelowTheChrome() {
        val router = router()
        val port = tutorialRoutesPort { router }

        port.showShell()
        port.showQuiz()
        port.showLesson()
        port.showCoach()

        // The shell is a context above the floor, and the lesson goes directly above
        // it however much chrome was announced first.
        assertEquals(
            listOf(
                "screen:${RouteIds.ROOT_MENU}",
                "layer:${RouteIds.TUTORIAL_SHELL}",
                "overlay:${RouteIds.TUTORIAL_LESSON}",
                "overlay:${RouteIds.TUTORIAL_QUIZ}",
                "overlay:${RouteIds.TUTORIAL_COACH}",
            ),
            keys(router),
        )

        // Re-asserting a visible surface must not stack a duplicate.
        port.showShell()
        port.showLesson()
        assertEquals(5, router.stack.entries.size)
    }

    @Test
    fun hidingTheLessonTearsDownTheChromeAboveIt() {
        val router = router()
        val port = tutorialRoutesPort { router }

        port.showShell()
        port.showLesson()
        port.showQuiz()
        port.showCoach()

        port.hideLesson()

        assertEquals(
            listOf("screen:${RouteIds.ROOT_MENU}", "layer:${RouteIds.TUTORIAL_SHELL}"),
            keys(router),
        )
    }

    @Test
    fun contentLayersStayInsideTheShellBelowRestoredChrome() {
        val router = router()
        val port = tutorialRoutesPort { router }
        port.showShell()
        router.push(Route.Reference(StackState.EMPTY))

        port.showLesson()
        port.showQuiz()
        port.showCoach()

        // The restored shape: chrome came back above the shell, and everything the
        // restore announces belongs inside the shell's local space, under that
        // chrome. Above it, an input-owning modal would clear the shell's local
        // space and the beat would be laid out for the whole screen.
        assertEquals(
            listOf(
                "screen:${RouteIds.ROOT_MENU}",
                "layer:${RouteIds.TUTORIAL_SHELL}",
                "overlay:${RouteIds.TUTORIAL_LESSON}",
                "overlay:${RouteIds.TUTORIAL_QUIZ}",
                "overlay:${RouteIds.TUTORIAL_COACH}",
                "reference",
            ),
            keys(router),
        )
    }

    @Test
    fun showAndHidePushAndRemoveTheTutorialRoutes() {
        val router = router()
        val port = tutorialRoutesPort { router }

        port.showShell()
        assertEquals(
            listOf("screen:${RouteIds.ROOT_MENU}", "layer:${RouteIds.TUTORIAL_SHELL}"),
            keys(router),
        )

        port.showQuiz()
        port.showCoach()
        assertEquals(
            listOf(
                "screen:${RouteIds.ROOT_MENU}",
                "layer:${RouteIds.TUTORIAL_SHELL}",
                "overlay:${RouteIds.TUTORIAL_QUIZ}",
                "overlay:${RouteIds.TUTORIAL_COACH}",
            ),
            keys(router),
        )

        // Re-asserting a visible surface must not stack a duplicate.
        port.showQuiz()
        port.showCoach()
        assertEquals(4, router.stack.entries.size)

        // Removing a lower owned layer removes everything above it too.
        port.hideQuiz()
        assertFalse(router.isOpen(Route.Overlay(RouteIds.TUTORIAL_QUIZ).key))
        assertFalse(router.isOpen(Route.Overlay(RouteIds.TUTORIAL_COACH).key))
        port.hideCoach()
        assertEquals(2, router.stack.entries.size)

        // The lesson that is gone takes the context it was hosted in with it.
        port.hideShell()
        assertFalse(router.isOpen(Route.Layer(RouteIds.TUTORIAL_SHELL).key))
        assertEquals(listOf("screen:${RouteIds.ROOT_MENU}"), keys(router))
        assertTrue(router.stack.entries.single().route is Route.Screen)
    }

    @Test
    fun lifecycleIsSafeBeforeTheRouterExists() {
        val port = tutorialRoutesPort { null }

        port.showShell()
        port.showLesson()
        port.showQuiz()
        port.showCoach()
        port.hideShell()
        port.hideLesson()
        port.hideQuiz()
        port.hideCoach()
    }
}
