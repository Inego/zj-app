package inego.zungjunggame.session.online

import inego.zungjunggame.engine.*
import inego.zungjunggame.engines.SeatObservation
import inego.zungjunggame.net.ChoiceWire
import inego.zungjunggame.net.ClaimBidderWire
import inego.zungjunggame.net.PresentationEventWire
import inego.zungjunggame.net.SeatSnapshotWire
import inego.zungjunggame.net.ServerMessageWire.DecisionPrompt
import inego.zungjunggame.session.hosts.ActionableDecision
import inego.zungjunggame.session.table.*
import kotlin.math.max

/** Maps a wire [ChoiceWire] to an engine [Choice]. */
fun ChoiceWire.toEngineChoice(): Choice {
    val engineKind = when (kind) {
        "Pass" -> ChoiceKind.Pass
        "Discard" -> ChoiceKind.Discard
        "Hu" -> ChoiceKind.Hu
        "Chow" -> ChoiceKind.Chow
        "Pung" -> ChoiceKind.Pung
        "Kong" -> ChoiceKind.Kong
        "PromoteKong" -> ChoiceKind.PromoteKong
        "ConcealedKong" -> ChoiceKind.ConcealedKong
        "Keep" -> ChoiceKind.Keep
        else -> ChoiceKind.Pass
    }
    return Choice(engineKind, payload)
}

/** Maps an engine [Choice] to a wire [ChoiceWire]. */
fun Choice.toWire(): ChoiceWire = ChoiceWire(kind.name, payload)

/** Maps a server [DecisionPrompt] into an [ActionableDecision]. */
fun DecisionPrompt.toActionableDecision(): ActionableDecision {
    val choiceList = ChoiceList()
    for (cw in choices) {
        choiceList.pushBack(cw.toEngineChoice())
    }
    val isDiscard = (decisionType == "ActOnHand" || decisionType == "act_on_hand") &&
        choices.any { it.kind == "Discard" }
    val isClaim = decisionType == "ReactToDiscard" || decisionType == "react_to_discard"
    val isActOnHandPanel = (decisionType == "ActOnHand" || decisionType == "act_on_hand") &&
        choices.any { it.kind in listOf("Hu", "ConcealedKong", "PromoteKong", "Keep") }

    return ActionableDecision(
        decisionId = decisionId.toString(),
        isDiscard = isDiscard,
        isClaim = isClaim,
        isActOnHandPanel = isActOnHandPanel,
        choices = choiceList,
        deadlineMs = deadlineMs,
    )
}

/** Projects a [SeatSnapshotWire] into an authoritative client [TableViewState]. */
fun SeatSnapshotWire.toTableViewState(
    decision: ActionableDecision? = null,
    isWaitingForOthers: Boolean = false,
): TableViewState {
    val dt = when (decisionType) {
        "ReactToDiscard", "react_to_discard" -> DecisionType.ReactToDiscard
        "ActOnHand", "act_on_hand" -> DecisionType.ActOnHand
        else -> DecisionType.ActOnHand
    }

    val pendingDiscarder = lastDiscarder
    val pendingTile = lastDiscarded
    val seatViews = seats.map { sw ->
        val meldGroups = sw.melds.map { mw ->
            MeldGroup(
                mw.tiles.map { tw ->
                    MeldTile(tw.tileType, tw.open, tw.rotated)
                },
            )
        }
        // Finalized engine rivers omit the tile still open for claims. Local
        // play appends it in buildRiverForDisplay; do the same here so the
        // discard is visible and the claim-panel curve has an anchor. Skip if
        // the snapshot already included it (newer servers).
        val river = if (
            isPendingDiscard &&
            pendingDiscarder != null &&
            pendingTile != null &&
            sw.seat == pendingDiscarder &&
            sw.river.lastOrNull() != pendingTile
        ) {
            sw.river + pendingTile
        } else {
            sw.river
        }
        SeatView(
            seat = sw.seat,
            concealedCount = sw.concealedCount,
            concealedTiles = sw.concealedTiles,
            drawnTile = sw.drawnTile,
            hasDrawnTile = sw.hasDrawnTile,
            melds = meldGroups,
            river = river,
            seatWindTile = sw.seatWindTile,
            name = sw.name,
            isBot = sw.isBot,
        )
    }

    val choices = decision?.choices ?: ChoiceList()
    val isDiscardTurn = !isTerminal && decision?.isDiscard == true
    val isClaimTurn = !isTerminal && decision?.isClaim == true
    val isActOnHandPanel = !isTerminal && decision?.isActOnHandPanel == true

    return TableViewState(
        mySeat = mySeat,
        eastSeat = eastSeat,
        liveWallSize = liveWallSize,
        currentPlayer = currentPlayer,
        decisionType = dt,
        lastDiscarder = lastDiscarder ?: -1,
        lastDiscarded = lastDiscarded ?: -1,
        isPendingDiscard = isPendingDiscard,
        isKongRobWindow = isKongRobWindow,
        handScore = handScore,
        winnerSeat = winnerSeat ?: -1,
        winSource = winSource ?: -1,
        isTerminal = isTerminal,
        scoreDeltas = scoreDeltas?.toIntArray(),
        seats = seatViews,
        myChoices = choices,
        isHumanDiscardTurn = isDiscardTurn,
        isHumanClaimTurn = isClaimTurn,
        isHumanActOnHandPanel = isActOnHandPanel,
        isWaitingForOthers = isWaitingForOthers,
    )
}

