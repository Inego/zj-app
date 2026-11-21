package inego.zungjunggame.runtime

import inego.zungjunggame.features.local.SetupCardCodec
import inego.zungjunggame.features.menu.GameMenuCardCodec
import inego.zungjunggame.features.online.hub.OnlineHubCodec
import inego.zungjunggame.features.ratinghistory.RatingHistoryCodec
import inego.zungjunggame.features.tutorial.TutorialShellCodec
import inego.zungjunggame.persist.ReferenceNavCodec
import inego.zungjunggame.ui.RouteScrollCodec
import inego.zungjunggame.ui.RouteStateCodec
import inego.zungjunggame.ui.stack.ContentPolicy
import inego.zungjunggame.ui.stack.LayerPolicy
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.LayerSpec
import inego.zungjunggame.ui.stack.Presentation
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.SessionKind

/** The single declaration of each route's layer behavior. */
object RouteSpecs {
    /**
     * Modals that take the generic **card** shape and are dismissed by back: a
     * press that misses every control inside one is that card's own outside press.
     *
     * The rating chart is dismissable too, but it is declared below rather than
     * here: it is a full-screen surface, so it differs in presentation and in what
     * it leaves on the felt, not only in being dismissable.
     */
    private val DISMISSABLE_MODALS = setOf(
        RouteIds.CONFIRM,
        RouteIds.SCORE_TABLE,
        RouteIds.MATCH_HISTORY,
        RouteIds.LEADERBOARD,
        RouteIds.SHADOW_RANKED_ATTEMPTS,
        RouteIds.SHADOW_RANKED_HANDS,
        RouteIds.NOTICES,
        RouteIds.NOTICE_POPUP,
        RouteIds.LANGUAGE_PICKER,
        RouteIds.SETTINGS,
        RouteIds.ACCOUNT,
        RouteIds.NEW_GAME_SETUP,
        RouteIds.CHANGE_DISPLAY_NAME,
        RouteIds.SPECIAL_KEY,
    )

    /**
     * Layers that are **announced** by an owner rather than opened by the player,
     * so they stay out of the persisted stack: the owner supplies their content
     * when it shows them, and a restored copy would come back inert or stale.
     *
     * Everything else persists — the snapshot is the whole stack, so quitting from
     * the local game menu brings that menu back, and a confirm card comes back
     * asking the same question, because its route carries that question.
     */
    private val ANNOUNCED_LAYERS = setOf(
        // The account surfaces below are raised by the account flow
        // (`AccountController`) with its own step and status.
        RouteIds.FIRST_LOGIN_ALIAS,
        RouteIds.SPECIAL_KEY,
        RouteIds.STARTING_LEVEL,
        // The connecting card is the online coordinator's connect state machine.
        RouteIds.ONLINE_CONNECT,
        // Presence reconcile owns the away scrim.
        RouteIds.AWAY,
        // The board announces the hint button (`PlayTableView` chrome).
        RouteIds.HINT_BUTTON,
        // The hand-end presenter shows the breakdown from live hand state.
        RouteIds.SCORE_BREAKDOWN,
        // The notice popup is raised by the online effect when a fresh notice
        // arrives; a restored copy would come back already seen.
        RouteIds.NOTICE_POPUP,
        // The tutorial host drives the lesson board, quiz, and coach per step.
        RouteIds.TUTORIAL_LESSON,
        RouteIds.TUTORIAL_COACH,
        RouteIds.TUTORIAL_QUIZ,
    )

    /**
     * Contexts that paint a surface of their own, so the fundamental overlay — the
     * felt and its version stamp — is not drawn behind them: a game in progress, a
     * lesson, the reference browser. Everything else stands on the felt.
     */
    private val OFF_FELT = setOf(
        RouteIds.LOCAL_BOARD,
        RouteIds.ONLINE_TABLE,
        RouteIds.TUTORIAL_SHELL,
    )

    /**
     * Roots and contexts whose own content *is* the game-menu card. No card is
     * pushed over these: the menu would be drawn twice. Their card yields the
     * felt's corner stamp block, which the runtime paints whenever the felt is.
     */
    private val MENU_SURFACES = setOf(RouteIds.ROOT_MENU, RouteIds.LOCAL_MENU)

