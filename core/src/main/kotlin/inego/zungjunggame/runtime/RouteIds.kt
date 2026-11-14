package inego.zungjunggame.runtime

import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.idOrNull

/** Canonical ids for every route kind; the one place route strings are spelled. */
object RouteIds {
    // The floor: the root screen renders the main menu.
    const val ROOT_MENU = "root_menu"

    // Context layers above the floor: the local pair, the online pair, and the
    // tutorial shell, whose lesson/quiz/coach are content layers inside it.
    const val LOCAL_MENU = "local_menu"
    const val LOCAL_BOARD = "local_board"
    const val ONLINE_HUB = "online_hub"
    const val ONLINE_TABLE = "online_table"
    const val TUTORIAL_SHELL = "tutorial_shell"

    // Modals
    const val CONFIRM = "confirm"
    const val GAME_MENU = "game_menu"
    const val LANGUAGE_PICKER = "language_picker"
    const val SETTINGS = "settings"
    /** The player's account panel: display name, Change name, Delete account. */
    const val ACCOUNT = "account"
    const val NEW_GAME_SETUP = "new_game_setup"
    const val SCORE_TABLE = "score_table"
    const val MATCH_HISTORY = "match_history"
    const val LEADERBOARD = "leaderboard"
    const val RATING_HISTORY = "rating_history"
    const val SHADOW_RANKED_ATTEMPTS = "shadow_ranked_attempts"
    const val SHADOW_RANKED_HANDS = "shadow_ranked_hands"
    const val NOTICES = "notices"
    const val NOTICE_POPUP = "notice_popup"
    const val FIRST_LOGIN_ALIAS = "first_login_alias"
    const val CHANGE_DISPLAY_NAME = "change_display_name"
    const val SPECIAL_KEY = "special_key"
    const val STARTING_LEVEL = "starting_level"
    const val ONLINE_CONNECT = "online_connect"

    // Overlays / tools
    const val REFERENCE = "reference"
    const val SCORE_BREAKDOWN = "score_breakdown"
    const val AWAY = "away"
    const val HINT_BUTTON = "hint_button"

    // Tutorial presentation (the shell's lesson board, quiz, and coach)
    const val TUTORIAL_LESSON = "tutorial_lesson"
    const val TUTORIAL_COACH = "tutorial_coach"
    const val TUTORIAL_QUIZ = "tutorial_quiz"

    /** Ordered catalog of every id, grouped by kind; the exhaustiveness-test source of truth. */
    val all: List<String> = listOf(
        // The floor
        ROOT_MENU,
        // Context layers
        LOCAL_MENU,
        LOCAL_BOARD,
        ONLINE_HUB,
        ONLINE_TABLE,
        TUTORIAL_SHELL,
        // Modals
        CONFIRM,
        GAME_MENU,
        LANGUAGE_PICKER,
        SETTINGS,
        ACCOUNT,
        NEW_GAME_SETUP,
        SCORE_TABLE,
        MATCH_HISTORY,
        LEADERBOARD,
        RATING_HISTORY,
        SHADOW_RANKED_ATTEMPTS,
        SHADOW_RANKED_HANDS,
        NOTICES,
        NOTICE_POPUP,
        FIRST_LOGIN_ALIAS,
        CHANGE_DISPLAY_NAME,
        SPECIAL_KEY,
        STARTING_LEVEL,
        ONLINE_CONNECT,
        // Overlays / tools
        REFERENCE,
        SCORE_BREAKDOWN,
        AWAY,
        HINT_BUTTON,
        // Tutorial presentation
        TUTORIAL_LESSON,
        TUTORIAL_COACH,
        TUTORIAL_QUIZ,
    )

    /**
     * The catalog id a route instance corresponds to; the reference browser is the
     * one route whose id is not its own ([REFERENCE]).
     */
    fun catalogIdOf(route: Route): String = route.idOrNull ?: REFERENCE
}