/**
 * Projects a server [SeatSnapshotWire] into the transport-neutral [PresentationFrame]
 * the sequencer paces — the online half of the seam local play fills from an apply.
 *
 * The wire's own events are the authority: whatever the server announced is what the
 * frame carries, in order, so a window that resolves to a Hu *and* a snapshot that
 * also ends the hand are two steps rather than one collapsed transition. Only when
 * the server announced nothing does this diff against [prev]; with no [prev] there is
 * nothing to diff, so the frame is a bare [PresentationTransition.SnapshotSync].
 *
 * [decision] and [isWaitingForOthers] fold the host's own state into the frame's
 * view: they are why the projection is taken once, here, rather than by the caller.
 */
fun SeatSnapshotWire.toPresentationFrame(
    prev: SeatSnapshotWire?,
    decision: ActionableDecision? = null,
    isWaitingForOthers: Boolean = false,
): PresentationFrame {
    val viewState = toTableViewState(decision, isWaitingForOthers)
    val transitions = if (events.isNotEmpty()) {
        events.map { it.toTransition() }
    } else if (prev == null) {
        listOf(PresentationTransition.SnapshotSync(viewState))
    } else {
        listOf(inferTransition(prev, this))
    }
    return PresentationFrame(
        viewState = viewState,
        handNumber = handNumber,
        transitions = transitions,
    )
}

/**
 * Heuristic inference of transition between adjacent snapshots when explicit events
 * are absent.
 *
 * A terminal snapshot infers nothing: the event-less one is an exhaustive draw, and
 * the win cue already reached the table on settlement's own `HandEnd`. Synthesising
 * a second one here is what doubled the cue.
 */
private fun inferTransition(
    prev: SeatSnapshotWire,
    curr: SeatSnapshotWire,
): PresentationTransition {
    // Discard detection
    if (curr.isPendingDiscard && curr.lastDiscarder != null && curr.lastDiscarded != null) {
        val dSeat = curr.lastDiscarder
        val prevDrawn = prev.seats.getOrNull(dSeat)?.hasDrawnTile == true
        return PresentationTransition.Discard(dSeat, slotIndex = null, isDrawn = prevDrawn)
    }

    // River growth detection if isPendingDiscard was false
    for (s in curr.seats.indices) {
        val prevRiver = prev.seats.getOrNull(s)?.river ?: emptyList()
        val currRiver = curr.seats.getOrNull(s)?.river ?: emptyList()
        if (currRiver.size > prevRiver.size) {
            val prevDrawn = prev.seats.getOrNull(s)?.hasDrawnTile == true
            return PresentationTransition.Discard(s, slotIndex = null, isDrawn = prevDrawn)
        }
    }

    // Draw detection (wall decreased or concealed count increased)
    if (curr.liveWallSize < prev.liveWallSize) {
        return PresentationTransition.Draw(curr.currentPlayer)
    }

    return PresentationTransition.SnapshotSync(curr.toTableViewState(null, false))
}

