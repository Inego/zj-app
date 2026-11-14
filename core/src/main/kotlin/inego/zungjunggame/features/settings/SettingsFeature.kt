package inego.zungjunggame.features.settings

import inego.zungjunggame.input.Intent
import inego.zungjunggame.input.TouchDiagnostics
import inego.zungjunggame.net.NotificationPrefsScale
import inego.zungjunggame.persist.UiPreferences
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteActions
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.runtime.RouteSpecs
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.stack.Route

/** Registers the [RouteIds.SETTINGS] modal with the runtime registry. */
object SettingsFeature {
    /**
     * Registers the [RouteIds.SETTINGS] modal.
     *
     * [preferences] is the `ui.prefs` slot the checkbox reads and the effect
     * writes; there is no default, because a settings card with a private copy of
     * the preference is the defect this signature exists to prevent.
     */
    fun register(
        registry: FeatureRegistry,
        overlay: () -> SettingsOverlay,
        preferences: StateSlot<UiPreferences>,
        notifications: () -> SettingsNotifications = { SettingsNotifications() },
    ) {
        val route = Route.Modal(RouteIds.SETTINGS)
        registry.register(
            routeKey = route.key,
            factory = { registered ->
                SettingsComponent(registered, preferences, overlay(), notifications = notifications)
            },
            spec = RouteSpecs.specFor(route),
            actions = SettingsActions,
        )
    }
}

/**
 * Maps the Settings card's controls to [Intent]s: each checkbox row to its toggle,
 * the volume slider to [Intent.SetSoundVolume] with the value it reported, and a
 * dismiss to [Intent.Back].
 */
object SettingsActions : RouteActions {
    const val CLOSE = "settings.close"
    const val TOGGLE_TILE_LABELS = "settings.tileLabels"
    const val TOGGLE_SOUNDS = "settings.sounds"
    const val SET_VOLUME = "settings.volume"
    const val TOGGLE_EMAIL_OPT_IN = "settings.emailOptIn"
    const val SET_DIGEST_INTERVAL = "settings.digestInterval"

    /**
     * Maps the control a completed press or a slider step on the card resolved to
     * its action, or null when the gesture resolved nothing.
     */
    fun actionFor(verdict: SettingsVerdict): ComponentAction? {
        val action = when (verdict) {
            is SettingsVerdict.Row -> when (verdict.id) {
                SettingsOverlay.TILE_LABELS_ROW -> ComponentAction(TOGGLE_TILE_LABELS)
                SettingsOverlay.SOUNDS_ROW -> ComponentAction(TOGGLE_SOUNDS)
                SettingsOverlay.EMAIL_OPT_IN_ROW -> ComponentAction(TOGGLE_EMAIL_OPT_IN)
                else -> null
            }
            is SettingsVerdict.Volume ->
                ComponentAction(SET_VOLUME, mapOf("value" to verdict.value))
            is SettingsVerdict.DigestInterval ->
                ComponentAction(SET_DIGEST_INTERVAL, mapOf("step" to verdict.step))
            SettingsVerdict.Close -> ComponentAction(CLOSE)
            SettingsVerdict.None -> null
        }
        TouchDiagnostics.log("set action verdict=$verdict action=${action?.id ?: "null"}")
        return action
    }

    override fun toIntent(route: Route, action: ComponentAction): Intent? = when (action.id) {
        CLOSE -> Intent.DismissSettings
        TOGGLE_TILE_LABELS -> Intent.ToggleTileLabels
        TOGGLE_SOUNDS -> Intent.ToggleSounds
        SET_VOLUME -> Intent.SetSoundVolume(action.payload["value"] as? Int ?: 0)
        TOGGLE_EMAIL_OPT_IN -> Intent.ToggleEmailOptIn
        SET_DIGEST_INTERVAL -> Intent.SetDigestIntervalHours(
            NotificationPrefsScale.hoursFor(action.payload["step"] as? Int ?: 0),
        )
        else -> null
    }

    override fun outside(): Intent = Intent.Back

    override fun back(route: Route): Intent = Intent.DismissSettings
}
