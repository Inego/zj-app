package inego.zungjunggame.features.settings

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.OrthographicCamera
import inego.zungjunggame.input.TouchDiagnostics
import inego.zungjunggame.net.NotificationPrefs
import inego.zungjunggame.persist.UiPreferences
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureBridge
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.RouteUiActions
import inego.zungjunggame.ui.StatePort
import inego.zungjunggame.ui.stack.Route

/**
 * Settings panel as a stack component.
 *
 * **`ui.prefs` is the controls.** The component reads the slot for the values it
 * draws and emits `ToggleTileLabels` / `ToggleSounds` / `SetSoundVolume` as the
 * card's gestures resolve; the effect writes the slot. The overlay holds no copy
 * of any preference, so the controls the player sees and the values that get
 * committed cannot disagree — the defect the old read-back draft allowed.
 *
 * Geometry comes from the wrapped [SettingsOverlay] through a [measure] seam
 * (GL-free); painting delegates to [SettingsOverlay.render]. The card's gestures
 * are the [GestureBridge]'s: the overlay owns the press machine and the volume
 * slider, and reports the row or slider value its gesture resolved, which
 * [SettingsActions.actionFor] maps. The body offset is route UI state — the
 * bridge writes it through [RouteUiActions], and [motion] carries a fling.
 */
/**
 * The account's notification settings as the card draws them.
 *
 * Read from the [inego.zungjunggame.net.NotificationPrefsClient] model; the section
 * is only shown while [visible] (a signed-in account has settings to store).
 */
data class SettingsNotifications(
    val visible: Boolean = false,
    val emailOptIn: Boolean = NotificationPrefs.DEFAULT.emailOptIn,
    val digestIntervalHours: Int = NotificationPrefs.DEFAULT.digestIntervalHours,
)

class SettingsComponent(
    route: Route,
    private val preferences: StateSlot<UiPreferences>,
    private val overlay: SettingsOverlay = SettingsOverlay(),
    private val measure: (Float, Float) -> SettingsGeometry = { _, _ -> overlay.measureGeometry() },
    /**
     * The account's notification settings for this frame (empty while signed out).
     * A provider, not a stored value: the card is a view of the model, not a copy.
     */
    private val notifications: () -> SettingsNotifications = { SettingsNotifications() },
    private val camera: () -> Camera = { OrthographicCamera() },
    /** The route's stored body offset; null when the player has not scrolled yet. */
    private val state: StatePort<Float> = StatePort { null },
) : GestureBridge<SettingsVerdict>(
    surface = { overlay },
    actionFor = { verdict ->
        // The body offset is route UI state, not a feature intent: it goes to the
        // same store every other scrollable card uses.
        if (verdict is SettingsVerdict.BodyScroll) {
            RouteUiActions.scroll(verdict.offset)
        } else {
            SettingsActions.actionFor(verdict)
        }
    },
    scrollActionFor = { offset -> RouteUiActions.scroll(offset) },
),
    Component {
    override val id: ComponentId = ComponentId(route.key.value)

    /** Control values for this frame's published geometry, read from the model. */
    private var showTileLabels: Boolean = preferences.requireValue().showTileLabels
    private var soundsEnabled: Boolean = preferences.requireValue().soundsEnabled
    private var soundVolume: Int = preferences.requireValue().soundVolume
    private var notificationsView: SettingsNotifications = SettingsNotifications()

    private var lastLayoutSignature: String? = null

    override fun motion(frame: Frame) {
        val before = overlay.scrollOffset()
        val after = overlay.advance(frame.constraints.deltaSec)
        if (after == before) return
        frame.emit(RouteUiActions.scroll(after))
    }

    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        val prefs = preferences.requireValue()
        showTileLabels = prefs.showTileLabels
        soundsEnabled = prefs.soundsEnabled
        soundVolume = prefs.soundVolume
        notificationsView = notifications()
        val geometry = measure(constraints.worldWidth, constraints.worldHeight)

        val gestureRegions: List<Rect> = listOfNotNull(geometry.panel)
        val signature = "${geometry.panel}|${constraints.worldWidth}x${constraints.worldHeight}"
        if (signature != lastLayoutSignature) {
            lastLayoutSignature = signature
            TouchDiagnostics.log(
                "set layout panel=${geometry.panel?.let(::rectText) ?: "null"} " +
                    "rows=${if (notificationsView.visible) 3 else 2} " +
                    "slider=${geometry.volumeTrack?.let(::rectText) ?: "null"} " +
                    "regions=${gestureRegions.size} show=$showTileLabels sounds=$soundsEnabled " +
                    "vol=$soundVolume " +
                    "world=${TouchDiagnostics.fmt(constraints.worldWidth)}," +
                    TouchDiagnostics.fmt(constraints.worldHeight),
            )
        }
        return Layout(panel = geometry.panel, gestureRegions = gestureRegions, scrollable = true)
    }

    private fun rectText(rect: Rect): String =
        "[${TouchDiagnostics.fmt(rect.left)},${TouchDiagnostics.fmt(rect.bottom)}," +
            "${TouchDiagnostics.fmt(rect.width)},${TouchDiagnostics.fmt(rect.height)}]"

    override fun draw(frame: Frame, canvas: Canvas) {
        val c = frame.constraints
        overlay.render(
            batch = canvas.batch,
            camera = camera(),
            screenW = c.worldWidth,
            screenH = c.worldHeight,
            scrollY = state.read() ?: 0f,
            showTileLabels = showTileLabels,
            soundsEnabled = soundsEnabled,
            soundVolume = soundVolume,
            pointerWorldX = c.pointerX,
            pointerWorldY = c.pointerY,
            notificationsVisible = notificationsView.visible,
            emailOptIn = notificationsView.emailOptIn,
            digestIntervalHours = notificationsView.digestIntervalHours,
        )
    }
}
