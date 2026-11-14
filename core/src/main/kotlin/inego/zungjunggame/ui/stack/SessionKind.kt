package inego.zungjunggame.ui.stack

/**
 * The context the navigation stack is in.
 *
 * Derived, never set: [SessionRegistry][inego.zungjunggame.session.SessionRegistry]
 * reads the topmost context layer's declared kind off the stack, and no context
 * layer means [Menu]. It is declared here, with the stack model that produces it,
 * because it is a fact about *where the player is*; the policy that hangs off it
 * (which context may write the free-play save, which may open the score table)
 * lives with the session domain in `session/SessionKindPolicy.kt`.
 */
enum class SessionKind {
    /** Idle main menu / no free-play or tutorial hand in progress. */
    Menu,

    /** Multi-hand free play via `GameSession`; owns the `session.freeplay` key persist. */
    FreePlay,

    /** Scripted lesson via `TutorialRunner`; mid-lesson via the `session.tutorial` key. */
    Tutorial,

    /** Online multiplayer game via `OnlinePlayHost`. */
    Online,
}
