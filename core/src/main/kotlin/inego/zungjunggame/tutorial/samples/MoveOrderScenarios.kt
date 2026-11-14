package inego.zungjunggame.tutorial.samples

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.engine.WALL_SIZE
import inego.zungjunggame.render.table.BoardHighlight
import inego.zungjunggame.render.table.HighlightWhere
import inego.zungjunggame.tutorial.ChoiceMatch
import inego.zungjunggame.tutorial.ScenarioDef
import inego.zungjunggame.tutorial.ScenarioLayoutSpec
import inego.zungjunggame.tutorial.TutorialDiagram
import inego.zungjunggame.tutorial.TutorialFocus
import inego.zungjunggame.tutorial.TutorialStep
import inego.zungjunggame.tutorial.TutorialTargets
import inego.zungjunggame.platform.newRandom
import inego.zungjunggame.tutorial.slide.SlidePresentation
import kotlin.random.Random

/**
 * Move order chapter — East / seat winds, discards, turn order.
 */
object MoveOrderScenarios {

    private fun dummyLayout(): ScenarioLayoutSpec =
        ScenarioLayoutSpec.Factory {
            GameState.fromWallOrder(
                wallOrder = SampleScenarios.canonicalWall(Random(0)),
                eastSeat = 0,
                allowChickenHands = false,
            )
        }

    /**
     * Official chicken shape as East's opening 14 (Blessing of Heaven):
     * `{567s}{333m}{456m}{234s}{FF}`. Seat 0 is East / human (bottom).
     */
    private fun blessingOfHeavenChickenLayout(): ScenarioLayoutSpec =
        ScenarioLayoutSpec.WallOrder(
            wall = blessingOfHeavenChickenWall(),
            eastSeat = 0,
        )

    private fun blessingOfHeavenChickenWall(): IntArray {
        val east14 = intArrayOf(
            Tiles.BAMBOO_5, Tiles.BAMBOO_6, Tiles.BAMBOO_7,
            Tiles.CHAR_3, Tiles.CHAR_3, Tiles.CHAR_3,
            Tiles.CHAR_4, Tiles.CHAR_5, Tiles.CHAR_6,
            Tiles.BAMBOO_2, Tiles.BAMBOO_3, Tiles.BAMBOO_4,
            Tiles.RED, Tiles.RED,
        )
        val used = IntArray(NUM_TILE_TYPES)
        for (tile in east14) used[tile]++
        val leftover = ArrayList<Int>(WALL_SIZE - east14.size)
        for (tile in 0 until NUM_TILE_TYPES) {
            repeat(4 - used[tile]) { leftover.add(tile) }
        }
        val wall = IntArray(WALL_SIZE)
        for (i in 0..12) wall[i] = east14[i]
        // Opening deal: 13 each to seats 0..3, then East's 14th at index 52.
        wall[52] = east14[13]
        var next = 0
        for (i in wall.indices) {
            if (i in 0..12 || i == 52) continue
            wall[i] = leftover[next++]
        }
        return wall
    }

    private fun moves(
        id: String,
        titleKey: String,
        steps: List<TutorialStep>,
        layout: ScenarioLayoutSpec = dummyLayout(),
        showsBoard: Boolean = false,
    ): ScenarioDef = ScenarioDef(
        id = id,
        titleKey = titleKey,
        layout = layout,
        steps = steps,
        humanSeat = 0,
        showsBoard = showsBoard,
    )

    private fun randomOpeningWall(rng: Random = newRandom()): IntArray {
        (0 until 128).forEach { _ ->
            val wall = SampleScenarios.canonicalWall(rng)
            val state = GameState.fromWallOrder(wall, eastSeat = 0, allowChickenHands = false)
            val choices = state.enumerateChoices().asList()
            val canDiscard = choices.any { it.kind == ChoiceKind.Discard }
            val canHu = choices.any { it.kind == ChoiceKind.Hu }
            if (canDiscard && !canHu) {
                return wall
            }
        }
        return SampleScenarios.canonicalWall(rng)
    }

    private fun randomOpeningLayout(rng: Random = newRandom()): ScenarioLayoutSpec =
        ScenarioLayoutSpec.WallOrder(
            wall = randomOpeningWall(rng),
            eastSeat = 0,
        )

    private data class TurnOrderOpening(
        val layout: ScenarioLayoutSpec,
        val eastDiscard: Int,
        val rightDiscard: Int,
    )

    /** Prefer a singleton discard; skip a winning opening hand. */
    private fun pickDiscardTile(state: GameState): Int? {
        val choices = state.enumerateChoices().asList()
        if (choices.any { it.kind == ChoiceKind.Hu }) return null
        val discards = choices.filter { it.kind == ChoiceKind.Discard }
        if (discards.isEmpty()) return null
        val concealed = state.players[state.currentPlayer].concealed
        val singleton = discards.firstOrNull { concealed[it.tile()] == 1 }
        return (singleton ?: discards.first()).tile()
    }