    /**
     * Contexts that publish the content panel their own content layers are laid
     * out and painted inside: the tutorial lesson, quiz and coach speak
     * content-local coordinates because the shell hosts them.
     */
    private val LOCAL_SPACE_PROVIDERS = setOf(RouteIds.TUTORIAL_SHELL)

    /**
     * Routes whose player-set state outlives the route: its last value is kept in
     * the remembered store and read back the next time the route is created. The
     * Online hub remembers the tab, the two scrolls, and the Shadow Ranked draft
     * it holds, so a Shadow Ranked attempt started after a restart opens on the rules
     * the player last chose. The tutorial shell remembers the TOC scroll, the
     * compact drawer, and the pull tab, so leaving for the main menu and coming
     * back opens the drawer the way the player left it. The reference browser
     * remembers its section, page, both scroll offsets, and drawer, so closing it
     * and opening it again lands on the page the player was reading. The rating
     * chart remembers the window it was left on, so closing it and opening it
     * again lands on the same range rather than jumping to all time.
     */
    private val REMEMBERED_STATES = setOf(
        RouteIds.ONLINE_HUB,
        RouteIds.TUTORIAL_SHELL,
        RouteIds.REFERENCE,
        RouteIds.RATING_HISTORY,
    )

    /**
     * Routes whose player-set state is stored in the route UI store, and the codec
     * that reads and writes it. One map so a stateful surface is a declaration
     * here, not a per-component callback.
     */
    private val STATE_CODECS: Map<String, RouteStateCodec<*>> = mapOf(
        // One shared card, three homes: each entry owns what the card held while up.
        RouteIds.ROOT_MENU to GameMenuCardCodec,
        RouteIds.LOCAL_MENU to GameMenuCardCodec,
        RouteIds.GAME_MENU to GameMenuCardCodec,
        // The local setup card's draft and body scroll (rounds + chicken + offset).
        RouteIds.NEW_GAME_SETUP to SetupCardCodec,
        // The unified Online screen: its tab, both tab scrolls, and the Shadow
        // Ranked draft it holds.
        RouteIds.ONLINE_HUB to OnlineHubCodec,
        // The tutorial shell's TOC scroll, drawer, and pull-tab position.
        RouteIds.TUTORIAL_SHELL to TutorialShellCodec,
        // Body scroll, one codec for every scrollable layer.
        RouteIds.SCORE_TABLE to RouteScrollCodec,
        RouteIds.MATCH_HISTORY to RouteScrollCodec,
        RouteIds.LEADERBOARD to RouteScrollCodec,
        // The rating chart's target (a public handle or self) and the window it
        // was left on; the fetched candles are E2's own byte-bounded cache, not
        // this route's state.
        RouteIds.RATING_HISTORY to RatingHistoryCodec,
        RouteIds.SHADOW_RANKED_ATTEMPTS to RouteScrollCodec,
        RouteIds.SHADOW_RANKED_HANDS to RouteScrollCodec,
        RouteIds.NOTICES to RouteScrollCodec,
        RouteIds.LANGUAGE_PICKER to RouteScrollCodec,
        // The settings card's body scroll.
        RouteIds.SETTINGS to RouteScrollCodec,
        // The reference browser: section, page, both scroll offsets, and drawer.
        RouteIds.REFERENCE to ReferenceNavCodec,
    )

    /** The session context a route establishes, or null when it establishes none. */
    private val CONTEXT_KINDS = mapOf(
        RouteIds.LOCAL_MENU to SessionKind.FreePlay,
        RouteIds.LOCAL_BOARD to SessionKind.FreePlay,
        RouteIds.ONLINE_TABLE to SessionKind.Online,
        RouteIds.TUTORIAL_SHELL to SessionKind.Tutorial,
    )

