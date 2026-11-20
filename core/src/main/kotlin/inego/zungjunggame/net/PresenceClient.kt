package inego.zungjunggame.net

import com.badlogic.gdx.utils.TimeUtils
import inego.zungjunggame.persist.FindGamePreferences
import inego.zungjunggame.state.StateSlot

/**
 * Heartbeat loop for signed-in presence. Driven from [inego.zungjunggame.Main]
 * `tickPresence` on the render thread — no daemon thread.
 *
 * Logout during an in-flight heartbeat still POSTs leave with the **captured**
 * Bearer ([stopAndLeave] copies [leaveToken] before [AuthSession.signOut] clears
 * the session). Delayed leave never reads [AuthSession].
 *
 * Owns the Find-game model: the last matchmaking selection and the hub
 * settings-drawer state, persisted through [findPreferences].
 */
class PresenceClient(
    private val api: PresenceSender = PresenceApi,
    private val clock: () -> Long = { TimeUtils.millis() },
    val findPreferences: StateSlot<FindGamePreferences>,
) {
    private val initialPreferences = findPreferences.requireValue()

    /** Opt-in “looking for a game.” Off until **Find game**. */
    var waitingEnabled: Boolean = false
        private set

    /** Elapsed-search clock; set when [startLooking] first arms the queue. */
    var searchStartedAtMs: Long? = null
        private set

    var minOtherHumans: Int = initialPreferences.minOtherHumans
        private set

    var queueRounds: QueueRounds = initialPreferences.rounds
        private set

    var queueChicken: QueueChicken = initialPreferences.chicken
        private set



    var snapshot: PresenceSnapshot? = null
        internal set

    var lastError: String? = null
        private set

    var lastSuccessAtMs: Long = 0L
        private set

    /**
     * Round-trip of the last successful heartbeat, in milliseconds.
     *
     * Measured on existing `/presence/heartbeat` traffic — no extra request.
     * Null until the first 200 of this presence session. This is the raw
     * measurement and survives a failure; surfaces print [freshRttMs], which
     * withholds it once it is older than [STALE_AFTER_MS].
     */
    var lastRttMs: Int? = null
        private set

    /**
     * A `401` from a heartbeat, carrying the token that beat was sent with: a
     * session that has already rotated past it re-arms instead of rotating again.
     */
    var onUnauthorized: ((usedToken: String?) -> Unit)? = null

    var onSnapshot: ((PresenceSnapshot) -> Unit)? = null

    private var generation: Int = 0
    private var stopped: Boolean = true
    private var inFlight: Boolean = false
    private var pending: Boolean = false
    private var leaveWhenIdle: Boolean = false
    private var activeToken: String? = null
    /** Captured Bearer for a leave still owed after [stopAndLeave] or [cancelAndLeave]. */
    private var leaveToken: String? = null
    private var leaveOnDone: (() -> Unit)? = null
    private var leaveRefreshSnapshot: Boolean = false
    private var lastAttemptAt: Long = 0L
    private var lastAttemptedActivity: PresenceActivity? = null
    private var lastAttemptedMinOtherHumans: Int? = null
    private var lastAttemptedRounds: QueueRounds? = null
    private var lastAttemptedChicken: QueueChicken? = null
    private var lastSentActivity: PresenceActivity? = null
    private var lastSentMinOtherHumans: Int? = null
    private var lastSentRounds: QueueRounds? = null
    private var lastSentChicken: QueueChicken? = null
    private var lastTickActivity: PresenceActivity? = null
    private var startedAtMs: Long = 0L
    /**
     * After [cancelAndLeave], ignore a table on incoming snapshots until the
     * server confirms we are unseated. Prevents a leftover Playing snapshot
     * (or a heartbeat that raced vacate) from rejoining the abandoned table.
     */
    private var suppressSeatedSnapshot: Boolean = false

    val signedIn: Boolean
        get() = !stopped && activeToken != null

    fun toggleWaiting() {
        if (waitingEnabled) stopLooking() else startLooking()
    }

    /** Enter the matchmaking queue. No-op when already looking. */
    fun startLooking() {
        if (!waitingEnabled) searchStartedAtMs = clock()
        waitingEnabled = true
    }

    /** Leave the matchmaking queue without toggling. No-op when already idle. */
    fun stopLooking() {
        waitingEnabled = false
        searchStartedAtMs = null
    }

    fun setMinOtherHumans(value: Int) {
        val clamped = value.coerceIn(0, FindGamePreferences.MAX_MIN_OTHER_HUMANS)
        if (minOtherHumans == clamped) return
        minOtherHumans = clamped
        persistPreferences()
    }

    fun cycleMinOtherHumans() {
        setMinOtherHumans((minOtherHumans + 1) % (FindGamePreferences.MAX_MIN_OTHER_HUMANS + 1))
    }

    fun cycleRounds() {
        queueRounds = when (queueRounds) {
            QueueRounds.Either -> QueueRounds.One
            QueueRounds.One -> QueueRounds.Two
            QueueRounds.Two -> QueueRounds.Either
        }
        persistPreferences()
    }

    fun cycleChicken() {
        queueChicken = when (queueChicken) {
            QueueChicken.Either -> QueueChicken.Allowed
            QueueChicken.Allowed -> QueueChicken.Disallowed
            QueueChicken.Disallowed -> QueueChicken.Either
        }
        persistPreferences()
    }



    /**
     * Log-out path: the matchmaking selection is account-scoped — it rides the
     * queue request under this account's token — so a second account must not
     * inherit it.
     */
    fun resetFindSelection() {
        minOtherHumans = FindGamePreferences.DEFAULT_MIN_OTHER_HUMANS
        queueRounds = FindGamePreferences.DEFAULT_ROUNDS
        queueChicken = FindGamePreferences.DEFAULT_CHICKEN
        persistPreferences()
    }

    private fun persistPreferences() {
        findPreferences.set(
            FindGamePreferences(
                minOtherHumans = minOtherHumans,
                rounds = queueRounds,
                chicken = queueChicken,
            )
        )
    }

    /**
     * Arm heartbeats for [token]. Bumps [generation] so an old 200 cannot
     * apply. Does **not** clear [leaveToken] / [leaveWhenIdle] — a previous
     * session may still owe leave with its captured JWT.
     *
     * Does **not** POST here. The next [tick] with [inego.zungjunggame.Main]
     * `currentPresenceActivity()` is the first beat (`idle` unless looking).
     */
    fun start(token: String) {
        generation += 1
        stopped = false
        pending = false
        // Keep last-selected queue prefs across sessions ("remember the last
        // selection"); only the search state resets.
        waitingEnabled = false
        searchStartedAtMs = null
        activeToken = token
        snapshot = null
        lastError = null
        lastSuccessAtMs = 0L
        lastRttMs = null
        lastAttemptAt = 0L
        lastAttemptedActivity = null
        lastAttemptedMinOtherHumans = null
        lastAttemptedRounds = null
        lastAttemptedChicken = null
        lastSentActivity = null
        lastSentMinOtherHumans = null
        lastSentRounds = null
        lastSentChicken = null
        lastTickActivity = null
        leaveOnDone = null
        leaveRefreshSnapshot = false
        suppressSeatedSnapshot = false
        startedAtMs = clock()
    }

    /**
     * Swap the Bearer on a live presence loop without resetting search or
     * snapshot. Used after access-token refresh so an in-match heartbeat
     * does not look like a new sign-in.
     *
     * If presence is stopped, this is [start].
     */
    fun replaceToken(token: String) {
        if (stopped || activeToken == null) {
            start(token)
            return
        }
        generation += 1
        activeToken = token
    }

    /**
     * Cancel an ongoing search or abandon a reserved/running table immediately
     * by POSTing `/presence/leave` while keeping the presence loop active.
     *
     * Drops the local seated table before the HTTP round-trip so
     * [inego.zungjunggame.online.OnlineCoordinator.reconcilePresence] cannot
     * re-ticket a table this client just left (forfeit / return home). In-flight
     * heartbeat generation is bumped so a pending seated snapshot cannot apply.
     * Heartbeats that still report a table are stripped until a seat-less
     * snapshot arrives. If a heartbeat is in flight, the leave call is queued
     * to execute upon heartbeat completion. Once leave completes, an Idle
     * heartbeat is requested to retrieve a clean empty snapshot.
     */
    fun cancelAndLeave(onDone: (() -> Unit)? = null) {
        generation += 1
        waitingEnabled = false
        searchStartedAtMs = null
        lastTickActivity = PresenceActivity.Idle
        suppressSeatedSnapshot = true
        dropLocalSeat()
        val token = activeToken
        if (token == null || stopped) {
            onDone?.invoke()
            return
        }
        if (inFlight) {
            leaveWhenIdle = true
            leaveToken = token
            leaveOnDone = onDone
            leaveRefreshSnapshot = true
        } else {
            sendLeaveNow(token, refreshSnapshot = true, onDone = onDone)
        }
    }

    /**
     * Stop heartbeats and POST leave with the captured [token].
     *
     * Call with [AuthSession.token] **before** [AuthSession.signOut]. If a
     * heartbeat is in flight, leave waits for that callback and still uses
     * this copy — not the now-cleared session token.
     */
    fun stopAndLeave(token: String?) {
        leaveToken = token
        leaveOnDone = null
        leaveRefreshSnapshot = false
        generation += 1
        stopped = true
        pending = false
        activeToken = null
        waitingEnabled = false
        searchStartedAtMs = null
        lastTickActivity = null
        lastRttMs = null
        if (token == null) {
            leaveWhenIdle = false
            leaveToken = null
            return
        }
        if (inFlight) {
            leaveWhenIdle = true
        } else {
            sendLeaveNow(token, refreshSnapshot = false)
            leaveToken = null
            leaveWhenIdle = false
        }
    }

    /** Stop without leave (401 / already unauthorized). */
    fun stop() {
        generation += 1
        stopped = true
        pending = false
        activeToken = null
        waitingEnabled = false
        searchStartedAtMs = null
        lastTickActivity = null
        leaveWhenIdle = false
        leaveToken = null
        leaveOnDone = null
        leaveRefreshSnapshot = false
        lastRttMs = null
    }

    fun requestNow() {
        if (stopped || activeToken == null) return
        lastTickActivity = defaultActivity()
        if (inFlight) {
            pending = true
            return
        }
        sendHeartbeatNow(lastTickActivity!!)
    }

    fun tick(nowMs: Long, activity: PresenceActivity) {
        lastTickActivity = activity
        if (stopped || activeToken == null) return
        val prefsChanged = activity == PresenceActivity.Waiting &&
            (minOtherHumans != lastAttemptedMinOtherHumans || queueRounds != lastAttemptedRounds || queueChicken != lastAttemptedChicken)
        if (inFlight) {
            if (activity != lastAttemptedActivity || prefsChanged) pending = true
            return
        }
        val due = lastAttemptAt == 0L ||
            nowMs - lastAttemptAt >= HEARTBEAT_INTERVAL_MS ||
            activity != lastAttemptedActivity ||
            prefsChanged ||
            pending
        if (!due) return
        pending = false
        sendHeartbeatNow(activity, nowMs)
    }

    fun isStale(nowMs: Long): Boolean {
        if (lastSuccessAtMs == 0L) {
            return startedAtMs != 0L && nowMs - startedAtMs >= STALE_AFTER_MS
        }
        return nowMs - lastSuccessAtMs >= STALE_AFTER_MS
    }

    /**
     * The value a surface may print at [nowMs]: [lastRttMs] while its success is
     * younger than [STALE_AFTER_MS], otherwise null.
     *
     * [lastRttMs] is the measurement and survives a non-401 failure, so without
     * this projection a dropped connection leaves a healthy-looking number on
     * screen indefinitely. The hub, the connect card and the live-table HUD all
     * read this one rule instead of judging age themselves.
     */
    fun freshRttMs(nowMs: Long): Int? =
        lastRttMs?.takeIf { nowMs - lastSuccessAtMs < STALE_AFTER_MS }

    private fun defaultActivity(): PresenceActivity =
        if (waitingEnabled) PresenceActivity.Waiting else PresenceActivity.Idle

    private fun dropLocalSeat() {
        val snap = snapshot ?: return
        snapshot = withoutSeat(snap)
    }

    /** Strip a seated/Playing self so presence reconcile cannot rejoin. */
    private fun withoutSeat(snap: PresenceSnapshot): PresenceSnapshot {
        val self = snap.self ?: return snap
        if (self.table == null && self.activity != PresenceActivity.Playing) return snap
        return snap.copy(
            self = self.copy(
                activity = if (self.activity == PresenceActivity.Playing) {
                    PresenceActivity.Idle
                } else {
                    self.activity
                },
                table = null,
            ),
        )
    }

    private fun applyIncomingSnapshot(snap: PresenceSnapshot): PresenceSnapshot {
        if (!suppressSeatedSnapshot) return snap
        if (snap.self?.table != null) return withoutSeat(snap)
        suppressSeatedSnapshot = false
        return snap
    }

    private fun sendHeartbeatNow(activity: PresenceActivity, nowMs: Long = clock()) {
        val token = activeToken ?: return
        if (stopped) return
        inFlight = true
        pending = false
        lastAttemptAt = nowMs
        lastAttemptedActivity = activity
        val minOthers = if (activity == PresenceActivity.Waiting) minOtherHumans else null
        val rounds = if (activity == PresenceActivity.Waiting) queueRounds else null
        val chicken = if (activity == PresenceActivity.Waiting) queueChicken else null
        lastAttemptedMinOtherHumans = minOthers
        lastAttemptedRounds = rounds
        lastAttemptedChicken = chicken
        val gen = generation
        api.heartbeat(token, activity, minOthers, rounds, chicken) { result ->
            onHeartbeatComplete(gen, token, activity, minOthers, rounds, chicken, result, nowMs)
        }
    }

    private fun sendLeaveNow(
        token: String,
        refreshSnapshot: Boolean = false,
        onDone: (() -> Unit)? = null,
    ) {
        api.leave(token) {
            onDone?.invoke()
            if (refreshSnapshot && !stopped && activeToken != null) {
                requestNow()
            }
        }
    }

    private fun onHeartbeatComplete(
        capturedGen: Int,
        capturedToken: String,
        capturedActivity: PresenceActivity,
        capturedMinOtherHumans: Int?,
        capturedRounds: QueueRounds?,
        capturedChicken: QueueChicken?,
        result: Result<PresenceSnapshot>,
        sentAt: Long,
    ) {
        inFlight = false
        if (leaveWhenIdle && leaveToken != null) {
            val owed = leaveToken
            val doneCb = leaveOnDone
            val refresh = leaveRefreshSnapshot
            leaveWhenIdle = false
            leaveToken = null
            leaveOnDone = null
            leaveRefreshSnapshot = false
            if (owed != null) sendLeaveNow(owed, refresh, doneCb)
        }
        if (capturedGen != generation || stopped) {
            // New session is armed ([lastAttemptAt] == 0). Next [tick] sends
            // with the live activity; do not POST waiting/idle from here.
            return
        }
        result.fold(
            onSuccess = { snap ->
                val applied = applyIncomingSnapshot(snap)
                snapshot = applied
                onSnapshot?.invoke(applied)
                lastError = null
                lastSuccessAtMs = clock()
                lastRttMs = (lastSuccessAtMs - sentAt).coerceIn(0L, 99_999L).toInt()
                lastSentActivity = capturedActivity
                lastSentMinOtherHumans = capturedMinOtherHumans
                lastSentRounds = capturedRounds
                lastSentChicken = capturedChicken
                val latest = lastTickActivity
                val currentActivity = latest ?: capturedActivity
                val prefsChanged = currentActivity == PresenceActivity.Waiting &&
                    (minOtherHumans != lastSentMinOtherHumans || queueRounds != lastSentRounds || queueChicken != lastSentChicken)
                if (pending || (latest != null && latest != lastSentActivity) || prefsChanged) {
                    pending = false
                    sendHeartbeatNow(currentActivity)
                }
            },
            onFailure = { err ->
                lastError = err.message
                if (err is PresenceException && err.unauthorized) {
                    stop()
                    onUnauthorized?.invoke(capturedToken)
                    return
                }
                if (pending) {
                    pending = false
                    sendHeartbeatNow(lastTickActivity ?: capturedActivity)
                }
            },
        )
    }

    companion object {
        const val HEARTBEAT_INTERVAL_MS = 5_000L
        const val STALE_AFTER_MS = 15_000L
    }
}
