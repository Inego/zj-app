package inego.zungjunggame.features.settings

import inego.zungjunggame.input.Intent
import inego.zungjunggame.net.NotificationPrefsScale
import inego.zungjunggame.persist.UiPreferences
import inego.zungjunggame.persist.UiPreferencesKey
import inego.zungjunggame.render.tiles.TileLabels
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.testFrame
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsFeatureTest {

    private val route = Route.Modal(RouteIds.SETTINGS)

    private val panel = Rect(0f, 0f, 300f, 200f)

    /** The `ui.prefs` model the component reads; the effect would write it. */
    private val preferences: StateSlot<UiPreferences> =
        StateSlot(DefaultStateStore(InMemoryStateBackend()), UiPreferencesKey)

    @AfterTest
    fun tearDown() {
        TileLabels.enabled = false
    }

    private fun component(overlay: SettingsOverlay = SettingsOverlay()): SettingsComponent =
        SettingsComponent(
            route = route,
            preferences = preferences,
            overlay = overlay,
            measure = { _, _ -> SettingsGeometry(panel) },
        )

    private fun constraints() = Constraints(400f, 500f, 0f, 0f, 0f)

    private fun recordingFrame(): Pair<Frame, MutableList<ComponentAction>> {
        val emitted = mutableListOf<ComponentAction>()
        return testFrame(constraints()) { emitted += it } to emitted
    }

    @Test
    fun layoutGestureOwnsTheCard() {
        val layout = component().layout(recordingFrame().first)

        assertEquals(panel, layout.panel)
        assertEquals(listOf(panel), layout.gestureRegions)
        assertEquals(0, layout.hitRegions.size)
    }

    @Test
    fun aRowTapEmitsTheToggleAndNeverChangesTheModel() {
        val overlay = SettingsOverlay().apply { seedLayoutForTest() }
        val component = component(overlay)
        val (frame, emitted) = recordingFrame()

        // The model starts off; the tap asks for a flip, it does not perform one.
        assertFalse(preferences.requireValue().showTileLabels)
        component.layout(frame)
        component.onGesturePress(300f, 60f, frame)
        component.onGestureRelease(300f, 60f, frame)

        assertEquals(listOf(ComponentAction(SettingsActions.TOGGLE_TILE_LABELS)), emitted)
        assertFalse(preferences.requireValue().showTileLabels, "the component only emits")
    }

    @Test
    fun aSoundsRowTapEmitsTheSoundsToggle() {
        val overlay = SettingsOverlay().apply { seedLayoutForTest() }
        val component = component(overlay)
        val (frame, emitted) = recordingFrame()

        component.layout(frame)
        component.onGesturePress(300f, 132f, frame)
        component.onGestureRelease(300f, 132f, frame)

        assertEquals(listOf(ComponentAction(SettingsActions.TOGGLE_SOUNDS)), emitted)
    }

    @Test
    fun aReleaseThatDidNotStartOnARowEmitsNothing() {
        val overlay = SettingsOverlay().apply { seedLayoutForTest() }
        val component = component(overlay)
        val (frame, emitted) = recordingFrame()

        component.layout(frame)
        component.onGesturePress(300f, 400f, frame)
        component.onGestureRelease(300f, 400f, frame)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun anEmailRowTapEmitsTheEmailToggle() {
        val overlay = SettingsOverlay().apply { seedLayoutForTest() }
        val component = component(overlay)
        val (frame, emitted) = recordingFrame()

        component.layout(frame)
        component.onGesturePress(300f, 270f, frame)
        component.onGestureRelease(300f, 270f, frame)

        assertEquals(listOf(ComponentAction(SettingsActions.TOGGLE_EMAIL_OPT_IN)), emitted)
    }

    @Test
    fun aDigestIntervalReleaseEmitsTheMappedStep() {
        val overlay = SettingsOverlay().apply { seedLayoutForTest() }
        val component = component(overlay)
        val (frame, emitted) = recordingFrame()

        component.layout(frame)
        // The seeded digest track runs x 246 (step 0) .. 554 (step 7, once a day).
        component.onGesturePress(246f, 339f, frame)
        component.onGestureDrag(554f, 339f, true, frame)
        component.onGestureRelease(554f, 339f, frame)

        assertEquals(
            listOf(ComponentAction(SettingsActions.SET_DIGEST_INTERVAL, mapOf("step" to 7))),
            emitted,
        )
    }

    @Test
    fun aCloseTapEmitsClose() {
        val overlay = SettingsOverlay().apply { seedLayoutForTest() }
        val component = component(overlay)
        val (frame, emitted) = recordingFrame()

        component.layout(frame)
        component.onGesturePress(568f, 400f, frame)
        component.onGestureRelease(568f, 400f, frame)

        assertEquals(listOf(ComponentAction(SettingsActions.CLOSE)), emitted)
    }

    @Test
    fun actionsMapEveryControlAndOutside() {
        assertEquals(
            Intent.DismissSettings,
            SettingsActions.toIntent(route, ComponentAction(SettingsActions.CLOSE)),
        )
        assertEquals(
            Intent.ToggleTileLabels,
            SettingsActions.toIntent(route, ComponentAction(SettingsActions.TOGGLE_TILE_LABELS)),
        )
        assertEquals(
            Intent.ToggleSounds,
            SettingsActions.toIntent(route, ComponentAction(SettingsActions.TOGGLE_SOUNDS)),
        )
        assertEquals(
            Intent.SetSoundVolume(7),
            SettingsActions.toIntent(
                route,
                ComponentAction(SettingsActions.SET_VOLUME, mapOf("value" to 7)),
            ),
        )
        assertEquals(
            Intent.ToggleEmailOptIn,
            SettingsActions.toIntent(route, ComponentAction(SettingsActions.TOGGLE_EMAIL_OPT_IN)),
        )
        assertEquals(
            Intent.SetDigestIntervalHours(8),
            SettingsActions.toIntent(
                route,
                ComponentAction(SettingsActions.SET_DIGEST_INTERVAL, mapOf("step" to 5)),
            ),
        )
        assertEquals(
            Intent.SetDigestIntervalHours(24),
            SettingsActions.toIntent(
                route,
                ComponentAction(
                    SettingsActions.SET_DIGEST_INTERVAL,
                    mapOf("step" to NotificationPrefsScale.MAX_STEP),
                ),
            ),
        )
        assertEquals(Intent.Back, SettingsActions.outside())
    }

    @Test
    fun verdictsMapToTheControlsActions() {
        assertEquals(
            SettingsActions.actionFor(SettingsVerdict.Close),
            ComponentAction(SettingsActions.CLOSE),
        )
        assertEquals(
            ComponentAction(SettingsActions.TOGGLE_SOUNDS),
            SettingsActions.actionFor(SettingsVerdict.Row(SettingsOverlay.SOUNDS_ROW)),
        )
        assertEquals(
            ComponentAction(SettingsActions.SET_VOLUME, mapOf("value" to 3)),
            SettingsActions.actionFor(SettingsVerdict.Volume(3)),
        )
        assertEquals(
            ComponentAction(SettingsActions.TOGGLE_EMAIL_OPT_IN),
            SettingsActions.actionFor(SettingsVerdict.Row(SettingsOverlay.EMAIL_OPT_IN_ROW)),
        )
        assertEquals(
            ComponentAction(SettingsActions.SET_DIGEST_INTERVAL, mapOf("step" to 3)),
            SettingsActions.actionFor(SettingsVerdict.DigestInterval(3)),
        )
        assertNull(SettingsActions.actionFor(SettingsVerdict.None))
    }

    @Test
    fun registerWiresSettingsRoute() {
        val registry = FeatureRegistry()
        SettingsFeature.register(registry, { SettingsOverlay() }, preferences)

        assertTrue(registry.create(route) is SettingsComponent)
        assertEquals(LayerRole.Modal, registry.specFor(route).role)
        assertEquals(
            Intent.ToggleSounds,
            registry.actionToIntent(route, ComponentAction(SettingsActions.TOGGLE_SOUNDS)),
        )
        assertEquals(Intent.Back, registry.outsideIntent(route))
    }
}
