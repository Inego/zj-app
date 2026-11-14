package inego.zungjunggame.tutorial.samples

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.GameStateLayout
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.render.table.BoardHighlight
import inego.zungjunggame.tutorial.ChoiceMatch
import inego.zungjunggame.tutorial.ScenarioDef
import inego.zungjunggame.tutorial.ScenarioLayoutSpec
import inego.zungjunggame.tutorial.TutorialDiagram
import inego.zungjunggame.tutorial.TutorialFocus
import inego.zungjunggame.tutorial.TutorialStep
import inego.zungjunggame.tutorial.TutorialTargets
import inego.zungjunggame.tutorial.slide.SlidePresentation
import kotlin.random.Random

/**
 * Hands and scoring chapter — winning shapes, then live-table scoring
 * (chicken hand, concealed hand). Diagram units keep [showsBoard] false.
 *
 * The waiting hands each unit teaches with are built by [HandsScenarioStates].
 */
object HandsScenarios {

    private const val YOU = ScenarioStateKit.YOU
        private const val CLICK_CALLOUT = "tutorial.claims.click_callout"

    /** Shared dummy board for the runner; never drawn. */
    private fun dummyLayout(): ScenarioLayoutSpec =
        ScenarioLayoutSpec.Factory {
            GameState.fromWallOrder(
                wallOrder = SampleScenarios.canonicalWall(Random(0)),
                eastSeat = 0,
                allowChickenHands = false,
            )
        }

    private fun hands(
        id: String,
        titleKey: String,
        steps: List<TutorialStep>,
    ): ScenarioDef = ScenarioDef(
        id = id,
        titleKey = titleKey,
        layout = dummyLayout(),
        steps = steps,
        humanSeat = 0,
        showsBoard = false,
    )

    fun winningHand(): ScenarioDef = hands(
        id = "hands-winning",
        titleKey = "tutorial.hands.win.title",
        steps = listOf(
            TutorialStep.Coach("tutorial.hands.win.hand_size"),
            TutorialStep.Coach(
                "tutorial.hands.win.patterns",
                diagram = TutorialDiagram.RegularHand,
                presentation = SlidePresentation.DiagramIntro,
            ),
            TutorialStep.Coach(
                "tutorial.hands.win.all_in_groups",
                diagram = TutorialDiagram.RegularHand,
                presentation = SlidePresentation.Standard,
            ),
            TutorialStep.Coach(
                "tutorial.hands.win.set_kinds",
                diagram = TutorialDiagram.SetKinds,
                presentation = SlidePresentation.DiagramIntro,
            ),
            TutorialStep.Coach(
                "tutorial.hands.win.kong_size",
                diagram = TutorialDiagram.RegularHand,
                focus = TutorialFocus(TutorialTargets.tile(Tiles.BAMBOO_8)),
                presentation = SlidePresentation.FocusBeat,
            ),
            TutorialStep.Complete,
        ),
    )

