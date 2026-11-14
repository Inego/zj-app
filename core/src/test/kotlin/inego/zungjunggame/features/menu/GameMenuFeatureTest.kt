package inego.zungjunggame.features.menu

import inego.zungjunggame.features.confirm.ConfirmPrompt
import inego.zungjunggame.input.Intent
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.RouteUiActions
import inego.zungjunggame.ui.RouteUiState
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.RouteKey
import inego.zungjunggame.ui.testFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameMenuFeatureTest {

    private val route = Route.Modal(RouteIds.GAME_MENU)

    private val panel = Rect(0f, 0f, 300f, 600f)
    private val dropdown = Rect(150f, 520f, 130f, 36f)

    private fun component(
        overlay: GameMenuOverlay = GameMenuOverlay(),
    ): GameMenuComponent = GameMenuComponent(
        route = route,
        overlay = overlay,
        measure = { _, _ -> GameMenuGeometry(panel = panel, dropdownItem = dropdown) },
    )

    private fun constraints() = Constraints(400f, 700f, 0f, 0f, 0f)

    /**
     * A frame that records every emitted action and the intent the registry maps
     * it to, applying a route-state write to [store] as the prefs effect does.
     */
    private fun recorderFrame(
        registry: FeatureRegistry,
        menuRoute: Route,
        store: RouteUiState? = null,
    ): Triple<Frame, MutableList<ComponentAction>, MutableList<Intent>> {
        val actions = mutableListOf<ComponentAction>()
        val intents = mutableListOf<Intent>()
        val frame = testFrame(constraints()) { action ->
            actions += action
            val intent = registry.actionToIntent(menuRoute, action) ?: return@testFrame
            intents += intent
            if (intent is Intent.WriteRouteUi) {
                store?.put(RouteKey(intent.routeKey), intent.value)
            }
        }
        return Triple(frame, actions, intents)
    }

    @Test
    fun layoutGestureOwnsTheCardAndItsDropdown() {
        val layout = component().layout(testFrame(constraints()))

        assertEquals(panel, layout.panel)
        // The card and the (open) dropdown are gesture regions; the overlay
        // resolves rows/badge/dropdown, so no per-control hit regions exist.
        assertEquals(listOf(panel, dropdown), layout.gestureRegions)
        assertEquals(0, layout.hitRegions.size)
        // The card body scrolls by wheel, so the layer opts into wheel routing.
        assertTrue(layout.scrollable)
    }

    @Test
    fun gestureReleaseReportsTheDrawerToggleThroughTheRouteUiWrite() {
        val overlay = GameMenuOverlay().apply {
            seedLayoutForTest()
            seedIdentityForTest(signedInLabel = "alice")
        }
        val component = component(overlay)
        val (frame, actions, _) = recorderFrame(FeatureRegistry(), route)

        // Tap the identity badge while signed in: no feature action, and the
        // release reports the new open state through the route UI write.
        component.onGesturePress(560f, 920f, frame)
        component.onGestureRelease(560f, 920f, frame)
        assertEquals(listOf(RouteUiActions.WRITE), actions.map { it.id })
        assertEquals(
            GameMenuCardState(0f, accountDropdownOpen = true),
            GameMenuCardCodec.decode(requireNotNull(RouteUiActions.valueOf(actions.single())).text),
        )
    }

    /**
     * The drawer's stored value is what the card toggles against: the release
     * writes the opposite of the store's value and keeps the stored offset.
     */
    @Test
    fun theStoredDrawerIsWhatTheCardToggles() {
        val store = RouteUiState()
        store.write(route.key, GameMenuCardCodec, GameMenuCardState(120f, accountDropdownOpen = true))
        val overlay = GameMenuOverlay().apply {
            seedLayoutForTest(scrollY = 120f, accountDropdownOpen = true)
            seedIdentityForTest(signedInLabel = "alice")
        }
        val registry = FeatureRegistry()
        GameMenuFeature.register(registry, overlay = { overlay }, routeUi = store)
        val card = registry.create(route) as GameMenuComponent
        val (frame, _, intents) = recorderFrame(registry, route, store)

        card.onGesturePress(560f, 920f, frame)
        card.onGestureRelease(560f, 920f, frame)

        val write = intents.filterIsInstance<Intent.WriteRouteUi>().single()
        assertEquals(route.key.value, write.routeKey)
        assertEquals(GameMenuCardState(120f, false), GameMenuCardCodec.decode(write.value.text))
    }

    /**
     * Selecting a dropdown row resolves the row's feature action and the drawer
     * close that rides the same release is emitted beside it, in the frame that
     * resolved them.
     */
    @Test
    fun selectingADropdownRowWritesTheClosedDrawerBesideTheFeatureAction() {
        val overlay = GameMenuOverlay().apply {
            seedLayoutForTest(accountDropdownOpen = true)
            seedIdentityForTest(
                signedInLabel = "alice",
                left = 400f,
                bottom = 800f,
                width = 100f,
                height = 30f,
                dropdownWidth = 140f,
                dropdownHeight = 40f,
            )
        }
        val component = component(overlay)
        val actions = mutableListOf<ComponentAction>()
        val frame = testFrame(constraints()) { actions += it }

        component.onGesturePress(400f, 766f, frame)
        component.onGestureRelease(400f, 766f, frame)

        assertEquals(
            listOf(RouteUiActions.WRITE, GameMenuActions.LOG_OUT),
            actions.map { it.id },
        )
        assertEquals(
            GameMenuCardState(0f, accountDropdownOpen = false),
            GameMenuCardCodec.decode(requireNotNull(RouteUiActions.valueOf(actions.first())).text),
        )
    }

    /**
     * One shared card across entries: the entry, not the widget, owns the state.
     * Each entry's frame is handed in and each interaction is written to that
     * entry's route UI state — the widget contributes its frame value and nothing
     * else.
     */
    @Test
    fun aSharedCardWritesEachEntrysOwnStoredState() {
        val store = RouteUiState()
        val shared = GameMenuOverlay()
        val registry = FeatureRegistry()
        GameMenuFeature.register(registry, overlay = { shared }, routeUi = store)
        val root = Route.Screen(RouteIds.ROOT_MENU)
        val local = Route.Layer(RouteIds.LOCAL_MENU)
        val ownerA = registry.create(root) as GameMenuComponent
        val ownerB = registry.create(local) as GameMenuComponent
        val (frameA, _, _) = recorderFrame(registry, root, store)
        val (frameB, _, _) = recorderFrame(registry, local, store)

        // A is up: its stored frame (scrolled, drawer open) is handed in; the
        // badge tap toggles against A's entry and keeps A's offset.
        store.write(root.key, GameMenuCardCodec, GameMenuCardState(80f, accountDropdownOpen = true))
        shared.seedLayoutForTest(scrollY = 80f, accountDropdownOpen = true)
        shared.seedIdentityForTest(signedInLabel = "alice")
        ownerA.onGesturePress(560f, 920f, frameA)
        ownerA.onGestureRelease(560f, 920f, frameA)
        assertEquals(GameMenuCardState(80f, false), store.read(root.key, GameMenuCardCodec))
        assertNull(store.read(local.key, GameMenuCardCodec), "only A's entry was written")

        // B's layer hands in its own frame (a fresh top, a closed drawer); B's
        // interaction is written to B's entry, not to A's offsets.
        shared.seedLayoutForTest()
        shared.seedIdentityForTest(signedInLabel = "alice")
        ownerB.onGesturePress(560f, 920f, frameB)
        ownerB.onGestureRelease(560f, 920f, frameB)
        assertEquals(GameMenuCardState(0f, true), store.read(local.key, GameMenuCardCodec))
        assertEquals(
            GameMenuCardState(80f, false),
            store.read(root.key, GameMenuCardCodec),
            "A's entry is untouched",
        )
    }

    @Test
    fun everyMenuActionHasAnIdAndIntent() {
        for (action in GameMenuOverlay.Action.entries) {
            val id = GameMenuActions.idFor(action)
            assertNotNull(GameMenuActions.toIntent(route, ComponentAction(id)), action.name)
        }
    }

    @Test
    fun actionsMapRepresentativeRows() {
        assertEquals(
            Intent.PushRoute(Route.Modal(RouteIds.SCORE_TABLE)),
            GameMenuActions.toIntent(route, ComponentAction(GameMenuActions.SCORE_TABLE)),
        )
        // The root Online row enters the hub on its remembered tab (null = no tab).
        assertEquals(
            Intent.OpenOnline(null),
            GameMenuActions.toIntent(route, ComponentAction(GameMenuActions.PLAYERS)),
        )
        assertEquals(
            Intent.OpenReference("about-license"),
            GameMenuActions.toIntent(route, ComponentAction(GameMenuActions.ABOUT)),
        )
        assertEquals(
            Intent.PushRoute(ConfirmPrompt.Forfeit.route()),
            GameMenuActions.toIntent(route, ComponentAction(GameMenuActions.FORFEIT)),
        )
        assertEquals(
            Intent.ExitApp,
            GameMenuActions.toIntent(route, ComponentAction(GameMenuActions.EXIT)),
        )
        assertEquals(Intent.LogOut, GameMenuActions.toIntent(route, ComponentAction(GameMenuActions.DROPDOWN_LOG_OUT)))
        assertEquals(
            Intent.ViewNotices,
            GameMenuActions.toIntent(route, ComponentAction(GameMenuActions.NOTICES)),
        )
    }

    @Test
    fun noticesVerdictResolvesToNoticesActionAndIntent() {
        val action = GameMenuActions.actionFor(GameMenuVerdict(selectedId = GameMenuOverlay.Action.Notices.name))
        assertEquals(ComponentAction(GameMenuActions.NOTICES), action)
        assertEquals(Intent.ViewNotices, GameMenuActions.toIntent(route, action!!))
    }

    /**
     * Continue is one row over two different surfaces: on the local play menu it
     * enters the parked board, and on the chrome card over a live board it only has
     * to get out of the way (pushing a board that is already up is a no-op).
     */
    @Test
    fun continueEntersTheBoardOverTheMenuAndHidesTheChromeCard() {
        assertEquals(
            Intent.PushRoute(Route.Layer(RouteIds.LOCAL_BOARD)),
            GameMenuActions.toIntent(
                Route.Layer(RouteIds.LOCAL_MENU),
                ComponentAction(GameMenuActions.CONTINUE),
            ),
        )
        assertEquals(
            Intent.HideMenu,
            GameMenuActions.toIntent(route, ComponentAction(GameMenuActions.CONTINUE)),
        )
    }

    @Test
    fun registerWiresMenuRoute() {
        val registry = FeatureRegistry()
        GameMenuFeature.register(registry)

        assertTrue(registry.create(route) is GameMenuComponent)
        assertEquals(LayerRole.Modal, registry.specFor(route).role)
        assertEquals(
            Intent.PushRoute(Route.Modal(RouteIds.SCORE_TABLE)),
            registry.actionToIntent(route, ComponentAction(GameMenuActions.SCORE_TABLE)),
        )
        assertEquals(Intent.Back, registry.outsideIntent(route))
    }
}
