package inego.zungjunggame.input

import com.badlogic.gdx.Input
import inego.zungjunggame.engine.Choice
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.LayerSpec
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.RouteInstance
import inego.zungjunggame.ui.stack.Stack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyboardArbiterTest {

    private class StubComponent(override val id: ComponentId) : Component {
        override fun layout(frame: Frame): Layout = Layout()
        override fun draw(frame: Frame, canvas: Canvas) {}
    }

    private fun instance(route: Route, role: LayerRole): RouteInstance =
        RouteInstance(route, StubComponent(ComponentId(route.key.value)), LayerSpec.defaultsFor(role))

    private val floor: RouteInstance = instance(Route.Screen(RouteIds.LOCAL_BOARD), LayerRole.Screen)

    private fun stackOf(vararg above: RouteInstance): Stack =
        Stack(listOf(floor, *above))

    private fun ctx(
        textFieldActive: Boolean = false,
        gameActive: Boolean = true,
        allowHint: Boolean = true,
        allowNextHand: Boolean = true,
        referenceVisible: Boolean = false,
        scoreTableVisible: Boolean = false,
        canDiscardDrawnTile: Boolean = false,
        unambiguousClaim: Choice? = null,
        nonHuDecline: Choice? = null,
        nextHandReady: Boolean = false,
    ): KeyboardContext = KeyboardContext(
        textFieldActive = textFieldActive,
        gameActive = gameActive,
        allowHint = allowHint,
        allowNextHand = allowNextHand,
        referenceVisible = referenceVisible,
        scoreTableVisible = scoreTableVisible,
        canDiscardDrawnTile = canDiscardDrawnTile,
        unambiguousClaim = unambiguousClaim,
        nonHuDecline = nonHuDecline,
        nextHandReady = nextHandReady,
    )

    private val keyboard = KeyboardArbiter()

    @Test
    fun f11TogglesFullscreenEvenWhileTyping() {
        assertEquals(
            listOf(Intent.ToggleFullscreen),
            keyboard.intentsFor(Input.Keys.F11, ctrl = false,
                shift = false,
                stack = stackOf(),
                ctx = ctx(textFieldActive = true)
            ),
        )
    }

    @Test
    fun ctrlAIsNotBoundToAnything() {
        // Auto play and Turbo play were removed; Ctrl+A / Ctrl+Shift+A must now be
        // dead keys rather than falling through to some other board action.
        assertTrue(keyboard.intentsFor(Input.Keys.A, ctrl = true, shift = false, stack = stackOf(), ctx = ctx()).isEmpty())
        assertTrue(keyboard.intentsFor(Input.Keys.A, ctrl = true, shift = true, stack = stackOf(), ctx = ctx()).isEmpty())
    }

    @Test
    fun confirmKeysMapYesAndNo() {
        val confirm = instance(Route.Modal(RouteIds.CONFIRM), LayerRole.Modal)
        val stack = stackOf(confirm)

        assertEquals(listOf(Intent.ConfirmYes), keyboard.intentsFor(Input.Keys.Y, ctrl = false,
            shift = false,
            stack = stack,
            ctx = ctx()
        ))
        assertEquals(
            listOf(Intent.ConfirmYes),
            keyboard.intentsFor(Input.Keys.ENTER, ctrl = false, shift = false, stack = stack, ctx = ctx()),
        )
        assertEquals(listOf(Intent.ConfirmNo), keyboard.intentsFor(Input.Keys.N, ctrl = false,
            shift = false,
            stack = stack,
            ctx = ctx()
        ))
        assertTrue(
            keyboard.intentsFor(Input.Keys.H, ctrl = false, shift = false, stack = stack, ctx = ctx()).isEmpty(),
            "the modal owns the keyboard for unmapped keys",
        )
    }

    @Test
    fun awayAndSetupKeysMapToTheirIntents() {
        val away = instance(Route.Overlay(RouteIds.AWAY), LayerRole.Overlay)
        assertEquals(
            listOf(Intent.AwayResume),
            keyboard.intentsFor(Input.Keys.ENTER, ctrl = false, shift = false, stack = stackOf(away), ctx = ctx()),
        )

        val setup = instance(Route.Modal(RouteIds.NEW_GAME_SETUP), LayerRole.Modal)
        assertEquals(
            listOf(Intent.NewGame),
            keyboard.intentsFor(Input.Keys.ENTER, ctrl = false, shift = false, stack = stackOf(setup), ctx = ctx()),
        )
    }

    @Test
    fun scoreTableKeyTogglesAndBoardKeysMap() {
        // The realistic stack: a context layer (a board) above the root floor. Board
        // keys are the board's, so a context layer must not swallow them on the way
        // down — only a chrome layer can claim one.
        val board = instance(Route.Layer(RouteIds.LOCAL_BOARD), LayerRole.Context)
        val boardStack = stackOf(board)

        assertEquals(
            listOf(Intent.PushRoute(Route.Modal(RouteIds.SCORE_TABLE))),
            keyboard.intentsFor(Input.Keys.T, ctrl = false, shift = false, stack = boardStack, ctx = ctx()),
        )

        val open = instance(Route.Modal(RouteIds.SCORE_TABLE), LayerRole.Modal)
        assertEquals(
            listOf(Intent.Back),
            keyboard.intentsFor(Input.Keys.T, ctrl = false, shift = false, stack = stackOf(board, open), ctx = ctx()),
        )

        assertEquals(listOf(Intent.Hint), keyboard.intentsFor(Input.Keys.H, ctrl = false,
            shift = false,
            stack = boardStack,
            ctx = ctx()
        ))
        assertEquals(listOf(Intent.NextHand), keyboard.intentsFor(Input.Keys.N, ctrl = false,
            shift = false,
            stack = boardStack,
            ctx = ctx()
        ))
        assertEquals(
            listOf(Intent.OpenReference()),
            keyboard.intentsFor(Input.Keys.R, ctrl = true, shift = false, stack = boardStack, ctx = ctx()),
        )
        assertEquals(
            listOf(Intent.AiAssist),
            keyboard.intentsFor(Input.Keys.SPACE, ctrl = true, shift = false, stack = boardStack, ctx = ctx()),
        )
    }

    @Test
    fun textFieldActiveSuppressesBoardShortcuts() {
        assertTrue(
            keyboard.intentsFor(Input.Keys.T, ctrl = false,
                shift = false,
                stack = stackOf(),
                ctx = ctx(textFieldActive = true)
            ).isEmpty(),
        )
        assertTrue(
            keyboard.intentsFor(Input.Keys.H, ctrl = false,
                shift = false,
                stack = stackOf(),
                ctx = ctx(textFieldActive = true)
            ).isEmpty(),
        )
    }

    @Test
    fun hintAndAiAssistBlockedWhenScoreTableVisible() {
        assertTrue(
            keyboard.intentsFor(Input.Keys.H, ctrl = false,
                shift = false,
                stack = stackOf(),
                ctx = ctx(scoreTableVisible = true)
            ).isEmpty(),
        )
        assertTrue(
            keyboard.intentsFor(Input.Keys.SPACE, ctrl = true,
                shift = false,
                stack = stackOf(),
                ctx = ctx(scoreTableVisible = true)
            ).isEmpty(),
        )
    }

    @Test
    fun spaceDiscardsDrawnTileWhenAllowed() {
        val board = instance(Route.Layer(RouteIds.LOCAL_BOARD), LayerRole.Context)
        val boardStack = stackOf(board)

        // Space maps to DiscardDrawnTile when allowed
        assertEquals(
            listOf(Intent.DiscardDrawnTile),
            keyboard.intentsFor(Input.Keys.SPACE, ctrl = false,
                shift = false,
                stack = boardStack,
                ctx = ctx(canDiscardDrawnTile = true)
            ),
        )

        // Space unhandled when canDiscardDrawnTile is false
        assertTrue(
            keyboard.intentsFor(Input.Keys.SPACE, ctrl = false,
                shift = false,
                stack = boardStack,
                ctx = ctx(canDiscardDrawnTile = false)
            ).isEmpty(),
        )

        // Blocked when score table is visible
        assertTrue(
            keyboard.intentsFor(
                Input.Keys.SPACE,
                ctrl = false,
                shift = false,
                stack = boardStack,
                ctx = ctx(canDiscardDrawnTile = true, scoreTableVisible = true),
            ).isEmpty(),
        )

        // Blocked when text field is active
        assertTrue(
            keyboard.intentsFor(
                Input.Keys.SPACE,
                ctrl = false,
                shift = false,
                stack = boardStack,
                ctx = ctx(canDiscardDrawnTile = true, textFieldActive = true),
            ).isEmpty(),
        )

        // Shift+Space does not discard
        assertTrue(
            keyboard.intentsFor(
                Input.Keys.SPACE,
                ctrl = false,
                shift = true,
                stack = boardStack,
                ctx = ctx(canDiscardDrawnTile = true),
            ).isEmpty(),
        )

        // Ctrl+Space still maps to AiAssist
        assertEquals(
            listOf(Intent.AiAssist),
            keyboard.intentsFor(Input.Keys.SPACE, ctrl = true,
                shift = false,
                stack = boardStack,
                ctx = ctx(canDiscardDrawnTile = true)
            ),
        )
    }

    @Test
    fun enterAcceptsUnambiguousClaimOrAdvancesAtHandEnd() {
        val board = instance(Route.Layer(RouteIds.LOCAL_BOARD), LayerRole.Context)
        val boardStack = stackOf(board)
        val claim = Choice.pung(5)

        assertEquals(
            listOf(Intent.ApplyChoice(claim)),
            keyboard.intentsFor(Input.Keys.ENTER, ctrl = false,
                shift = false,
                stack = boardStack,
                ctx = ctx(unambiguousClaim = claim)
            ),
        )
        assertEquals(
            listOf(Intent.ApplyChoice(claim)),
            keyboard.intentsFor(Input.Keys.NUMPAD_ENTER, ctrl = false,
                shift = false,
                stack = boardStack,
                ctx = ctx(unambiguousClaim = claim)
            ),
        )

        // A finished hand turns Enter into Next hand.
        assertEquals(
            listOf(Intent.NextHand),
            keyboard.intentsFor(Input.Keys.ENTER, ctrl = false,
                shift = false,
                stack = boardStack,
                ctx = ctx(nextHandReady = true)
            ),
        )

        // Mid-hand with a choice to make, Enter is deliberately inert.
        assertTrue(keyboard.intentsFor(Input.Keys.ENTER, ctrl = false, shift = false, stack = boardStack, ctx = ctx()).isEmpty())
    }

    @Test
    fun spaceDeclinesNonHuClaimOrKeep() {
        val board = instance(Route.Layer(RouteIds.LOCAL_BOARD), LayerRole.Context)
        val boardStack = stackOf(board)
        val pass = Choice.pass()
        val keep = Choice.keep(3)

        assertEquals(
            listOf(Intent.ApplyChoice(pass)),
            keyboard.intentsFor(Input.Keys.SPACE, ctrl = false,
                shift = false,
                stack = boardStack,
                ctx = ctx(nonHuDecline = pass)
            ),
        )
        assertEquals(
            listOf(Intent.ApplyChoice(keep)),
            keyboard.intentsFor(Input.Keys.SPACE, ctrl = false,
                shift = false,
                stack = boardStack,
                ctx = ctx(nonHuDecline = keep)
            ),
        )

        // No Pass/Keep on a Hu claim (nonHuDecline is null then), and a modifier keeps
        // Space for its own binding.
        assertTrue(keyboard.intentsFor(Input.Keys.SPACE, ctrl = false, shift = false, stack = boardStack, ctx = ctx()).isEmpty())
        assertTrue(
            keyboard.intentsFor(Input.Keys.SPACE,
                ctrl = false,
                shift = true,
                stack = boardStack,
                ctx = ctx(nonHuDecline = pass)
            ).isEmpty(),
        )
    }
}
