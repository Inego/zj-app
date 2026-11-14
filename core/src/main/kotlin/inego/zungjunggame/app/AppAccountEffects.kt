package inego.zungjunggame.app

import inego.zungjunggame.online.AccountController

/**
 * Authentication / account lifecycle effects.
 *
 * The controller owns the credential, the prompts, and the account surfaces; this
 * effect is the app's call surface onto it.
 *
 * **The main menu's account badge is the only entry point that starts sign-in.**
 * Its dropdown offers Google and, for the Google Play review account, a special
 * key. A successful sign-in lands in the Online hub ([AccountController]'s
 * `enterLobby`), and a Google failure leaves the menu up with `lastAuthError` set:
 * the hub is a signed-in surface, so it is never opened to show a sign-in in
 * progress. That error reaches the player through the menu's identity badge
 * (`MenuSignIn`), which is also the control that starts the next attempt. The
 * special-key card reports its own failures inline and stays open for a retry.
 */
internal class AppAccountEffects(
    private val accountController: AccountController,
    private val transitions: ScreenTransitions,
) : AccountEffects {

    override fun logIn() {
        accountController.beginGoogleSignIn()
    }

    override fun logInSpecialKey() {
        accountController.beginSpecialKeySignIn()
    }

    override fun specialKeySubmit() {
        accountController.submitSpecialKey()
    }

    override fun specialKeyCancel() {
        accountController.cancelSpecialKeySignIn()
    }

    override fun firstLoginSubmit() {
        accountController.submitFirstLoginAlias()
    }

    override fun firstLoginBack() {
        accountController.cancelFirstLoginAlias()
    }

    override fun editDisplayName() {
        accountController.beginAliasEdit()
    }

    override fun saveDisplayName() {
        accountController.submitAliasEdit()
    }

    override fun cancelDisplayName() {
        accountController.cancelAliasEdit()
    }

    override fun startingLevelPick(level: String) {
        accountController.setStartingLevel(level)
    }

    override fun openAccount() {
        transitions.openAccountPanel()
    }

    override fun dismissAccount() {
        transitions.dismissAccountPanel()
    }
}
