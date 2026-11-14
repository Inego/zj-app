package inego.zungjunggame.features.score

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.engine.SessionScoreTable
import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.ModalVerdict
import inego.zungjunggame.ui.RouteScrollCodec
import inego.zungjunggame.ui.RouteUiState
import inego.zungjunggame.ui.stack.Route

/** Registers the [RouteIds.SCORE_TABLE] modal with the runtime registry. */
object ScoreTableFeature {
    fun register(
        registry: FeatureRegistry,
        table: () -> SessionScoreTable,
        humanSeat: () -> Int,
    ) {
        val route = Route.Modal(RouteIds.SCORE_TABLE)
        registry.register(
            routeKey = route.key,
            factory = { registered -> ScoreTableComponent(registered, table, humanSeat) },
            spec = RouteSpecs.specFor(route),
            actions = ScoreTableActions,
        )
    }

    /** Registers the [RouteIds.SCORE_TABLE] modal with a host-owned overlay. */
    fun register(
        registry: FeatureRegistry,
        table: () -> SessionScoreTable,
        humanSeat: () -> Int,
        overlay: () -> ScoreTableOverlay,
        camera: () -> Camera = { OrthographicCamera() },
        routeUi: RouteUiState = RouteUiState(),
    ) {
        val route = Route.Modal(RouteIds.SCORE_TABLE)
        registry.register(
            routeKey = route.key,
            factory = { registered ->
                ScoreTableComponent(
                    route = registered,
                    table = table,
                    humanSeat = humanSeat,
                    overlay = overlay(),
                    camera = camera,
                    state = routeUi.port(registered.key, RouteScrollCodec),
                )
            },
            spec = RouteSpecs.specFor(route),
            actions = ScoreTableActions,
        )
    }
}

/** Maps the close control and outside press to [Intent.Back]. */
object ScoreTableActions : RouteActions {
    const val DISMISS = "scoreTable.dismiss"

    /** Maps the control a completed press on the table resolved to its action. */
    fun actionFor(verdict: ModalVerdict): ComponentAction? =
        if (verdict.close) ComponentAction(DISMISS) else null

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        DISMISS -> Intent.Back
        else -> null
    }

    override fun outside(): Intent = Intent.Back

    override fun back(route: Route): Intent = Intent.DismissScoreTable
}