    /**
     * Lecture overlay: Opposite discarded the pair wait. You already have
     * one open pung, so the hand is not concealed; sequences + a triplet +
     * terminals keep every 5-point basic pattern off. Engine offers Hu 1 / Pass.
     */
    fun chickenHand(): ScenarioDef {
        val state = HandsScenarioStates.chickenOfferState()
        return liveHands(
            id = "hands-chicken",
            titleKey = "tutorial.hands.chicken.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.hands.chicken.one",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.hands.chicken.tournament",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.hands.chicken.next",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Complete,
            ),
        )
    }

    /**
     * Action preamble: Opposite discarded a mid-sequence tile (5-dot) completing
     * 4-5-6 dot. You cannot chow from Opposite during regular play, but claiming
     * Hu on any discard is legal and does not break Concealed Hand.
     * All sets were drawn from the wall; engine offers Hu 5 / Pass.
     */
    fun concealedHand(): ScenarioDef {
        val state = HandsScenarioStates.concealedOfferState()
        val tile = state.lastDiscarded
        return liveHands(
            id = "hands-concealed",
            titleKey = "tutorial.hands.concealed.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.hands.concealed.offer",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        calloutKey = CLICK_CALLOUT,
                        highlight = BoardHighlight.prompt(Tiles.DOT_4, Tiles.DOT_6),
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.hu()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("hu claimed on concealed hand (5 pts)") { s ->
                    s.handScore == 5 &&
                        s.currentPlayer == YOU &&
                        s.isTerminal()
                },
                TutorialStep.Coach(
                    "tutorial.hands.concealed.points",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.hands.concealed.concealed_rule",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.hands.concealed.discard_win",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.hands.concealed.kongs",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Complete,
            ),
        )
    }

    /**
     * Action preamble: You already hold an open chow (1-2-3 man) claimed from
     * Left, plus concealed 456p, 234s, pair of Red, and 78s.
     * Opposite discards 9s, completing the 4th sequence.
     * With all four sets as sequences, the engine offers Hu 5 / Pass for
     * All Sequences (5 pts) even though the hand is open.
     */
    fun allSequences(): ScenarioDef {
        val state = HandsScenarioStates.allSequencesOfferState()
        val tile = state.lastDiscarded
        return liveHands(
            id = "hands-all-sequences",
            titleKey = "tutorial.hands.all_sequences.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.hands.all_sequences.offer",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        calloutKey = CLICK_CALLOUT,
                        highlight = BoardHighlight.prompt(Tiles.BAMBOO_7, Tiles.BAMBOO_8),
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.hu()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("hu claimed on all sequences (5 pts)") { s ->
                    s.handScore == 5 &&
                        s.currentPlayer == YOU &&
                        s.isTerminal()
                },
                TutorialStep.Coach(
                    "tutorial.hands.all_sequences.points",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.hands.all_sequences.open_allowed",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.hands.all_sequences.unrestricted",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Complete,
            ),
        )
    }

    /**
     * Action preamble: You hold an open pung of 5-dot claimed from Opposite,
     * plus concealed 234m, 345s, a pair of 8m, and 67s waiting on 8s.
     * Opposite discards 8s. Every tile in the hand is a simple (2-8).
     * The engine offers Hu 5 / Pass for No Terminals (5 pts).
     */
    fun noTerminals(): ScenarioDef {
        val state = HandsScenarioStates.noTerminalsOfferState()
        val tile = state.lastDiscarded
        return liveHands(
            id = "hands-no-terminals",
            titleKey = "tutorial.hands.no_terminals.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.hands.no_terminals.offer",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        calloutKey = CLICK_CALLOUT,
                        highlight = BoardHighlight.prompt(Tiles.BAMBOO_6, Tiles.BAMBOO_7),
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.hu()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("hu claimed on no terminals (5 pts)") { s ->
                    s.handScore == 5 &&
                        s.currentPlayer == YOU &&
                        s.isTerminal()
                },
                TutorialStep.Coach(
                    "tutorial.hands.no_terminals.terminals_def",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.hands.no_terminals.points",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.hands.no_terminals.flexibility",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.hands.no_terminals.stacking",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Complete,
            ),
        )
    }

    /**
     * Action preamble: You hold two open kongs (1-man and 5-dot) called from
     * Opposite, plus concealed 234s, a pair of 9-dot, and 67s waiting on 8s.
     * Opposite discards 8s, completing the 16-tile hand.
     * The engine offers Hu 20 / Pass for Two Kongs (20 pts).
     */
    fun kongs(): ScenarioDef {
        val state = HandsScenarioStates.kongsOfferState()
        val tile = state.lastDiscarded
        return liveHands(
            id = "hands-kongs",
            titleKey = "tutorial.hands.kongs.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.hands.kongs.offer",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        calloutKey = CLICK_CALLOUT,
                        highlight = BoardHighlight.prompt(Tiles.BAMBOO_6, Tiles.BAMBOO_7),
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.hu()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("hu claimed on two kongs (20 pts)") { s ->
                    s.handScore == 20 &&
                        s.currentPlayer == YOU &&
                        s.isTerminal()
                },
                TutorialStep.Coach(
                    "tutorial.hands.kongs.two_kongs",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.hands.kongs.hand_size",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.hands.kongs.ladder",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Complete,
            ),
        )
    }

    /**
     * Action preamble: You hold an open pung of Red Dragons called from
     * Opposite, plus concealed 234s, 567m, pair of 9-dot, and 34p waiting on 5p.
     * Opposite discards 5p, completing the 4th set.
     * The engine offers Hu 10 / Pass for Value Honor (10 pts).
     */
    fun valueHonor(): ScenarioDef {
        val state = HandsScenarioStates.valueHonorOfferState()
        val tile = state.lastDiscarded
        return liveHands(
            id = "hands-value-honor",
            titleKey = "tutorial.hands.value_honor.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.hands.value_honor.offer",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        calloutKey = CLICK_CALLOUT,
                        highlight = BoardHighlight.prompt(Tiles.DOT_3, Tiles.DOT_4),
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.hu()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("hu claimed on value honor (10 pts)") { s ->
                    s.handScore == 10 &&
                        s.currentPlayer == YOU &&
                        s.isTerminal()
                },
                TutorialStep.Coach(
                    "tutorial.hands.value_honor.dragons",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.hands.value_honor.seat_winds",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.hands.value_honor.stacking",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(Tiles.RED),
                        highlight = BoardHighlight.prompt(Tiles.RED, Tiles.EAST),
                    ),
                    layout = ScenarioLayoutSpec.Layout(
                GameStateLayout.from(HandsScenarioStates.twoValueHonorsWinState()),
            ),
                ),
                TutorialStep.Complete,
            ),
        )
    }

    /**
     * Action preamble: You hold three open pungs (1-man, 3-dot, 6-bamboo)
     * called from Right, Opposite, and Left, plus concealed triplet 8-man
     * and a singleton 9-dot waiting on the second 9-dot to make the pair.
     * Opposite discards 9-dot, completing the winning hand.
     * The engine offers Hu 30 / Pass for All Triplets (30 pts).
     */
    fun allTriplets(): ScenarioDef {
        val state = HandsScenarioStates.allTripletsOfferState()
        val tile = state.lastDiscarded
        return liveHands(
            id = "hands-all-triplets",
            titleKey = "tutorial.hands.all_triplets.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.hands.all_triplets.offer",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        calloutKey = CLICK_CALLOUT,
                        highlight = BoardHighlight.prompt(tile),
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.hu()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("hu claimed on all triplets (30 pts)") { s ->
                    s.handScore == 30 &&
                        s.currentPlayer == YOU &&
                        s.isTerminal()
                },
                TutorialStep.Coach(
                    "tutorial.hands.all_triplets.pattern",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.hands.all_triplets.settlement_cap",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.hands.all_triplets.self_draw",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Complete,
            ),
        )
    }

    /**
     * Action preamble: You hold open chow 123m called from Left,
     * plus concealed 456m, 78m (waiting on 9m), triplet of 5-dot, and pair of 9-bamboo.
     * Opposite discards 9-man, completing the 1 through 9 straight in characters.
     * The engine offers Hu 40 / Pass for Nine-Tile Straight (40 pts).
     */
    fun nineTileStraight(): ScenarioDef {
        val state = HandsScenarioStates.nineTileStraightOfferState()
        val tile = state.lastDiscarded
        return liveHands(
            id = "hands-nine-tile-straight",
            titleKey = "tutorial.hands.nine_tile_straight.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.hands.nine_tile_straight.offer",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        calloutKey = CLICK_CALLOUT,
                        highlight = BoardHighlight.prompt(Tiles.CHAR_7, Tiles.CHAR_8),
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.hu()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("hu claimed on nine-tile straight (40 pts)") { s ->
                    s.handScore == 40 &&
                        s.currentPlayer == YOU &&
                        s.isTerminal()
                },
                TutorialStep.Coach(
                    "tutorial.hands.nine_tile_straight.pattern",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(Tiles.CHAR_1),
                        highlight = BoardHighlight.prompt(
                            Tiles.CHAR_1, Tiles.CHAR_2, Tiles.CHAR_3,
                            Tiles.CHAR_4, Tiles.CHAR_5, Tiles.CHAR_6,
                            Tiles.CHAR_7, Tiles.CHAR_8, Tiles.CHAR_9,
                        ),
                    ),
                ),
                TutorialStep.Coach(
                    "tutorial.hands.nine_tile_straight.composition",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(Tiles.DOT_5),
                        highlight = BoardHighlight.prompt(Tiles.DOT_5, Tiles.BAMBOO_9),
                    ),
                ),
                TutorialStep.Coach(
                    "tutorial.hands.nine_tile_straight.settlement",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Complete,
            ),
        )
    }

    /**
     * Action preamble: You hold 6 pairs ({11m}, {44m}, {22p}, {55p}, {88p}, {33s})
     * and a singleton 7-bamboo waiting for its match.
     * Opposite discards 7-bamboo, completing Seven Pairs (30 pts).
     * The engine offers Hu 30 / Pass.
     */
    fun sevenPairs(): ScenarioDef {
        val state = HandsScenarioStates.sevenPairsOfferState()
        val tile = state.lastDiscarded
        return liveHands(
            id = "hands-seven-pairs",
            titleKey = "tutorial.hands.seven_pairs.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.hands.seven_pairs.offer",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        calloutKey = CLICK_CALLOUT,
                        highlight = BoardHighlight.prompt(tile),
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.hu()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("hu claimed on seven pairs (30 pts)") { s ->
                    s.handScore == 30 &&
                        s.currentPlayer == YOU &&
                        s.isTerminal()
                },
                TutorialStep.Coach(
                    "tutorial.hands.seven_pairs.pattern",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.hands.seven_pairs.concealed_only",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Coach(
                    "tutorial.hands.seven_pairs.four_of_a_kind",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Complete,
            ),
        )
    }

    /**
     * Action preamble: You hold an open pung of 5-dot called from Opposite,
     * plus concealed 22334s, 678m, and pair of 9-bamboo.
     * Opposite discards 4-bamboo, completing the second 234s sequence.
     * The engine offers Hu 10 / Pass for Two Identical Sequences (10 pts).
     */
    fun twoIdenticalSequences(): ScenarioDef {
        val state = HandsScenarioStates.twoIdenticalSequencesOfferState()
        val tile = state.lastDiscarded
        return liveHands(
            id = "hands-two-identical-sequences",
            titleKey = "tutorial.hands.two_identical_sequences.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.hands.two_identical_sequences.offer",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        calloutKey = CLICK_CALLOUT,
                        highlight = BoardHighlight.prompt(Tiles.BAMBOO_2, Tiles.BAMBOO_3, Tiles.BAMBOO_4),
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.hu()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("hu claimed on two identical sequences (10 pts)") { s ->
                    s.handScore == 10 &&
                        s.currentPlayer == YOU &&
                        s.isTerminal()
                },
                TutorialStep.Coach(
                    "tutorial.hands.two_identical_sequences.pattern",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(Tiles.BAMBOO_2),
                        highlight = BoardHighlight.prompt(Tiles.BAMBOO_2, Tiles.BAMBOO_3, Tiles.BAMBOO_4),
                    ),
                ),
                TutorialStep.Coach(
                    "tutorial.hands.two_identical_sequences.ladder",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Complete,
            ),
        )
    }

    /**
     * Action preamble: You hold an open chow 123m called from Left,
     * plus concealed triplets 3-dot and 6-bamboo, pair of 9-bamboo, and sequence wait 45m.
     * Opposite discards 6-man, completing the sequence 456m.
     * The engine offers Hu 5 / Pass for Two Concealed Triplets (5 pts).
     */
    fun twoConcealedTriplets(): ScenarioDef {
        val state = HandsScenarioStates.twoConcealedTripletsOfferState()
        val tile = state.lastDiscarded
        return liveHands(
            id = "hands-two-concealed-triplets",
            titleKey = "tutorial.hands.two_concealed_triplets.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.hands.two_concealed_triplets.offer",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        calloutKey = CLICK_CALLOUT,
                        highlight = BoardHighlight.prompt(Tiles.CHAR_4, Tiles.CHAR_5),
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.hu()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("hu claimed on two concealed triplets (5 pts)") { s ->
                    s.handScore == 5 &&
                        s.currentPlayer == YOU &&
                        s.isTerminal()
                },
                TutorialStep.Coach(
                    "tutorial.hands.two_concealed_triplets.pattern",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(Tiles.DOT_3),
                        highlight = BoardHighlight.prompt(Tiles.DOT_3, Tiles.BAMBOO_6),
                    ),
                ),
                TutorialStep.Coach(
                    "tutorial.hands.two_concealed_triplets.ladder",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                ),
                TutorialStep.Complete,
            ),
        )
    }

    /**
     * Action preamble: You hold open pungs of 5-man (from Right) and 5-dot (from Opposite),
     * plus concealed triplet 5-bamboo, pair of 1-man, and sequence wait 78 bamboo.
     * Opposite discards 9-bamboo, completing the winning hand.
     * The engine offers Hu 120 / Pass for Three Similar Triplets (120 pts).
     */
    fun threeSimilarTriplets(): ScenarioDef {
        val state = HandsScenarioStates.threeSimilarTripletsOfferState()
        val tile = state.lastDiscarded
        return liveHands(
            id = "hands-three-similar-triplets",
            titleKey = "tutorial.hands.three_similar_triplets.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.hands.three_similar_triplets.offer",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        calloutKey = CLICK_CALLOUT,
                        highlight = BoardHighlight.prompt(Tiles.BAMBOO_7, Tiles.BAMBOO_8),
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.hu()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("hu claimed on three similar triplets (120 pts)") { s ->
                    s.handScore == 120 &&
                        s.currentPlayer == YOU &&
                        s.isTerminal()
                },
                TutorialStep.Coach(
                    "tutorial.hands.three_similar_triplets.pattern",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(Tiles.BAMBOO_5),
                        highlight = BoardHighlight.prompt(Tiles.CHAR_5, Tiles.DOT_5, Tiles.BAMBOO_5),
                    ),
                ),
                TutorialStep.Coach(
                    "tutorial.hands.three_similar_triplets.small_triplets",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(Tiles.BAMBOO_5),
                        highlight = BoardHighlight.prompt(Tiles.CHAR_5, Tiles.DOT_5, Tiles.BAMBOO_5),
                    ),
                    layout = ScenarioLayoutSpec.Layout(
                GameStateLayout.from(HandsScenarioStates.smallThreeSimilarTripletsWinState()),
            ),
                ),
                TutorialStep.Complete,
            ),
        )
    }

    /**
     * Action preamble: You hold an open chow 234-dot called from Left,
     * plus concealed 456-dot, sequence wait 78-dot, pair of 1-dot, and triplet of West wind.
     * Opposite discards 9-dot, completing sequence 789-dot and the winning hand.
     * The engine offers Hu 40 / Pass for Mixed One-Suit (40 pts).
     */
    fun oneSuit(): ScenarioDef {
        val state = HandsScenarioStates.oneSuitOfferState()
        val tile = state.lastDiscarded
        return liveHands(
            id = "hands-one-suit",
            titleKey = "tutorial.hands.one_suit.title",
            layout = ScenarioLayoutSpec.Layout(GameStateLayout.from(state)),
            steps = listOf(
                TutorialStep.Coach(
                    "tutorial.hands.one_suit.offer",
                    presentation = SlidePresentation.FocusBeat,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(tile),
                        calloutKey = CLICK_CALLOUT,
                        highlight = BoardHighlight.prompt(Tiles.DOT_7, Tiles.DOT_8),
                    ),
                ),
                TutorialStep.WaitChoice(ChoiceMatch.hu()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.AssertState("hu claimed on mixed one-suit (40 pts)") { s ->
                    s.handScore == 40 &&
                        s.currentPlayer == YOU &&
                        s.isTerminal()
                },
                TutorialStep.Coach(
                    "tutorial.hands.one_suit.mixed",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(Tiles.WEST),
                        highlight = BoardHighlight.promptAndSecondary(
                            promptTypes = intArrayOf(
                                Tiles.DOT_1, Tiles.DOT_2, Tiles.DOT_3,
                                Tiles.DOT_4, Tiles.DOT_5, Tiles.DOT_6,
                                Tiles.DOT_7, Tiles.DOT_8, Tiles.DOT_9,
                            ),
                            secondaryTypes = intArrayOf(Tiles.WEST),
                        ),
                    ),
                ),
                TutorialStep.Coach(
                    "tutorial.hands.one_suit.pure",
                    presentation = SlidePresentation.Standard,
                    showsBoard = true,
                    focus = TutorialFocus(
                        targetId = TutorialTargets.tile(Tiles.DOT_5),
                        highlight = BoardHighlight.prompt(
                            Tiles.DOT_1, Tiles.DOT_2, Tiles.DOT_3,
                            Tiles.DOT_4, Tiles.DOT_5, Tiles.DOT_6,
                            Tiles.DOT_7, Tiles.DOT_8, Tiles.DOT_9,
                        ),
                    ),
                    layout = ScenarioLayoutSpec.Layout(
                GameStateLayout.from(HandsScenarioStates.pureOneSuitWinState()),
            ),
                ),
                TutorialStep.Complete,
            ),
        )
    }

    /**
     * Lecture / wrap-up: We have covered the essential patterns.
     * Rarer patterns and limit hands are documented in the Reference.
     */
    fun morePatterns(): ScenarioDef = hands(
        id = "hands-more-patterns",
        titleKey = "tutorial.hands.more_patterns.title",
        steps = listOf(
            TutorialStep.Coach(
                "tutorial.hands.more_patterns.rarer",
                presentation = SlidePresentation.Standard,
            ),
            TutorialStep.Coach(
                "tutorial.hands.more_patterns.practice",
                presentation = SlidePresentation.Standard,
            ),
            TutorialStep.Complete,
        ),
    )

    private fun liveHands(
        id: String,
        titleKey: String,
        layout: ScenarioLayoutSpec,
        steps: List<TutorialStep>,
    ): ScenarioDef = ScenarioDef(
        id = id,
        titleKey = titleKey,
        layout = layout,
        steps = steps,
        humanSeat = 0,
        showsBoard = true,
    )
}