    /**
     * Layer behavior for [route]; unknown ids fall back to their kind's defaults.
     *
     * The floor is the root menu and nothing else, so it declares no session
     * context: [LayerSpec.contextKind] belongs to the contexts above it.
     */
    fun specFor(route: Route): LayerSpec = when (route) {
        is Route.Screen -> LayerSpec.defaultsFor(LayerRole.Screen).copy(
            menuSurface = route.id in MENU_SURFACES,
            onFelt = route.id !in OFF_FELT,
        ).state(route.id)

        is Route.Layer -> LayerSpec.defaultsFor(LayerRole.Context).copy(
            contextKind = CONTEXT_KINDS[route.id],
            menuSurface = route.id in MENU_SURFACES,
            onFelt = route.id !in OFF_FELT,
            content = if (route.id in LOCAL_SPACE_PROVIDERS) {
                ContentPolicy.ProvidesSpace
            } else {
                ContentPolicy.Normal
            },
        ).state(route.id)

        is Route.Modal -> {
            val defaults = LayerSpec.defaultsFor(LayerRole.Modal)
                .persisting(route.id !in ANNOUNCED_LAYERS)
                .state(route.id)
            // Every modal occludes the connecting card except the score table and
            // the card itself (the legacy `otherModal` list).
            val suppressesConnectCard =
                route.id != RouteIds.ONLINE_CONNECT && route.id != RouteIds.SCORE_TABLE
            when {
                // The connecting card is not part of the legacy automation-blocking set.
                route.id == RouteIds.ONLINE_CONNECT ->
                    defaults.copy(blocksAutomation = false, suppressesConnectCard = false)

                // The chart is a screen, not a card: it paints the window edge to
                // edge, so nothing below it is modelled (Opaque) and the version
                // stamp steps aside with it. Back and its close control are still
                // the exits; nothing is dismissed by tapping the surface, which is
                // why this is a presentation change and not a new route kind.
                route.id == RouteIds.RATING_HISTORY ->
                    defaults.copy(
                        dismissOnBack = true,
                        suppressesConnectCard = suppressesConnectCard,
                        onFelt = false,
                        policy = defaults.policy.copy(presentation = Presentation.Opaque),
                    )

                route.id in DISMISSABLE_MODALS ->
                    defaults.copy(dismissOnBack = true, suppressesConnectCard = suppressesConnectCard)

                else -> defaults.copy(suppressesConnectCard = suppressesConnectCard)
            }
        }

        is Route.Overlay -> when (route.id) {
            RouteIds.AWAY -> LayerSpec.defaultsFor(LayerRole.Overlay)
                .copy(
                    ownsInput = true,
                    blocksAutomation = true,
                    suppressesConnectCard = true,
                )

            RouteIds.HINT_BUTTON -> LayerSpec.defaultsFor(LayerRole.Overlay)
                .copy(ownsInput = false)

            RouteIds.TUTORIAL_LESSON, RouteIds.SCORE_BREAKDOWN ->
                LayerSpec.defaultsFor(LayerRole.Overlay)
                    .copy(content = ContentPolicy.InSpace(0))

            RouteIds.TUTORIAL_QUIZ ->
                LayerSpec.defaultsFor(LayerRole.Overlay)
                    .copy(blocksAutomation = true, content = ContentPolicy.InSpace(0))

            RouteIds.TUTORIAL_COACH ->
                LayerSpec.defaultsFor(LayerRole.Overlay)
                    .copy(blocksAutomation = true, content = ContentPolicy.InSpace(1))

            else -> LayerSpec.defaultsFor(LayerRole.Overlay)
        }.persisting(route.id !in ANNOUNCED_LAYERS)

        is Route.Tooltip -> LayerSpec.defaultsFor(LayerRole.Tooltip)

        is Route.Reference -> LayerSpec.defaultsFor(LayerRole.Overlay).copy(
            ownsInput = true,
            dismissOnBack = true,
            blocksAutomation = true,
            policy = LayerPolicy(Presentation.Below, persists = true),
            onFelt = false,
            suppressesConnectCard = true,
        ).state(RouteIds.REFERENCE)
    }

    /** This spec with its layer's persistence flipped; presentation is unchanged. */
    private fun LayerSpec.persisting(persist: Boolean): LayerSpec =
        copy(policy = policy.copy(persists = persist))

    /** This spec with the route's state codec when it declares one. */
    private fun LayerSpec.state(id: String): LayerSpec {
        val codec = STATE_CODECS[id] ?: return this
        return copy(state = codec, remembersState = id in REMEMBERED_STATES)
    }
}
