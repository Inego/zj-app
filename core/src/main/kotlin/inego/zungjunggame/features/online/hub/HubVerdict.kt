package inego.zungjunggame.features.online.hub

/**
 * The verdict a hub tab panel's press/drag/release machine resolves.
 *
 * One vocabulary for both tabs: the shell routes gestures to the active
 * [GestureSurface][inego.zungjunggame.ui.GestureSurface], and the bridge maps the
 * verdict to the hub's action ids through [OnlineHubActions]. Keeping the panels
 * on one verdict type is why the component can own a single gesture bridge instead
 * of one per tab.
 *
 * Tabs and the close button are not here: they are discrete taps published as
 * `Layout.hitRegions` by [OnlineHubComponent], not panel gestures.
 */
sealed interface HubVerdict {
    /** Where a body drag settled; null for a verdict that resolved a control. */
    val settledScrollOffset: Float? get() = null

    // Live tab controls.
    data object Primary : HubVerdict
    data class SetMinOtherHumans(val value: Int) : HubVerdict
    data object CycleRounds : HubVerdict
    data object CycleChicken : HubVerdict
    data object PastGames : HubVerdict

    // Shadow Ranked tab controls.
    data object Start : HubVerdict
    data object CancelStart : HubVerdict
    data object Retry : HubVerdict
    data object SetRoundsOne : HubVerdict
    data object SetRoundsTwo : HubVerdict
    data object ToggleChicken : HubVerdict
    data object ToggleFastBots : HubVerdict

    /** The right-aligned link that opens the attempts card. */
    data object ViewAttempts : HubVerdict

    /**
     * The description's info control: opens the Reference page explaining the
     * format. It sits on the Shadow Ranked tab's prose, so it carries the
     * page it deep-links to rather than a generic "help".
     */
    data object ShadowRankedReference : HubVerdict

    /** A press that claimed the body but resolved no control. */
    data object None : HubVerdict

    /** The panel body scrolled; carries the offset it settled at. */
    data class BodyScroll(override val settledScrollOffset: Float) : HubVerdict
}
