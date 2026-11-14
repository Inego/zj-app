package inego.zungjunggame.features.local

import inego.zungjunggame.engine.GameConfig
import inego.zungjunggame.engine.PatternIds
import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.*
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewGameSetupFeatureTest {

    private val route = Route.Modal(RouteIds.NEW_GAME_SETUP)

    private val panel = Rect(0f, 0f, 320f, 500f)
    private val rounds4 = Rect(20f, 300f, 280f, 40f)
    private val roundsUnlimited = Rect(20f, 200f, 280f, 40f)
    private val chicken = Rect(20f, 150f, 200f, 40f)
    private val chickenInfo = Rect(240f, 155f, 30f, 30f)
    private val start = Rect(60f, 20f, 90f, 50f)
    private val cancel = Rect(170f, 20f, 90f, 50f)

    private fun geometry() = NewGameSetupGeometry(
        panel = panel,
        rounds = listOf(
            NewGameSetupRoundGeometry(rounds4, 4),
            NewGameSetupRoundGeometry(roundsUnlimited, null),
        ),
        chickenToggle = chicken,
        chickenInfo = chickenInfo,
        start = start,
        cancel = cancel,
    )

    private fun component(
        overlay: NewGameSetupOverlay = NewGameSetupOverlay(),
        state: StatePort<SetupCardState> = StatePort { null },
    ): NewGameSetupComponent = NewGameSetupComponent(
        route = route,
        overlay = overlay,
        measure = { _, _ -> geometry() },
        state = state,
    )

    private fun constraints() = Constraints(400f, 600f, 0f, 0f, 0f)

    @Test
    fun layoutGestureOwnsTheCardSoControlsAreNotDuplicatedAsHitRegions() {
        val layout = component().layout(testFrame(constraints()))

        assertEquals(panel, layout.panel)
        // The card is one gesture region; the overlay resolves the control, so no
        // per-control hit regions exist to disagree with the widget's own state.
        assertEquals(listOf(panel), layout.gestureRegions)
        assertEquals(0, layout.hitRegions.size)
        // The card body scrolls by wheel, so the layer opts into wheel routing.
        assertTrue(layout.scrollable)
    }

    /**
     * The rounds radio and chicken checkbox report an edited draft with no feature
     * action, so the release writes the whole card; Start emits its feature action
     * against the draft the store already holds.
     */
    @Test
    fun gestureReleaseWritesTheEditedDraftThroughTheRouteUiWrite() {
        val store = RouteUiState()
        val overlay = NewGameSetupOverlay()
        val component = component(overlay, store.port(route.key, SetupCardCodec))
        overlay.seedLayoutForTest(geometry())
        val emitted = mutableListOf<ComponentAction>()
        val frame = testFrame(constraints()) { action ->
            emitted += action
            RouteUiActions.valueOf(action)?.let { store.put(route.key, it) }
        }

        // A tap on the "Unlimited" row reports the whole draft; no feature action.
        assertTrue(component.onGesturePress(40f, 220f, frame))
        assertTrue(component.onGestureRelease(40f, 220f, frame))
        assertEquals(listOf(RouteUiActions.WRITE), emitted.map { it.id })
        assertEquals(
            SetupCardState(GameConfig(rounds = null, allowChickenHands = false), 0f),
            store.read(route.key, SetupCardCodec),
        )

        // The next frame hands the stored draft in; the chicken tap flips that
        // draft, not the widget's default.
        emitted.clear()
        val storedDraft = requireNotNull(store.read(route.key, SetupCardCodec)).draft
        overlay.seedLayoutForTest(geometry(), draft = storedDraft)
        component.onGesturePress(40f, 170f, frame)
        component.onGestureRelease(40f, 170f, frame)
        assertEquals(listOf(RouteUiActions.WRITE), emitted.map { it.id })
        assertEquals(
            SetupCardState(GameConfig(rounds = null, allowChickenHands = true), 0f),
            store.read(route.key, SetupCardCodec),
        )

        // Start emits the START action, which maps to Intent.NewGame.
        emitted.clear()
        component.onGesturePress(80f, 40f, frame)
        component.onGestureRelease(80f, 40f, frame)
        assertEquals(listOf(ComponentAction(NewGameSetupActions.START)), emitted)
    }

    @Test
    fun theEditedDraftIsWhatClosingTheCardTakes() {
        val store = RouteUiState()
        val overlay = NewGameSetupOverlay()
        val component = component(overlay, store.port(route.key, SetupCardCodec))
        overlay.seedLayoutForTest(geometry())
        val frame = testFrame(constraints()) { action ->
            RouteUiActions.valueOf(action)?.let { store.put(route.key, it) }
        }

        component.onGesturePress(40f, 220f, frame)
        component.onGestureRelease(40f, 220f, frame)

        // Closing the card is one consume: it returns exactly the draft the widget
        // wrote and clears the state in the same step, so no consumer can read it
        // after the route is gone.
        val taken = store.consume(route.key, SetupCardCodec)
        assertEquals(GameConfig(rounds = null, allowChickenHands = false), taken?.draft)
        assertNull(store.read(route.key, SetupCardCodec))
    }

    @Test
    fun actionsMapStartCancelAndChickenInfo() {
        assertEquals(
            Intent.NewGame,
            NewGameSetupActions.toIntent(route, ComponentAction(NewGameSetupActions.START)),
        )
        assertEquals(
            Intent.Back,
            NewGameSetupActions.toIntent(route, ComponentAction(NewGameSetupActions.CANCEL)),
        )
        assertEquals(
            Intent.OpenReference(PatternIds.CHICKEN_HAND),
            NewGameSetupActions.toIntent(route, ComponentAction(NewGameSetupActions.CHICKEN_INFO)),
        )
        assertNull(NewGameSetupActions.toIntent(route, ComponentAction(NewGameSetupActions.ROUNDS)))
        assertNull(NewGameSetupActions.toIntent(route, ComponentAction(NewGameSetupActions.CHICKEN_TOGGLE)))
        assertEquals(Intent.Back, NewGameSetupActions.outside())
    }

    @Test
    fun registerWiresSetupRoute() {
        val registry = FeatureRegistry()
        NewGameSetupFeature.register(registry)

        assertTrue(registry.create(route) is NewGameSetupComponent)
        assertEquals(LayerRole.Modal, registry.specFor(route).role)
        assertEquals(
            Intent.NewGame,
            registry.actionToIntent(route, ComponentAction(NewGameSetupActions.START)),
        )
        assertEquals(Intent.Back, registry.outsideIntent(route))
    }
}
