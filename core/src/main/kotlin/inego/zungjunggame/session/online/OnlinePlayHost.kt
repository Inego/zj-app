package inego.zungjunggame.session.online

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.TimeUtils
import inego.zungjunggame.audio.SoundCue
import inego.zungjunggame.audio.TimerStage
import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.engine.HandScoreComponent
import inego.zungjunggame.engine.HandScoreDetails
import inego.zungjunggame.engine.HandScoreRow
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.SessionScoreTable
import inego.zungjunggame.net.ApiConfig
import inego.zungjunggame.net.ClientMessageWire
import inego.zungjunggame.net.HandScoreRowWire
import inego.zungjunggame.net.PlayerRatingUpdateWire
import inego.zungjunggame.net.SeatSnapshotWire
import inego.zungjunggame.net.ServerMessageWire
import inego.zungjunggame.net.TableTicket
import inego.zungjunggame.net.TableTicketApi
import inego.zungjunggame.net.TableTicketException
import inego.zungjunggame.net.WireJson
import inego.zungjunggame.platform.WebSocketClient
import inego.zungjunggame.session.hosts.ActionableDecision
import inego.zungjunggame.session.hosts.ConnectionStatus
import inego.zungjunggame.session.hosts.PlayHost
import inego.zungjunggame.session.snapshots.SettlementSnapshot
import inego.zungjunggame.session.snapshots.TableSnapshot
import inego.zungjunggame.session.table.TableViewState
import inego.zungjunggame.session.hosts.MatchPhase
import inego.zungjunggame.session.table.DiscardOrigin
import java.util.concurrent.atomic.AtomicLong

typealias TableTicketRequester = (
    tableId: String,
    token: String,
    onDone: (Result<TableTicket>) -> Unit,
) -> Unit

/**
 * Online play host connecting to a zj-server live table via WebSocket.
 *
 * Implements [PlayHost]:
 * - Submits choices via [submitChoice] / [submitDiscard]; does not run a local engine.
 * - Updates [tableView] immediately from authoritative [SeatSnapshotWire].
 * - Enforces "Waiting for other players" state after choice submission until resolution.
 * - Supports reconnecting with a fresh ticket.
 */