private fun PresentationEventWire.toTransition(): PresentationTransition = when (this) {
    is PresentationEventWire.Draw -> PresentationTransition.Draw(seat)
    is PresentationEventWire.Discard ->
        PresentationTransition.Discard(seat, slotIndex, isDrawn)
    is PresentationEventWire.Claim ->
        PresentationTransition.Claim(seat, sourceSeat, kind, bidders.map { it.toClaimBidder() })
    is PresentationEventWire.ReactionHu ->
        PresentationTransition.ReactionHu(seat, sourceSeat, bidders.map { it.toClaimBidder() })
    is PresentationEventWire.Kong ->
        PresentationTransition.DeclareKong(seat)
    is PresentationEventWire.HandEnd ->
        PresentationTransition.HandEnd(winnerSeat, winSource)
}

private fun ClaimBidderWire.toClaimBidder(): ClaimBidder = ClaimBidder(seat, kind)

/** Projects an authoritative [TableViewState] into a privacy-preserving [inego.zungjunggame.engines.SeatObservation]. */
fun TableViewState.toSeatObservation(): SeatObservation? {
    val ownSeat = seats.firstOrNull { it.seat == mySeat } ?: return null
    val ps = ownSeat.toPlayerState() ?: return null

    // Remaining tiles:
    // 1. Start at 4 of each tile.
    val remaining = IntArray(NUM_TILE_TYPES) { 4 }

    // 2. Subtract own concealed (including drawn).
    for (t in 0 until NUM_TILE_TYPES) {
        remaining[t] = max(0, remaining[t] - ps.concealed[t])
    }

    // 3 & 5. Subtract own and opponent meld tiles.
    for (seat in seats) {
        for ((tiles) in seat.melds) {
            for ((t) in tiles) {
                if (t in 0 until NUM_TILE_TYPES) {
                    remaining[t] = max(0, remaining[t] - 1)
                }
            }
        }
    }

    // 4. Subtract every seat's river as projected by toTableViewState
    for (seat in seats) {
        for (t in seat.river) {
            if (t in 0 until NUM_TILE_TYPES) {
                remaining[t] = max(0, remaining[t] - 1)
            }
        }
    }

    // 6. lastDiscarded is an extra visible tile only while it is still the
    // pending discard. Once a claim resolves it lives in the claimer's meld (or
    // the promoter's kong), and once everyone passes it is in the river; both
    // were already subtracted above. Keep the fallback for a pending discard the
    // view did not place in its discarder's river.
    if (isPendingDiscard &&
        lastDiscarded in 0 until NUM_TILE_TYPES &&
        lastDiscarder in 0 until NUM_PLAYERS
    ) {
        val discarderSeat = seats.firstOrNull { it.seat == lastDiscarder }
        if (discarderSeat != null && discarderSeat.river.lastOrNull() != lastDiscarded) {
            val inMelds = discarderSeat.melds.any { mg -> mg.tiles.any { it.tileType == lastDiscarded } }
            if (!inMelds) {
                remaining[lastDiscarded] = max(0, remaining[lastDiscarded] - 1)
            }
        }
    }

    val seatWind = if (ownSeat.seatWindTile in 0 until NUM_TILE_TYPES) {
        ownSeat.seatWindTile
    } else {
        Tiles.EAST + ((mySeat - eastSeat + NUM_PLAYERS) % NUM_PLAYERS)
    }

    return SeatObservation(
        seat = mySeat,
        seatWind = seatWind,
        liveWallSize = liveWallSize,
        player = ps,
        remainingTiles = remaining,
        lastDiscarded = lastDiscarded,
        lastDiscarder = lastDiscarder,
    )
}

/** Projects a server [SeatSnapshotWire] into a privacy-preserving [SeatObservation]. */
fun SeatSnapshotWire.toSeatObservation(): SeatObservation? =
    toTableViewState().toSeatObservation()

