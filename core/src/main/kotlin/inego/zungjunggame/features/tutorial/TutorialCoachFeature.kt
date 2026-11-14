package inego.zungjunggame.features.tutorial

import inego.zungjunggame.input.Intent
import inego.zungjunggame.render.wallbuild.WallBuildDeal
import inego.zungjunggame.render.wallbuild.WallBuildHandoffSnapshot
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.stack.Route

/**
 * Registers the [RouteIds.TUTORIAL_COACH] overlay above the tutorial lesson
 * screen. The spec defaults to [inego.zungjunggame.ui.stack.LayerRole.Overlay]:
 * missable input, no scrim, not back-dismissable — matching the legacy
 * tutorial-content layer.
 */
object TutorialCoachFeature {
    fun register(
        registry: FeatureRegistry,
        overlay: () -> TutorialCoachOverlay,
    ) {
        val route = Route.Overlay(RouteIds.TUTORIAL_COACH)
        registry.register(
            routeKey = route.key,
            factory = { registered -> TutorialCoachComponent(registered, overlay) },
            spec = RouteSpecs.specFor(route),
            actions = TutorialCoachActions,
        )
    }
}

/** Maps the coach panel's resolutions and body links to tutorial intents. */
object TutorialCoachActions : RouteActions {
    const val PRIMARY = "tutorialCoach.primary"
    const val BACK = "tutorialCoach.back"
    const val LINK = "tutorialCoach.link"

    /** The slide exit finished; the host lands the recorded leave destination. */
    const val LEAVE_COMPLETE = "tutorialCoach.leaveComplete"

    /** The wall view committed a new deal; the host writes the park. */
    const val WALL_DEAL = "tutorialCoach.wallDeal"

    /** The wall view published handoff poses; the host parks them for the table. */
    const val WALL_HANDOFF = "tutorialCoach.wallHandoff"

    const val KEY_HREF = "href"
    const val KEY_DEAL = "deal"
    const val KEY_HANDOFF = "handoff"

    /**
     * The action a completed press emits: the body link it landed on, or the
     * control it resolved. A release that resolved nothing emits no action — the
     * bridge still consumed the lift, so it is not re-read as a tap.
     */
    fun actionFor(verdict: TutorialCoachOverlay.PointerUpResult): ComponentAction? {
        verdict.linkHref?.let { href ->
            return ComponentAction(LINK, mapOf(KEY_HREF to href))
        }
        return when {
            verdict.back -> ComponentAction(BACK)
            verdict.primary -> ComponentAction(PRIMARY)
            else -> null
        }
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        PRIMARY -> Intent.TutorialPrimary
        BACK -> Intent.TutorialBack
        LINK -> (action.payload[KEY_HREF] as? String)?.let { Intent.TutorialOpenHref(it) }
        LEAVE_COMPLETE -> Intent.TutorialCoachLeaveComplete
        WALL_DEAL -> (action.payload[KEY_DEAL] as? WallBuildDeal)
            ?.let { Intent.TutorialWallDealCommitted(it) }
        WALL_HANDOFF -> (action.payload[KEY_HANDOFF] as? WallBuildHandoffSnapshot)
            ?.let { Intent.TutorialWallHandoffCommitted(it) }
        else -> null
    }
}
