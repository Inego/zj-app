package inego.zungjunggame.session

import inego.zungjunggame.audio.SoundCue
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.net.ChoiceWire
import inego.zungjunggame.net.PresentationEventWire
import inego.zungjunggame.net.SeatSnapshotViewWire
import inego.zungjunggame.net.SeatSnapshotWire
import inego.zungjunggame.net.ServerMessageWire
import inego.zungjunggame.session.hosts.ActionableDecision
import inego.zungjunggame.session.online.PresentationSequencer
import inego.zungjunggame.session.online.toActionableDecision
import inego.zungjunggame.session.table.DiscardOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresentationSequencerTest {

    private fun makeSampleSnapshot(
        mySeat: Int = 0,
        handNumber: Int = 1,
        eastSeat: Int = 0,
        currentPlayer: Int = 0,
        decisionType: String = "ActOnHand",
        isPendingDiscard: Boolean = false,
        lastDiscarder: Int? = null,
        lastDiscarded: Int? = null,
        liveWallSize: Int = 70,
        isTerminal: Boolean = false,
        winnerSeat: Int? = null,
        winSource: Int? = null,
        events: List<PresentationEventWire> = emptyList(),
        riverCounts: List<Int> = listOf(0, 0, 0, 0),
    ): SeatSnapshotWire {
        val seatViews = (0..3).map { s ->
            val riverSize = riverCounts.getOrElse(s) { 0 }
            val river = (0 until riverSize).map { 10 + it }
            SeatSnapshotViewWire(
                seat = s,
                concealedCount = 13,
                concealedTiles = if (s == mySeat) listOf(0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 9) else null,
                drawnTile = if (s == currentPlayer && !isPendingDiscard) 10 else -1,
                hasDrawnTile = s == currentPlayer && !isPendingDiscard,
                melds = emptyList(),
                river = river,
                seatWindTile = s,
            )
        }
        return SeatSnapshotWire(
            handNumber = handNumber,
            mySeat = mySeat,
            eastSeat = eastSeat,
            currentPlayer = currentPlayer,
            decisionType = decisionType,
            lastDiscarder = lastDiscarder,
            lastDiscarded = lastDiscarded,
            isPendingDiscard = isPendingDiscard,
            isKongRobWindow = false,
            liveWallSize = liveWallSize,
            handScore = 0,
            winnerSeat = winnerSeat,
            winSource = winSource,
            isTerminal = isTerminal,
            scoreDeltas = null,
            seats = seatViews,
            events = events,
        )
    }

    private fun makeSampleDecision(isDiscard: Boolean = true): ActionableDecision {
        val prompt = if (isDiscard) {
            ServerMessageWire.DecisionPrompt(
                decisionId = 1L,
                decisionType = "ActOnHand",
                choices = listOf(ChoiceWire(ChoiceKind.Discard.name, 5)),
                deadlineMs = 5000L,
            )
        } else {
            ServerMessageWire.DecisionPrompt(
                decisionId = 2L,
                decisionType = "ReactToDiscard",
                choices = listOf(ChoiceWire(ChoiceKind.Pass.name, 0)),
                deadlineMs = 5000L,
            )
        }
        return prompt.toActionableDecision()
    }

    private fun makeClaimDecision(vararg kinds: ChoiceKind): ActionableDecision {
        val prompt = ServerMessageWire.DecisionPrompt(
            decisionId = 3L,
            decisionType = "ReactToDiscard",
            choices = kinds.map { ChoiceWire(it.name, 0) },
            deadlineMs = 5000L,
        )
        return prompt.toActionableDecision()
    }

    /** Apply one queued step, then consume the post-apply idle pause. */
    private fun applyOneIdleStep(sequencer: PresentationSequencer) {
        assertTrue(sequencer.tryStep(boardIdle = true))
        assertFalse(sequencer.tryStep(boardIdle = true))
    }

    @Test
    fun `first snapshot initializes view state immediately with zero delay`() {
        val sequencer = PresentationSequencer()
        val snap = makeSampleSnapshot()

        sequencer.enqueueSnapshot(snap)

        assertNotNull(sequencer.displayedViewState)
        assertEquals(0, sequencer.queueSize)
        assertTrue(sequencer.isCaughtUp)
    }

    @Test
    fun `sequential playback paces bot transitions and latches decision only upon completion`() {
        val sequencer = PresentationSequencer()
        val snap0 = makeSampleSnapshot(currentPlayer = 0)
        sequencer.enqueueSnapshot(snap0)

        // Bot 1 discards tile 15
        val snap1 = makeSampleSnapshot(
            currentPlayer = 1,
            isPendingDiscard = true,
            lastDiscarder = 1,
            lastDiscarded = 15,
            events = listOf(PresentationEventWire.Discard(seat = 1, tile = 15)),
        )
        sequencer.enqueueSnapshot(snap1)
        assertEquals(1, sequencer.queueSize)

        // Decision prompt arrives for human to react to discard
        val prompt = makeSampleDecision(isDiscard = false)
        sequencer.enqueuePrompt(prompt)

        // Prompt must NOT be displayed yet while a transition is queued
        assertNull(sequencer.displayedDecision)
        assertFalse(sequencer.displayedViewState?.isHumanClaimTurn ?: false)

        // Busy board: no apply
        assertFalse(sequencer.tryStep(boardIdle = false))
        assertEquals(1, sequencer.queueSize)
        assertNull(sequencer.displayedDecision)

        assertTrue(sequencer.tryStep(boardIdle = true))
        assertEquals(0, sequencer.queueSize)
        assertNull(sequencer.displayedDecision)
        assertFalse(sequencer.isCaughtUp)

        // First idle after apply consumes the pause and latches the prompt
        assertFalse(sequencer.tryStep(boardIdle = true))
        assertNotNull(sequencer.displayedDecision)
        assertEquals(prompt, sequencer.displayedDecision)
        assertTrue(sequencer.displayedViewState?.isHumanClaimTurn ?: false)
        assertTrue(sequencer.isCaughtUp)
    }

    @Test
    fun `burst of snapshots still applies one step per idle cycle`() {
        val sequencer = PresentationSequencer()
        val snap0 = makeSampleSnapshot()
        sequencer.enqueueSnapshot(snap0)

        repeat(7) { i ->
            val snap = makeSampleSnapshot(
                currentPlayer = (i + 1) % 4,
                events = listOf(PresentationEventWire.Discard(seat = (i + 1) % 4, tile = 10 + i)),
            )
            sequencer.enqueueSnapshot(snap)
        }
        assertEquals(7, sequencer.queueSize)
        assertFalse(sequencer.isCaughtUp)

        repeat(7) {
            applyOneIdleStep(sequencer)
        }
        assertEquals(0, sequencer.queueSize)
        assertTrue(sequencer.isCaughtUp)
    }

    @Test
    fun `busy board does not consume the post-apply pause`() {
        val sequencer = PresentationSequencer()
        sequencer.enqueueSnapshot(makeSampleSnapshot())
        sequencer.enqueueSnapshot(
            makeSampleSnapshot(
                currentPlayer = 1,
                events = listOf(PresentationEventWire.Discard(seat = 1, tile = 15)),
            ),
        )

        assertTrue(sequencer.tryStep(boardIdle = true))
        assertEquals(0, sequencer.queueSize)
        assertFalse(sequencer.isCaughtUp)

        // Flights in progress: pause stays armed
        repeat(3) {
            assertFalse(sequencer.tryStep(boardIdle = false))
            assertFalse(sequencer.isCaughtUp)
        }

        // First idle consumes pause; still not caught up until that call returns
        assertFalse(sequencer.tryStep(boardIdle = true))
        assertTrue(sequencer.isCaughtUp)
    }

    @Test
    fun `fastForward clears queue and synchronizes authoritative state and prompt immediately`() {
        val sequencer = PresentationSequencer()
        val snap0 = makeSampleSnapshot()
        sequencer.enqueueSnapshot(snap0)

        repeat(5) { i ->
            val snap = makeSampleSnapshot(
                currentPlayer = (i + 1) % 4,
                events = listOf(PresentationEventWire.Discard(seat = (i + 1) % 4, tile = 10 + i)),
            )
            sequencer.enqueueSnapshot(snap)
        }
        val prompt = makeSampleDecision(isDiscard = true)
        sequencer.enqueuePrompt(prompt)

        assertTrue(sequencer.queueSize > 0)
        assertNull(sequencer.displayedDecision)

        val latestSnap = makeSampleSnapshot(currentPlayer = 0)
        sequencer.fastForward(latestSnap)

        assertEquals(0, sequencer.queueSize)
        assertEquals(prompt, sequencer.displayedDecision)
        assertTrue(sequencer.displayedViewState?.isHumanDiscardTurn ?: false)
        assertTrue(sequencer.isCaughtUp)
    }

    @Test
    fun `latching a claim prompt with Hu cues the sting exactly once`() {
        val sequencer = PresentationSequencer()
        sequencer.enqueueSnapshot(makeSampleSnapshot())
        val prompt = makeClaimDecision(ChoiceKind.Hu, ChoiceKind.Pass)

        sequencer.enqueuePrompt(prompt)

        assertEquals(prompt, sequencer.displayedDecision)
        assertEquals(listOf(SoundCue.HuPrompt), sequencer.consumePendingSoundCues())
        sequencer.enqueuePrompt(prompt)
        assertTrue(sequencer.consumePendingSoundCues().isEmpty(), "an open panel does not re-sting")
    }

    @Test
    fun `latching a claim prompt without Hu cues attention`() {
        val sequencer = PresentationSequencer()
        sequencer.enqueueSnapshot(makeSampleSnapshot())
        val prompt = makeClaimDecision(ChoiceKind.Pung, ChoiceKind.Pass)

        sequencer.enqueuePrompt(prompt)

        assertEquals(prompt, sequencer.displayedDecision)
        assertEquals(listOf(SoundCue.ClaimPrompt), sequencer.consumePendingSoundCues())
    }

    @Test
    fun `fastForward delivers a pending Hu prompt cue on the same edge`() {
        val sequencer = PresentationSequencer()
        sequencer.enqueueSnapshot(makeSampleSnapshot())
        sequencer.enqueueSnapshot(
            makeSampleSnapshot(
                currentPlayer = 1,
                events = listOf(PresentationEventWire.Discard(seat = 1, tile = 15)),
            ),
        )
        val prompt = makeClaimDecision(ChoiceKind.Hu, ChoiceKind.Pass)
        sequencer.enqueuePrompt(prompt)
        assertNull(sequencer.displayedDecision)
        assertTrue(sequencer.consumePendingSoundCues().isEmpty(), "an unlatched prompt is silent")

        sequencer.fastForward(makeSampleSnapshot(currentPlayer = 0))

        assertEquals(prompt, sequencer.displayedDecision)
        assertEquals(listOf(SoundCue.HuPrompt), sequencer.consumePendingSoundCues())
        sequencer.fastForward(makeSampleSnapshot(currentPlayer = 0))
        assertTrue(sequencer.consumePendingSoundCues().isEmpty(), "a resync onto the same prompt is silent")
    }

    @Test
    fun `hand boundary immediately syncs without delay`() {
        val sequencer = PresentationSequencer()
        val snapHand1 = makeSampleSnapshot(handNumber = 1, eastSeat = 0)
        sequencer.enqueueSnapshot(snapHand1)

        val snapHand1Move = makeSampleSnapshot(
            handNumber = 1,
            eastSeat = 0,
            events = listOf(PresentationEventWire.Discard(1, 10)),
        )
        sequencer.enqueueSnapshot(snapHand1Move)
        assertEquals(1, sequencer.queueSize)

        val snapHand2 = makeSampleSnapshot(handNumber = 2, eastSeat = 1)
        sequencer.enqueueSnapshot(snapHand2)

        assertEquals(0, sequencer.queueSize)
        assertEquals(1, sequencer.displayedViewState?.eastSeat)
        assertTrue(sequencer.isCaughtUp)
    }

    @Test
    fun `fallback inference derives transitions when server events are absent`() {
        val sequencer = PresentationSequencer()
        val snap0 = makeSampleSnapshot(
            liveWallSize = 70,
            riverCounts = listOf(0, 0, 0, 0),
        )
        sequencer.enqueueSnapshot(snap0)

        val snapDiscard = makeSampleSnapshot(
            isPendingDiscard = true,
            lastDiscarder = 2,
            lastDiscarded = 18,
            events = emptyList(),
        )
        sequencer.enqueueSnapshot(snapDiscard)
        assertEquals(1, sequencer.queueSize)

        val snapDraw = makeSampleSnapshot(
            currentPlayer = 3,
            liveWallSize = 69,
            events = emptyList(),
        )
        sequencer.enqueueSnapshot(snapDraw)
        assertEquals(2, sequencer.queueSize)

        val snapEnd = makeSampleSnapshot(
            isTerminal = true,
            winnerSeat = 1,
            winSource = 2,
            events = emptyList(),
        )
        sequencer.enqueueSnapshot(snapEnd)
        assertEquals(3, sequencer.queueSize)
    }

    @Test
    fun `successive snapshots in background tab fast-forward directly to latest snapshot upon resume`() {
        val sequencer = PresentationSequencer()
        val snap0 = makeSampleSnapshot(currentPlayer = 0)
        sequencer.enqueueSnapshot(snap0)

        var latestSnapshot = snap0
        repeat(20) { i ->
            val snap = makeSampleSnapshot(
                currentPlayer = (i + 1) % 4,
                events = listOf(PresentationEventWire.Discard(seat = (i + 1) % 4, tile = 10 + i)),
            )
            sequencer.enqueueSnapshot(snap)
            latestSnapshot = snap
        }

        // Simulate returning to tab: fastForward to the latest snapshot
        sequencer.fastForward(latestSnapshot)

        assertEquals(0, sequencer.queueSize)
        assertEquals(latestSnapshot.currentPlayer, sequencer.displayedViewState?.currentPlayer)
        assertTrue(sequencer.isCaughtUp)
    }

    @Test
    fun `rapid burst of opponent turns from an away client queues all steps without dropping moves`() {
        val sequencer = PresentationSequencer()
        val snap0 = makeSampleSnapshot(currentPlayer = 0)
        sequencer.enqueueSnapshot(snap0)

        // Opponent away / bots take turns in 0ms (3 snapshots in burst: Draw, Discard, My Draw)
        val snap1 = makeSampleSnapshot(currentPlayer = 1, events = listOf(PresentationEventWire.Draw(1, null)))
        val snap2 = makeSampleSnapshot(currentPlayer = 1, events = listOf(PresentationEventWire.Discard(1, 15)))
        val snap3 = makeSampleSnapshot(currentPlayer = 0, events = listOf(PresentationEventWire.Draw(0, 8)))

        sequencer.enqueueSnapshot(snap1)
        sequencer.enqueueSnapshot(snap2)
        sequencer.enqueueSnapshot(snap3)

        assertEquals(3, sequencer.queueSize)
        assertFalse(sequencer.isCaughtUp)

        // Sequentially plays out
        applyOneIdleStep(sequencer)
        assertEquals(2, sequencer.queueSize)
        applyOneIdleStep(sequencer)
        assertEquals(1, sequencer.queueSize)
        applyOneIdleStep(sequencer)
        assertEquals(0, sequencer.queueSize)
        assertTrue(sequencer.isCaughtUp)
    }

    @Test
    fun `MAX_QUEUED_STEPS safety ceiling drops intermediate steps when queue overflows`() {
        val sequencer = PresentationSequencer()
        val snap0 = makeSampleSnapshot()
        sequencer.enqueueSnapshot(snap0)

        repeat(PresentationSequencer.MAX_QUEUED_STEPS) { i ->
            val snap = makeSampleSnapshot(currentPlayer = 1, events = listOf(PresentationEventWire.Discard(1, 10 + i)))
            sequencer.enqueueSnapshot(snap)
        }
        assertEquals(PresentationSequencer.MAX_QUEUED_STEPS, sequencer.queueSize)

        // One more step exceeding MAX_QUEUED_STEPS triggers the safety catch-up guard
        val overflowSnap = makeSampleSnapshot(currentPlayer = 3, events = listOf(PresentationEventWire.Discard(3, 99)))
        sequencer.enqueueSnapshot(overflowSnap)

        assertEquals(0, sequencer.queueSize)
        assertEquals(3, sequencer.displayedViewState?.currentPlayer)
        assertTrue(sequencer.isCaughtUp)
    }

    @Test
    fun `pending discard origin is a session value for hand and drawn slots`() {
        val sequencer = PresentationSequencer()
        sequencer.enqueueSnapshot(makeSampleSnapshot())
        sequencer.enqueueSnapshot(
            makeSampleSnapshot(
                currentPlayer = 1,
                events = listOf(PresentationEventWire.Discard(seat = 1, tile = 15, slotIndex = 4)),
            ),
        )
        applyOneIdleStep(sequencer)

        assertEquals(DiscardOrigin.hand(1, 4), sequencer.consumePendingDiscardOrigin())
        // Consuming clears the pending origin.
        assertNull(sequencer.consumePendingDiscardOrigin())
    }

    @Test
    fun `pending discard origin of a drawn tile uses the sentinel slot`() {
        val sequencer = PresentationSequencer()
        sequencer.enqueueSnapshot(makeSampleSnapshot())
        sequencer.enqueueSnapshot(
            makeSampleSnapshot(
                currentPlayer = 1,
                events = listOf(PresentationEventWire.Discard(seat = 2, tile = 15, isDrawn = true)),
            ),
        )
        applyOneIdleStep(sequencer)

        assertEquals(DiscardOrigin.drawn(2), sequencer.consumePendingDiscardOrigin())
    }
}
