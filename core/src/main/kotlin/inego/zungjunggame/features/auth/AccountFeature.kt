package inego.zungjunggame.features.auth

import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.stack.Route

/** Registers the [RouteIds.ACCOUNT] modal with the runtime registry. */
object AccountFeature {
    /**
     * Registers the account card.
     *
     * [displayName] is the account model's signed-in label, read each layout;
     * the card never stores it, so a rename that lands while the panel is up is
     * painted on the next frame.
     */
    fun register(
        registry: FeatureRegistry,
        overlay: () -> AccountOverlay,
        displayName: () -> String = { "" },
    ) {
        val route = Route.Modal(RouteIds.ACCOUNT)
        registry.register(
            routeKey = route.key,
            factory = { registered -> AccountComponent(registered, overlay(), displayName) },
            spec = RouteSpecs.specFor(route),
            actions = AccountActions,
        )
    }
}

/**
 * Maps the account card's controls to [Intent]s.
 *
 * **Change name** reuses [Intent.EditDisplayName], the one display-name editor, so
 * both the dropdown's old entry point and this panel land in the same card.
 * **Delete account** is a link to the public deletion page — the same URL Google
 * Play Console is given — so the in-app path to deletion stays open.
 */
object AccountActions : RouteActions {
    const val CLOSE = "account.close"
    const val CHANGE_NAME = "account.changeName"
    const val DELETE_ACCOUNT = "account.delete"

    /** The public account-deletion page; the same URL Play Console is given. */
    const val DELETE_ACCOUNT_URL = "https://www.zungjung.org/delete-account.html"

    /**
     * Maps the control a completed press on the card resolved to its action, or
     * null when the release resolved nothing.
     */
    fun actionFor(verdict: AccountOverlay.PointerResult): ComponentAction? = when (verdict) {
        AccountOverlay.PointerResult.ChangeName -> ComponentAction(CHANGE_NAME)
        AccountOverlay.PointerResult.DeleteAccount -> ComponentAction(DELETE_ACCOUNT)
        AccountOverlay.PointerResult.Close -> ComponentAction(CLOSE)
        AccountOverlay.PointerResult.None -> null
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        CLOSE -> Intent.DismissAccount
        CHANGE_NAME -> Intent.EditDisplayName
        DELETE_ACCOUNT -> Intent.OpenReferenceExternal(DELETE_ACCOUNT_URL)
        else -> null
    }

    override fun outside(): Intent = Intent.DismissAccount

    override fun back(route: Route): Intent = Intent.DismissAccount
}
