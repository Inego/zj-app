package inego.zungjunggame.features.score

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.input.Intent
import inego.zungjunggame.persist.UiPreferences
import inego.zungjunggame.persist.UiPreferencesKey
import inego.zungjunggame.render.handend.HandEndAction
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.stack.Route

/** Registers the [RouteIds.SCORE_BREAKDOWN] overlay with the runtime registry. */
object ScoreBreakdownFeature {
    fun register(registry: FeatureRegistry) {
        val route = Route.Overlay(RouteIds.SCORE_BREAKDOWN)
        registry.register(
            routeKey = route.key,
            factory = { registered ->
                ScoreBreakdownComponent(
                    registered,
                    preferences = StateSlot(DefaultStateStore(InMemoryStateBackend()), UiPreferencesKey),
                )
            },
            spec = RouteSpecs.specFor(route),
            actions = ScoreBreakdownActions,
        )
    }

    /**
     * Registers the [RouteIds.SCORE_BREAKDOWN] overlay with a host-owned overlay and
     * the display preferences the card's parked place lives in.
     */
    fun register(
        registry: FeatureRegistry,
        overlay: () -> ScoreBreakdownOverlay,
        preferences: StateSlot<UiPreferences>,
        camera: () -> Camera = { OrthographicCamera() },
    ) {
        val route = Route.Overlay(RouteIds.SCORE_BREAKDOWN)
        registry.register(
            routeKey = route.key,
            factory = { registered ->
                ScoreBreakdownComponent(registered, preferences, overlay(), camera)
            },
            spec = RouteSpecs.specFor(route),
            actions = ScoreBreakdownActions,
        )
    }
}

/**
 * Maps the hand-end card's controls to [Intent]s.
 *
 * The pattern rows deep-link to the reference, the footer chips map to the same
 * `Intent`s the board's hand-end controls used to (the Scores chip opens the
 * score-table modal), a drag that moved the card reports the place it was left
 * at, the body reports the offset a wheel or a drag settled at, and the fold control
 * asks for the fold to be flipped. All of those are the player's display state, kept
 * in `ui.prefs` beside the tile labels rather than in the card: the card renders the
 * fold it is handed and reports the press, so the value has one writer and outlives
 * the hand and the process. The card is non-modal, so an outside press maps to
 * nothing either.
 */
object ScoreBreakdownActions : RouteActions {
    const val PARK = "scoreBreakdown.park"
    const val FOLD = "scoreBreakdown.fold"
    const val SCROLL = "scoreBreakdown.scroll"
    const val PATTERN_INFO = "scoreBreakdown.patternInfo"
    const val ACTION = "scoreBreakdown.action"
    const val KEY_PARK_X = "parkX"
    const val KEY_PARK_Y = "parkY"
    const val KEY_SCROLL_Y = "scrollY"
    const val KEY_PAGE_ID = "pageId"
    const val KEY_HAND_END = "handEnd"
    const val KEY_PUBLIC_ID = "publicId"

    /** The wheel's settled offset, emitted through the bridge's scroll channel. */
    fun scrollActionFor(offset: Float): ComponentAction =
        ComponentAction(SCROLL, mapOf(KEY_SCROLL_Y to offset))

    /**
     * Maps what a completed press on the card resolved to its action. A release that
     * only dragged (the body scrolled, or the card was moved) resolves nothing to
     * *do* here beyond the place it was left at, which the model keeps. A release
     * that hit the fold control reports the request: the card does not fold itself.
     * The pattern rows never arrive here, because the card hands those presses back
     * to the click path.
     */
    fun actionFor(verdict: ScoreBreakdownOverlay.PointerUpResult): ComponentAction? = when {
        verdict.parked -> ComponentAction(
            PARK,
            mapOf(KEY_PARK_X to verdict.parkX, KEY_PARK_Y to verdict.parkY),
        )
        verdict.foldToggle -> ComponentAction(FOLD)
        verdict.action != null ->
            ComponentAction(
                ACTION,
                buildMap {
                    put(KEY_HAND_END, verdict.action)
                    // The Comparison chip names the challenge; the feature maps it
                    // to the open intent. Null for every other chip.
                    verdict.publicId?.let { put(KEY_PUBLIC_ID, it) }
                },
            )
        verdict.bodyScrollY != null -> scrollActionFor(verdict.bodyScrollY)
        else -> null
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        PARK -> Intent.ParkScoreBreakdown(
            x = action.payload[KEY_PARK_X] as? Float ?: 0f,
            y = action.payload[KEY_PARK_Y] as? Float ?: 0f,
        )
        FOLD -> Intent.ToggleScoreBreakdownFold
        SCROLL -> Intent.SetScoreBreakdownScroll(action.payload[KEY_SCROLL_Y] as? Float ?: 0f)
        PATTERN_INFO -> Intent.OpenReference(action.payload[KEY_PAGE_ID] as? String)
        ACTION -> when (action.payload[KEY_HAND_END]) {
            HandEndAction.ScoreTable -> Intent.PushRoute(Route.Modal(RouteIds.SCORE_TABLE))
            HandEndAction.NextHand -> Intent.NextHand
            HandEndAction.NewGame -> Intent.NewGame
            HandEndAction.PlayAgain -> Intent.PlayAgain
            HandEndAction.ReturnHome -> Intent.OnlineReturnHome
            // No public id means the comparison could not supply one: emit nothing
            // rather than a broken open intent.
            HandEndAction.Comparison ->
                (action.payload[KEY_PUBLIC_ID] as? String)?.let { Intent.OpenShadowRankedHands(it) }
            else -> null
        }
        else -> null
    }
}
