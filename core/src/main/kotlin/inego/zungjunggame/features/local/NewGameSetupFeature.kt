package inego.zungjunggame.features.local

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.engine.LocalGameConfig
import inego.zungjunggame.engine.PatternIds
import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.RouteUiState
import inego.zungjunggame.ui.stack.Route

/** Registers the [RouteIds.NEW_GAME_SETUP] modal with the runtime registry. */
object NewGameSetupFeature {
    fun register(registry: FeatureRegistry) {
        val route = Route.Modal(RouteIds.NEW_GAME_SETUP)
        registry.register(
            routeKey = route.key,
            factory = { registered -> NewGameSetupComponent(registered) },
            spec = RouteSpecs.specFor(route),
            actions = NewGameSetupActions,
        )
    }

    /** Registers the [RouteIds.NEW_GAME_SETUP] modal with host-owned config seams. */
    fun register(
        registry: FeatureRegistry,
        overlay: () -> NewGameSetupOverlay,
        config: () -> LocalGameConfig = { LocalGameConfig() },
        camera: () -> Camera = { OrthographicCamera() },
        routeUi: RouteUiState = RouteUiState(),
    ) {
        val route = Route.Modal(RouteIds.NEW_GAME_SETUP)
        registry.register(
            routeKey = route.key,
            factory = { registered ->
                NewGameSetupComponent(
                    route = registered,
                    overlay = overlay(),
                    config = config,
                    camera = camera,
                    state = routeUi.port(registered.key, SetupCardCodec),
                )
            },
            spec = RouteSpecs.specFor(route),
            actions = NewGameSetupActions,
        )
    }
}

/**
 * Maps the setup controls to [Intent]s.
 *
 * Approximations (no exact intent exists in this stage): `start` uses
 * [Intent.NewGame] for "begin with the draft config"; the rounds and bot radios
 * and the chicken checkbox report an edited draft, which the component writes as
 * the route's state and which deliberately maps to no feature intent;
 * `cancel`/dismiss use [Intent.Back]. `chickenInfo` matches the legacy deep-link
 * to the chicken-hand reference page.
 */
object NewGameSetupActions : RouteActions {
    const val START = "setup.start"
    const val CANCEL = "setup.cancel"
    const val ROUNDS = "setup.rounds"
    const val CHICKEN_TOGGLE = "setup.chicken"
    const val CHICKEN_INFO = "setup.chickenInfo"

    /**
     * Maps the control a completed press on the card resolved to its action. The
     * overlay reports its own `SELECT_*` id, and a release on nothing (a drag or an
     * edited control) resolves to no feature action.
     */
    fun actionFor(verdict: NewGameSetupVerdict): ComponentAction? = when (verdict.selectedId) {
        NewGameSetupOverlay.SELECT_START -> ComponentAction(START)
        NewGameSetupOverlay.SELECT_CANCEL -> ComponentAction(CANCEL)
        NewGameSetupOverlay.SELECT_CHICKEN_INFO -> ComponentAction(CHICKEN_INFO)
        else -> null
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        START -> Intent.NewGame
        CANCEL -> Intent.Back
        CHICKEN_INFO -> Intent.OpenReference(PatternIds.CHICKEN_HAND)
        ROUNDS, CHICKEN_TOGGLE -> null
        else -> null
    }

    override fun outside(): Intent = Intent.Back

    override fun back(route: Route): Intent = Intent.CancelSetup
}
