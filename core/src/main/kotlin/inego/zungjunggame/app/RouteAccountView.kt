package inego.zungjunggame.app

import inego.zungjunggame.online.AccountController
import inego.zungjunggame.features.auth.AliasPrompt
import inego.zungjunggame.features.auth.StartingLevelOverlay
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.session.hosts.FreePlayMenuPort
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.Router

/**
 * Route-backed [AccountController.View].
 *
 * Visibility is the stack: every account surface pushes or removes its modal
 * route, and a `is…Visible` read is stack membership. Busy / text / error are
 * widget state the component owns, so this reads the overlay instances the
 * components wrap — the controller never touches a concrete overlay, and the
 * account domain has no `render/` import. Display name is [AliasPrompt]: a
 * stack card on keyboard profiles, the OS dialog on touch. Presence is just the
 * ONLINE_HUB floor.
 */
internal class RouteAccountView(
    private val router: () -> Router?,
    private val aliasPrompt: AliasPrompt,
    private val startingLevelOverlay: StartingLevelOverlay? = null,
    private val gameMenu: FreePlayMenuPort? = null,
    private val presentOnline: () -> Unit,
    private val dismissOnline: () -> Unit,
    /**
     * True while no context owns the display, so an account surface may be shown
     * over it. Answered from the session registry by the composition root; an
     * unresolved router (boot, before the stack is built) has no session up.
     */
    private val entrySurfaceFree: () -> Boolean = { true },
) : AccountController.View {
    private fun isOpen(id: String): Boolean =
        router()?.isOpen(Route.Modal(id).key) == true

    private fun push(id: String) {
        router()?.let { if (!it.isOpen(Route.Modal(id).key)) it.push(Route.Modal(id)) }
    }

    private fun remove(id: String) {
        router()?.remove(Route.Modal(id).key)
    }

    override fun hideMenu() {
        gameMenu?.hide()
    }

    // ---- Presence --------------------------------------------------------------

    /**
     * A live session owns the display (a local board, a lesson, an online table);
     * the account may not cover it. The registry derives that from the stack, so
     * the rule lives in one place.
     */
    override fun isEntrySurfaceFree(): Boolean = entrySurfaceFree()

    override fun showPresence() = presentOnline()
    override fun hidePresence() {
        dismissOnline()
        remove(RouteIds.MATCH_HISTORY)
        remove(RouteIds.LEADERBOARD)
    }

    // ---- First-login alias -----------------------------------------------------

    override val isFirstLoginAliasVisible: Boolean
        get() = aliasPrompt.isOpen(AliasPrompt.Kind.FirstLogin)
    override val isFirstLoginAliasBusy: Boolean
        get() = aliasPrompt.isBusy(AliasPrompt.Kind.FirstLogin)
    override val firstLoginAliasText: String
        get() = aliasPrompt.text(AliasPrompt.Kind.FirstLogin)
    override fun showFirstLoginAlias(displayName: String) {
        aliasPrompt.show(AliasPrompt.Kind.FirstLogin, displayName)
    }

    override fun hideFirstLoginAlias() {
        aliasPrompt.hide(AliasPrompt.Kind.FirstLogin)
    }

    override fun setFirstLoginAliasBusy(busy: Boolean) {
        aliasPrompt.setBusy(AliasPrompt.Kind.FirstLogin, busy)
    }

    override fun setFirstLoginAliasError(message: String) {
        aliasPrompt.setError(AliasPrompt.Kind.FirstLogin, message)
    }

    // ---- Account display-name editor ------------------------------------------

    override val isAliasEditVisible: Boolean
        get() = aliasPrompt.isOpen(AliasPrompt.Kind.ChangeName)
    override val isAliasEditBusy: Boolean
        get() = aliasPrompt.isBusy(AliasPrompt.Kind.ChangeName)
    override val aliasEditText: String
        get() = aliasPrompt.text(AliasPrompt.Kind.ChangeName)
    override fun showAliasEdit(displayName: String) {
        aliasPrompt.show(AliasPrompt.Kind.ChangeName, displayName)
    }

    override fun cancelAliasEdit() {
        aliasPrompt.hide(AliasPrompt.Kind.ChangeName)
    }

    override fun setAliasEditBusy(busy: Boolean) {
        aliasPrompt.setBusy(AliasPrompt.Kind.ChangeName, busy)
    }

    override fun setAliasEditError(message: String) {
        aliasPrompt.setError(AliasPrompt.Kind.ChangeName, message)
    }

    // ---- Special key -----------------------------------------------------------

    override val isSpecialKeyVisible: Boolean
        get() = aliasPrompt.isOpen(AliasPrompt.Kind.SpecialKey)
    override val isSpecialKeyBusy: Boolean
        get() = aliasPrompt.isBusy(AliasPrompt.Kind.SpecialKey)
    override val specialKeyText: String
        get() = aliasPrompt.text(AliasPrompt.Kind.SpecialKey)
    override fun showSpecialKey() {
        aliasPrompt.show(AliasPrompt.Kind.SpecialKey, "")
    }

    override fun hideSpecialKey() {
        aliasPrompt.hide(AliasPrompt.Kind.SpecialKey)
    }

    override fun setSpecialKeyBusy(busy: Boolean) {
        aliasPrompt.setBusy(AliasPrompt.Kind.SpecialKey, busy)
    }

    override fun setSpecialKeyError(message: String) {
        aliasPrompt.setError(AliasPrompt.Kind.SpecialKey, message)
    }

    // ---- Starting level --------------------------------------------------------

    override val isStartingLevelVisible: Boolean get() = isOpen(RouteIds.STARTING_LEVEL)
    override val isStartingLevelBusy: Boolean get() = startingLevelOverlay?.busy == true
    override fun showStartingLevel() {
        if (isStartingLevelVisible) return
        startingLevelOverlay?.reset()
        push(RouteIds.STARTING_LEVEL)
    }

    override fun hideStartingLevel() = remove(RouteIds.STARTING_LEVEL)
    override fun setStartingLevelBusy(busy: Boolean) {
        startingLevelOverlay?.setBusy(busy)
    }

    override fun setStartingLevelError(message: String) {
        startingLevelOverlay?.setError(message)
    }
}
