package inego.zungjunggame.online

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.input.TouchDiagnostics
import inego.zungjunggame.net.AuthSession
import inego.zungjunggame.net.MatchHistoryClient
import inego.zungjunggame.net.MatchSummary
import inego.zungjunggame.net.NoticeListClient
import inego.zungjunggame.net.NotificationPrefsClient
import inego.zungjunggame.net.TableTicket
import inego.zungjunggame.net.PresenceActivity
import inego.zungjunggame.net.PresenceClient
import inego.zungjunggame.platform.PresenceScheduler
import inego.zungjunggame.session.online.ConnectionFailure
import inego.zungjunggame.session.hosts.MatchPhase
import inego.zungjunggame.session.online.OnlinePlayHost
import inego.zungjunggame.session.hosts.PlayHost
import inego.zungjunggame.ui.stack.OnlineTab
import inego.zungjunggame.ui.stack.SessionKind
import inego.zungjunggame.state.StateSlot

/**
 * Coordinates online multiplayer lifecycle: host creation, presence-driven joins,
 * matchmaking preference toggles, and online table teardown.
 */
class OnlineCoordinator(
    val authSession: AuthSession,
    val presenceClient: PresenceClient,
    val matchHistory: MatchHistoryClient = MatchHistoryClient(),
    /** The player's notice inbox for the popup and the notices list. */
    val noticeList: NoticeListClient = NoticeListClient(),
    /**
     * Fired after a notice refresh replaces the inbox; the app presents the newest
     * unseen notice. Injected here because the coordinator owns [noticeList] and
     * wires its callbacks, so nothing reaches into the client after construction.
     */
    private val onNoticesChanged: () -> Unit = {},
    /** The notification settings the Settings card edits. */
    val notificationPrefs: NotificationPrefsClient,
    /**
     * Last live table this client was seated at (empty = none). The id must
     * survive while the hub is the floor — a parked running seat offers Rejoin
     * before presence answers — so it is online session state, not a route arg.
     */
    private val onlineTableId: StateSlot<String>,
    val presenceScheduler: PresenceScheduler = PresenceScheduler.create(),
    private val hostFactory: (tableId: String, token: String, startsAway: Boolean, onLeave: () -> Unit) -> OnlinePlayHost = { id, tok, startsAway, onLeave ->
        OnlinePlayHost(id, tok, onLeaveCallback = onLeave, resolveToken = { authSession.token }, initialAway = startsAway)
    },
    private val away: OnlineAwayPort,
    private val sessionKind: () -> SessionKind,
    /** Screen/board surfaces of the live table; see [OnlineTablePort]. */
    private val tablePort: OnlineTablePort,
    /** The local host to fall back to when an online table detaches. */
    private val onRestoreLocalHost: () -> PlayHost,
    private val onStartingLevelPromptRequested: () -> Unit = {},
    /**
     * Presence delivered a settled match this session has not applied yet: a
     * forfeit while parked, or the replay of the last settlement the server still
     * remembers. The callback is a **trigger**, not a value — the rating on the
     * summary is the account only as of that match, so what it establishes is
     * that the account may have moved, and the account's owner is what reads it.
     */
    private val onSettledMatch: (MatchSummary) -> Unit = {},
) : Disposable {


    /**
     * The last live table id, read from the `online.table` key.
     *
     * The key is the only copy: a memory mirror could disagree with storage (a
     * refused write, a second process), and the cold start reads it once through
     * `AppState` and hands the value in.
     */
    private fun storedTableId(): String? = onlineTableId.requireValue().takeIf { it.isNotEmpty() }

    private fun rememberTable(tableId: String) {
        if (tableId.isEmpty()) return forgetTable()
        onlineTableId.set(tableId)
    }

    private fun forgetTable() {
        if (storedTableId() == null) return
        onlineTableId.clear()
    }

    var isAppPaused: Boolean = false
        private set

    fun isAppVisible(): Boolean = !isAppPaused && presenceScheduler.isAppVisible()

    var onlineHost: OnlinePlayHost? = null
        private set

    var lastLoggedTableId: String? = null
        private set

    /**
     * When the current `Online` context first stood without a host, in the
     * [tick] clock's milliseconds. Null when a host is attached, the context is
     * not `Online`, or the deadline already fired. See [trackAdoptionDeadline].
     */
    private var unadoptedTableSinceMs: Long? = null

    /**
     * Explanation shown on Online home after an abort, a vanished table, or
     * an expired session. Cleared when the player starts a new search or joins.
     */
    var homeNotice: HomeNotice? = null
        private set

    private var hostGeneration = 0L
    private var disposed = false
    private var appliedSettledMatchId: String? = null

    init {
        presenceClient.onSnapshot = {
            reconcilePresence()
        }
        noticeList.onNoticesChanged = onNoticesChanged
    }

    enum class HomeNoticeKind {
        TableAborted,
        GameUnavailable,
        SessionExpired,
        /** The server refused a search while the abuse cooldown is running. */
        MatchmakingCooldown,
        /** The server restart ended the match. */
        ServerShutdown,
    }

    data class HomeNotice(
        val kind: HomeNoticeKind,
        val detail: String? = null,
    )

    fun clearHomeNotice() {
        homeNotice = null
    }

    fun noteSessionExpired() {
        homeNotice = HomeNotice(HomeNoticeKind.SessionExpired)
    }

    fun isSeated(): Boolean =
        presenceClient.snapshot?.self?.activity == PresenceActivity.Playing
            || presenceClient.snapshot?.self?.table != null

    fun isServerShutdown(): Boolean =
        presenceClient.snapshot?.serverShutdown != null

    fun currentPresenceActivity(): PresenceActivity {
        if (presenceClient.waitingEnabled) return PresenceActivity.Waiting
        return PresenceActivity.Idle
    }

    fun tick(nowMs: Long) {
        if (!authSession.isSignedIn) return
        presenceClient.tick(nowMs, currentPresenceActivity())
        trackAdoptionDeadline(nowMs)
    }

    /**
     * A restored table that presence never adopts must not hold the screen
     * forever. Once the `Online` context has stood without a host for
     * [ADOPTION_TIMEOUT_MS], [returnUnadoptedTableToHub] takes the table layer
     * down and the hub's Live tab offers Rejoin — the model-driven Retry.
     *
     * The deadline is only armed while signed in ([tick]'s gate): before the
     * access token rotates there is no session to ask, and the session-dropped
     * path owns that state. A host attaching, or the context leaving `Online`,
     * disarms it.
     */
    private fun trackAdoptionDeadline(nowMs: Long) {
        if (sessionKind() != SessionKind.Online || onlineHost != null) {
            unadoptedTableSinceMs = null
            return
        }
        val since = unadoptedTableSinceMs
        if (since == null) {
            unadoptedTableSinceMs = nowMs
            return
        }
        if (nowMs - since < ADOPTION_TIMEOUT_MS) return
        unadoptedTableSinceMs = null
        returnUnadoptedTableToHub()
    }

    /**
     * The restored table was never adopted: take its layer down and leave
     * `online.table` in place, so the hub it reveals offers Rejoin. Nothing was
     * attached, so no socket or seat is touched — this is not [leaveOnlineTable],
     * which forfeits the reservation.
     */
    private fun returnUnadoptedTableToHub() {
        detachOnlinePlay(abandon = true)
        tablePort.tableExited()
    }

    /**
     * Reconciles authoritative presence table state with the hosted board.
     *
     * Join when seated and there is no live host, and the stack says the player is
     * on the table (a search that just matched, or the context the boot restored and
     * never attached). A parked running seat stays parked until [rejoinActiveTable].
     * Do not steal FreePlay/Tutorial. [lastLoggedTableId] is a log latch, not a join
     * gate. A snapshot with no seat takes an unhosted `Online` stack down, which is
     * how a restored table whose seat is gone reveals the hub.
     */
    fun reconcilePresence() {
        applySettledMatchFromPresence()
        val host = onlineHost
        if (host != null && host.matchPhase.isOver) {
            away.hide()
        }
        if (host != null &&
            (host.matchPhase == MatchPhase.Aborted || host.shutdownEndedMatch) &&
            sessionKind() == SessionKind.Online
        ) {
            leaveOnlineTable()
            lastLoggedTableId = null
            return
        }
        val snap = presenceClient.snapshot
        if (snap?.needsStartingLevel == true && snap.self?.table == null) {
            if (presenceClient.waitingEnabled) {
                presenceClient.stopLooking()
            }
            onStartingLevelPromptRequested()
        }
        // Drop looking if server entered drain/shutdown.
        if (snap?.serverShutdown != null && presenceClient.waitingEnabled) {
            presenceClient.stopLooking()
        }
        // The server refuses the enqueue rather than the search, so a client that
        // kept its local "searching" flag would show a spinner until the player gave up. The
        // snapshot's cooldown is authoritative: drop the search and say why.
        reportCooldownIfSearching(snap)
        val table = snap?.self?.table
        val currentKind = sessionKind()
        if (table != null) {
            if (table.id != lastLoggedTableId) {
                lastLoggedTableId = table.id
                TouchDiagnostics.log("presence seated table=${table.id} humans=${table.humans} bots=${table.bots}")
            }
            if (onlineHost == null && shouldJoinPresentTable(currentKind)) {
                enterOnlineTable(table.id)
            }
        } else if (snap != null) {
            if (currentKind == SessionKind.Online) {
                // Presence drop is not a leave. A Running table still exists;
                // the host reconnects. Only abort (or no host) tears down.
                if (host == null || host.matchPhase == MatchPhase.Aborted) {
                    leaveOnlineTable()
                }
            } else if (onlineHost == null) {
                forgetTable()
            }
            lastLoggedTableId = null
        }
    }

    /**
     * The last settlement the server remembers, replayed on every heartbeat until
     * it is replaced. The trigger is keyed on the match id and only asks the
     * account and an open history card to be read again, so a replay is a no-op
     * rather than a second application of a stale rating.
     */
    private fun applySettledMatchFromPresence() {
        val match = presenceClient.snapshot?.self?.lastMatch ?: return
        if (match.id == appliedSettledMatchId) return
        appliedSettledMatchId = match.id
        authSession.token?.let { matchHistory.reloadAllIfLoaded(it) }
        onSettledMatch(match)
    }

    /**
     * The cooldown the server is currently applying, if any.
     *
     * Read off the latest snapshot: the server is the authority on whether a search may start,
     * and the timestamp in the snapshot is rounded up, so a stale one can only make this
     * conservative about *reporting*, never about letting a search through — the server still
     * has the last word on the request itself.
     */
    private fun activeCooldown() = presenceClient.snapshot?.self?.matchmakingCooldown

    /** Sets the cooldown notice, phrased with what the server reported. */
    private fun noteCooldown(secondsRemaining: Int) {
        homeNotice = HomeNotice(
            HomeNoticeKind.MatchmakingCooldown,
            secondsRemaining.toString(),
        )
    }

    /**
     * Stops a local search the server has already refused, and reports the cooldown.
     *
     * Called on every snapshot: a **Find game** tap races the cooldown rather than being blocked
     * by it, so the refusal is usually discovered here rather than before the request.
     */
    private fun reportCooldownIfSearching(snap: inego.zungjunggame.net.PresenceSnapshot?) {
        val cooldown = snap?.self?.matchmakingCooldown ?: return
        if (!presenceClient.waitingEnabled) return
        presenceClient.stopLooking()
        noteCooldown(cooldown.secondsRemaining)
    }

    /**
     * The two join intents, and nothing else auto-enters.
     *
     * A search that just matched is the queue's own answer. An `Online` stack is the
     * **restored** context's: the player was on the board when the app closed, and the
     * seat presence reports is that context's session — the boot could not ask for it
     * (the access token is memory-only, see [canRestoreOnlineTable]), so the first
     * snapshot is where it is adopted. A **parked** seat is neither: leaving a running
     * table for the hub keeps the seat, so the kind is `Menu` there and it waits for
     * an explicit [rejoinActiveTable] instead of being re-entered under the player.
     * FreePlay and a lesson own their display and are never stolen.
     */
    private fun shouldJoinPresentTable(currentKind: SessionKind): Boolean {
        return !(currentKind == SessionKind.FreePlay || currentKind == SessionKind.Tutorial) && (currentKind == SessionKind.Online || presenceClient.waitingEnabled)
    }

    /**
     * Host [tableId] and connect.
     *
     * [ticket] is a ticket the server already minted for this table — the Shadow
     * Ranked wire start returns one with its table — so the client skips the
     * extra ticket round trip. Null (the queue/rejoin path) asks for a fresh ticket.
     *
     * [origin] is the hub tab this table belongs to; it is handed to the app so the
     * table's route carries it and leaving reveals the hub on that tab. The queue and
     * rejoin paths are live, so they default to Live; a Shadow Ranked start passes
     * ShadowRanked.
     *
     * A missing access token cannot host a table: the enter reports a
     * [HomeNoticeKind.SessionExpired] notice on the hub rather than returning
     * silently, so the refusal is visible where the player asked for the table.
     */
    fun enterOnlineTable(
        tableId: String,
        ticket: TableTicket? = null,
        origin: OnlineTab = OnlineTab.Live,
    ) {
        clearHomeNotice()
        val token = authSession.token
        if (token == null) {
            homeNotice = HomeNotice(HomeNoticeKind.SessionExpired)
            return
        }
        val startsAway = !isAppVisible()
        TouchDiagnostics.log("enter table=$tableId startsAway=$startsAway")
        try {
            Gdx.app?.log("Online", "enter table=$tableId startsAway=$startsAway")
        } catch (_: Throwable) {
            println("[Online] enter table=$tableId startsAway=$startsAway")
        }
        detachOnlinePlay(abandon = true, restoreLocal = false)
        val generation = hostGeneration
        val host = hostFactory(tableId, token, startsAway) {
            onHostExited(generation)
        }
        host.onMatchLive = { stopLookingForLiveMatch() }
        onlineHost = host
        rememberTable(tableId)
        // A live table now owns the screen and the board; the app moves the floor
        // (the kind is derived from it, never set here).
        tablePort.tableJoined(origin)
        if (ticket != null) host.connectWithTicket(ticket) else host.connect()
    }

    /**
     * Looking keeps a Reserved table alive. Once tiles are dealt, leftover
     * Waiting heartbeats would enqueue this seat again when the match ends.
     */
    private fun stopLookingForLiveMatch() {
        if (!presenceClient.waitingEnabled) return
        presenceClient.stopLooking()
        presenceClient.requestNow()
    }

    /**
     * Navigation away from a live table: close the socket, keep the stored
     * table id, do not send `leave_game`. Presence still reports the seat.
     *
     * The floor move is the caller's (the local-play path continues into the
     * board, the menu path replaces the screen); the coordinator only stops
     * looking and detaches the host.
     */
    fun parkOnlineTable() {
        if (onlineHost == null && sessionKind() != SessionKind.Online) return
        if (presenceClient.waitingEnabled) {
            presenceClient.stopLooking()
            presenceClient.requestNow()
        }
        TouchDiagnostics.log("park table=${onlineHost?.tableId}")
        detachOnlinePlay(abandon = false)
    }

    fun leaveOnlineForLocalPlay() {
        parkOnlineTable()
    }

    fun leaveOnlineTable() {
        captureHomeNotice(onlineHost)
        detachOnlinePlay(abandon = true)
        forgetTable()
        lastLoggedTableId = null
        presenceClient.cancelAndLeave()
        // The port's fact is "no table is hosted", not "a socket was closed": a context
        // that never attached — a restored table whose seat is gone, or whose session
        // never came back — still has to come down. A caller that lands on the
        // hub right after finds it already up.
        tablePort.tableExited()
    }

    /**
     * The session this client restored is gone, and the table the boot restored was
     * never attached: the context stands for a seat this client can no longer ask for,
     * so it comes down and the hub it was entered from is what is left.
     *
     * Only that state is touched. A live match owns its own path — the host's reconnect
     * and the connect card's sign-in — and a stack that is not `Online` has no table
     * context to take down. Until this fires, the context is *pending*, not gone: the
     * boot cannot ask a table endpoint before the access token exists, so a credential
     * that is still refreshing must not be read as a dead seat.
     */
    fun abandonRestoredTable() {
        if (onlineHost != null) return
        if (sessionKind() != SessionKind.Online) return
        leaveOnlineTable()
    }

    private fun captureHomeNotice(host: OnlinePlayHost?) {
        if (host == null) return
        homeNotice = when {
            host.shutdownEndedMatch -> HomeNotice(
                HomeNoticeKind.ServerShutdown,
                host.shutdownMessage?.trim()?.takeIf { it.isNotEmpty() },
            )
            host.matchPhase == MatchPhase.Aborted -> HomeNotice(
                HomeNoticeKind.TableAborted,
                host.abortReason?.trim()?.takeIf { it.isNotEmpty() },
            )
            host.connectionFailure == ConnectionFailure.TableUnavailable ->
                HomeNotice(HomeNoticeKind.GameUnavailable)
            host.connectionFailure == ConnectionFailure.Authentication ->
                HomeNotice(HomeNoticeKind.SessionExpired)
            else -> homeNotice
        }
    }

    /**
     * Close out the hosted table: hide the away overlay, clear [onlineHost], and
     * abandon or disconnect the socket.
     *
     * @return true when a host was actually attached. That answers whether a *socket*
     *   ended, which is what an owner-originated exit needs; whether the **context**
     *   comes down is a different question and follows "is a table hosted", not this.
     */
    private fun detachOnlinePlay(abandon: Boolean, restoreLocal: Boolean = true): Boolean {
        away.hide()
        val host = onlineHost
        onlineHost = null
        hostGeneration += 1
        if (restoreLocal) {
            onRestoreLocalHost()
        }
        if (host == null) return false
        if (abandon) host.leave() else host.disconnect()
        return true
    }

    /** Handles a host-originated leave without calling leave() recursively. */
    private fun onHostExited(generation: Long) {
        if (disposed || generation != hostGeneration || onlineHost == null) return
        captureHomeNotice(onlineHost)
        // Capture before the detach clears the host, mirroring [leaveOnlineTable].
        val detached = detachOnlinePlay(abandon = false)
        forgetTable()
        if (detached) {
            tablePort.tableExited()
        }
    }

    fun toggleWaiting() {
        if (!presenceClient.waitingEnabled && authSession.user?.needsStartingLevel() == true) {
            onStartingLevelPromptRequested()
            return
        }
        // Starting a search while the server is delaying one is the tap that must not be
        // silently ignored.
        if (!presenceClient.waitingEnabled) {
            val cooldown = activeCooldown()
            if (cooldown != null) {
                noteCooldown(cooldown.secondsRemaining)
                return
            }
        }
        presenceClient.toggleWaiting()
        presenceClient.requestNow()
    }

    /** Explicit **Find game**. No-op when already searching or seated. */
    fun startSearch() {
        if (isSeated() || presenceClient.waitingEnabled) return
        if (authSession.user?.needsStartingLevel() == true) {
            onStartingLevelPromptRequested()
            return
        }
        val cooldown = activeCooldown()
        if (cooldown != null) {
            noteCooldown(cooldown.secondsRemaining)
            return
        }
        clearHomeNotice()
        presenceClient.startLooking()
        presenceClient.requestNow()
    }

    /**
     * Explicit **Cancel search**. Stops matchmaking and dissolves a reserved
     * table (Looking stays armed until the first live snapshot).
     *
     * A parked **running** seat is not a search: `/presence/leave` does not
     * vacate Running tables, so leaving would only hide the seat locally.
     * No-op when not looking.
     */
    fun cancelSearch() {
        if (!presenceClient.waitingEnabled) return
        presenceClient.cancelAndLeave()
    }

    /**
     * Explicit **Rejoin** for a parked running (or stored) table.
     * No-op when there is no table to return to.
     */
    fun rejoinActiveTable() {
        val tableId = presenceClient.snapshot?.self?.table?.id
            ?: storedTableId()
            ?: return
        val host = onlineHost
        if (host != null && host.tableId == tableId) {
            tablePort.tableJoined(OnlineTab.Live)
            return
        }
        enterOnlineTable(tableId)
    }

    /**
     * Minimum other humans can be adjusted while idle or actively searching.
     * When searching, immediately flushes a heartbeat via [requestNow].
     * Frozen (no-op) once seated at a table.
     */
    fun setMinOtherHumans(value: Int) {
        if (isSeated()) return
        val searching = presenceClient.waitingEnabled
        val old = presenceClient.minOtherHumans
        presenceClient.setMinOtherHumans(value)
        if (searching && presenceClient.minOtherHumans != old) {
            presenceClient.requestNow()
        }
    }

    fun cycleMinOtherHumans() {
        setMinOtherHumans((presenceClient.minOtherHumans + 1) % 4)
    }

    fun cycleRounds() {
        if (isSeated() || presenceClient.waitingEnabled) return
        presenceClient.cycleRounds()
    }

    fun cycleChicken() {
        if (isSeated() || presenceClient.waitingEnabled) return
        presenceClient.cycleChicken()
    }

    /**
     * Whether the online-table context the boot restored has a session that can still
     * arrive — read off the credential the boot handed in (`AppState`).
     *
     * It cannot be adopted *now*, on any cold start: the access token is memory-only,
     * so [AuthSession.load] leaves this process holding a refresh token and nothing it
     * could present to a table endpoint. A restored credential means a session may
     * still arrive, so the context stays and presence resolves the seat later —
     * [reconcilePresence] joins the seat the stack says we are on, or takes the layer
     * down when the snapshot has none. No credential means no session to come back to,
     * so the boot pops the layer instead.
     *
     * The bug this replaces answered the same question with [AuthSession.isSignedIn],
     * which is false until the refresh rotation lands — so the answer was false on
     * every fresh process and a live seat was reported "gone" without ever being asked
     * about.
     */
    fun canRestoreOnlineTable(): Boolean = !authSession.refreshToken.isNullOrBlank()

    fun onAppResumed() {
        isAppPaused = false
        presenceClient.requestNow()
        onlineHost?.onAppResumed()
        val host = onlineHost
        if (host != null && host.wasAway && !host.matchPhase.isOver) {
            away.show()
        }
    }

    fun onAppPaused() {
        isAppPaused = true
        if (authSession.isSignedIn) presenceClient.requestNow()
        onlineHost?.onAppPaused()
    }

    fun resumeFromAway() {
        away.hide()
        onlineHost?.resumeFromAway()
    }

    override fun dispose() {
        disposed = true
        val host = onlineHost
        onlineHost = null
        hostGeneration += 1
        // A process exit is not a quit: close the socket and leave the running seat
        // where it is, so the next launch can rejoin it — the same thing a crash
        // leaves behind, and what the server's abandonment timeout is for. Only an
        // explicit forfeit vacates a running seat ([leaveOnlineTable], reached from
        // the game menu's confirmed Forfeit); a `leave_game` here would charge the
        // player a table-integrity strike for closing the window.
        host?.disconnect()
        presenceClient.stopAndLeave(authSession.token)
    }

    companion object {
        /**
         * How long a restored `Online` context may stand without a host before it
         * is taken down to the hub. Four presence heartbeats
         * ([PresenceClient.HEARTBEAT_INTERVAL_MS] is 5s): long enough for a slow
         * first snapshot, short enough that a dead seat cannot hang the card.
         */
        const val ADOPTION_TIMEOUT_MS = 20_000L
    }
}
