package inego.zungjunggame.tutorial.samples

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.DecisionType
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.GameStateLayout
import inego.zungjunggame.engine.PlayerState
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.render.table.BoardHighlight
import inego.zungjunggame.render.table.HighlightGroup
import inego.zungjunggame.render.table.HighlightWhere
import inego.zungjunggame.render.tiles.TileFocusGlow
import inego.zungjunggame.tutorial.ChoiceMatch
import inego.zungjunggame.tutorial.ScenarioDef
import inego.zungjunggame.tutorial.ScenarioLayoutSpec
import inego.zungjunggame.tutorial.TutorialFocus
import inego.zungjunggame.tutorial.TutorialStep
import inego.zungjunggame.tutorial.TutorialTargets
import inego.zungjunggame.tutorial.slide.SlidePresentation
import kotlin.random.Random

/**
 * Claiming chapter — pung, last discard, chow, hu, priority.
 * Pung and Chow are live-table walks: offer, claim, reveal, then discard.
 * Pung then notes that claiming skips Left's wall draw (they would have been next),
 * then a contrast snapshot: one copy of the discarded tile (Pass-only — third
 * for two, not a second for one).
 * Last discard is the claim window: You hold a pair of a tile already in
 * Opposite's river; the pending discard is something else (Pass-only).
 * Chow is claimed from Left; a later beat shows the same table with
 * Opposite discarding that tile (Pass-only — chow only from Left).
 * Hu is a pair wait: Opposite's discard completes the eyes, not a set.
 * Priority demonstrates the claim hierarchy (Pung beats Chow) and turn jump.
 *
 * The boards these units teach on are built by [ClaimScenarioStates].
 */
object ClaimScenarios {

    private const val YOU = ScenarioStateKit.YOU
    private const val RIGHT = ScenarioStateKit.RIGHT
    private const val OPPOSITE = ScenarioStateKit.OPPOSITE
    private const val LEFT = ScenarioStateKit.LEFT
    private const val CLICK_CALLOUT = "tutorial.claims.click_callout"

    private fun dummyLayout(): ScenarioLayoutSpec =
        ScenarioLayoutSpec.Factory {
            GameState.fromWallOrder(
                wallOrder = SampleScenarios.canonicalWall(Random(0)),
                eastSeat = 0,
                allowChickenHands = false,
            )
        }

    private fun claims(
        id: String,
        titleKey: String,
        steps: List<TutorialStep>,
        layout: ScenarioLayoutSpec = dummyLayout(),
        showsBoard: Boolean = false,
        revealedSeats: Set<Int> = emptySet(),
    ): ScenarioDef = ScenarioDef(
        id = id,
        titleKey = titleKey,
        layout = layout,
        steps = steps,
        humanSeat = 0,
        showsBoard = showsBoard,
        revealedSeats = revealedSeats,
    )

