package inego.zungjunggame.features.notices

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
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

/**
 * Registers the [RouteIds.NOTICES] list and the [RouteIds.NOTICE_POPUP] card with
 * the runtime registry.
 */
object NoticesFeature {
    fun register(registry: FeatureRegistry) {
        register(
            registry = registry,
            listOverlay = { NoticesOverlay() },
            listInput = { NoticesRenderInput() },
            popupOverlay = { NoticesOverlay(compact = true) },
            popupInput = { NoticesRenderInput() },
        )
    }

    /** Registers both notice cards with host-owned overlays and input seams. */
    fun register(
        registry: FeatureRegistry,
        listOverlay: () -> NoticesOverlay,
        listInput: () -> NoticesRenderInput = { NoticesRenderInput() },
        popupOverlay: () -> NoticesOverlay,
        popupInput: () -> NoticesRenderInput = { NoticesRenderInput() },
        camera: () -> Camera = { OrthographicCamera() },
        routeUi: RouteUiState = RouteUiState(),
    ) {
        val listRoute = Route.Modal(RouteIds.NOTICES)
        registry.register(
            routeKey = listRoute.key,
            factory = { registered ->
                NoticesComponent(
                    route = registered,
                    overlay = listOverlay(),
                    renderInput = listInput,
                    camera = camera,
                    state = routeUi.port(registered.key, RouteScrollCodec),
                )
            },
            spec = RouteSpecs.specFor(listRoute),
            actions = NoticesActions,
        )

        val popupRoute = Route.Modal(RouteIds.NOTICE_POPUP)
        registry.register(
            routeKey = popupRoute.key,
            factory = { registered ->
                NoticesComponent(
                    route = registered,
                    overlay = popupOverlay(),
                    renderInput = popupInput,
                    camera = camera,
                )
            },
            spec = RouteSpecs.specFor(popupRoute),
            actions = NoticesActions,
        )
    }
}

/**
 * Maps the notices card's close control to [Intent.Back], and the card's declared
 * back to the intent that takes its route down.
 */
object NoticesActions : RouteActions {
    const val DISMISS = "notices.dismiss"

    /** Maps the control a completed press on the card resolved to its action. */
    fun actionFor(verdict: ModalVerdict): ComponentAction? =
        if (verdict.close) ComponentAction(DISMISS) else null

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        DISMISS -> Intent.Back
        else -> null
    }

    override fun outside(): Intent = Intent.Back

    override fun back(route: Route): Intent =
        if (RouteIds.catalogIdOf(route) == RouteIds.NOTICE_POPUP) {
            Intent.NoticePopupDismiss
        } else {
            Intent.NoticesDismiss
        }
}
