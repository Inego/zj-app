package inego.zungjunggame.features.board

import inego.zungjunggame.engine.GameState
import inego.zungjunggame.render.table.TableInteract
import inego.zungjunggame.session.snapshots.HandSnapshot
import inego.zungjunggame.session.snapshots.SessionSnapshot
import inego.zungjunggame.tutorial.samples.SampleScenarios
import inego.zungjunggame.ui.stack.SessionKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardViewTest {

    private fun session(
        kind: SessionKind,
        boardHosted: Boolean,
        hand: HandSnapshot? = null,
    ) = SessionSnapshot(kind = kind, boardHosted = boardHosted, table = null, hand = hand)

    private fun dealtState(): GameState =
        GameState.fromWallOrder(SampleScenarios.canonicalWall(Random(0)), eastSeat = 0)

    private val freePlay = FreePlayRenderFacts(seatScores = intArrayOf(1, 2, 3, 4))

    private val online = OnlineRenderFacts(seatScores = intArrayOf(5, 6, 7, 8))

    @Test
    fun unhostedBoardKeepsScoresOff() {
        val inputs = boardTableInputs(
            session = session(SessionKind.Menu, boardHosted = false),
            origin = null,
            freePlay = freePlay,
            online = online,
        )

        assertFalse(inputs.hintsEnabled)
        assertContentEquals(IntArray(4), inputs.seatScores)
    }

    @Test
    fun tutorialHostsTheBoardWithHintsOff() {
        val inputs = boardTableInputs(
            session = session(
                SessionKind.Tutorial,
                boardHosted = true,
                hand = HandSnapshot(state = dealtState(), active = true, humanSeat = 2),
            ),
            origin = null,
            freePlay = freePlay,
            online = null,
        )

        assertEquals(2, inputs.humanSeat)
        assertFalse(inputs.hintsEnabled)
    }

    @Test
    fun freePlayBoardTakesScoresFromItsFacts() {
        val inputs = boardTableInputs(
            session = session(
                SessionKind.FreePlay,
                boardHosted = true,
                hand = HandSnapshot(state = dealtState(), active = true, humanSeat = 1),
            ),
            origin = null,
            freePlay = freePlay,
            online = null,
        )

        assertEquals(1, inputs.humanSeat)
        assertTrue(inputs.hintsEnabled)
        assertContentEquals(intArrayOf(1, 2, 3, 4), inputs.seatScores)
    }

    @Test
    fun freePlayWithoutADealFallsBackToTheKindDefaults() {
        val inputs = boardTableInputs(
            session = session(SessionKind.FreePlay, boardHosted = true, hand = null),
            origin = null,
            freePlay = freePlay,
            online = null,
        )

        assertTrue(inputs.hintsEnabled)
        assertContentEquals(IntArray(4), inputs.seatScores)
    }

    @Test
    fun onlineBoardTakesScoresAndHintsOff() {
        val inputs = boardTableInputs(
            session = session(SessionKind.Online, boardHosted = true, hand = null),
            origin = null,
            freePlay = freePlay,
            online = online,
        )

        assertFalse(inputs.hintsEnabled)
        assertContentEquals(intArrayOf(5, 6, 7, 8), inputs.seatScores)
    }

    @Test
    fun onlineKindWithoutAFactFallsBackToTheKindDefaults() {
        val inputs = boardTableInputs(
            session = session(SessionKind.Online, boardHosted = true, hand = null),
            origin = null,
            freePlay = freePlay,
            online = null,
        )

        assertFalse(inputs.hintsEnabled)
        assertContentEquals(IntArray(4), inputs.seatScores)
    }

    @Test
    fun tutorialChromeLocksTheTable() {
        val inputs = boardTableInputs(
            session = session(
                SessionKind.Tutorial,
                boardHosted = true,
                hand = HandSnapshot(state = dealtState(), active = true, humanSeat = 0),
            ),
            origin = null,
            freePlay = freePlay,
            online = null,
            chrome = TableChrome(
                interact = TableInteract.ReadOnly,
                highlightLiveWall = true,
                overlayLeaderActive = true,
                revealedSeats = setOf(2),
            ),
        )

        assertEquals(TableInteract.ReadOnly, inputs.interact)
        assertTrue(inputs.highlightLiveWall)
        assertTrue(inputs.overlayLeaderActive)
        assertEquals(setOf(2), inputs.revealedSeats)
        assertFalse(inputs.hintsEnabled)
    }

    @Test
    fun onlineDisconnectFreezesTheTable() {
        val inputs = boardTableInputs(
            session = session(SessionKind.Online, boardHosted = true, hand = null),
            origin = null,
            freePlay = freePlay,
            online = online.copy(connected = false),
        )

        assertEquals(TableInteract.ReadOnly, inputs.interact)
    }

    @Test
    fun freePlayStaysInteractive() {
        val inputs = boardTableInputs(
            session = session(
                SessionKind.FreePlay,
                boardHosted = true,
                hand = HandSnapshot(state = dealtState(), active = true, humanSeat = 0),
            ),
            origin = null,
            freePlay = freePlay,
            online = null,
        )

        assertEquals(TableInteract.Play, inputs.interact)
    }
}
