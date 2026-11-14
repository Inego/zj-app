package inego.zungjunggame.features.score

import inego.zungjunggame.engine.HandScoreDetails
import inego.zungjunggame.input.Intent
import inego.zungjunggame.persist.UiPreferences
import inego.zungjunggame.persist.UiPreferencesKey
import inego.zungjunggame.render.handend.HandEndAction
import inego.zungjunggame.runtime.AppModel
import inego.zungjunggame.runtime.FeatureRegistry
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.testFrame
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoreBreakdownComponentTest {

    private val route = Route.Overlay(RouteIds.SCORE_BREAKDOWN)
    private val preferences: StateSlot<UiPreferences> =
        StateSlot(DefaultStateStore(InMemoryStateBackend()), UiPreferencesKey)
    private val overlay = ScoreBreakdownOverlay()
    private val panel = Rect(50f, 50f, 300f, 250f)
    private val block = Rect(40f, 30f, 320f, 290f)
    private val info = Rect(60f, 200f, 20f, 20f)

    private fun geometry(): ScoreBreakdownGeometry = ScoreBreakdownGeometry(
        panel = panel,
        block = block,
        patternInfos = listOf(ScoreBreakdownInfoGeometry(info, "1.1")),
    )

    private fun component(): ScoreBreakdownComponent = ScoreBreakdownComponent(
        route = route,
        preferences = preferences,
        overlay = overlay,
        measure = { _, _ -> geometry() },
    )

    private fun constraints() = Constraints(400f, 400f, 0f, 0f, 0f)

    private fun frame(content: ScoreBreakdownContent? = null) =
        testFrame(constraints(), model = AppModel.Absent.copy(scoreBreakdown = content))

    @Test
    fun layoutPublishesTheDeepLinksAndTheCardAsItsOwnGestureRegion() {
        val layout = component().layout(frame())

        // The card owns the wheel and the drag over its own bounds and reports the
        // fold toggle and the footer chip on release; only the pattern rows it
        // hands back stay hit regions.
        assertEquals(block, layout.panel)
        assertEquals(listOf(block), layout.gestureRegions)
        assertTrue(layout.scrollable)
        assertEquals(1, layout.hitRegions.size)
        assertEquals(
            ComponentAction(ScoreBreakdownActions.PATTERN_INFO, mapOf(ScoreBreakdownActions.KEY_PAGE_ID to "1.1")),
            layout.hitRegions.hitAt(70f, 210f),
        )
    }

    @Test
    fun foldIsARequestTheStoredValueAnswers() {
        val action = { a: HandEndAction ->
            ComponentAction(ScoreBreakdownActions.ACTION, mapOf(ScoreBreakdownActions.KEY_HAND_END to a))
        }

        // The fold is remembered model state: the release asks for the flip (one
        // writer) and the card renders the value it is handed.
        assertEquals(
            ComponentAction(ScoreBreakdownActions.FOLD),
            ScoreBreakdownActions.actionFor(ScoreBreakdownOverlay.PointerUpResult(foldToggle = true)),
        )
        assertEquals(
            Intent.ToggleScoreBreakdownFold,
            ScoreBreakdownActions.toIntent(route, ComponentAction(ScoreBreakdownActions.FOLD)),
        )
        assertNull(
            ScoreBreakdownActions.actionFor(ScoreBreakdownOverlay.PointerUpResult(wasDrag = true)),
        )
        preferences.set(UiPreferences(breakdownCollapsed = true))
        component().layout(frame())
        assertTrue(overlay.collapsed)

        assertEquals(
            Intent.OpenReference("1.1"),
            ScoreBreakdownActions.toIntent(
                route,
                ComponentAction(ScoreBreakdownActions.PATTERN_INFO, mapOf(ScoreBreakdownActions.KEY_PAGE_ID to "1.1")),
            ),
        )
        assertEquals(
            Intent.PushRoute(Route.Modal(RouteIds.SCORE_TABLE)),
            ScoreBreakdownActions.toIntent(route, action(HandEndAction.ScoreTable)),
        )
        assertEquals(Intent.NextHand, ScoreBreakdownActions.toIntent(route, action(HandEndAction.NextHand)))
        assertEquals(Intent.NewGame, ScoreBreakdownActions.toIntent(route, action(HandEndAction.NewGame)))
        assertEquals(Intent.PlayAgain, ScoreBreakdownActions.toIntent(route, action(HandEndAction.PlayAgain)))
        assertEquals(
            Intent.OnlineReturnHome,
            ScoreBreakdownActions.toIntent(route, action(HandEndAction.ReturnHome)),
        )
        assertEquals(
            Intent.OpenReference(null),
            ScoreBreakdownActions.toIntent(route, ComponentAction(ScoreBreakdownActions.PATTERN_INFO)),
        )
        assertNull(ScoreBreakdownActions.toIntent(route, ComponentAction("scoreBreakdown.other")))
        assertNull(ScoreBreakdownActions.outside())
    }

    @Test
    fun bodyScrollIsInstalledPerFrameAndReportedOnSettle() {
        // The card renders the offset its owner installs for the frame and keeps
        // none of its own.
        overlay.seedLayoutForTest()
        overlay.seedBodyOverflowForTest(contentH = 400f, viewH = 48f, scrollY = 120f)
        assertEquals(120f, overlay.bodyScrollYForTest)

        // A body drag step reports the live offset, the release where it settled;
        // the wheel reports through the bridge's scroll channel. All map to the
        // one prefs intent.
        val settled = ComponentAction(
            ScoreBreakdownActions.SCROLL,
            mapOf(ScoreBreakdownActions.KEY_SCROLL_Y to 77f),
        )
        assertEquals(
            settled,
            ScoreBreakdownActions.actionFor(
                ScoreBreakdownOverlay.PointerUpResult(wasDrag = true, bodyScrollY = 77f),
            ),
        )
        assertEquals(ScoreBreakdownActions.scrollActionFor(77f), settled)
        assertEquals(
            Intent.SetScoreBreakdownScroll(77f),
            ScoreBreakdownActions.toIntent(route, settled),
        )
    }

    @Test
    fun layoutAppliesFrameContentAndClearsWhenAbsent() {
        val content = ScoreBreakdownContent(
            details = HandScoreDetails(totalScore = 10),
            headline = "You win",
            settlement = emptyList(),
            primaryAction = HandEndAction.NextHand,
            primaryLabel = "Next",
            gameResult = "1st place",
        )
        val card = component()
        card.layout(frame(content))
        assertEquals("1st place", overlay.gameResultLine)
        card.layout(frame(null))
        assertNull(overlay.gameResultLine)
    }

    @Test
    fun registerWiresScoreBreakdownRoute() {
        val registry = FeatureRegistry()
        ScoreBreakdownFeature.register(registry)

        assertTrue(registry.create(route) is ScoreBreakdownComponent)
        assertEquals(LayerRole.Overlay, registry.specFor(route).role)
        assertEquals(
            Intent.NextHand,
            registry.actionToIntent(
                route,
                ComponentAction(
                    ScoreBreakdownActions.ACTION,
                    mapOf(ScoreBreakdownActions.KEY_HAND_END to HandEndAction.NextHand),
                ),
            ),
        )
    }
}
