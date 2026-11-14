package inego.zungjunggame.features.board

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.input.Intent
import inego.zungjunggame.persist.UiPreferences
import inego.zungjunggame.persist.UiPreferencesKey
import inego.zungjunggame.render.claim.ClaimActionPanel
import inego.zungjunggame.render.hint.HintButtonOverlay
import inego.zungjunggame.render.ui.ParkRelease
import inego.zungjunggame.render.table.TableRenderInputs
import inego.zungjunggame.runtime.AppModel
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.stack.SessionKind
import inego.zungjunggame.session.snapshots.HandSnapshot
import inego.zungjunggame.session.snapshots.SessionSnapshot
import inego.zungjunggame.features.tutorial.TutorialSnapshot
import inego.zungjunggame.state.DefaultStateStore
import inego.zungjunggame.state.InMemoryStateBackend
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.testFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardComponentTest {

    private val route = Route.Screen(RouteIds.LOCAL_BOARD)

    private fun session() = SessionSnapshot(
        kind = SessionKind.FreePlay,
        boardHosted = true,
        table = null,
        hand = null,
    )

    private fun preferences(): StateSlot<UiPreferences> =
        StateSlot(DefaultStateStore(InMemoryStateBackend()), UiPreferencesKey)

    private fun constraints() = Constraints(200f, 200f, 10f, 10f, 0f)

    private fun appModel() = AppModel(
        session = session(),
        tutorial = TutorialSnapshot.Absent,
        tableInputs = TableRenderInputs(),
        boardRender = BoardRenderInput(),
    )

    private fun component(regions: List<BoardRegion>): BoardComponent = BoardComponent(
        route = route,
        table = { error("a fake measure must not construct PlayTableView") },
        preferences = preferences(),
        measure = { _, _, _, _, _ -> BoardMeasurement(regions) },
    )

    @Test
    fun layoutPublishesPanelAndRegionsAtTheirPoints() {
        val actionA = ComponentAction("a")
        val actionB = ComponentAction("b")
        val regions = listOf(
            BoardRegion(Rect(0f, 0f, 40f, 40f), actionA, rank = 2),
            BoardRegion(Rect(20f, 20f, 40f, 40f), actionB, rank = 1),
        )

        val layout = component(regions).layout(testFrame(constraints(), model = appModel()))

        assertEquals(Rect(0f, 0f, 200f, 200f), layout.panel)
        assertEquals(2, layout.hitRegions.size)
        assertEquals(actionA, layout.hitRegions.hitAt(10f, 10f))
        assertEquals(actionB, layout.hitRegions.hitAt(50f, 50f))
        assertNull(layout.hitRegions.hitAt(150f, 150f))
    }

    @Test
    fun lowerRankWinsOverlapRegardlessOfInsertionOrder() {
        val lowRank = ComponentAction("low")
        val highRank = ComponentAction("high")
        val regions = listOf(
            BoardRegion(Rect(0f, 0f, 40f, 40f), highRank, rank = 5),
            BoardRegion(Rect(20f, 20f, 40f, 40f), lowRank, rank = 1),
        )

        val layout = component(regions).layout(testFrame(constraints(), model = appModel()))

        assertEquals(lowRank, layout.hitRegions.hitAt(30f, 30f))
    }

    @Test
    fun layoutPassesConstraintsAndViewToMeasure() {
        var seen: Constraints? = null
        var seenView: BoardView? = null
        val component = BoardComponent(
            route = route,
            table = { error("a fake measure must not construct PlayTableView") },
            preferences = preferences(),
            measure = { _, view, c, _, _ ->
                seenView = view
                seen = c
                BoardMeasurement(
                    listOf(BoardRegion(Rect(1f, 1f, 2f, 2f), ComponentAction("x"), 0)),
                )
            },
        )

        val constraints = constraints()
        val model = appModel()
        component.layout(testFrame(constraints, model = model))

        assertEquals(constraints, seen)
        assertEquals(boardView(model), seenView)
    }

    @Test
    fun actionsMapEveryBoardControl() {
        val choice = Choice.pung(2)

        val cases = listOf(
            ComponentAction(
                BoardActions.DISCARD,
                mapOf(
                    BoardActions.KEY_TILE_TYPE to 5,
                    BoardActions.KEY_VISUAL_ID to 7,
                    BoardActions.KEY_IS_DRAWN to true,
                ),
            ) to Intent.Discard(5, 7, true),
            ComponentAction(BoardActions.DISCARD, mapOf(BoardActions.KEY_TILE_TYPE to 3)) to
                Intent.Discard(3, -1, false),
            ComponentAction(BoardActions.CLAIM, mapOf(BoardActions.KEY_CHOICE to choice)) to
                Intent.ApplyChoice(choice),
            ComponentAction(BoardActions.HINT) to Intent.Hint,
            ComponentAction(BoardActions.SCORE_BREAKDOWN) to
                Intent.PushRoute(Route.Modal(RouteIds.SCORE_TABLE)),
            ComponentAction(
                BoardActions.PATTERN_INFO,
                mapOf(BoardActions.KEY_PAGE_ID to "1.1"),
            ) to Intent.OpenReference("1.1"),
            ComponentAction(
                BoardActions.PARK,
                mapOf(
                    BoardActions.KEY_CHROME to HintButtonOverlay.PARK_LABEL,
                    BoardActions.KEY_PARK_X to -30f,
                    BoardActions.KEY_PARK_Y to 12f,
                ),
            ) to Intent.ParkHintButton(-30f, 12f),
            ComponentAction(
                BoardActions.PARK,
                mapOf(
                    BoardActions.KEY_CHROME to ClaimActionPanel.PARK_LABEL,
                    BoardActions.KEY_PARK_X to 5f,
                    BoardActions.KEY_PARK_Y to -4f,
                ),
            ) to Intent.ParkClaimPanel(5f, -4f),
        )

        for ((action, expected) in cases) {
            assertEquals(expected, BoardActions.toIntent(route, action), "action=${action.id}")
        }

        assertNull(BoardActions.toIntent(route, ComponentAction("board.other")))
        assertNull(
            BoardActions.toIntent(route, ComponentAction(BoardActions.CLAIM)),
        )
        // A park that names no movable block the board knows maps to nothing.
        assertNull(
            BoardActions.toIntent(
                route,
                ComponentAction(BoardActions.PARK, mapOf(BoardActions.KEY_CHROME to "other")),
            ),
        )
    }

    private fun recordingFrame(): Pair<Frame, MutableList<ComponentAction>> {
        val emitted = mutableListOf<ComponentAction>()
        return testFrame(constraints()) { emitted += it } to emitted
    }

    private class FakeParkSurface(private val release: ParkRelease) : GestureSurface<ParkRelease> {
        override fun onPointerDown(x: Float, y: Float): Boolean = true

        override fun onPointerUp(x: Float, y: Float): ParkRelease = release
    }

    private fun componentWithSurface(surface: GestureSurface<ParkRelease>): BoardComponent = BoardComponent(
        route = route,
        table = { error("a fake surface must not construct PlayTableView") },
        preferences = preferences(),
        measure = { _, _, _, _, _ -> BoardMeasurement(emptyList()) },
        surface = { surface },
    )

    @Test
    fun consumedParkEmitsThePlaceAndConsumesTheRelease() {
        val (frame, emitted) = recordingFrame()
        val component = componentWithSurface(
            FakeParkSurface(
                ParkRelease(
                    consumed = true,
                    parkChromeLabel = ClaimActionPanel.PARK_LABEL,
                    parkX = 5f,
                    parkY = -4f,
                ),
            ),
        )

        assertTrue(component.onGestureRelease(10f, 20f, frame))
        assertEquals(
            listOf(
                ComponentAction(
                    BoardActions.PARK,
                    mapOf(
                        BoardActions.KEY_CHROME to ClaimActionPanel.PARK_LABEL,
                        BoardActions.KEY_PARK_X to 5f,
                        BoardActions.KEY_PARK_Y to -4f,
                    ),
                ),
            ),
            emitted,
        )
    }

    @Test
    fun tapReleaseFallsThroughWithoutAnAction() {
        val (frame, emitted) = recordingFrame()
        val component = componentWithSurface(FakeParkSurface(ParkRelease.Tap))

        assertFalse(component.onGestureRelease(10f, 20f, frame))
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun consumedReleaseWithoutABlockEmitsNothingButConsumes() {
        val (frame, emitted) = recordingFrame()
        val component = componentWithSurface(FakeParkSurface(ParkRelease.Consumed))

        assertTrue(component.onGestureRelease(10f, 20f, frame))
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun onlineSessionWithoutOnlineTableViewNeverFallsBackToLocalHand() {
        val onlineSession = SessionSnapshot(
            kind = SessionKind.Online,
            boardHosted = true,
            table = null,
            hand = HandSnapshot(active = true, humanSeat = 0, state = GameState()),
        )
        assertNull(
            boardTableView(onlineSession, emptySet()),
            "online session must never fall back to local hand state",
        )

        val freePlaySession = SessionSnapshot(
            kind = SessionKind.FreePlay,
            boardHosted = true,
            table = null,
            hand = HandSnapshot(active = true, humanSeat = 0, state = GameState()),
        )
        assertEquals(
            0,
            boardTableView(freePlaySession, emptySet())?.mySeat,
            "free-play session falls back to local hand state when table is absent",
        )
    }
}
