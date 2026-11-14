package inego.zungjunggame.features.settings

import inego.zungjunggame.i18n.AppLocale
import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.RouteScrollCodec
import inego.zungjunggame.ui.RouteUiActions
import inego.zungjunggame.ui.RouteUiState
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.testFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguagePickerFeatureTest {

    private val route = Route.Modal(RouteIds.LANGUAGE_PICKER)

    private val panel = Rect(0f, 0f, 300f, 400f)

    private fun component(): LanguagePickerComponent = LanguagePickerComponent(
        route = route,
        measure = { _, _ -> LanguagePickerGeometry(panel = panel) },
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
        // The card body scrolls by wheel, so the layer opts into wheel routing.
        assertTrue(layout.scrollable)
    }

    @Test
    fun gestureReleaseEmitsThePickedLocaleTag() {
        val overlay = LanguagePickerOverlay().apply {
            seedLayoutForTest(locales = listOf(AppLocale.English))
        }
        val component = LanguagePickerComponent(route = route, overlay = overlay)
        val (frame, emitted) = recordingFrame()

        // The single seeded row (en-US) fills the body: top 332, bottom 188.
        component.onGesturePress(300f, 250f, frame)
        component.onGestureRelease(300f, 250f, frame)
        assertEquals(
            listOf(
                ComponentAction(
                    LanguagePickerActions.PICK,
                    mapOf(LanguagePickerActions.KEY_TAG to "en-US"),
                ),
            ),
            emitted,
        )
    }

    @Test
    fun actionsMapLocalePickAndOutside() {
        assertEquals(
            Intent.PickLocale(AppLocale.Russian.tag),
            LanguagePickerActions.toIntent(
                route,
                ComponentAction(
                    LanguagePickerActions.PICK,
                    mapOf(LanguagePickerActions.KEY_TAG to AppLocale.Russian.tag),
                ),
            ),
        )
        assertNull(LanguagePickerActions.toIntent(route, ComponentAction(LanguagePickerActions.PICK)))
        assertEquals(Intent.Back, LanguagePickerActions.outside())
    }

    /**
     * Opening the picker with nothing stored brings the active locale into view:
     * the first motion resolves the row and writes it through the route store, so
     * the paint that follows shows it. The active locale is the default (English),
     * seeded last so it starts below the viewport; once the store holds an offset
     * the reveal never runs again, so the player's own scroll back to the top
     * stands.
     */
    @Test
    fun firstMotionRevealsTheActiveLocaleOnceWhenNothingIsStored() {
        val store = RouteUiState()
        val overlay = LanguagePickerOverlay().apply {
            seedLayoutForTest(
                locales = listOf(AppLocale.Russian, AppLocale.German, AppLocale.English),
                itemH = 50f,
                itemGap = 10f,
                bodyBottom = 0f,
                bodyH = 60f,
            )
        }
        val component = LanguagePickerComponent(
            route = route,
            overlay = overlay,
            state = store.port(route.key, RouteScrollCodec),
        )
        val record: (ComponentAction) -> Unit = { action ->
            RouteUiActions.valueOf(action)?.let { store.put(route.key, it) }
        }
        val motion = testFrame(constraints().copy(deltaSec = 1f / 60f), emit = record)

        component.motion(motion)
        // English is index 2: content 170, view 60, so the last row is revealed at 110.
        assertEquals(110f, store.read(route.key, RouteScrollCodec))

        store.write(route.key, RouteScrollCodec, 0f)
        component.motion(motion)
        assertEquals(
            0f,
            store.read(route.key, RouteScrollCodec),
            "a stored offset belongs to the player, not to a repeated reveal",
        )
    }

    @Test
    fun registerWiresLanguagePickerRoute() {
        val registry = FeatureRegistry()
        LanguagePickerFeature.register(registry)

        assertTrue(registry.create(route) is LanguagePickerComponent)
        assertEquals(LayerRole.Modal, registry.specFor(route).role)
        assertEquals(Intent.Back, registry.outsideIntent(route))
    }

    /**
     * The motion phase end to end: a released fling coasts through the route UI
     * store — every frame the component writes where the list is now, so nothing
     * ever reads the widget to learn the value — and the writes stop when the
     * coast settles.
     */
    @Test
    fun aReleasedFlingCoastsThroughTheRouteStateAndStopsWhenItSettles() {
        val store = RouteUiState()
        val overlay = LanguagePickerOverlay().apply {
            seedLayoutForTest(locales = List(12) { AppLocale.English })
        }
        val component = LanguagePickerComponent(
            route = route,
            overlay = overlay,
            state = store.port(route.key, RouteScrollCodec),
        )
        val writes = mutableListOf<Float>()
        val record: (ComponentAction) -> Unit = { action ->
            // What AppPrefsEffects does with the emitted write.
            RouteUiActions.valueOf(action)?.let { value ->
                store.put(route.key, value)
                RouteScrollCodec.decode(value.text)?.let { writes += it }
            }
        }
        val frame = testFrame(constraints(), emit = record)
        val motionFrame = testFrame(constraints().copy(deltaSec = 1f / 60f), emit = record)

        // A fast upward flick, kept clear of the end so velocity samples unclamped.
        assertTrue(component.onGesturePress(300f, 250f, frame))
        repeat(5) { i ->
            Thread.sleep(4)
            component.onGestureDrag(300f, 250f + (i + 1) * 60f, dragging = true, frame)
        }
        assertTrue(component.onGestureRelease(300f, 550f, frame))
        val atRelease = writes.size
        assertTrue(atRelease > 0)

        // The coast writes a new offset for a few frames ...
        var coastFrames = 0
        while (coastFrames < 600) {
            val before = writes.size
            component.motion(motionFrame)
            if (writes.size == before) break
            coastFrames++
        }
        assertTrue(coastFrames > 0, "the fling coasted through motion")
        val coasted = writes.drop(atRelease)
        assertTrue(
            coasted.zipWithNext().all { (a, b) -> b >= a },
            "the coast only moves forward: $coasted",
        )

        // ... and once it has settled there is nothing left to write.
        val settled = writes.size
        repeat(10) { component.motion(motionFrame) }
        assertEquals(settled, writes.size, "a settled list emits nothing")
        assertEquals(writes.last(), store.read(route.key, RouteScrollCodec))
    }
}
