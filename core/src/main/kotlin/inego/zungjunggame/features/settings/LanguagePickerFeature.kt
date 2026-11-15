package inego.zungjunggame.features.settings

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.i18n.AppLocale
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

/** Registers the [RouteIds.LANGUAGE_PICKER] modal with the runtime registry. */
object LanguagePickerFeature {
    fun register(registry: FeatureRegistry) {
        val route = Route.Modal(RouteIds.LANGUAGE_PICKER)
        registry.register(
            routeKey = route.key,
            factory = { registered -> LanguagePickerComponent(registered) },
            spec = RouteSpecs.specFor(route),
            actions = LanguagePickerActions,
        )
    }

    /** Registers the [RouteIds.LANGUAGE_PICKER] modal with a host-owned overlay. */
    fun register(
        registry: FeatureRegistry,
        overlay: () -> LanguagePickerOverlay,
        camera: () -> Camera = { OrthographicCamera() },
        routeUi: RouteUiState = RouteUiState(),
    ) {
        val route = Route.Modal(RouteIds.LANGUAGE_PICKER)
        registry.register(
            routeKey = route.key,
            factory = { registered ->
                LanguagePickerComponent(
                    route = registered,
                    overlay = overlay(),
                    camera = camera,
                    state = routeUi.port(registered.key, RouteScrollCodec),
                )
            },
            spec = RouteSpecs.specFor(route),
            actions = LanguagePickerActions,
        )
    }
}

/** Maps each locale row to [Intent.PickLocale], the ✕ to [Intent.DismissLanguagePicker]. */
object LanguagePickerActions : RouteActions {
    const val PICK = "language.pick"
    const val DISMISS = "language.dismiss"
    const val KEY_TAG = "localeTag"

    /**
     * Maps the row a completed press on the card resolved to its action. The row
     * reports its own locale name, so an unknown name resolves to nothing rather
     * than to a wrong locale.
     */
    fun actionFor(verdict: ModalVerdict): ComponentAction? {
        if (verdict.close) return ComponentAction(DISMISS)
        val name = verdict.selectedId ?: return null
        val tag = runCatching { AppLocale.valueOf(name).tag }.getOrNull() ?: return null
        return ComponentAction(PICK, mapOf(KEY_TAG to tag))
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        PICK -> (action.payload[KEY_TAG] as? String)?.let { Intent.PickLocale(it) }
        DISMISS -> Intent.DismissLanguagePicker
        else -> null
    }

    override fun outside(): Intent = Intent.Back

    override fun back(route: Route): Intent = Intent.DismissLanguagePicker
}
