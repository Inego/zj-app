package inego.zungjunggame.app

import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.i18n.AppLocale
import inego.zungjunggame.net.NotificationPrefs
import inego.zungjunggame.persist.AuthPersistedSession
import inego.zungjunggame.persist.FindGamePreferences
import inego.zungjunggame.persist.RememberedRouteUi
import inego.zungjunggame.persist.SessionSnapshot
import inego.zungjunggame.persist.TutorialSessionSnapshot
import inego.zungjunggame.persist.UiPreferences
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.tutorial.TutorialProgressState
import inego.zungjunggame.ui.stack.StackState

/**
 * Every persisted artifact a cold start decides from, in one value.
 *
 * The store keeps one versioned key per artifact and each artifact's owner reads
 * and writes that key. The boot is the only place that has to look at several
 * keys together, so it does so **once**: [AppStateSource.read] composes this
 * value and [AppGraph] replays it in one order (stack → session host → account
 * surfaces) instead of letting every owner probe the store as it is built.
 *
 * A key whose owner reads it lazily on first use — the find-game preferences
 * ([FindGamePreferences]), the tutorial campaign progress
 * ([TutorialProgressState]), the last server-confirmed notification settings
 * (`notification.prefs`), and the last live table (`online.table`, which only
 * [inego.zungjunggame.online.OnlineCoordinator] reads) — is deliberately absent: the
 * boot makes no decision from it, so reading it here would be a second source rather
 * than a replay.
 */
data class AppState(
    /** The route stack, each entry carrying its component's persisted state. */
    val stack: StackState,
    /** Free-play board + scores (`session.freeplay`), null when none is saved. */
    val session: SessionSnapshot?,
    /** Mid-lesson board + director progress (`session.tutorial`), null when none. */
    val tutorialSession: TutorialSessionSnapshot?,
    /** Signed-in account with its refresh token (`auth.session`), null when signed out. */
    val account: AuthPersistedSession?,
    /** Last committed New Game config (`game.prefs`). */
    val game: GameConfig,
    /** Display preferences (`ui.prefs`). */
    val ui: UiPreferences,
    /** Active language tag (`ui.locale`). */
    val locale: AppLocale,
)

/**
 * The typed handles for every persisted artifact of the app, and the boot's
 * single read.
 *
 * One [StateSlot] per key, handed to the model that owns the value
 * ([StateSlot]'s narrow-dependency rule); [read] is the cold start's one pass
 * over them.
 */
class AppStateSource(
    /** Route stack with component state; written by [AppRuntime] on pause/exit. */
    val stack: StateSlot<StackState>,
    /** Free-play board + scores. */
    val session: StateSlot<SessionSnapshot>,
    /** Mid-lesson tutorial board + director progress. */
    val tutorialSession: StateSlot<TutorialSessionSnapshot>,
    /** Signed-in account (secret: the store seals it). */
    val account: StateSlot<AuthPersistedSession>,
    /** Last live online table id. */
    val onlineTableId: StateSlot<String>,
    /** Last committed New Game config. */
    val game: StateSlot<GameConfig>,
    /** Display preferences. */
    val ui: StateSlot<UiPreferences>,
    /** Active language tag. */
    val locale: StateSlot<AppLocale>,
    /** Find-game preferences; read lazily by the presence client. */
    val find: StateSlot<FindGamePreferences>,
    /** Tutorial campaign progress; read lazily by the tutorial host. */
    val tutorialProgress: StateSlot<TutorialProgressState>,
    /** Users whose first-login prompt was answered; read lazily by the account. */
    val firstLoginPrompts: StateSlot<Set<String>>,
    /** Remembered route UI state; read lazily by the route UI store. */
    val remembered: StateSlot<RememberedRouteUi>,
    /** Last server-confirmed notification settings; read lazily by the prefs client. */
    val notificationPrefs: StateSlot<NotificationPrefs>,
) {
    /** The cold start's one read: see [AppState]. */
    fun read(): AppState = AppState(
        stack = stack.requireValue(),
        session = session.get(),
        tutorialSession = tutorialSession.get(),
        account = account.get(),
        game = game.requireValue(),
        ui = ui.requireValue(),
        locale = locale.requireValue(),
    )
}
