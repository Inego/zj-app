package inego.zungjunggame.session

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.engines.DefaultEngine
import inego.zungjunggame.session.board.BoardController
import inego.zungjunggame.session.board.BotTiming
import inego.zungjunggame.session.board.EngineNonHumanPicker
import inego.zungjunggame.session.board.NonHumanPicker
import inego.zungjunggame.session.board.NonHumanTurnStepper
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [inego.zungjunggame.session.board.NonHumanTurnStepper] animation contract + injectable [inego.zungjunggame.session.board.NonHumanPicker].
 */
class NonHumanTurnStepperTest {

    @Test
    fun pauseUntilNextIdleSkipsOneStepThenApplies() {
        val state = GameState.fromWallOrder(canonicalWall(Random(5)), eastSeat = 0)
        // Seat 0 is East / human in free play; force non-human by treating no one as human.
        var applyCount = 0
        val picker = NonHumanPicker { s ->
            val discard = s.enumerateChoices().asList().firstOrNull { it.kind == ChoiceKind.Discard }
                ?: return@NonHumanPicker false
            s.applyChoice(discard)
            applyCount++
            true
        }
        val stepper = stepper(picker)
        stepper.pauseUntilNextIdle = true
        assertFalse(stepper.tryStep(state, active = true))
        assertEquals(0, applyCount)
        assertFalse(stepper.pauseUntilNextIdle)

        assertTrue(stepper.tryStep(state, active = true))
        assertEquals(1, applyCount)
        assertTrue(stepper.pauseUntilNextIdle)
    }

    @Test
    fun doesNotStepWhileAnimatingOrOnHumanTurn() {
        val state = GameState.fromWallOrder(canonicalWall(Random(6)), eastSeat = 0)
        var applyCount = 0
        val picker = NonHumanPicker {
            applyCount++
            false
        }
        val stepper = stepper(picker, isHumanInteractiveTurn = { true })
        assertFalse(stepper.tryStep(state, active = true))
        assertEquals(0, applyCount)

        val animating = stepper(picker, isAnimating = { true })
        assertFalse(animating.tryStep(state, active = true))
        assertEquals(0, applyCount)
    }

    @Test
    fun enginePickerAdvancesPastAllPassReactionWindow() {
        val state = GameState.fromWallOrder(canonicalWall(Random(11)), eastSeat = 0)
        val board = BoardController()
        val engine = DefaultEngine()
        val rng = Random(0)
        // Discard as "human" seat 0 without going through stepper.
        val discard = state.enumerateChoices().asList().first { it.kind == ChoiceKind.Discard }
        state.applyChoice(discard)
        assertEquals(DecisionType.ReactToDiscard, state.decisionType)

        val stepper = stepper(
            picker = EngineNonHumanPicker(board, engine) { rng },
            // Human is seat 0; during reaction, seats 1..3 are non-human until 0 would bid
            // (0 is discarder, never reacts). Treat only seat 0 ActOnHand as human.
            isHumanInteractiveTurn = { s ->
                s.decisionType == DecisionType.ActOnHand && s.currentPlayer == 0
            },
        )
        // May need more than one tryStep if pause is set after first reaction batch.
        var steps = 0
        while (state.decisionType == DecisionType.ReactToDiscard && steps < 8) {
            stepper.pauseUntilNextIdle = false
            stepper.tryStep(state, active = true)
            steps++
        }
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertTrue(state.currentPlayer in 0 until 4)
    }

    @Test
    fun scriptedPickerCanReplaceEngine() {
        val state = GameState.fromWallOrder(canonicalWall(Random(8)), eastSeat = 0)
        val target = state.enumerateChoices().asList()
            .filter { it.kind == ChoiceKind.Discard }
            .maxBy { it.tile() }
        val scripted = NonHumanPicker { s ->
            val match = s.enumerateChoices().asList()
                .firstOrNull { it.kind == ChoiceKind.Discard && it.tile() == target.tile() }
                ?: return@NonHumanPicker false
            s.applyChoice(match)
            true
        }
        val stepper = stepper(scripted)
        assertTrue(stepper.tryStep(state, active = true))
        assertEquals(target.tile(), state.lastDiscarded)
    }

