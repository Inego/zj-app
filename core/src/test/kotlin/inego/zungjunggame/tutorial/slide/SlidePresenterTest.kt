package inego.zungjunggame.tutorial.slide

import inego.zungjunggame.tutorial.TutorialDiagram
import kotlin.test.Test
import kotlin.test.assertTrue

class SlidePresenterTest {

    private fun scene(
        body: String,
        diagram: TutorialDiagram = TutorialDiagram.ThreeSuits,
        title: String = "title",
    ) = SlideScene(
        titleKey = title,
        bodyKey = body,
        diagram = diagram,
    )

    @Test
    fun sameContinuityRequiresMatchingDiagram() {
        val a = scene("a", TutorialDiagram.ThreeSuits)
        val b = scene("b", TutorialDiagram.Honors)
        assertTrue(!SlidePresenter.sameContinuity(a, b))
        assertTrue(SlidePresenter.sameContinuity(a, scene("c", TutorialDiagram.ThreeSuits)))
    }

    @Test
    fun sameContinuityHonorsExplicitKeyWithoutDiagram() {
        val a = SlideScene(
            titleKey = "title",
            bodyKey = "east_hu",
            continuityKey = "board:moves-discarding",
        )
        val b = SlideScene(
            titleKey = "title",
            bodyKey = "east_hu_odds",
            continuityKey = "board:moves-discarding",
        )
        assertTrue(SlidePresenter.sameContinuity(a, b))
        assertTrue(
            !SlidePresenter.sameContinuity(
                a,
                scene("x", TutorialDiagram.None),
            ),
        )
    }
}
