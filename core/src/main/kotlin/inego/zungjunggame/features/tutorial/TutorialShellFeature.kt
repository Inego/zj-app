package inego.zungjunggame.features.tutorial

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.RouteUiState
import inego.zungjunggame.ui.stack.Route

/**
 * Registers the [RouteIds.TUTORIAL_SHELL] route.
 *
 * Role: the shell is the context for `SessionKind.Tutorial`, directly above the
 * floor. The lesson board is a pass-through content layer above it, so the shell
 * paints the display and owns the input that reaches it while the board's
 * content is laid out in the shell's local space. `Route.Layer` gives it
 * [inego.zungjunggame.ui.stack.LayerRole.Context]: it publishes the TOC hit
 * regions in screen space, it provides the content panel the lesson is laid out
 * in, and a press that misses the TOC is the shell's own [TutorialShellActions.outside]
 * rather than a row of the menu underneath it.
 */
object TutorialShellFeature {
    fun register(
        registry: FeatureRegistry,
        routeUi: RouteUiState = RouteUiState(),
        shell: () -> TutorialShell,
    ) {
        register(registry, shell, { OrthographicCamera() }, routeUi)
    }

    /** Registers the [RouteIds.TUTORIAL_SHELL] context with a host-owned camera. */
    fun register(
        registry: FeatureRegistry,
        shell: () -> TutorialShell,
        camera: () -> Camera,
        routeUi: RouteUiState = RouteUiState(),
    ) {
        val route = Route.Layer(RouteIds.TUTORIAL_SHELL)
        registry.register(
            routeKey = route.key,
            factory = { registered ->
                TutorialShellComponent(
                    route = registered,
                    shell = shell,
                    camera = camera,
                    state = routeUi.port(registered.key, TutorialShellCodec),
                )
            },
            spec = RouteSpecs.specFor(route),
            actions = TutorialShellActions,
        )
    }
}

/**
 * Maps a TOC row to [Intent.TutorialSelect], the top-right menu to
 * [Intent.ShowMenu], and back/dismiss to [Intent.Back].
 */
object TutorialShellActions : RouteActions {
    const val NAV_SELECT = "tutorialShell.navSelect"
    const val MENU = "tutorialShell.menu"

    /**
     * Back / dismiss affordance. No dedicated control exists on the shell yet, so
     * the route's system-back path raises this (or publishes a
     * region for it); [outside] uses the same intent, so a press that reaches the
     * shell and hits no TOC row is the same request as the back affordance.
     */
    const val BACK = "tutorialShell.back"
    const val KEY_LESSON_ID = "lessonId"

    /** The nav action for the lesson a release selected, or null when it selected none. */
    fun actionFor(verdict: TutorialShellVerdict): ComponentAction? = when (verdict) {
        is TutorialShellVerdict.Select -> navSelect(verdict.lessonId)
        is TutorialShellVerdict.Changed -> null
    }

    /** The nav action for [lessonId], shared by the hit regions and the gesture verdict. */
    fun navSelect(lessonId: String): ComponentAction =
        ComponentAction(NAV_SELECT, mapOf(KEY_LESSON_ID to lessonId))

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        NAV_SELECT -> (action.payload[KEY_LESSON_ID] as? String)?.let { Intent.TutorialSelect(it) }
        MENU -> Intent.ShowMenu
        BACK -> Intent.Back
        else -> null
    }

    override fun outside(): Intent = Intent.Back
}
