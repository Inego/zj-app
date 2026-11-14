package inego.zungjunggame.tutorial

/**
 * Read-only position in a lesson's slide sequence: which [scenario] and which
 * [stepIndex]. Implemented by the live [TutorialRunner] and by any value that
 * needs to answer slide navigation ([TutorialSlideNav]) without depending on
 * the runner.
 */
interface SlideCursor {
    val scenario: ScenarioDef
    val stepIndex: Int
}
