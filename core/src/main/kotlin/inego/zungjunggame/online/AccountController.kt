package inego.zungjunggame.online

import com.badlogic.gdx.utils.TimeUtils
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.i18n.LocaleBus
import inego.zungjunggame.net.AccessJwt
import inego.zungjunggame.net.AliasPolicy
import inego.zungjunggame.net.AuthApi
import inego.zungjunggame.net.AuthException
import inego.zungjunggame.net.AuthLogin
import inego.zungjunggame.net.AuthRefreshGate
import inego.zungjunggame.net.AuthSession
import inego.zungjunggame.net.AuthUser
import inego.zungjunggame.net.GoogleChallenge
import inego.zungjunggame.net.GoogleVerifier
import inego.zungjunggame.net.MatchHistoryClient
import inego.zungjunggame.net.NoticeListClient
import inego.zungjunggame.net.NotificationPrefsClient
import inego.zungjunggame.net.PresenceClient
import inego.zungjunggame.net.ShadowRankedAttemptsClient
import inego.zungjunggame.net.ShadowRankedHandsClient
import inego.zungjunggame.platform.GoogleAuthProvider
import inego.zungjunggame.platform.PlatformTimeZone
import inego.zungjunggame.platform.GoogleSignInCancelled
import inego.zungjunggame.platform.GoogleSignInException
import inego.zungjunggame.state.StateSlot

typealias AuthRefreshRequester = (
    refreshToken: String,
    onDone: (Result<AuthLogin>) -> Unit,
) -> Unit

typealias AuthLogoutRequester = (
    token: String,
    onDone: (Result<Unit>) -> Unit,
) -> Unit

typealias ProfileRequester = (
    token: String,
    onDone: (Result<AuthUser>) -> Unit,
) -> Unit

typealias GoogleChallengeRequester = (
    clientId: String,
    verifierHash: String,
    onDone: (Result<GoogleChallenge>) -> Unit,
) -> Unit

typealias GoogleExchangeRequester = (
    verifier: String,
    idToken: String,
    onDone: (Result<AuthLogin>) -> Unit,
) -> Unit

typealias SpecialKeyRequester = (
    key: String,
    onDone: (Result<AuthLogin>) -> Unit,
) -> Unit

typealias AliasUpdateRequester = (
    token: String,
    displayName: String,
    onDone: (Result<AuthUser>) -> Unit,
) -> Unit

typealias StartingLevelRequester = (
    token: String,
    level: String,
    onDone: (Result<AuthUser>) -> Unit,
) -> Unit

enum class AuthLifecycle {
    SignedOut,
    SigningIn,
    SignedIn,
    Refreshing,
}

/** Step of an in-flight Google sign-in. [Idle] when [AuthLifecycle] is not [SigningIn] via Google. */
enum class GoogleSignInStep {
    Idle,
    Challenging,
    AwaitingGoogle,
    Exchanging,
}

/** Last Google (or shared) auth failure. [retryable] means [AccountController.beginGoogleSignIn] can be tried again. */
data class AuthError(
    val message: String,
    val retryable: Boolean,
)

/**
 * Manages authentication lifecycle, credentials persistence,
 * and associated presence / UI side effects.
 */