    private fun passRemainingReactions(state: GameState) {
        var guard = 0
        while (state.decisionType == DecisionType.ReactToDiscard && guard++ < 8) {
            val pass = state.enumerateChoices().asList()
                .firstOrNull { it.kind == ChoiceKind.Pass } ?: break
            state.applyChoice(pass)
        }
    }

    /**
     * East opening discard (not a win) plus a later Right discard after the
     * unclaimed tile passes and Right draws.
     */
    private fun turnOrderOpening(rng: Random = newRandom()): TurnOrderOpening {
        (0 until 256).forEach { _ ->
            val wall = SampleScenarios.canonicalWall(rng)
            val state = GameState.fromWallOrder(wall, eastSeat = 0, allowChickenHands = false)
            val eastDiscard = pickDiscardTile(state) ?: return@forEach
            val trial = state.copy()
            trial.applyChoice(Choice.discard(eastDiscard))
            passRemainingReactions(trial)
            if (trial.currentPlayer != 1) return@forEach
            if (trial.decisionType != DecisionType.ActOnHand) return@forEach
            val rightDiscard = pickDiscardTile(trial) ?: return@forEach
            trial.applyChoice(Choice.discard(rightDiscard))
            passRemainingReactions(trial)
            if (trial.currentPlayer != 2) return@forEach
            if (trial.decisionType != DecisionType.ActOnHand) return@forEach
            return TurnOrderOpening(
                layout = ScenarioLayoutSpec.WallOrder(wall = wall, eastSeat = 0),
                eastDiscard = eastDiscard,
                rightDiscard = rightDiscard,
            )
        }
        error("could not find a turn-order opening (East discard, then Right discard)")
    }

    fun eastPlayer(): ScenarioDef = moves(
        id = "moves-east-player",
        titleKey = "tutorial.moves.east_player.title",
        steps = listOf(
            TutorialStep.Coach(
                "tutorial.moves.east_player.four",
                diagram = TutorialDiagram.SeatWinds,
                presentation = SlidePresentation.DiagramIntro,
            ),
            TutorialStep.Coach(
                "tutorial.moves.east_player.pass",
                diagram = TutorialDiagram.SeatWinds,
                presentation = SlidePresentation.Standard,
            ),
            TutorialStep.Complete,
        ),
    )

    fun discarding(): ScenarioDef = moves(
        id = "moves-discarding",
        titleKey = "tutorial.moves.discarding.title",
        layout = blessingOfHeavenChickenLayout(),
        steps = listOf(
            TutorialStep.Coach("tutorial.moves.discarding.east_14"),
            TutorialStep.Coach(
                "tutorial.moves.discarding.east_hu",
                presentation = SlidePresentation.Standard,
                showsBoard = true,
            ),
            TutorialStep.Coach(
                "tutorial.moves.discarding.east_hu_odds",
                presentation = SlidePresentation.Standard,
                showsBoard = true,
            ),
            TutorialStep.Coach(
                "tutorial.moves.discarding.must_discard",
                presentation = SlidePresentation.Standard,
                showsBoard = true,
                layout = randomOpeningLayout(),
            ),
            TutorialStep.Coach(
                "tutorial.moves.discarding.least_useful",
                presentation = SlidePresentation.Standard,
                showsBoard = true,
            ),
            TutorialStep.Coach(
                "tutorial.moves.discarding.others_claim",
                presentation = SlidePresentation.Standard,
                showsBoard = true,
            ),
            TutorialStep.Complete,
        ),
    )

    fun counterclockwise(): ScenarioDef {
        val opening = turnOrderOpening()
        val eastTile = opening.eastDiscard
        val rightTile = opening.rightDiscard
        return moves(
            id = "moves-counterclockwise",
            titleKey = "tutorial.moves.counterclockwise.title",
            layout = opening.layout,
            showsBoard = true,
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.moves.counterclockwise.this_tile",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(eastTile),
                        calloutKey = "tutorial.moves.counterclockwise.click_callout",
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.discard(eastTile)),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("unclaimed discard passed to the right") { s ->
                    s.lastDiscarder == 0 &&
                        s.discards[0].lastOrNull() == eastTile &&
                        s.currentPlayer == 1
                },
                TutorialStep.Coach(
                    "tutorial.moves.counterclockwise.if_nobody",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.moves.counterclockwise.the_river",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(eastTile),
                        highlight = BoardHighlight.prompt(
                            eastTile,
                            where = HighlightWhere.Rivers,
                        ),
                    ),
                ),
                TutorialStep.Coach(
                    "tutorial.moves.counterclockwise.to_the_right",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.moves.counterclockwise.right_next",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.ScriptChoice(Choice.discard(rightTile)),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("Right discarded and turn passed to Opposite") { s ->
                    s.lastDiscarder == 1 &&
                        s.discards[1].lastOrNull() == rightTile &&
                        s.currentPlayer == 2 &&
                        s.decisionType == DecisionType.ActOnHand &&
                        s.lastDrawnPlayer == 2
                },
                TutorialStep.Coach(
                    "tutorial.moves.counterclockwise.right_discards",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Complete,
            ),
        )
    }
}