    fun pung(): ScenarioDef {
        val state = ClaimScenarioStates.pungOfferState()
        val onlyOne = ClaimScenarioStates.singletonNoPungState(avoidClaimTile = state.lastDiscarded)
        return claims(
            id = "claims-pung",
            titleKey = "tutorial.claims.pung.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            showsBoard = true,
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.claims.pung.offer",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(state.lastDiscarded),
                        calloutKey = CLICK_CALLOUT,
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.pung(state.lastDiscarded)),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("open pung claimed, 14-tile act") { s ->
                    val you = s.players[YOU]
                    val tile = state.lastDiscarded
                    you.pungsKongs[tile] == PlayerState.PK_OPEN_PUNG &&
                        s.decisionType == DecisionType.ActOnHand &&
                        s.currentPlayer == YOU
                },
                TutorialStep.Coach(
                    "tutorial.claims.pung.reveal",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(state.lastDiscarded),
                    ),
                ),
                TutorialStep.Coach(
                    "tutorial.claims.pung.must_discard",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.claims.pung.skips_left",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.claims.pung.third_for_two",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(targetId = "river.last"),
                    layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(onlyOne)),
                ),
                TutorialStep.Complete,
            ),
        )
    }

    /**
     * Lecture overlay: the claim window. You hold a pair of a tile already
     * sitting in Opposite's river; Opposite just discarded an honor You do
     * not hold. Pass-only — only the pending discard can be claimed.
     */
    fun lastDiscard(): ScenarioDef {
        val state = ClaimScenarioStates.staleRiverNoClaimState()
        val stale = state.discards[OPPOSITE].single()
        return claims(
            id = "claims-last-discard",
            titleKey = "tutorial.claims.last_discard.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            showsBoard = true,
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.claims.last_discard.only_fresh",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = "river.last",
                        calloutKey = "tutorial.claims.last_discard.callout",
                        highlight = BoardHighlight(
                            BoardHighlight.deny(stale, where = HighlightWhere.Rivers).groups +
                                BoardHighlight.prompt(stale).groups,
                        ),
                    ),
                ),
                TutorialStep.Complete,
            ),
        )
    }

    fun chow(): ScenarioDef {
        val state = ClaimScenarioStates.chowOfferState()
        val tile = state.lastDiscarded
        val firstTile = state.enumerateChoices().asList()
            .first { it.kind == ChoiceKind.Chow }
            .chowFirstTile()
        val sequence = intArrayOf(firstTile, firstTile + 1, firstTile + 2)
        val held = sequence.filter { it != tile }
        check(held.size == 2) { "chow companions for discard $tile in ${sequence.contentToString()}" }
        val notFromOther = ClaimScenarioStates.chowFromOppositeState(state)
        return claims(
            id = "claims-chow",
            titleKey = "tutorial.claims.chow.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            showsBoard = true,
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.claims.chow.offer",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        calloutKey = CLICK_CALLOUT,
                        highlight = BoardHighlight.prompt(held[0], held[1]),
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.chow(firstTile)),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("open chow claimed, 14-tile act") { s ->
                    val you = s.players[YOU]
                    val chowIndex = (firstTile / 9) * 7 + (firstTile % 9)
                    you.openChows[chowIndex] == 1 &&
                        you.meldCount == 1 &&
                        you.meldClaimTile[0] == tile &&
                        you.meldFrom[0] == LEFT &&
                        s.decisionType == DecisionType.ActOnHand &&
                        s.currentPlayer == YOU
                },
                TutorialStep.Coach(
                    "tutorial.claims.chow.reveal",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        highlight = BoardHighlight.prompt(sequence[0], sequence[1], sequence[2]),
                    ),
                ),
                TutorialStep.Coach(
                    "tutorial.claims.chow.must_discard",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.claims.chow.only_left",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = "river.last",
                        highlight = BoardHighlight.denyPendingAndPromptYou(
                            held[0],
                            held[1],
                        ),
                    ),
                    layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(notFromOther)),
                ),
                TutorialStep.Complete,
            ),
        )
    }

    fun hu(): ScenarioDef {
        val state = ClaimScenarioStates.huPairWaitOfferState()
        val tile = state.lastDiscarded
        return claims(
            id = "claims-hu",
            titleKey = "tutorial.claims.hu.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            showsBoard = true,
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.claims.hu.offer",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        calloutKey = CLICK_CALLOUT,
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.hu()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("hu claimed on pair wait") { s ->
                    s.handScore > 0 &&
                        s.currentPlayer == YOU &&
                        s.isTerminal()
                },
                TutorialStep.Coach(
                    "tutorial.claims.hu.not_a_set",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                    ),
                ),
                TutorialStep.Coach(
                    "tutorial.claims.hu.breakdown",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Complete,
            ),
        )
    }

    fun priority(): ScenarioDef {
        val state = ClaimScenarioStates.priorityOfferState()
        val claimTile = state.lastDiscarded
        return claims(
            id = "claims-priority",
            titleKey = "tutorial.claims.priority.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            showsBoard = true,
            revealedSeats = setOf(RIGHT),
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.claims.priority.conflict",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    revealedSeats = setOf(RIGHT),
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(claimTile),
                        calloutKey = CLICK_CALLOUT,
                        highlight = BoardHighlight(
                            listOf(
                                HighlightGroup(
                                    types = listOf(claimTile),
                                    glow = TileFocusGlow.Prompt,
                                    where = HighlightWhere.PendingDiscard,
                                ),
                                HighlightGroup(
                                    types = listOf(Tiles.BAMBOO_1, Tiles.BAMBOO_2),
                                    glow = TileFocusGlow.Secondary,
                                    where = HighlightWhere.YouHand,
                                ),
                                HighlightGroup(
                                    types = listOf(claimTile),
                                    glow = TileFocusGlow.Prompt,
                                    where = HighlightWhere.OpponentHand,
                                ),
                            ),
                        ),
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.chow(Tiles.BAMBOO_1)),
                TutorialStep.AssertState("pung overrides chow, tile goes to right") { s ->
                    s.players[RIGHT].pungsKongs[claimTile] == PlayerState.PK_OPEN_PUNG &&
                        s.decisionType == DecisionType.ActOnHand &&
                        s.currentPlayer == RIGHT
                },
                TutorialStep.Coach(
                    "tutorial.claims.priority.pung_beats_chow",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    revealedSeats = setOf(RIGHT),
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(claimTile),
                        highlight = BoardHighlight(
                            listOf(
                                HighlightGroup(
                                    types = listOf(claimTile),
                                    glow = TileFocusGlow.Prompt,
                                    where = HighlightWhere.Open,
                                ),
                            ),
                        ),
                    ),
                ),
                TutorialStep.Coach(
                    "tutorial.claims.priority.hierarchy",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    revealedSeats = setOf(RIGHT),
                ),
                TutorialStep.Coach(
                    "tutorial.claims.priority.multi_win",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    revealedSeats = setOf(RIGHT),
                ),
                TutorialStep.Complete,
            ),
        )
    }
}
