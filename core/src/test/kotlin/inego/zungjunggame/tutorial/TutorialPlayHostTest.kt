package inego.zungjunggame.tutorial

import inego.zungjunggame.session.hosts.ConnectionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TutorialPlayHostTest {

    private fun discardWaitRunner(): TutorialRunner =
        TutorialRunner.start(TutorialTestFixtures.discardWaitScenario())

    @Test
    fun tableViewIsNullWithoutRunner() {
        val host = TutorialPlayHost(
            runnerSource = { null },
            revealedSeats = { emptySet() },
            onChoice = { false },
            onDiscard = { false },
        )

        assertFalse(host.isActive)
        assertNull(host.tableView)
        assertNull(host.myDecision)
        assertEquals(0, host.humanSeat)
        assertEquals(ConnectionStatus.Local, host.connectionStatus)
    }

    @Test
    fun tableViewProjectsRunnerStateAndRevealedSeats() {
        val runner = discardWaitRunner()
        val host = TutorialPlayHost(
            runnerSource = { runner },
            revealedSeats = { setOf(1) },
            onChoice = { false },
            onDiscard = { false },
        )

        val view = assertNotNull(host.tableView)
        assertTrue(host.isActive)
        assertEquals(runner.humanSeat, view.mySeat)
        assertEquals(runner.humanSeat, host.humanSeat)
        assertNotNull(view.seats[1].concealedTiles)
    }

    @Test
    fun myDecisionFollowsHumanInteractiveTurn() {
        val runner = discardWaitRunner()
        val host = TutorialPlayHost(
            runnerSource = { runner },
            revealedSeats = { emptySet() },
            onChoice = { false },
            onDiscard = { false },
        )

        val decision = assertNotNull(host.myDecision)
        assertTrue(decision.isDiscard)
        assertFalse(decision.isClaim)
    }

}