    @Test
    fun inactiveOrNullStateNoops() {
        val stepper = stepper({ true })
        assertFalse(stepper.tryStep(null, active = true))
        val state = GameState.fromWallOrder(canonicalWall(Random(1)), eastSeat = 0)
        assertFalse(stepper.tryStep(state, active = false))
    }

    @Test
    fun actOnHandWaitsThinkDelayThenApplies() {
        val state = GameState.fromWallOrder(canonicalWall(Random(5)), eastSeat = 0)
        var now = 0L
        var applyCount = 0
        val picker = NonHumanPicker { s ->
            val discard = s.enumerateChoices().asList().firstOrNull { it.kind == ChoiceKind.Discard }
                ?: return@NonHumanPicker false
            s.applyChoice(discard)
            applyCount++
            true
        }
        val stepper = stepper(picker, timing = fixedTiming(thinkMs = 2000L), nowMs = { now })
        assertFalse(stepper.tryStep(state, active = true))
        assertEquals(0, applyCount)
        now = 1999L
        assertFalse(stepper.tryStep(state, active = true))
        assertEquals(0, applyCount)
        now = 2000L
        assertTrue(stepper.tryStep(state, active = true))
        assertEquals(1, applyCount)
    }

    @Test
    fun passOnlyReactionSkipsThinkDelay() {
        val state = GameState.fromWallOrder(canonicalWall(Random(11)), eastSeat = 0)
        val discard = state.enumerateChoices().asList().first { it.kind == ChoiceKind.Discard }
        state.applyChoice(discard)
        assertEquals(DecisionType.ReactToDiscard, state.decisionType)

        val now = 0L
        val stepper = stepper(
            picker = EngineNonHumanPicker(BoardController(), DefaultEngine()) { Random(0) },
            isHumanInteractiveTurn = { s ->
                s.decisionType == DecisionType.ActOnHand && s.currentPlayer == 0
            },
            timing = fixedTiming(thinkMs = 2000L),
            nowMs = { now },
        )
        var steps = 0
        while (state.decisionType == DecisionType.ReactToDiscard && steps < 8) {
            stepper.pauseUntilNextIdle = false
            stepper.tryStep(state, active = true)
            steps++
        }
        assertEquals(DecisionType.ActOnHand, state.decisionType)
        assertEquals(0L, now)
    }

    @Test
    fun nonTrivialReactionWaitsThinkDelayOnce() {
        val state = GameState()
        state.currentPlayer = 1
        state.decisionType = DecisionType.ReactToDiscard
        state.lastDiscarder = 0
        state.lastDiscarded = Tiles.EAST
        state.liveWallSize = 40
        state.players[1].concealed[Tiles.EAST] = 3
        state.players[1].activeConcealedTileCount = -1

        var now = 0L
        var applyCount = 0
        val picker = NonHumanPicker { s ->
            s.applyChoice(Choice.pass())
            applyCount++
            true
        }
        val stepper = stepper(picker, timing = fixedTiming(thinkMs = 2000L), nowMs = { now })
        assertFalse(stepper.tryStep(state, active = true))
        assertEquals(0, applyCount)
        now = 2000L
        assertTrue(stepper.tryStep(state, active = true))
        assertTrue(applyCount >= 1)
    }

