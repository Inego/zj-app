package inego.zungjunggame.input

import com.badlogic.gdx.Input
import inego.zungjunggame.engine.Choice
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.ui.stack.LayerRole
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.Stack

/** Per-frame facts a keyboard decision depends on (mirrors the legacy gates). */
data class KeyboardContext(
    val textFieldActive: Boolean,
    val gameActive: Boolean,
    val allowHint: Boolean,
    val allowNextHand: Boolean,
    val referenceVisible: Boolean,
    val scoreTableVisible: Boolean,
    val canDiscardDrawnTile: Boolean = false,
    /**
     * The one action the open panel leaves the human (the sole non-pass, non-discard
     * button: a lone claim, or a self-drawn win beside discards), else null. Enter
     * accepts it.
     */
    val unambiguousClaim: Choice? = null,
    /**
     * The decline action (Pass of an open claim reaction or Keep of a concealed
     * kong gate) when no Hu is on offer, else null. Space declines it.
     */
    val nonHuDecline: Choice? = null,
    /** True when the hand is finished and the session can deal the next one. */
    val nextHandReady: Boolean = false,
)

/**
 * Maps one key press to [Intent]s through the new pipeline.
 *
 * Global accelerators are applied first with the legacy gates (F11 always;
 * the hint key only while a game is active and no text field owns the
 * keyboard). Per-overlay keys are then read top-down over the paint range; a
 * modal that owns the keyboard stops the walk, while scrolling overlays are
 * transparent and let the board floor handle the key. Esc/back never reach here
 * — the device backend reports it as [InputEvent.SystemBack], which becomes
 * [Intent.Back].
 */
class KeyboardArbiter {
    /** @return intents for [keycode]; empty when the key is unhandled. */
    fun intentsFor(
        keycode: Int,
        ctrl: Boolean,
        shift: Boolean,
        stack: Stack,
        ctx: KeyboardContext,
    ): List<Intent> {
        val out = ArrayList<Intent>(2)

        if (keycode == Input.Keys.F11) out += Intent.ToggleFullscreen

        if (ctx.textFieldActive) return out

        for (instance in stack.paintRange.asReversed()) {
            // The floor and the context layers above it are what the board keys act
            // on, not chrome that could claim one: a key the walk reaches them with
            // is the board's (see [boardKeys]).
            if (instance.spec.role == LayerRole.Screen ||
                instance.spec.role == LayerRole.Context
            ) {
                continue
            }
            val id = RouteIds.catalogIdOf(instance.route)
            when (id) {
                RouteIds.CONFIRM -> {
                    when (keycode) {
                        Input.Keys.Y, Input.Keys.ENTER, Input.Keys.NUMPAD_ENTER ->
                            out += Intent.ConfirmYes
                        Input.Keys.N -> out += Intent.ConfirmNo
                    }
                    return out
                }
                RouteIds.AWAY -> {
                    when (keycode) {
                        Input.Keys.ENTER, Input.Keys.NUMPAD_ENTER, Input.Keys.SPACE ->
                            out += Intent.AwayResume
                    }
                    return out
                }
                RouteIds.NEW_GAME_SETUP -> {
                    if (keycode == Input.Keys.ENTER || keycode == Input.Keys.NUMPAD_ENTER) {
                        out += Intent.NewGame
                    }
                    return out
                }
                RouteIds.SCORE_TABLE, RouteIds.MATCH_HISTORY, RouteIds.LEADERBOARD -> Unit
                RouteIds.SHADOW_RANKED_HANDS -> {
                    when (keycode) {
                        Input.Keys.LEFT -> out += Intent.ShadowRankedHandsStep(-1)
                        Input.Keys.RIGHT -> out += Intent.ShadowRankedHandsStep(1)
                    }
                    return out
                }
                RouteIds.REFERENCE -> {
                    if (keycode == Input.Keys.BACKSPACE) out += Intent.ReferenceBack
                    return out
                }
                RouteIds.SCORE_BREAKDOWN, RouteIds.HINT_BUTTON,
                RouteIds.TUTORIAL_COACH, RouteIds.TUTORIAL_QUIZ,
                RouteIds.TUTORIAL_LESSON,
                -> Unit
                else -> return out
            }
        }

        out += boardKeys(keycode, ctrl, shift, stack, ctx)
        return out
    }

    private fun boardKeys(
        keycode: Int,
        ctrl: Boolean,
        shift: Boolean,
        stack: Stack,
        ctx: KeyboardContext,
    ): List<Intent> {
        val out = ArrayList<Intent>(1)
        when (keycode) {
            Input.Keys.T -> if (ctx.gameActive) {
                val open = stack.contains(Route.Modal(RouteIds.SCORE_TABLE).key)
                out += if (open) Intent.Back else Intent.PushRoute(Route.Modal(RouteIds.SCORE_TABLE))
            }
            Input.Keys.N -> if (ctx.allowNextHand) out += Intent.NextHand
            Input.Keys.ENTER, Input.Keys.NUMPAD_ENTER -> {
                val claim = ctx.unambiguousClaim
                if (claim != null) {
                    out += Intent.ApplyChoice(claim)
                } else if (ctx.nextHandReady) {
                    out += Intent.NextHand
                }
            }
            Input.Keys.H -> if (ctx.allowHint && !ctx.scoreTableVisible) out += Intent.Hint
            Input.Keys.SPACE -> {
                val decline = ctx.nonHuDecline
                when {
                    ctrl && ctx.allowHint && !ctx.scoreTableVisible -> out += Intent.AiAssist
                    decline != null && !ctrl && !shift && !ctx.scoreTableVisible ->
                        out += Intent.ApplyChoice(decline)
                    !ctrl && !shift && ctx.canDiscardDrawnTile && !ctx.scoreTableVisible ->
                        out += Intent.DiscardDrawnTile
                }
            }
            Input.Keys.R -> if (ctrl && !ctx.referenceVisible) out += Intent.OpenReference()
        }
        return out
    }
}
