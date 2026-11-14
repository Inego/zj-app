package inego.zungjunggame.features.auth

import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.ModalVerdict
import inego.zungjunggame.ui.stack.Route

/**
 * Registers the shared account text card at its three routes: the first-login
 * prompt, the display-name editor, and the Google Play reviewer's special key.
 *
 * The composition root supplies **one** shared [AliasCardOverlay], and the three
 * routes can never be on the stack together, so the widget's draft/busy/error
 * cannot be fought over by two presentations:
 *  - `AccountController.beginAliasEdit` refuses while `awaitingFirstLoginAlias`,
 *    so the editor cannot open during the first-login prompt;
 *  - each prompt is a modal that owns input, so a tap that would reach the
 *    account dropdown behind it is consumed as that layer's outside intent;
 *  - the prompts are raised only by the sign-in flow, which dismisses them on
 *    sign-out.
 *
 * Neither component keeps player-set route state: the field's in-flight text, and
 * the busy / error the owner sets from a request in flight, are view-local. The
 * card is one shared widget with no per-entry store value, so there is nothing to
 * clone.
 */
object AliasCardFeature {
    fun register(
        registry: FeatureRegistry,
        overlay: () -> AliasCardOverlay,
    ) {
        val route = Route.Modal(RouteIds.FIRST_LOGIN_ALIAS)
        registry.register(
            routeKey = route.key,
            factory = { registered -> AliasCardComponent(registered, overlay()) },
            spec = RouteSpecs.specFor(route),
            actions = FirstLoginAliasActions,
        )
        // The same card is the account's display-name editor: opened from the
        // menu's account dropdown, it publishes the identical controls, so it
        // needs only its own route and its own action mapping.
        val changeRoute = Route.Modal(RouteIds.CHANGE_DISPLAY_NAME)
        registry.register(
            routeKey = changeRoute.key,
            factory = { registered -> AliasCardComponent(registered, overlay()) },
            spec = RouteSpecs.specFor(changeRoute),
            actions = ChangeDisplayNameActions,
        )
        // And the review key: the same field, with its own labels and no
        // display-name policy.
        val specialKeyRoute = Route.Modal(RouteIds.SPECIAL_KEY)
        registry.register(
            routeKey = specialKeyRoute.key,
            factory = { registered -> AliasCardComponent(registered, overlay()) },
            spec = RouteSpecs.specFor(specialKeyRoute),
            actions = SpecialKeyActions,
        )
    }
}

/**
 * The action ids the card publishes. Both route mappings key off these, so the
 * card's vocabulary is neutral and each route decides what the controls mean.
 */
object AliasCardActionIds {
    const val SUBMIT = "aliasCard.submit"
    const val BACK = "aliasCard.back"

    /**
     * Maps the row a completed press on the card resolved to its neutral action, or
     * null when the release resolved nothing. Both routes share this one card, so
     * they share this one mapping; each route's `toIntent` decides what the action
     * means.
     */
    fun actionFor(verdict: ModalVerdict): ComponentAction? = when (verdict.selectedId) {
        AliasCardOverlay.ROW_SUBMIT -> ComponentAction(SUBMIT)
        AliasCardOverlay.ROW_BACK -> ComponentAction(BACK)
        else -> null
    }
}

/**
 * Maps the card's controls on the first-login prompt ([RouteIds.FIRST_LOGIN_ALIAS]).
 */
object FirstLoginAliasActions : RouteActions {
    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        AliasCardActionIds.SUBMIT -> Intent.FirstLoginSubmit
        AliasCardActionIds.BACK -> Intent.FirstLoginBack
        else -> null
    }

    override fun back(route: Route): Intent = Intent.FirstLoginBack
}

/**
 * Maps the card's controls on the account display-name editor
 * ([RouteIds.CHANGE_DISPLAY_NAME]): Save and Cancel instead of the first-login
 * submit/cancel flow.
 */
object ChangeDisplayNameActions : RouteActions {
    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        AliasCardActionIds.SUBMIT -> Intent.SaveDisplayName
        AliasCardActionIds.BACK -> Intent.CancelDisplayName
        else -> null
    }

    override fun outside(): Intent = Intent.CancelDisplayName

    override fun back(route: Route): Intent = Intent.CancelDisplayName
}

/**
 * Maps the card's controls on the special-key prompt ([RouteIds.SPECIAL_KEY]):
 * the same field and Save/Cancel controls as the display-name editor, with the
 * sign-in actions on the other side.
 */
object SpecialKeyActions : RouteActions {
    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        AliasCardActionIds.SUBMIT -> Intent.SpecialKeySubmit
        AliasCardActionIds.BACK -> Intent.SpecialKeyCancel
        else -> null
    }

    override fun outside(): Intent = Intent.SpecialKeyCancel

    override fun back(route: Route): Intent = Intent.SpecialKeyCancel
}