    @Test
    fun realBotReactionDoesNotDelayHumanClaimPanel() {
        val state = GameState()
        state.decisionType = DecisionType.ReactToDiscard
        state.lastDiscarder = 2
        state.lastDiscarded = Tiles.EAST
        state.liveWallSize = 40
        state.currentPlayer = 3
        state.players[3].concealed[Tiles.EAST] = 3
        state.players[3].activeConcealedTileCount = -1
        state.players[0].concealed[Tiles.EAST] = 2
        state.players[0].activeConcealedTileCount = -1

        val now = 0L
        var applyCount = 0
        val picker = NonHumanPicker { s ->
            s.applyChoice(Choice.pass())
            applyCount++
            true
        }
        val stepper = stepper(
            picker = picker,
            isHumanInteractiveTurn = { s ->
                s.decisionType == DecisionType.ReactToDiscard && s.currentPlayer == 0
            },
            timing = fixedTiming(thinkMs = 2000L),
            nowMs = { now },
            isHumanSeat = { it == 0 },
        )
        assertTrue(stepper.tryStep(state, active = true))
        assertEquals(1, applyCount)
        assertEquals(0, state.currentPlayer)
        assertEquals(0L, now)
    }

    private fun stepper(
        picker: NonHumanPicker,
        isHumanInteractiveTurn: (GameState) -> Boolean = { false },
        isAnimating: () -> Boolean = { false },
        timing: BotTiming = fixedTiming(),
        nowMs: () -> Long = { 0L },
        isHumanSeat: (Int) -> Boolean = { false },
    ) = NonHumanTurnStepper(
        isHumanInteractiveTurn = isHumanInteractiveTurn,
        isAnimating = isAnimating,
        picker = picker,
        timing = timing,
        nowMs = nowMs,
        isHumanSeat = isHumanSeat,
    )

    /** A deterministic [BotTiming] for tests: zero unless a case names a beat. */
    private fun fixedTiming(dwellMs: Long = 0L, thinkMs: Long = 0L): BotTiming =
        BotTiming(dwellSample = { dwellMs }, thinkSample = { thinkMs })

    /**
     * The frame contract, which is what makes the stepper run at all.
     *
     * Under `setContinuousRendering(false)` nothing presents unless something asks.
     * This stepper *consumes* a frame in two places nothing else covers: it skips one
     * idle frame after each apply so the table can start tile motion (so the apply is
     * deferred to a frame that must exist), and its think delay is wall-clock, which
     * cannot wake the loop on its own. The board used to be pinned open by an
     * unrelated always-on clock that supplied those frames by accident; with that
     * gone, `needsFrame` is the only thing asking.
     *
     * The pair matters. A stepper that always answered true would keep the board
     * spinning forever — the defect this work removed — while one that always
     * answered false never moves a bot at all.
     */
    @Test
    fun needsFrameOnlyWhileANonHumanDecisionIsOutstanding() {
        val state = GameState.fromWallOrder(canonicalWall(Random(7)), eastSeat = 0)
        val botTurn = stepper({ false })
        assertTrue(
            botTurn.needsFrame(state, active = true),
            "a bot decision is outstanding, so a frame is owed",
        )

        // The human owes the decision: this is the "player is thinking" case that
        // must cost nothing, and the reason the old always-on clock was wrong.
        assertFalse(
            botTurn.needsFrame(state, active = false),
            "an inactive hand owes nothing",
        )
        assertFalse(
            stepper({ false }, isHumanInteractiveTurn = { true })
                .needsFrame(state, active = true),
            "the human's own turn must not hold the loop open",
        )

        // Still true across the think window: the deadline is wall-clock, so the
        // frames have to keep coming until it is reached.
        var now = 0L
        val thinking = stepper(
            picker = { false },
            timing = fixedTiming(thinkMs = 1500L),
            nowMs = { now },
        )
        assertTrue(thinking.needsFrame(state, active = true))
        now = 1_400L
        assertTrue(
            thinking.needsFrame(state, active = true),
            "still inside the think delay, so still owed a frame",
        )
    }

    private fun canonicalWall(rng: Random): IntArray {
        val wall = IntArray(inego.zungjunggame.engine.WALL_SIZE) { it / 4 }
        for (i in wall.lastIndex downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = wall[i]
            wall[i] = wall[j]
            wall[j] = tmp
        }
        return wall
    }
}
