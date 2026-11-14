package inego.zungjunggame.app

import inego.zungjunggame.audio.SoundCue
import inego.zungjunggame.i18n.AppLocale
import inego.zungjunggame.i18n.LocaleController
import inego.zungjunggame.input.TouchDiagnostics
import inego.zungjunggame.persist.UiPreferences
import inego.zungjunggame.render.tiles.TileLabels
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.ui.RouteUiState
import inego.zungjunggame.ui.RouteUiValue
import inego.zungjunggame.ui.stack.RouteKey

/**
 * Display / locale preferences and their pickers.
 *
 * `ui.prefs` is the model: the effect flips the value on the slot and mirrors it
 * into the paint-time [TileLabels] switch. The Settings card renders that model
 * (it holds no copy), so there is nothing to poke on the overlay here. The
 * hand-end card's parked place and fold, and the board's HINT / claim row parks, live
 * in the same value — display state of player-arranged controls, read by the surface
 * and written here when the player moves or folds it — so every write copies the value
 * it is changing instead of rebuilding it.
 *
 * The tile-label checkbox value is computed from the slot rather than read back
 * from the surface that drew it, so a rebuilt component and the model can never
 * disagree.
 */
internal class AppPrefsEffects(
    private val preferences: StateSlot<UiPreferences>,
    private val transitions: ScreenTransitions,
    private val routeUi: RouteUiState,
    /**
     * Auditions the sound level as the player sets it. Optional so headless tests
     * and a build without audio still construct the effect; the player itself
     * gates on the very preference this effect just wrote.
     */
    private val soundPlayer: SoundPlayer? = null,
) : PrefsEffects {

    override fun toggleTileLabels() {
        val before = preferences.requireValue().showTileLabels
        val enabled = !before
        TileLabels.enabled = enabled
        preferences.set(preferences.requireValue().copy(showTileLabels = enabled))
        TouchDiagnostics.log("set toggle $before -> $enabled")
    }

    override fun toggleSounds() {
        val current = preferences.requireValue()
        val enabled = !current.soundsEnabled
        preferences.set(current.copy(soundsEnabled = enabled))
        // Turning sound on auditions it; turning it off is already silent.
        if (enabled) soundPlayer?.play(SoundCue.Preview)
    }

    override fun setSoundVolume(value: Int) {
        val clamped = value.coerceIn(0, UiPreferences.MAX_SOUND_VOLUME)
        preferences.set(preferences.requireValue().copy(soundVolume = clamped))
        soundPlayer?.play(SoundCue.Preview)
    }

    override fun parkScoreBreakdown(x: Float, y: Float) {
        preferences.set(
            preferences.requireValue().copy(breakdownParkX = x, breakdownParkY = y),
        )
    }

    override fun toggleScoreBreakdownFold() {
        val current = preferences.requireValue()
        preferences.set(current.copy(breakdownCollapsed = !current.breakdownCollapsed))
    }

    override fun setScoreBreakdownScroll(offset: Float) {
        preferences.set(preferences.requireValue().copy(breakdownScrollY = offset))
    }

    override fun writeRouteUi(routeKey: String, value: RouteUiValue, remember: Boolean) {
        routeUi.put(RouteKey(routeKey), value, remember = remember)
    }

    override fun parkHintButton(x: Float, y: Float) {
        preferences.set(preferences.requireValue().copy(hintParkX = x, hintParkY = y))
    }

    override fun parkClaimPanel(x: Float, y: Float) {
        preferences.set(preferences.requireValue().copy(claimParkX = x, claimParkY = y))
    }

    override fun pickLocale(locale: AppLocale) {
        LocaleController.setLocale(locale)
        // The picker sat over the menu card (or tutorial shell); popping reveals it.
        transitions.dismissLanguagePicker()
    }

    override fun openLanguagePicker() {
        transitions.openLanguagePicker()
    }

    override fun dismissLanguagePicker() {
        transitions.dismissLanguagePicker()
    }

    override fun openSettings() {
        transitions.openSettings()
    }

    override fun dismissSettings() {
        transitions.dismissSettings()
    }
}