class AccountController(
    val authSession: AuthSession,
    private val presenceClient: PresenceClient,
    private val view: View,
    /**
     * The `GET /me` reader behind [refreshAccount]: the account payload is the
     * only writer of the cached rating.
     *
     * Required on purpose: an inert default would let a composition root forget
     * the wiring and leave every account surface silently stale, which is exactly
     * the failure this class exists to prevent. `AppGraph` supplies `AuthApi.me`;
     * a test supplies a double.
     */
    private val profileRequester: ProfileRequester,
    refreshRequester: AuthRefreshRequester = { token, done ->
        AuthApi.refresh(token, done)
    },
    private val logoutRequester: AuthLogoutRequester = { token, done ->
        AuthApi.logout(token, done)
    },
    private val matchHistory: MatchHistoryClient? = null,
    /**
     * The player's Shadow Ranked attempts. A refresh that rotates the session
     * reloads it (the hub's list follows the new access token); log-out and
     * expiry clear it, so a second account never sees the first's list.
     */
    private val shadowRankedAttempts: ShadowRankedAttemptsClient? = null,
    /**
     * The open hand tables of one challenge. Cleared on log-out and expiry like
     * the attempts list, so a second account never sees the first's tables, and
     * reloaded under a rotated session.
     */
    private val shadowRankedHands: ShadowRankedHandsClient? = null,
    /**
     * The Shadow Ranked start lifecycle. Log-out resets it, so a pending start
     * and its remembered draft cannot outlive the account that began them.
     */
    private val shadowRankedStarter: ShadowRankedStarter? = null,
    /** The player's notice inbox; cleared on log-out and expiry like history. */
    private val noticeList: NoticeListClient? = null,
    /** The notification settings the Settings card edits; cleared like the notice inbox. */
    private val notificationPrefs: NotificationPrefsClient? = null,
    val logLifecycle: ((String) -> Unit)? = null,
    private val clock: () -> Long = { TimeUtils.millis() },
    private val onLogOut: () -> Unit = {},
    /**
     * Called when a usable access token exists again: a completed refresh, or a
     * sign-in.
     *
     * The access token is memory-only, so a cold start raises the whole stack
     * before the session is back: anything up at that moment was fetched with no
     * token (or not fetched at all), and a public read is missing the parts only a
     * token carries. This layer cannot see which surfaces are up, so it announces
     * the event and the effects layer that owns the router decides what to reload.
     */
    private val onSessionEstablished: () -> Unit = {},
    /**
     * Called when a session this client held is **dropped** rather than signed out of:
     * a refresh the server refused, or a 401 with no refresh token left. Distinct from
     * [onLogOut] because the two need different landings — an explicit log-out forfeits
     * a running seat, while an expiry must leave a live match alone (its host owns the
     * reconnect/sign-in path). A cold start uses it to drop a context it restored but
     * could never adopt.
     */
    private val onSessionDropped: () -> Unit = {},
    private val googleAuth: GoogleAuthProvider = GoogleAuthProvider.create(),
    private val googleChallengeRequester: GoogleChallengeRequester = { clientId, hash, done ->
        AuthApi.googleChallenge(clientId, hash, done)
    },
    private val googleExchangeRequester: GoogleExchangeRequester = { verifier, idToken, done ->
        AuthApi.googleExchange(verifier, idToken, done)
    },
    private val specialKeyRequester: SpecialKeyRequester = { key, done ->
        AuthApi.specialKey(key, done)
    },
    private val aliasUpdateRequester: AliasUpdateRequester = { token, displayName, done ->
        AuthApi.updateAlias(token, displayName, done)
    },
    private val startingLevelRequester: StartingLevelRequester = { token, level, done ->
        AuthApi.setStartingLevel(token, level, done)
    },
    /** Users whose first-login prompt this install has already answered. */
    private val firstLoginPrompts: StateSlot<Set<String>>,
) {
    interface View {
        /**
         * Close the main menu. A signed-in surface ([showPresence],
         * [showFirstLoginAlias]) always sits *above* the menu in the overlay
         * stack, so leaving it up would shadow the surface with a screen the
         * player cannot see. Restore lands asynchronously, so the boot path can
         * otherwise end with both up.
         */
        fun hideMenu() {}
        fun showPresence()
        fun hidePresence()

        /**
         * True when no live session screen owns the stack, so an account surface
         * may cover it.
         *
         * The router stack is the screen authority: a restored local board,
         * lesson, or online table owns the display, and covering it — a setup
         * card, or the sign-in landing — would drop the game the player is in.
         * The composition root answers from the floor route.
         */
        fun isEntrySurfaceFree(): Boolean = true
        val isFirstLoginAliasVisible: Boolean get() = false
        val isFirstLoginAliasBusy: Boolean get() = false
        val firstLoginAliasText: String get() = ""
        fun showFirstLoginAlias(displayName: String) {}
        fun hideFirstLoginAlias() {}
        fun setFirstLoginAliasBusy(busy: Boolean) {}
        fun setFirstLoginAliasError(message: String) {}
        val isAliasEditVisible: Boolean get() = false
        val isAliasEditBusy: Boolean get() = false
        val aliasEditText: String get() = ""
        /** Open the account display-name editor with [displayName] as the seed. */
        fun showAliasEdit(displayName: String) {}
        fun cancelAliasEdit() {}
        fun setAliasEditBusy(busy: Boolean) {}
        fun setAliasEditError(message: String) {}
        val isSpecialKeyVisible: Boolean get() = false
        val isSpecialKeyBusy: Boolean get() = false
        val specialKeyText: String get() = ""
        /** Open the special-key field for the review account. */
        fun showSpecialKey() {}
        fun hideSpecialKey() {}
        fun setSpecialKeyBusy(busy: Boolean) {}
        fun setSpecialKeyError(message: String) {}
        val isStartingLevelVisible: Boolean get() = false
        val isStartingLevelBusy: Boolean get() = false
        fun showStartingLevel() {}
        fun hideStartingLevel() {}
        fun setStartingLevelBusy(busy: Boolean) {}
        fun setStartingLevelError(message: String) {}
    }

    private val refreshGate = AuthRefreshGate(refreshRequester)
    private var authGeneration = 0

    /** True while the special-key request is in flight; distinct from a Google step. */
    private var specialKeyInFlight = false

    /** The [authGeneration] of the refresh [refreshGate] is running; see [onRefreshResult]. */
    private var refreshGeneration = 0

    /**
     * The [refreshAccount] read whose reply may still be applied. A read issued
     * later is the newer account, so a reply that lands after it is dropped.
     */
    private var profileGeneration = 0

    /**
     * When the next refresh may be presented, or null when none is owed.
     *
     * A refresh that failed for a reason that does not condemn the stored token
     * sets this (see [onRefreshResult]) instead of dropping the session, so the
     * cooldown is what stops the retry from becoming a request per frame: the
     * present loop calls [tick] continuously, and every route into a refresh goes
     * through [beginRefresh].
     */
    private var refreshRetryAtMs: Long? = null

    /**
     * The token a stale-token `401` has already been re-read under, so several
     * surfaces refused in the same instant reload once rather than once each. It
     * names the held token, not a rotation: the next token is a new answer.
     */
    private var retriedUnderToken: String? = null

    var lifecycle: AuthLifecycle = AuthLifecycle.SignedOut
        private set

    var googleSignInStep: GoogleSignInStep = GoogleSignInStep.Idle
        private set

    var lastAuthError: AuthError? = null
        private set

    /** True while a new Google account must confirm or change the generated alias. */
    var awaitingFirstLoginAlias: Boolean = false
        private set

    /** True while a player must pick a starting rating level before ranked games. */
    var awaitingStartingLevel: Boolean = false
        private set

    val googleSignInAvailable: Boolean
        get() = googleAuth.isAvailable && googleAuth.clientId.isNotBlank()

    /**
     * Push the current locale, and the device zone, whenever the locale changes while a session
     * is live. A player who never opens Settings still gets mail in the language they chose, and
     * a locale write carries the zone so it cannot clear one the server already stored. The
     * account controller owns the authenticated notification-prefs surface, so the locale event
     * is routed here rather than from the picker's feature layer.
     */
    private val localeListener = LocaleBus.Listener { locale ->
        val token = authSession.token
        if (authSession.isSignedIn && token != null) {
            notificationPrefs?.setLanguage(token, locale.tag, PlatformTimeZone.id())
        }
    }

    init {
        presenceClient.onUnauthorized = ::onUnauthorized
        matchHistory?.onUnauthorized = ::onUnauthorized
        shadowRankedAttempts?.onUnauthorized = ::onUnauthorized
        shadowRankedHands?.onUnauthorized = ::onUnauthorized
        noticeList?.onUnauthorized = ::onUnauthorized
        notificationPrefs?.onUnauthorized = ::onUnauthorized
        LocaleBus.addListener(localeListener)
    }

    val isSignedIn: Boolean get() = authSession.isSignedIn
    val user: AuthUser? get() = authSession.user
    val token: String? get() = authSession.token
    /** Alias for own chrome; not an identifier. */
    val signedInLabel: String? get() = authSession.user?.identityLabel()

    /**
     * Current Elo for Online home, from the server-provided account payload.
     * Null when the server did not include a rating.
     */
    val eloRating: Double? get() = authSession.user?.eloRating

    /**
     * Cold-start account restore: start presence for the credential the
     * composition root already read into [authSession], or rotate a stored
     * refresh token when there is no access token yet.
     *
     * **It never opens a surface on its own.** The router stack is the authority
     * for what is up: the boot has already replayed the saved stack and restored
     * the session it named, so entering the hub here (as the sign-in path does)
     * would drop the game the player was in. Only a pending account-setup prompt
     * is raised, and only while no session owns the display
     * ([View.isEntrySurfaceFree]); otherwise presence comes up behind the board.
     *
     * With no credential at all the opposite holds: it *closes* the one surface
     * that cannot stand. The Online hub is signed-in only, so a hub replayed from
     * the saved stack comes down and the main menu is the landing.
     */
    fun restoreSession() {
        val restoredToken = authSession.token
        if (authSession.isSignedIn && restoredToken != null) {
            if (view.isEntrySurfaceFree()) {
                if (beginFirstLoginAliasIfNeeded()) return
                if (beginStartingLevelIfNeeded()) return
            }
            startPresence(restoredToken)
            return
        }
        if (!authSession.refreshToken.isNullOrBlank()) {
            beginRefresh()
            return
        }
        // Neither token exists, so nothing will sign this session in: a hub the
        // boot restored has no account behind it and must not stay up.
        view.hidePresence()
    }

    /**
     * Re-reads the signed-in account (`GET /me`).
     *
     * The account payload is the **only** writer of the cached rating. A
     * settlement tells the client what this account was after *that* match, and
     * presence replays the last settlement the server still remembers — which
     * may be days stale — so neither can be written here. What a settlement
     * establishes is that the account *may* have moved, not what it moved to;
     * this read is the answer.
     *
     * Failures leave the last known value in place.
     */
    fun refreshAccount() {
        val token = authSession.token ?: return
        val generation = ++profileGeneration
        profileRequester(token) { result ->
            if (generation != profileGeneration) return@profileRequester
            result.fold(
                onSuccess = { user ->
                    if (authSession.token == token) authSession.updateUser(user)
                },
                onFailure = { err ->
                    if ((err as? AuthException)?.unauthorized == true) onUnauthorized(token)
                },
            )
        }
    }

    /**
     * Reloads [refreshAccount] plus the notification settings, the other surface
     * that belongs to the account.
     *
     * Called wherever presence starts or the access token rotates. A settlement
     * asks for [refreshAccount] alone — it moves the account, not the prefs.
     */
    fun refreshAccountSurfaces() {
        refreshAccount()
        val token = authSession.token ?: return
        // Notification prefs are an account surface too: load them wherever the
        // others load, so a restored Settings card shows the server's values
        // rather than the client defaults. The current locale and the device
        // zone ride the read, so the server learns both without the player
        // opening Settings. A stored value that already matches sends nothing,
        // and a device that cannot name its zone leaves the stored one.
        notificationPrefs?.refresh(token, L10n.currentLocale.tag, PlatformTimeZone.id())
    }

    /**
     * Start the Google challenge → ID token → exchange flow.
     *
     * Calling this while an attempt is already in flight restarts it. The browser
     * tab or system sheet that attempt opened can be gone — closed, lost behind
     * other windows, or swept away while the app was in the background — and the
     * menu badge that starts sign-in is the player's only way back into the flow.
     *
     * The superseded attempt is dropped first: its callbacks are invalidated and
     * its platform request cancelled, so a tab finished afterwards cannot deliver
     * a credential this sign-in never asked for.
     */
    fun beginGoogleSignIn() {
        if (lifecycle == AuthLifecycle.SigningIn) {
            logLifecycle?.invoke("google sign-in restart")
            abandonSignIn()
        }
        if (lifecycle != AuthLifecycle.SignedOut) return
        // A special-key field left open is a separate attempt; the menu row it
        // was opened from is behind it, so this only happens if the two methods
        // are driven programmatically.
        view.hideSpecialKey()
        lastAuthError = null
        if (!googleSignInAvailable) {
            lastAuthError = AuthError(
                message = "Google Sign-In is not available on this platform",
                retryable = false,
            )
            logLifecycle?.invoke("google sign-in unavailable")
            return
        }
        val verifier = GoogleVerifier.generate()
        val hash = GoogleVerifier.sha256Hex(verifier)
        val clientId = googleAuth.clientId
        lifecycle = AuthLifecycle.SigningIn
        googleSignInStep = GoogleSignInStep.Challenging
        val gen = bumpAuth()
        logLifecycle?.invoke("google sign-in start")
        googleChallengeRequester(clientId, hash) { result ->
            if (gen != authGeneration) return@googleChallengeRequester
            result.fold(
                onSuccess = { challenge -> requestGoogleIdToken(gen, verifier, challenge.nonce) },
                onFailure = { failGoogleSignIn(it) },
            )
        }
    }

    /**
     * Open the special-key field for the Google Play review account.
     *
     * This is a prompt, not an attempt: [lifecycle] stays [AuthLifecycle.SignedOut]
     * until the player submits a key, so the menu badge behind the card does not
     * claim a sign-in is running while the field is empty.
     */
    fun beginSpecialKeySignIn() {
        if (lifecycle == AuthLifecycle.SigningIn) {
            logLifecycle?.invoke("special-key sign-in supersedes an in-flight attempt")
            abandonSignIn()
        }
        if (lifecycle != AuthLifecycle.SignedOut) return
        lastAuthError = null
        // Reopening is a fresh entry: hide first so the shared card is seeded
        // empty even when its route was already up.
        view.hideSpecialKey()
        view.showSpecialKey()
    }

    /** Submit the open special-key field. */
    fun submitSpecialKey() {
        if (lifecycle != AuthLifecycle.SignedOut) return
        if (!view.isSpecialKeyVisible || view.isSpecialKeyBusy) return
        val key = view.specialKeyText.trim()
        if (key.isEmpty()) {
            view.setSpecialKeyError(L10n.get("special_key.error_required"))
            return
        }
        lastAuthError = null
        lifecycle = AuthLifecycle.SigningIn
        googleSignInStep = GoogleSignInStep.Idle
        specialKeyInFlight = true
        view.setSpecialKeyBusy(true)
        val gen = bumpAuth()
        logLifecycle?.invoke("special-key sign-in start")
        specialKeyRequester(key) { result ->
            if (gen != authGeneration) return@specialKeyRequester
            specialKeyInFlight = false
            result.fold(
                onSuccess = { signed ->
                    view.hideSpecialKey()
                    completeSignIn(signed)
                },
                onFailure = { err ->
                    lifecycle = AuthLifecycle.SignedOut
                    googleSignInStep = GoogleSignInStep.Idle
                    // The card is the surface that reports this failure. It
                    // stays open with the message, so the badge behind it must
                    // not switch to the menu's "log in again" state.
                    view.setSpecialKeyError(specialKeyErrorMessage(err))
                    logLifecycle?.invoke("special-key sign-in failed")
                },
            )
        }
    }

    /** Close the special-key field, invalidating a request that is still in flight. */
    fun cancelSpecialKeySignIn() {
        if (specialKeyInFlight) {
            bumpAuth()
            specialKeyInFlight = false
            lifecycle = AuthLifecycle.SignedOut
            googleSignInStep = GoogleSignInStep.Idle
        }
        view.hideSpecialKey()
        logLifecycle?.invoke("special-key sign-in cancelled")
    }

    /**
     * Drops an in-flight attempt so nothing it still owes can land. Both sign-in
     * methods share the invalidation: whichever one supersedes the other, a late
     * callback cannot deliver a credential the new attempt did not ask for.
     */
    private fun abandonSignIn() {
        bumpAuth()
        googleAuth.cancel()
        specialKeyInFlight = false
        lifecycle = AuthLifecycle.SignedOut
        googleSignInStep = GoogleSignInStep.Idle
    }

    private fun specialKeyErrorMessage(err: Throwable): String {
        val auth = err as? AuthException
        return when {
            auth?.rateLimited == true -> L10n.get("special_key.error_rate_limited")
            auth?.unavailable == true -> L10n.get("special_key.error_unavailable")
            auth?.unauthorized == true -> L10n.get("special_key.error_invalid")
            else -> err.message ?: L10n.get("special_key.error_generic")
        }
    }

    /**
     * Logs out the active user: revokes the server session, leaves presence,
     * drops online sockets, clears credentials, and cancels in-flight auth.
     */
    fun logOut() {
        val token = authSession.token
        bumpAuth()
        googleAuth.cancel()
        logLifecycle?.invoke("presence stop logout")
        presenceClient.stopAndLeave(token)
        // The matchmaking selection is account-scoped (it rides the queue request
        // under this account's token); the settings-drawer state is display state
        // and is kept, exactly as the hub's Shadow Ranked draft is.
        presenceClient.resetFindSelection()
        matchHistory?.clear()
        shadowRankedAttempts?.clear()
        shadowRankedHands?.clear()
        shadowRankedStarter?.reset()
        noticeList?.clear()
        notificationPrefs?.clear()
        authSession.signOut()
        lifecycle = AuthLifecycle.SignedOut
        refreshRetryAtMs = null
        retriedUnderToken = null
        googleSignInStep = GoogleSignInStep.Idle
        specialKeyInFlight = false
        awaitingFirstLoginAlias = false
        awaitingStartingLevel = false
        lastAuthError = null
        view.hideFirstLoginAlias()
        view.hideSpecialKey()
        view.hideStartingLevel()
        view.cancelAliasEdit()
        view.hidePresence()
        onLogOut()
        // Local Google state only — does not sign the user out of Google globally.
        googleAuth.clearCredentialState()
        if (!token.isNullOrBlank()) {
            logoutRequester(token) { /* best-effort; local state is already cleared */ }
        }
    }

    /**
     * Handles an unauthorized (HTTP 401) rejection.
     *
     * [usedToken] is the access token the refused request presented, when its
     * caller knows it. A refusal of a credential this client has **already
     * replaced** is not a session failure: the request outlived the rotation that
     * swapped its token, and re-reading under the token now held is the whole
     * recovery. Rotating there would consume a second refresh token for nothing
     * and race the refresh that had just landed — the log's `6c → e9`, in-flight
     * revalidation `401`, `e9 → 269` sequence.
     *
     * A refusal of the token still held is the real expiry: it joins
     * [refreshGate]'s refresh, or starts one. The gate owns the dedupe, so
     * concurrent 401s from different surfaces coalesce into one request and every
     * caller waits for its result rather than the late ones returning early.
     */
    fun onUnauthorized(usedToken: String? = null) {
        val held = authSession.token
        if (usedToken != null && held != null && usedToken != held) {
            logLifecycle?.invoke(
                "401 on token=${AuthSession.tokenId(usedToken)} (held ${AuthSession.tokenId(held)}) → retry, no rotation",
            )
            // A refresh already running will reload everything when it lands, and
            // re-reading under a token it is about to replace would only 401 again.
            if (refreshGate.inFlight) {
                beginRefresh()
                return
            }
            if (retriedUnderToken != held) {
                retriedUnderToken = held
                retryUnderHeldToken(held)
            }
            return
        }
        val refresh = authSession.refreshToken
        if (refresh.isNullOrBlank()) {
            logLifecycle?.invoke("401 → signOut")
            expireSession(hidePresence = true)
            return
        }
        logLifecycle?.invoke("401 → refresh")
        beginRefresh()
    }

    /**
     * Re-read what a refused request covered under a token that is already
     * current: the same surface reloads a completed rotation performs, without
     * rotating. Each reload is synchronous and lands after the failing client has
     * recorded its own error, so the fresh read is what the surface keeps.
     *
     * Writes (an alias or starting-level edit) are not replayed here; only the
     * reads a replacement token is for are.
     */
    private fun retryUnderHeldToken(token: String) {
        refreshRetryAtMs = null
        shadowRankedAttempts?.load(token)
        shadowRankedHands?.reload(token)
        onSessionEstablished()
        if (presenceClient.signedIn) {
            presenceClient.replaceToken(token)
        } else {
            presenceClient.start(token)
        }
        presenceClient.requestNow()
        refreshAccountSurfaces()
    }

    /**
     * Drops the cached session. [hidePresence] false keeps Online home visible
     * so a Sign-in-to-play state can replace the signed-in card in place.
     */
    fun expireSession(hidePresence: Boolean = true) {
        bumpAuth()
        googleAuth.cancel()
        refreshRetryAtMs = null
        retriedUnderToken = null
        lifecycle = AuthLifecycle.SignedOut
        googleSignInStep = GoogleSignInStep.Idle
        specialKeyInFlight = false
        awaitingFirstLoginAlias = false
        presenceClient.stop()
        matchHistory?.clear()
        shadowRankedAttempts?.clear()
        shadowRankedHands?.clear()
        noticeList?.clear()
        notificationPrefs?.clear()
        authSession.signOut()
        view.hideFirstLoginAlias()
        view.hideSpecialKey()
        view.cancelAliasEdit()
        // Before the hub is told to go, so anything the drop implies is settled while
        // [View.hidePresence] still gets to decide the landing. Hiding the hub first
        // would no-op behind a context no session can adopt and leave it stranded up.
        onSessionDropped()
        if (hidePresence) view.hidePresence()
    }

    /**
     * Refresh the access JWT before [exp] so heartbeats and ticket requests
     * do not 401, and present a token again after a refresh failed in transport.
     * No-op when the access token is not a JWT (test fixtures) or no refresh
     * token is stored. Driven from the render loop.
     *
     * Does not touch an open match socket: reconnect later reads the live
     * access token for a fresh ticket.
     */
    fun tick(nowMs: Long = clock()) {
        // The present loop and the one-second presence clock both call this.
        // An idle picture no longer presents, so the clock has to be able to
        // refresh the access token on its own.
        synchronized(this) {
            val retrying = refreshRetryAtMs != null
            // A retry is owed even where there is no live session to keep: a
            // restored one has no access token yet, and a failed transport is
            // exactly the state a boot refresh leaves behind.
            if (!retrying && lifecycle != AuthLifecycle.SignedIn) return
            if (authSession.refreshToken.isNullOrBlank()) return
            if (!retrying) {
                val access = authSession.token ?: return
                val exp = AccessJwt.expiresAtEpochSeconds(access) ?: return
                if (exp * 1000L - nowMs > REFRESH_SKEW_MS) return
            }
            beginRefresh()
        }
    }

    private fun beginRefresh() {
        val refresh = authSession.refreshToken
        if (refresh.isNullOrBlank()) {
            logLifecycle?.invoke("refresh: no stored token, signing out")
            expireSession()
            return
        }
        // A refresh that failed in transport is presented again only after its
        // cooldown: the same request would fail the same way, and the present loop
        // asks every frame.
        val notBefore = refreshRetryAtMs
        if (notBefore != null && clock() < notBefore) return
        // Joining a refresh already in flight only waits for its result: the
        // starter owns the lifecycle transition, and [onRefreshResult] applies
        // that one result once.
        if (refreshGate.inFlight) {
            refreshGate.request(refresh) { result -> onRefreshResult(result) }
            return
        }
        logLifecycle?.invoke("refresh: presenting token=${AuthSession.tokenId(refresh)}")
        lifecycle = AuthLifecycle.Refreshing
        refreshGeneration = bumpAuth()
        refreshGate.request(refresh) { result -> onRefreshResult(result) }
    }

    private fun onRefreshResult(result: Result<AuthLogin>) {
        if (refreshGeneration != authGeneration) return
        if (lifecycle != AuthLifecycle.Refreshing) return
        result.fold(
            onSuccess = { signed ->
                applyRefreshedSession(signed)
                if (!awaitingFirstLoginAlias) {
                    presenceClient.requestNow()
                    refreshAccountSurfaces()
                }
                logLifecycle?.invoke("refresh ok user=${signed.user.id}")
            },
            onFailure = { error ->
                val cause = error.message ?: error::class.java.simpleName
                if ((error as? AuthException)?.unauthorized == true) {
                    // The server read the token and refused it. There is nothing to
                    // retry: the stored session is spent.
                    logLifecycle?.invoke("refresh rejected ($cause) → signOut")
                    expireSession()
                } else {
                    // A dropped packet, a timeout or a 5xx says nothing about the
                    // stored session, and the access token it was protecting is
                    // usually still inside its own expiry. Clearing the key here
                    // signs the player out over one bad response and throws away a
                    // durable token, so the session is kept: the lifecycle returns
                    // to what it was before the attempt, and the clock presents the
                    // token again once the cooldown is out (see [beginRefresh]).
                    lifecycle =
                        if (authSession.isSignedIn) AuthLifecycle.SignedIn else AuthLifecycle.SignedOut
                    refreshRetryAtMs = clock() + REFRESH_RETRY_MS
                    // Still no access token: the rotation that would have signed
                    // this boot in failed, so a hub restored from the stack has no
                    // account behind it and comes down.
                    if (!authSession.isSignedIn) view.hidePresence()
                    logLifecycle?.invoke(
                        "refresh failed ($cause) → session kept, retry in ${REFRESH_RETRY_MS / 1000}s",
                    )
                }
            },
        )
    }

    private fun requestGoogleIdToken(gen: Int, verifier: String, nonce: String) {
        if (gen != authGeneration) return
        googleSignInStep = GoogleSignInStep.AwaitingGoogle
        googleAuth.requestIdToken(nonce) { idResult ->
            if (gen != authGeneration) return@requestIdToken
            idResult.fold(
                onSuccess = { idToken -> exchangeGoogle(gen, verifier, idToken) },
                onFailure = { failGoogleSignIn(it) },
            )
        }
    }

    private fun exchangeGoogle(gen: Int, verifier: String, idToken: String) {
        if (gen != authGeneration) return
        googleSignInStep = GoogleSignInStep.Exchanging
        googleExchangeRequester(verifier, idToken) { result ->
            if (gen != authGeneration) return@googleExchangeRequester
            result.fold(
                onSuccess = { signed -> completeSignIn(signed) },
                onFailure = { failGoogleSignIn(it) },
            )
        }
    }

    private fun completeSignIn(signed: AuthLogin) {
        googleSignInStep = GoogleSignInStep.Idle
        specialKeyInFlight = false
        lastAuthError = null
        refreshRetryAtMs = null
        retriedUnderToken = null
        authSession.signIn(signed)
        val token = authSession.token
        if (token == null) {
            lifecycle = AuthLifecycle.SignedOut
            return
        }
        if (beginFirstLoginAliasIfNeeded()) return
        if (beginStartingLevelIfNeeded()) return
        enterLobby(token)
    }

    /**
     * Keep the generated `user-<tag>` alias without changing it: mark the
     * prompt dismissed locally in the `auth.firstLogin` key, then enter the hub.
     * No network request is sent; [alias_customized_at] remains null on the server.
     */
    fun confirmFirstLoginAlias() {
        if (!awaitingFirstLoginAlias || view.isFirstLoginAliasBusy) return
        val user = authSession.user ?: return
        val token = authSession.token ?: return
        dismissFirstLoginPrompt(user.id)
        awaitingFirstLoginAlias = false
        view.hideFirstLoginAlias()
        if (beginStartingLevelIfNeeded()) return
        enterLobby(token)
    }

    /**
     * Dismiss the first-login custom alias prompt by choosing to use the
     * random generated alias without sending network requests.
     */
    fun cancelFirstLoginAlias() {
        if (!awaitingFirstLoginAlias || view.isFirstLoginAliasBusy) return
        confirmFirstLoginAlias()
    }

    /** Submit a custom alias from the first-login card, then enter the hub. */
    fun submitFirstLoginAlias() {
        if (!awaitingFirstLoginAlias || view.isFirstLoginAliasBusy) return
        val name = view.firstLoginAliasText.trim()
        if (name.isEmpty()) {
            view.setFirstLoginAliasError(L10n.get("alias.error_required"))
            return
        }
        if (name == authSession.user?.displayName?.trim()) {
            confirmFirstLoginAlias()
            return
        }
        if (!AliasPolicy.meetsMinimum(name)) {
            view.setFirstLoginAliasError(L10n.get("alias.error_too_short"))
            return
        }
        if (!AliasPolicy.withinLimits(name)) {
            view.setFirstLoginAliasError(L10n.get("alias.error_too_long"))
            return
        }
        patchFirstLoginAlias(name)
    }

    /** Open the account display-name editor with the current display name. */
    fun beginAliasEdit() {
        if (!isSignedIn || awaitingFirstLoginAlias) return
        if (view.isAliasEditBusy) return
        view.showAliasEdit(authSession.user?.displayName.orEmpty())
    }

    fun cancelAliasEdit() {
        if (view.isAliasEditBusy) return
        view.cancelAliasEdit()
    }

    /**
     * Save a new alias from the display-name editor via `POST /me`. Stays signed in.
     * Unchanged names close the editor without a request.
     */
    fun submitAliasEdit() {
        if (!isSignedIn || awaitingFirstLoginAlias) return
        if (!view.isAliasEditVisible || view.isAliasEditBusy) return
        val name = view.aliasEditText.trim()
        if (name.isEmpty()) {
            view.setAliasEditError(L10n.get("alias.error_required"))
            return
        }
        if (name == authSession.user?.displayName?.trim()) {
            view.cancelAliasEdit()
            return
        }
        if (!AliasPolicy.meetsMinimum(name)) {
            view.setAliasEditError(L10n.get("alias.error_too_short"))
            return
        }
        if (!AliasPolicy.withinLimits(name)) {
            view.setAliasEditError(L10n.get("alias.error_too_long"))
            return
        }
        val token = authSession.token ?: return
        view.setAliasEditBusy(true)
        val gen = authGeneration
        aliasUpdateRequester(token, name) { result ->
            if (gen != authGeneration) return@aliasUpdateRequester
            result.fold(
                onSuccess = { user ->
                    authSession.updateUser(user)
                    view.cancelAliasEdit()
                    logLifecycle?.invoke("alias updated user=${user.id}")
                },
                onFailure = { err ->
                    if ((err as? AuthException)?.unauthorized == true) {
                        onUnauthorized(token)
                        return@fold
                    }
                    view.setAliasEditError(
                        err.message ?: L10n.get("alias.error_generic"),
                    )
                },
            )
        }
    }

    private fun patchFirstLoginAlias(displayName: String) {
        val token = authSession.token ?: return
        view.setFirstLoginAliasBusy(true)
        val gen = authGeneration
        aliasUpdateRequester(token, displayName) { result ->
            if (gen != authGeneration) return@aliasUpdateRequester
            if (!awaitingFirstLoginAlias) return@aliasUpdateRequester
            result.fold(
                onSuccess = { user ->
                    dismissFirstLoginPrompt(user.id)
                    authSession.updateUser(user)
                    awaitingFirstLoginAlias = false
                    view.hideFirstLoginAlias()
                    if (beginStartingLevelIfNeeded()) return@fold
                    enterLobby(token)
                },
                onFailure = { err ->
                    if ((err as? AuthException)?.unauthorized == true) {
                        onUnauthorized(token)
                        return@fold
                    }
                    view.setFirstLoginAliasError(
                        err.message ?: L10n.get("alias.error_generic"),
                    )
                },
            )
        }
    }

    /**
     * @return true when the first-login alias prompt is now showing (hub
     *         delayed until Save / Cancel).
     */
    private fun beginFirstLoginAliasIfNeeded(): Boolean {
        val user = authSession.user ?: return false
        if (!user.needsFirstLoginAlias()) return false
        if (user.id in firstLoginPrompts.requireValue()) return false
        // A live session screen owns the display; account setup waits for a
        // screen the account may cover.
        if (!view.isEntrySurfaceFree()) return false
        awaitingFirstLoginAlias = true
        lifecycle = AuthLifecycle.SignedIn
        googleSignInStep = GoogleSignInStep.Idle
        view.hidePresence()
        // The alias prompt is above the menu in the stack; a menu left up from
        // cold start would swallow its input (a `Below` chrome card never needs its
        // surface raised first).
        view.hideMenu()
        view.showFirstLoginAlias(user.displayName)
        logLifecycle?.invoke("first-login alias prompt user=${user.id}")
        return true
    }

    /**
     * @return true when the starting-level prompt is now showing (hub
     *         delayed until a level is picked).
     */
    private fun beginStartingLevelIfNeeded(): Boolean {
        val user = authSession.user ?: return false
        if (!user.needsStartingLevel()) return false
        // A live session screen owns the display; account setup waits for a
        // screen the account may cover.
        if (!view.isEntrySurfaceFree()) return false
        awaitingStartingLevel = true
        lifecycle = AuthLifecycle.SignedIn
        googleSignInStep = GoogleSignInStep.Idle
        view.hidePresence()
        view.hideMenu()
        view.showStartingLevel()
        logLifecycle?.invoke("starting level prompt user=${user.id}")
        return true
    }

    /** Surface starting level selection (e.g. when Find game is clicked while unset). */
    fun showStartingLevelPrompt() {
        if (!isSignedIn) return
        val user = authSession.user ?: return
        if (!user.needsStartingLevel()) return
        awaitingStartingLevel = true
        view.hideMenu()
        view.showStartingLevel()
        logLifecycle?.invoke("starting level prompt user=${user.id}")
    }

    /**
     * Submits the chosen starting level to `POST /me/starting-level`.
     * On success, updates user Elo and profile, dismisses overlay, and enters the hub.
     */
    fun setStartingLevel(level: String) {
        if (!awaitingStartingLevel || view.isStartingLevelBusy) return
        val token = authSession.token ?: return
        view.setStartingLevelBusy(true)
        val gen = authGeneration
        startingLevelRequester(token, level) { result ->
            if (gen != authGeneration) return@startingLevelRequester
            if (!awaitingStartingLevel) return@startingLevelRequester
            result.fold(
                onSuccess = { user ->
                    view.hideStartingLevel()
                    awaitingStartingLevel = false
                    authSession.updateUser(user)
                    enterLobby(token)
                    logLifecycle?.invoke("starting level set user=${user.id} level=$level rating=${user.eloRating}")
                },
                onFailure = { err ->
                    if ((err as? AuthException)?.unauthorized == true) {
                        onUnauthorized(token)
                        return@fold
                    }
                    view.setStartingLevelError(
                        err.message ?: L10n.get("starting_level.error_generic"),
                    )
                },
            )
        }
    }

    /** Mark the first-login prompt answered for [userId] (idempotent). */
    private fun dismissFirstLoginPrompt(userId: String) {
        if (userId.isBlank()) return
        val dismissed = firstLoginPrompts.requireValue()
        if (userId in dismissed) return
        firstLoginPrompts.set(dismissed + userId)
    }

    /**
     * Signed in, account setup answered: Online Home is the landing, so it
     * replaces the menu (never both up in the stack). Also the sign-in path's
     * landing; a cold start does not call it ([restoreSession]).
     */
    private fun enterLobby(token: String) {
        awaitingFirstLoginAlias = false
        view.hideFirstLoginAlias()
        startPresence(token)
        view.hideMenu()
        view.showPresence()
    }

    /** Start presence for [token] and refresh the account surfaces it feeds. */
    private fun startPresence(token: String) {
        enterSignedIn(token)
        logLifecycle?.invoke("presence start user=${authSession.user?.id}")
        onSessionEstablished()
        refreshAccountSurfaces()
    }

    private fun failGoogleSignIn(err: Throwable) {
        bumpAuth()
        lifecycle = AuthLifecycle.SignedOut
        googleSignInStep = GoogleSignInStep.Idle
        if (err is GoogleSignInCancelled) {
            lastAuthError = null
            logLifecycle?.invoke("google sign-in cancelled")
            return
        }
        lastAuthError = toAuthError(err)
        logLifecycle?.invoke("google sign-in failed retryable=${lastAuthError?.retryable}")
    }

    private fun toAuthError(err: Throwable): AuthError {
        val retryable = when (err) {
            is GoogleSignInException -> err.retryable
            is AuthException -> err.rateLimited || err.cause != null
            is IllegalStateException -> false
            else -> true
        }
        return AuthError(
            message = err.message ?: "Google sign-in failed",
            retryable = retryable,
        )
    }

    private fun enterSignedIn(token: String) {
        lifecycle = AuthLifecycle.SignedIn
        googleSignInStep = GoogleSignInStep.Idle
        presenceClient.start(token)
    }

    /** Apply a rotated session. Keep a live presence loop in place. */
    private fun applyRefreshedSession(signed: AuthLogin) {
        refreshRetryAtMs = null
        retriedUnderToken = null
        authSession.applyRefreshed(signed)
        val token = signed.token
        // A rotated token is what the attempts list loads under: the card may be
        // up while a refresh lands, and nothing else repopulates it.
        shadowRankedAttempts?.load(token)
        // The hand tables may be up while a cold-start refresh lands; re-issue the
        // document the restored modal asked for under the new token.
        shadowRankedHands?.reload(token)
        // A card restored over a cold start came up before this token existed, so
        // whatever it shows was fetched without one. The effects layer owns which
        // surfaces are up and reloads them.
        onSessionEstablished()
        lifecycle = AuthLifecycle.SignedIn
        if (awaitingFirstLoginAlias || awaitingStartingLevel) return
        if (beginFirstLoginAliasIfNeeded()) return
        if (beginStartingLevelIfNeeded()) return
        if (presenceClient.signedIn) {
            presenceClient.replaceToken(token)
        } else {
            presenceClient.start(token)
        }
    }

    private fun bumpAuth(): Int {
        authGeneration += 1
        return authGeneration
    }

    companion object {
        /** Refresh this far before JWT `exp` so heartbeats never see 401. */
        const val REFRESH_SKEW_MS = 90_000L

        /**
         * How long a refresh that failed in transport waits before the stored token
         * is presented again. Long enough that a blip does not become a request per
         * frame, short enough that a player whose network came back is signed in
         * again well before they notice.
         */
        const val REFRESH_RETRY_MS = 30_000L
    }
}