class OnlinePlayHost(
    val tableId: String,
    val token: String,
    private val wsClient: WebSocketClient = WebSocketClient.create(),
    private val onLeaveCallback: (() -> Unit)? = null,
    private val requestTicket: TableTicketRequester = { id, tok, done ->
        TableTicketApi.requestTicket(id, tok, done)
    },
    /** Live access JWT. Reconnect after refresh must not use a captured token. */
    private val resolveToken: () -> String? = { token },
    val initialAway: Boolean = false,
    /**
     * Monotonic clock used to turn the prompt's schedule into instants. One
     * reading is taken at receipt, and every frame reads the same clock; a fake
     * source can therefore drive the countdown without frames or ticks.
     */
    private val nowNanos: () -> Long = { TimeUtils.nanoTime() },
) : PlayHost {

    /**
     * Fired once, on the first [ServerMessageWire.SeatSnapshotMsg] (tiles dealt /
     * table Running). Not [ServerMessageWire.TableJoined] — that is still Reserved.
     */
    var onMatchLive: (() -> Unit)? = null

    private val reqCounter = AtomicLong(0L)

    val sequencer = PresentationSequencer()

    override var connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected
        private set

    override var humanSeat: Int = -1
        private set

    /**
     * Whether the table was already running when this connection attached, from
     * `TableJoined`. False means this attach is one of the players whose arrival
     * starts the match, so the next snapshot is the opening deal to play out;
     * true means the hand was dealt before this connection existed, so the next
     * snapshot is the table's position to show as it is. Null until `TableJoined`
     * answers — no attach has resolved yet.
     */
    var runningAtAttach: Boolean? = null
        private set

    override val tableView: TableViewState?
        get() = sequencer.displayedViewState ?: lastSnapshot?.toTableViewState(authoritativeDecision, isWaitingForOthers)

    /**
     * Enriches the generic [TableSnapshot] with the online-only read model:
     * seat names, connection recovery state, and the active settlement card.
     * Pure value projection — render never sees this host.
     */
    override val snapshot: TableSnapshot
        get() = TableSnapshot.from(this).copy(
            playerNames = playerNames.toList(),
            isBot = isBot.toList(),
            connectionFailure = connectionFailure,
            reconnectRequired = reconnectRequired,
            shadowRankedBreakdown = shadowRankedBreakdown,
            settlement = handSettlement?.let { msg ->
                SettlementSnapshot(
                    winnerSeat = msg.winnerSeat,
                    details = settlementDetails() ?: HandScoreDetails(),
                    scoreRows = scores.rows(),
                    runningTotals = scores.runningTotals().toList(),
                    ratingUpdates = lastRatingUpdates,
                    startedAt = matchStartedAt,
                    finishedAt = matchFinishedAt,
                    readyForNextHand = readyForNextHand,
                    nextHandTimeLeftMs = nextHandTimeLeftMs,
                    sequencerCaughtUp = sequencer.isCaughtUp,
                )
            },
        )

    fun consumePendingDiscardOrigin(): DiscardOrigin? =
        sequencer.consumePendingDiscardOrigin()

    /** Timer-threshold cues for the current decision; drained once by the frame loop. */
    private val pendingTimerCues = ArrayDeque<SoundCue>()
    private var lastTimerStage: TimerStage? = null

    /**
     * Drain the table cues the sequencer applied plus any countdown-threshold cue,
     * in the order they occurred. GL-free: the app plays what this describes.
     */
    fun consumePendingSoundCues(): List<SoundCue> {
        val timer = if (pendingTimerCues.isEmpty()) {
            emptyList()
        } else {
            pendingTimerCues.toList().also { pendingTimerCues.clear() }
        }
        return sequencer.consumePendingSoundCues() + timer
    }

    /**
     * Push a [SoundCue.Timer] when the countdown crosses a threshold. The stage is
     * the current band, so a new decision (or a reset) re-arms it.
     */
    private fun recordTimerStage(remainingMs: Long?) {
        val stage = when {
            remainingMs == null -> null
            remainingMs <= 0L -> TimerStage.Expired
            remainingMs <= URGENT_MS -> TimerStage.Urgent
            remainingMs <= WARN_MS -> TimerStage.Warn
            else -> null
        }
        if (stage == lastTimerStage) return
        lastTimerStage = stage
        if (stage != null) pendingTimerCues += SoundCue.Timer(stage)
    }

    override val myDecision: ActionableDecision?
        get() = sequencer.displayedDecision
            ?: authoritativeDecision?.takeIf { sequencer.isCaughtUp }

    var authoritativeDecision: ActionableDecision? = null
        private set

    override var decisionTimeLeftMs: Long? = null
        private set

    override var decisionPreparing: Boolean = false
        private set

    override var decisionSlackMs: Long? = null
        private set

    override var decisionBaseMs: Long? = null
        private set

    private var decisionClockId: Long? = null

    /**
     * The prompt's schedule on the client monotonic clock. Both are null on the
     * legacy path (a prompt without `ms_until_think_starts`/`ms_until_timeout`),
     * which keeps decrementing [decisionTimeLeftMs] by the frame delta.
     */
    private var decisionThinkStartsAtNs: Long? = null
    private var decisionExpiresAtNs: Long? = null

    /**
     * After reconnect, the next empty-events snapshot is an authoritative
     * resync rather than a live transition to animate.
     */
    private var presentationResync: Boolean = false

    override fun advance(deltaSec: Float) {
        if (decisionExpiresAtNs != null) {
            refreshDecisionClock(nowNanos())
        } else if (deltaSec > 0f &&
            authoritativeDecision != null &&
            decisionClockId == currentDecisionId &&
            sequencer.isCaughtUp
        ) {
            val fullThink = (decisionBaseMs ?: 0L) + (decisionSlackMs ?: 0L)
            val inGrace = fullThink > 0L && (decisionTimeLeftMs ?: 0L) >= fullThink
            if (!inGrace) {
                decisionTimeLeftMs = decisionTimeLeftMs
                    ?.minus((deltaSec * 1000f).toLong())
                    ?.coerceAtLeast(0L)
            }
        }
        if (deltaSec > 0f) {
            recordTimerStage(if (decisionPreparing) null else decisionTimeLeftMs)
        }
        if (deltaSec > 0f && reconnectScheduled) {
            reconnectInSec -= deltaSec
            if (reconnectInSec <= 0f) {
                reconnectScheduled = false
                reconnect()
            }
        }
        if (deltaSec > 0f && !readyForNextHand && nextHandTimeLeftMs != null) {
            nextHandTimeLeftMs = nextHandTimeLeftMs
                ?.minus((deltaSec * 1000f).toLong())
                ?.coerceAtLeast(0L)
            if (nextHandTimeLeftMs == 0L) {
                requestNextHand()
            }
        }
        if (deltaSec > 0f && shutdownRemainingMs != null) {
            shutdownRemainingMs = shutdownRemainingMs
                ?.minus((deltaSec * 1000f).toLong())
                ?.coerceAtLeast(0L)
        }
    }

    /**
     * Recomputes the visible clock from the prompt's schedule. The prompt carries
     * one reading of the server's remaining time; the instants derived from it are
     * the display's only input, so neither packet cadence nor frame cadence can
     * move the countdown.
     */
    private fun refreshDecisionClock(nowNs: Long) {
        val expiresAtNs = decisionExpiresAtNs ?: return
        decisionTimeLeftMs = (expiresAtNs - nowNs).coerceAtLeast(0L) / NANOS_PER_MS
        val thinkStartsAtNs = decisionThinkStartsAtNs
        decisionPreparing = thinkStartsAtNs != null && nowNs < thinkStartsAtNs
    }

    /** Clears every per-decision clock field, scheduled or legacy. */
    private fun clearDecisionClock() {
        decisionTimeLeftMs = null
        decisionSlackMs = null
        decisionBaseMs = null
        decisionClockId = null
        decisionThinkStartsAtNs = null
        decisionExpiresAtNs = null
        decisionPreparing = false
    }

    /**
     * Apply at most one queued presentation step when the table is idle.
     * Call after [inego.zungjunggame.render.table.PlayTableView.render] so
     * [boardIdle] reflects the latest reconcile.
     */
    fun tryPresent(boardIdle: Boolean): Boolean = sequencer.tryStep(boardIdle)

    /**
     * True while a later frame still has to apply or release a queued step.
     *
     * Read after [tryPresent]. An apply arms the idle gate, and the first settled
     * frame spends that pause without taking the next snapshot — [tryPresent]
     * returns false while the rest of the batch is still queued. That frame has
     * to be asked for; nothing else wakes it once the pointer is still. False
     * once the pause is spent and the queue is empty, so the human's turn does
     * not keep presenting.
     */
    fun needsPresentFrame(): Boolean = !sequencer.isCaughtUp

    /**
     * True while a countdown still has to move after the sequencer is caught up.
     *
     * The next-hand button and a reconnect delay are not the decision clock, and
     * [HandSettlement] clears that clock. Without a present they freeze, and the
     * auto-ready or the reconnect never fires until a pointer event.
     */
    fun needsClockFrame(): Boolean =
        nextHandTimeLeftMs != null || reconnectScheduled

    var isWaitingForOthers: Boolean = false
        private set

    override var currentHandNumber: Int = 1
        private set

    override var maxHands: Int? = null
        private set

    override var allowChickenHands: Boolean = false
        private set

    var currentDecisionId: Long = 0L
        private set

    var lastSnapshot: SeatSnapshotWire? = null
        private set

    var handSettlement: ServerMessageWire.HandSettlement? = null
        private set

    /** Pattern breakdown for the score overlay; null when no settlement is showing. */
    fun settlementDetails(): HandScoreDetails? {
        val msg = handSettlement ?: return null
        val details = HandScoreDetails()
        details.totalScore = msg.handScore
        details.uncappedTotal = msg.handScore
        for ((id, points, formatArgs) in msg.patterns) {
            details.components += HandScoreComponent(id, points, formatArgs)
        }
        return details
    }

    /** True after this seat pressed (or auto-pressed) Next hand and is waiting. */
    var readyForNextHand: Boolean = false
        private set

    /** Remaining next-hand auto-ready time; null for no_timer / already ready. */
    var nextHandTimeLeftMs: Long? = null
        private set

    /**
     * Phase of this match. Latched by [endMatch]; never returns to [MatchPhase.Live].
     *
     * It is derived from the authoritative snapshot as well as from the end-of-match
     * wire messages: a terminal snapshot on the match's last hand *is* the end of the
     * match, so deriving it keeps a reconnect — where `GameFinished` is never
     * replayed — from leaving the client thinking a finished match is still live.
     */
    override var matchPhase: MatchPhase = MatchPhase.Live
        private set

    /** True once the match played its final hand: `matchPhase == Finished`. */
    val isGameFinished: Boolean get() = matchPhase == MatchPhase.Finished

    var lastRatingUpdates: List<PlayerRatingUpdateWire> = emptyList()
        private set

    /** RFC3339 match start from the last `GameFinished`; the Score Breakdown times from it. */
    var matchStartedAt: String? = null
        private set

    /** RFC3339 finish from the last `GameFinished`. */
    var matchFinishedAt: String? = null
        private set

    /**
     * The Score Breakdown of a finished Shadow Ranked attempt, once the server sends
     * it. Null for an ordinary table. The bot's match total reaches the player only
     * through this message.
     */
    var shadowRankedBreakdown: ServerMessageWire.ChallengeScoreBreakdown? = null
        private set

    /** True once the table was abandoned: `matchPhase == Aborted`. */
    val isTableAborted: Boolean get() = matchPhase == MatchPhase.Aborted

    var abortReason: String? = null
        private set

    var lastRejectedReason: String? = null
        private set

    private var submittedDecision: ActionableDecision? = null

    /** True when automatic recovery stopped without abandoning the table. */
    var reconnectRequired: Boolean = false
        private set

    var connectionFailure: ConnectionFailure? = null
        private set

    override var shutdownPhase: String? = null
        private set

    override var shutdownRemainingMs: Long? = null
        private set

    override var shutdownMessage: String? = null
        private set

    /** True when socket was lost while a graceful shutdown was pending. */
    var shutdownEndedMatch: Boolean = false
        private set

    val playerNames = Array(NUM_PLAYERS) { "" }
    val isBot = BooleanArray(NUM_PLAYERS) { false }
    val scores = SessionScoreTable()

    private var left = false
    private var socketGen: Int = 0
    private var reconnectAttempts: Int = 0
    private var reconnectScheduled: Boolean = false
    private var reconnectInSec: Float = 0f
    private var unreachableFailures: Int = 0

    private val wsListener = object : WebSocketClient.Listener {
        override fun onOpen() {
            val gen = socketGen
            connectionStatus = ConnectionStatus.Connected
            reconnectAttempts = 0
            reconnectScheduled = false
            logOnline("ws open table=$tableId seat=$humanSeat gen=$gen")
        }

        override fun onMessage(text: String) {
            val msg = runCatching { WireJson.parseServerMessage(text) }.getOrElse { err ->
                logOnline("ws bad message: ${err.message}")
                return
            }
            handleServerMessage(msg)
        }

        override fun onClose(code: Int, reason: String) {
            logOnline("ws close table=$tableId code=$code reason=$reason")
            handleSocketLost()
        }

        override fun onError(cause: Throwable) {
            logOnline("ws error table=$tableId: ${cause.message}")
            logOnlineError("ws error table=$tableId", cause)
            handleSocketLost()
        }
    }

    /**
     * Acquire a ticket and connect to the table via WebSocket.
     */
    fun connect() {
        beginTicketConnect(ConnectionStatus.Connecting)
    }

    /**
     * Connect directly with an already-acquired [TableTicket].
     */
    fun connectWithTicket(ticket: TableTicket) {
        if (connectionStatus != ConnectionStatus.Connecting &&
            connectionStatus != ConnectionStatus.Reconnecting
        ) {
            socketGen += 1
            connectionStatus = ConnectionStatus.Connecting
        }
        humanSeat = ticket.seat
        unreachableFailures = 0
        val base = ApiConfig.baseUrl.trimEnd('/')
        val wsScheme = if (base.startsWith("https://")) "wss://" else "ws://"
        val hostPort = base.substringAfter("://")
        val wsUrl = "$wsScheme$hostPort/ws?ticket=${ticket.ticket}"

        logOnline("ws connect table=$tableId seat=${ticket.seat} gen=$socketGen")
        wsClient.connect(wsUrl, emptyMap(), generationGuard(socketGen))
    }

    /**
     * Reconnect to the live table by acquiring a fresh ticket.
     * The server actor replaces the active stream and sends an authoritative snapshot.
     */
    fun reconnect() {
        if (isDone()) return
        if (connectionStatus == ConnectionStatus.Connecting ||
            connectionStatus == ConnectionStatus.Reconnecting
        ) {
            return
        }
        beginTicketConnect(ConnectionStatus.Reconnecting)
    }

    /** Retry after the UI has presented the recoverable connection state. */
    fun retryConnection() {
        if (isDone()) return
        reconnectRequired = false
        connectionFailure = null
        unreachableFailures = 0
        reconnectAttempts = 0
        reconnectScheduled = false
        reconnectInSec = 0f
        connectionStatus = ConnectionStatus.Disconnected
        reconnect()
    }

    var wasAway: Boolean = initialAway

    fun onAppPaused() {
        if (isDone()) return
        wasAway = true
        if (connectionStatus == ConnectionStatus.Connected && wsClient.isConnected) {
            sendClientMessage(ClientMessageWire.SetAway(away = true))
        }
    }

    fun resumeFromAway() {
        if (isDone()) return
        wasAway = false
        if (connectionStatus == ConnectionStatus.Connected && wsClient.isConnected) {
            sendClientMessage(ClientMessageWire.SetAway(away = false))
        }
        sequencer.fastForward(lastSnapshot, isWaitingForOthers)
    }

    /** Screen/process came back: retry immediately if the socket is dead. */
    fun onAppResumed() {
        if (isDone()) return
        sequencer.fastForward(lastSnapshot, isWaitingForOthers)
        if (connectionStatus == ConnectionStatus.Connected && wsClient.isConnected) return
        reconnectScheduled = false
        reconnectInSec = 0f
        if (connectionStatus == ConnectionStatus.Connecting ||
            connectionStatus == ConnectionStatus.Reconnecting
        ) {
            return
        }
        reconnect()
    }

    private fun beginTicketConnect(status: ConnectionStatus) {
        socketGen += 1
        val gen = socketGen
        reconnectScheduled = false
        connectionStatus = status
        reconnectRequired = false
        connectionFailure = null
        if (status == ConnectionStatus.Reconnecting) {
            presentationResync = true
            wsClient.close()
        }
        val access = resolveToken()?.takeIf { it.isNotBlank() }
            ?: token.takeIf { it.isNotBlank() }
        if (access == null) {
            handleTicketFailure(TableTicketException(401, "Missing access token"))
            return
        }
        logOnline("ticket request table=$tableId gen=$gen status=$status")
        requestTicket(tableId, access) { result ->
            if (left || gen != socketGen) return@requestTicket
            result.fold(
                onSuccess = { ticket ->
                    connectWithTicket(ticket)
                },
                onFailure = { err ->
                    handleTicketFailure(err)
                },
            )
        }
    }

    private fun generationGuard(gen: Int): WebSocketClient.Listener =
        object : WebSocketClient.Listener {
            override fun onOpen() {
                if (gen != socketGen) return
                wsListener.onOpen()
            }

            override fun onMessage(text: String) {
                if (gen != socketGen) return
                wsListener.onMessage(text)
            }

            override fun onClose(code: Int, reason: String) {
                if (gen != socketGen) return
                wsListener.onClose(code, reason)
            }

            override fun onError(cause: Throwable) {
                if (gen != socketGen) return
                wsListener.onError(cause)
            }
        }

    private fun handleSocketLost() {
        if (isDone()) {
            connectionStatus = ConnectionStatus.Disconnected
            return
        }
        if (shutdownRemainingMs != null) {
            shutdownEndedMatch = true
            connectionStatus = ConnectionStatus.Disconnected
            reconnectScheduled = false
            reconnectRequired = false
            logOnline("socket lost during shutdown table=$tableId")
            onLeaveCallback?.invoke()
            return
        }
        connectionStatus = ConnectionStatus.Disconnected
        if (giveUpIfUnreachable()) return
        armReconnect()
    }

    private fun handleTicketFailure(err: Throwable) {
        connectionStatus = ConnectionStatus.Disconnected
        logOnline("ticket failed table=$tableId: ${err.message}")
        logOnlineError("ticket failed table=$tableId", err)
        if (isDone()) return
        if (err is TableTicketException && err.httpStatus == 401) {
            markReconnectRequired(ConnectionFailure.Authentication)
            return
        }
        if (err is TableTicketException && err.tableGone) {
            logOnline("table unavailable table=$tableId status=${err.httpStatus}")
            markReconnectRequired(ConnectionFailure.TableUnavailable)
            return
        }
        if (giveUpIfUnreachable()) return
        armReconnect()
    }

    /**
     * The server is unreachable: retry a few times, then stop and leave the
     * decision to the player. The board and stored table id remain available
     * for an explicit retry or return-home action.
     */
    private fun giveUpIfUnreachable(): Boolean {
        unreachableFailures += 1
        logOnline("unreachable table=$tableId failures=$unreachableFailures/$MAX_UNREACHABLE_FAILURES")
        if (unreachableFailures < MAX_UNREACHABLE_FAILURES) return false
        logOnline("automatic recovery paused table=$tableId")
        markReconnectRequired(ConnectionFailure.Unreachable)
        return true
    }

    private fun markReconnectRequired(failure: ConnectionFailure) {
        reconnectScheduled = false
        reconnectInSec = 0f
        reconnectRequired = true
        connectionFailure = failure
        connectionStatus = ConnectionStatus.ReconnectRequired
    }

    private fun armReconnect() {
        if (isDone() || reconnectScheduled) return
        reconnectScheduled = true
        reconnectInSec = reconnectDelaySec(reconnectAttempts)
        reconnectAttempts += 1
        logOnline("reconnect armed table=$tableId delay=${reconnectInSec}s attempt=$reconnectAttempts")
    }

    private fun reconnectDelaySec(attempt: Int): Float = when (attempt) {
        0 -> 0f
        1 -> 1f
        2 -> 2f
        3 -> 4f
        else -> 8f
    }

    fun handleServerMessage(msg: ServerMessageWire) {
        when (msg) {
            is ServerMessageWire.TableJoined -> {
                humanSeat = msg.seat
                runningAtAttach = msg.running
                connectionStatus = ConnectionStatus.Connected
                maxHands = parseRoundsToMaxHands(msg.rounds)
                allowChickenHands = msg.allowChicken
                if (msg.scoreRows.isNotEmpty()) {
                    currentHandNumber = (msg.scoreRows.size + 1).coerceAtLeast(1)
                }
                if (wasAway) {
                    sendClientMessage(ClientMessageWire.SetAway(away = true))
                }
                reconnectRequired = false
                connectionFailure = null
                lastRejectedReason = null
                shadowRankedBreakdown = null
                shutdownPhase = null
                shutdownRemainingMs = null
                shutdownMessage = null
                shutdownEndedMatch = false
                for ((seat, name, isBot1) in msg.players) {
                    if (seat in 0 until NUM_PLAYERS) {
                        playerNames[seat] = name
                        isBot[seat] = isBot1
                    }
                }
                scores.restore(msg.scoreRows.map { it.toSessionRow() })
                logOnline("table joined table=$tableId seat=${msg.seat} scoreRows=${msg.scoreRows.size}")
            }
            is ServerMessageWire.SeatSnapshotMsg -> {
                reconnectRequired = false
                connectionFailure = null
                unreachableFailures = 0
                lastRejectedReason = null
                val first = lastSnapshot == null
                val settlement = handSettlement
                val nextHandStarted = settlement != null &&
                    (!msg.snapshot.isTerminal || msg.snapshot.handNumber != settlement.handNumber)
                if (nextHandStarted) {
                    handSettlement = null
                    readyForNextHand = false
                    nextHandTimeLeftMs = null
                }
                lastSnapshot = msg.snapshot
                currentHandNumber = msg.snapshot.handNumber
                if (isFinalHandOfMatch(msg.snapshot)) endMatch(MatchPhase.Finished)
                for ((seat, name, isBot1) in msg.snapshot.seats) {
                    if (seat in 0 until NUM_PLAYERS) {
                        if (name.isNotEmpty()) playerNames[seat] = name
                        isBot[seat] = isBot1
                    }
                }
                if (first) {
                    logOnline(
                        "first snapshot table=$tableId hand=${msg.snapshot.handNumber} " +
                            "terminal=${msg.snapshot.isTerminal} wall=${msg.snapshot.liveWallSize}",
                    )
                    onMatchLive?.invoke()
                }
                if (msg.snapshot.isTerminal) {
                    authoritativeDecision = null
                    clearDecisionClock()
                    isWaitingForOthers = false
                    sequencer.clearPrompt()
                }
                val resync = presentationResync && msg.snapshot.events.isEmpty()
                presentationResync = false
                if (resync) {
                    sequencer.fastForward(msg.snapshot, isWaitingForOthers)
                } else {
                    // The attach that starts the match receives the opening deal as
                    // its first snapshot; play its shuffle. A join to a running table
                    // rematerializes a position, and a reconnect does not replay it.
                    sequencer.enqueueSnapshot(
                        msg.snapshot,
                        isWaitingForOthers,
                        isOpeningDeal = first && runningAtAttach == false,
                    )
                }
            }
            is ServerMessageWire.DecisionPrompt -> {
                currentDecisionId = msg.decisionId
                val decision = msg.toActionableDecision()
                authoritativeDecision = decision
                submittedDecision = null
                lastRejectedReason = null
                decisionSlackMs = msg.deadlineMs?.let { msg.slackMs?.coerceAtLeast(0L) }
                decisionBaseMs = msg.deadlineMs?.let { msg.baseMs?.coerceAtLeast(0L) }
                decisionClockId = msg.deadlineMs?.let { msg.decisionId }
                val untilThink = msg.msUntilThinkStarts
                val untilTimeout = msg.msUntilTimeout
                if (msg.deadlineMs != null && untilThink != null && untilTimeout != null) {
                    // One reading anchors the server's schedule; every later value is
                    // read off these instants, never accumulated from frames or ticks.
                    val receivedAtNs = nowNanos()
                    decisionThinkStartsAtNs =
                        receivedAtNs + untilThink.coerceAtLeast(0L) * NANOS_PER_MS
                    decisionExpiresAtNs =
                        receivedAtNs + untilTimeout.coerceAtLeast(0L) * NANOS_PER_MS
                    refreshDecisionClock(receivedAtNs)
                } else {
                    val cappedLeft = msg.deadlineMs?.coerceAtLeast(0L)
                    decisionThinkStartsAtNs = null
                    decisionExpiresAtNs = null
                    decisionPreparing = false
                    decisionTimeLeftMs = cappedLeft
                }
                isWaitingForOthers = false
                sequencer.enqueuePrompt(decision)
            }
            is ServerMessageWire.DecisionTimeLeft -> {
                if (msg.decisionId == currentDecisionId &&
                    msg.decisionId == decisionClockId &&
                    decisionTimeLeftMs != null
                ) {
                    if (decisionExpiresAtNs == null) {
                        decisionTimeLeftMs =
                            minOf(decisionTimeLeftMs ?: Long.MAX_VALUE, msg.remainingMs.coerceAtLeast(0L))
                    }
                    // With a schedule the prompt owns the display; a correction is
                    // advisory and would only add packet jitter. It stays on the wire
                    // for clients that only know the legacy deadline.
                }
            }
            is ServerMessageWire.ChoiceAcknowledged -> {
                submittedDecision = null
                lastRejectedReason = null
            }
            is ServerMessageWire.ChoiceRejected -> {
                lastRejectedReason = msg.reason
                isWaitingForOthers = false
                submittedDecision?.let { decision ->
                    authoritativeDecision = decision
                    sequencer.enqueuePrompt(decision)
                }
            }
            is ServerMessageWire.HandSettlement -> {
                val sameHand = handSettlement?.handNumber == msg.handNumber
                handSettlement = msg
                if (!sameHand) {
                    readyForNextHand = false
                    nextHandTimeLeftMs = null
                }
                authoritativeDecision = null
                clearDecisionClock()
                isWaitingForOthers = false
                sequencer.clearPrompt()
                val winner = msg.winnerSeat ?: -1
                // The snapshot's win_source is the authoritative answer and already
                // covers the discard case: the server fills it from last_discarder
                // while the hand is a pending discard and leaves it null for a
                // self-draw (see protocol.rs). last_discarder on its own is *stale* —
                // after a self-draw it still names whoever discarded before the winner
                // drew — which is how a self-draw came to be titled "wins on discard
                // from <previous discarder>". Only a hand that ended on a pending
                // discard may borrow it.
                val snap = lastSnapshot
                val discarder = snap?.winSource?.takeIf { it >= 0 }
                    ?: snap?.lastDiscarder?.takeIf { it >= 0 && snap.isPendingDiscard }
                    ?: soleLoserSeat(msg, winner)
                val deltas = IntArray(NUM_PLAYERS) { i -> msg.deltas.getOrElse(i) { 0 } }
                val runningTotals = IntArray(NUM_PLAYERS) { i -> msg.runningTotals.getOrElse(i) { 0 } }
                if (scores.rows().none { it.handNumber == msg.handNumber }) {
                    scores.recordHand(
                        handNumber = msg.handNumber,
                        handValue = msg.handScore,
                        winnerSeat = winner,
                        discarderSeat = discarder,
                        deltas = deltas,
                        runningTotals = runningTotals,
                        patterns = msg.patterns.map { HandScoreComponent(it.id, it.points, it.formatArgs) },
                    )
                }
            }
            is ServerMessageWire.NextHandPrompt -> {
                readyForNextHand = msg.ready
                nextHandTimeLeftMs = if (msg.ready) {
                    null
                } else {
                    msg.deadlineMs?.coerceAtLeast(0L)
                }
            }
            is ServerMessageWire.NextHandTimeLeft -> {
                if (!readyForNextHand && nextHandTimeLeftMs != null) {
                    nextHandTimeLeftMs = minOf(
                        nextHandTimeLeftMs ?: Long.MAX_VALUE,
                        msg.remainingMs.coerceAtLeast(0L),
                    )
                }
            }
            is ServerMessageWire.GameFinished -> {
                endMatch(MatchPhase.Finished)
                lastRatingUpdates = msg.ratingUpdates
                matchStartedAt = msg.startedAt
                matchFinishedAt = msg.finishedAt
                authoritativeDecision = null
                clearDecisionClock()
                isWaitingForOthers = false
                readyForNextHand = false
                nextHandTimeLeftMs = null
                sequencer.clearPrompt()
            }
            is ServerMessageWire.ChallengeScoreBreakdown -> {
                // The finish is a `GameFinished` first, then this breakdown. Latching
                // it refreshes the already-open hand-end card; the bot's match total
                // is in here and nowhere earlier.
                shadowRankedBreakdown = msg
                logOnline("shadow ranked breakdown table=$tableId comparisons=${msg.comparisons.size}")
            }
            is ServerMessageWire.TableAborted -> {
                endMatch(MatchPhase.Aborted)
                abortReason = msg.reason
                authoritativeDecision = null
                clearDecisionClock()
                isWaitingForOthers = false
                readyForNextHand = false
                nextHandTimeLeftMs = null
                sequencer.clear()
                reconnectRequired = false
                connectionFailure = null
                logOnline("table aborted table=$tableId reason=${msg.reason}")
            }
            is ServerMessageWire.ServerShutdown -> {
                shutdownPhase = msg.phase
                shutdownMessage = msg.message
                val newRemaining = msg.remainingMs.coerceAtLeast(0L)
                shutdownRemainingMs = if (shutdownRemainingMs == null) {
                    newRemaining
                } else {
                    minOf(shutdownRemainingMs ?: Long.MAX_VALUE, newRemaining)
                }
                logOnline("server shutdown phase=${msg.phase} remaining=${shutdownRemainingMs}ms")
            }
            ServerMessageWire.ServerShutdownWithdrawn -> {
                shutdownPhase = null
                shutdownRemainingMs = null
                shutdownMessage = null
                shutdownEndedMatch = false
                logOnline("server shutdown withdrawn table=$tableId")
            }
            ServerMessageWire.Pong -> Unit
        }
    }

    /**
     * Latch the end of the match. Monotone: the first reason wins, so a snapshot that
     * arrives after the end can never resurrect a live phase.
     */
    private fun endMatch(phase: MatchPhase) {
        if (matchPhase.isOver) return
        matchPhase = phase
        logOnline("match $phase table=$tableId")
    }

    /**
     * True when this host will not act again: it was left, or the match is over.
     * Every internal guard asks this instead of testing the phase piecemeal.
     */
    private fun isDone(): Boolean = left || matchPhase.isOver || shutdownEndedMatch

    /**
     * True when [snapshot] is the terminal snapshot of the match's final hand.
     *
     * The server finishes the match instead of dealing again as soon as its recorded
     * hand count reaches the target, so a terminal snapshot on the last hand *is* the
     * end of the match — waiting for `GameFinished` on top of that is what let a
     * reconnect leave the client believing the match was still running. An unparsable
     * `rounds` value leaves [maxHands] null, and then the message is the only signal.
     */
    private fun isFinalHandOfMatch(snapshot: SeatSnapshotWire): Boolean {
        val target = maxHands ?: return false
        return snapshot.isTerminal && snapshot.handNumber >= target
    }

    private fun logOnline(message: String) {
        try {
            val app = Gdx.app
            if (app != null) {
                app.log("Online", message)
            } else {
                println("[Online] $message")
            }
        } catch (_: Throwable) {
            println("[Online] $message")
        }
    }

    private fun logOnlineError(message: String, cause: Throwable) {
        try {
            Gdx.app?.error("Online", message, cause)
        } catch (_: Throwable) {
            System.err.println("[Online] $message")
            cause.printStackTrace()
        }
    }

    /** True when a discard/choice can actually be sent. */
    fun canSubmitActions(): Boolean =
        !isDone() && connectionStatus == ConnectionStatus.Connected

    fun submitChoice(choice: Choice, slotIndex: Int? = null, isDrawn: Boolean = false): Boolean {
        if (!canSubmitActions()) return false
        val decision = myDecision ?: authoritativeDecision ?: return false
        val legalChoice = decision.choices.asList().firstOrNull {
            it.kind == choice.kind && (it.kind == ChoiceKind.Hu || it.payload == choice.payload)
        } ?: return false

        val reqId = "req_${reqCounter.incrementAndGet()}"
        val wireMsg = ClientMessageWire.SubmitChoice(
            tableId = tableId,
            handId = currentHandNumber,
            decisionId = currentDecisionId,
            requestId = reqId,
            choice = legalChoice.toWire(),
            slotIndex = slotIndex,
            isDrawn = if (isDrawn) true else null,
        )

        authoritativeDecision = null
        submittedDecision = decision
        clearDecisionClock()
        isWaitingForOthers = true
        sequencer.clearPrompt(isWaitingForOthers = true)

        val json = WireJson.serializeClientMessage(wireMsg)
        return wsClient.send(json)
    }


    override fun submitChoice(choice: Choice): Boolean = submitChoice(choice, slotIndex = null, isDrawn = false)

    override fun submitDiscard(tileType: Int, slotIndex: Int?, isDrawn: Boolean): Boolean {
        val decision = myDecision ?: authoritativeDecision ?: return false
        if (!decision.isDiscard) return false

        val discardChoice = decision.choices.asList().firstOrNull {
            it.kind == ChoiceKind.Discard && it.payload == tileType
        } ?: return false

        return submitChoice(discardChoice, slotIndex, isDrawn)
    }

    override fun requestNextHand() {
        if (!canSubmitActions() || readyForNextHand) return
        val terminal = lastSnapshot?.isTerminal == true || handSettlement != null
        if (!terminal) return
        readyForNextHand = true
        nextHandTimeLeftMs = null
        val wireMsg = ClientMessageWire.ReadyForNextHand(tableId)
        val json = WireJson.serializeClientMessage(wireMsg)
        wsClient.send(json)
    }

    private fun sendClientMessage(wireMsg: ClientMessageWire): Boolean {
        val json = WireJson.serializeClientMessage(wireMsg)
        return wsClient.send(json)
    }

    /**
     * Close the socket without sending `leave_game`. The server keeps a
     * running-table seat so a later host can reconnect with a fresh ticket.
     */
    fun disconnect() {
        if (left) return
        left = true
        wasAway = false
        sequencer.clear()
        submittedDecision = null
        reconnectScheduled = false
        reconnectRequired = false
        socketGen += 1
        wsClient.close()
        connectionStatus = ConnectionStatus.Disconnected
    }

    override fun leave() {
        if (left) return
        val wireMsg = ClientMessageWire.LeaveGame(tableId)
        val json = WireJson.serializeClientMessage(wireMsg)
        wsClient.send(json)
        disconnect()
        onLeaveCallback?.invoke()
    }

    companion object {
        const val MAX_UNREACHABLE_FAILURES = 5

        /** Countdown thresholds that raise a [SoundCue.Timer], in milliseconds. */
        private const val WARN_MS = 10_000L
        private const val URGENT_MS = 3_000L

        private const val NANOS_PER_MS = 1_000_000L

        /**
         * The only seat that lost points, for a settlement whose snapshot did not name
         * a discarder — -1 when nobody did (a draw) or when everybody did (the usual
         * Zung Jung payment, where all three opponents pay the winner).
         */
        private fun soleLoserSeat(msg: ServerMessageWire.HandSettlement, winner: Int): Int {
            if (winner < 0) return -1
            val losers = (0 until NUM_PLAYERS).filter { msg.deltas.getOrElse(it) { 0 } < 0 }
            return if (losers.size == 1) losers[0] else -1
        }

        fun parseRoundsToMaxHands(rounds: String?): Int? {
            if (rounds == null) return null
            val trimmed = rounds.trim().lowercase()
            when (trimmed) {
                "four_hands" -> return 4
                "eight_hands" -> return 8
                "sixteen_hands" -> return 16
            }
            val count = when (trimmed) {
                "one", "eastonly" -> 1
                "two" -> 2
                "four", "fourrounds" -> 4
                else -> {
                    if (trimmed.startsWith("rounds")) {
                        trimmed.removePrefix("rounds").toIntOrNull()
                    } else {
                        trimmed.toIntOrNull()
                    }
                }
            }
            return count?.let { it * GameConfig.HANDS_PER_ROUND }
        }
    }
}

private fun HandScoreRowWire.toSessionRow(): HandScoreRow {
    val deltasArr = IntArray(NUM_PLAYERS) { i -> deltas.getOrElse(i) { 0 } }
    val totalsArr = IntArray(NUM_PLAYERS) { i -> runningTotals.getOrElse(i) { 0 } }
    return HandScoreRow(
        handNumber = handNumber,
        handValue = handValue,
        winnerSeat = winnerSeat ?: -1,
        discarderSeat = discarderSeat ?: -1,
        deltas = deltasArr,
        runningTotals = totalsArr,
        patterns = patterns.map { HandScoreComponent(it.id, it.points, it.formatArgs) },
    )
}
