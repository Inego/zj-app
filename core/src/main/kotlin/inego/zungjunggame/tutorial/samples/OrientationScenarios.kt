package inego.zungjunggame.tutorial.samples

import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.tutorial.QuizContentFactories
import inego.zungjunggame.tutorial.QuizKind
import inego.zungjunggame.tutorial.ScenarioDef
import inego.zungjunggame.tutorial.ScenarioLayoutSpec
import inego.zungjunggame.tutorial.TutorialDiagram
import inego.zungjunggame.tutorial.TutorialFocus
import inego.zungjunggame.tutorial.TutorialStep
import inego.zungjunggame.tutorial.TutorialTargets
import inego.zungjunggame.tutorial.slide.SlidePresentation
import kotlin.random.Random

/**
 * Tiles and wall chapter — recognition / quiz. No live table ([showsBoard] = false).
 */
object OrientationScenarios {

    /** Shared dummy board for the runner; never drawn. */
    private fun dummyLayout(): ScenarioLayoutSpec =
        ScenarioLayoutSpec.Factory {
            GameState.fromWallOrder(
                wallOrder = SampleScenarios.canonicalWall(Random(0)),
                eastSeat = 0,
                allowChickenHands = false,
            )
        }

    private fun orient(
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

    fun tiles(): ScenarioDef = orient(
        id = "orient-tiles",
        titleKey = "tutorial.orient.tiles.title",
        steps = listOf(
            TutorialStep.Coach(
                "tutorial.orient.tiles.intro",
                diagram = TutorialDiagram.TileTypes,
                presentation = SlidePresentation.DiagramIntro,
            ),
            TutorialStep.Coach(
                "tutorial.orient.tiles.tags",
                diagram = TutorialDiagram.TileTags,
                presentation = SlidePresentation.DiagramIntro,
            ),
            TutorialStep.Coach(
                "tutorial.orient.tiles.tags_try",
                diagram = TutorialDiagram.TileTags,
                presentation = SlidePresentation.Standard,
            ),
            TutorialStep.Complete,
        ),
    )

    fun numbers(): ScenarioDef = orient(
        id = "orient-numbers",
        titleKey = "tutorial.orient.numbers.title",
        steps = listOf(
            TutorialStep.Coach(
                "tutorial.orient.numbers.intro",
                diagram = TutorialDiagram.ThreeSuits,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // Highlight 1 Bamboo + callout; continuity keeps the suit grid settled.
            TutorialStep.Coach(
                textKey = "tutorial.orient.numbers.bamboo1",
                diagram = TutorialDiagram.ThreeSuits,
                focus = TutorialFocus(
                    targetId = TutorialTargets.tile(Tiles.BAMBOO_1),
                    calloutKey = "tutorial.orient.numbers.bamboo1_callout",
                ),
                presentation = SlidePresentation.FocusBeat,
            ),
            // What a Sequence is (exactly three consecutive, same suit).
            TutorialStep.Coach(
                textKey = "tutorial.orient.numbers.sequence",
                diagram = TutorialDiagram.SequenceExample,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // Four consecutive are not one sequence — then peel each spare on Next.
            TutorialStep.Coach(
                textKey = "tutorial.orient.numbers.four",
                diagram = TutorialDiagram.NotASequenceRun,
                presentation = SlidePresentation.DiagramIntro,
            ),
            TutorialStep.Coach(
                textKey = "tutorial.orient.numbers.four_aside3",
                diagram = TutorialDiagram.NotASequenceRun,
                presentation = SlidePresentation.DiagramIntro,
            ),
            TutorialStep.Coach(
                textKey = "tutorial.orient.numbers.four_aside6",
                diagram = TutorialDiagram.NotASequenceRun,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // Find the single sequence in a random unsorted 13-tile number hand.
            TutorialStep.Quiz(
                promptKey = "tutorial.orient.numbers.prompt",
                kind = QuizKind.TileSelect,
                contentFactory = QuizContentFactories.oneSequenceHand,
                retryAfterCorrect = true,
            ),
            TutorialStep.Complete,
        ),
    )

    fun honors(): ScenarioDef = orient(
        id = "orient-honors",
        titleKey = "tutorial.orient.honors.title",
        steps = listOf(
            TutorialStep.Coach(
                "tutorial.orient.honors.intro",
                diagram = TutorialDiagram.Honors,
                presentation = SlidePresentation.DiagramIntro,
            ),
            TutorialStep.Quiz(
                promptKey = "tutorial.orient.honors.prompt",
                kind = QuizKind.TileSelect,
                // 13-tile hand from a shuffled wall (≥3 honors); regenerated on Try again.
                contentFactory = QuizContentFactories.honorsHand,
                retryAfterCorrect = true,
            ),
            TutorialStep.Complete,
        ),
    )

    fun wallDead(): ScenarioDef = orient(
        id = "orient-wall-dead",
        titleKey = "tutorial.orient.wall.title",
        steps = listOf(
            // Prototype: full set → ×4 → face-down → shuffle → wall → push-in.
            TutorialStep.Coach(
                "tutorial.orient.wall.faces",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            TutorialStep.Coach(
                "tutorial.orient.wall.copies",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // Same ×4 open pack; wall ≈ card draw pile (hands + mid-hand draws).
            TutorialStep.Coach(
                "tutorial.orient.wall.drawpile",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            TutorialStep.Coach(
                "tutorial.orient.wall.ritual",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            TutorialStep.Coach(
                "tutorial.orient.wall.facedown",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            TutorialStep.Coach(
                "tutorial.orient.wall.shuffle",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            TutorialStep.Coach(
                "tutorial.orient.wall.formed",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            TutorialStep.Coach(
                "tutorial.orient.wall.pushed",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // Same pushed wall + seat winds (E bottom, S right, W top, N left).
            TutorialStep.Coach(
                "tutorial.orient.wall.seats",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // Same diagram; highlight East as dealer / first to act.
            TutorialStep.Coach(
                "tutorial.orient.wall.east",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // Same diagram + East highlight; copy explains the deal roll.
            TutorialStep.Coach(
                "tutorial.orient.wall.deal",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // Same diagram; two d6 tumble then settle in the table center.
            TutorialStep.Coach(
                "tutorial.orient.wall.dice",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // Dice stay; sum counts stacks from the right end of East's wall.
            TutorialStep.Coach(
                "tutorial.orient.wall.count",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // Count glow off; uncounted East stacks shift left (break gap).
            TutorialStep.Coach(
                "tutorial.orient.wall.break",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // Gap stays; orange-highlight 7 stacks from the gap’s right edge
            // (East remainder, then onto South if needed).
            TutorialStep.Coach(
                "tutorial.orient.wall.take7",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // Same 7 stacks ease down into an axis-aligned horizontal dead wall.
            TutorialStep.Coach(
                "tutorial.orient.wall.dead",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // Same diagram; copy explains why the dead wall matters.
            TutorialStep.Coach(
                "tutorial.orient.wall.dead_out",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // 3 rounds × 2 stacks per seat from the gap clockwise → three
            // separated 2-stack squares in each player’s hand area.
            TutorialStep.Coach(
                "tutorial.orient.wall.hands",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            TutorialStep.Coach(
                "tutorial.orient.wall.hands_13",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // East draws one more from the live wall to open at 14.
            TutorialStep.Coach(
                "tutorial.orient.wall.east_14",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // Unstack into a line of 13 (14 East); East open unsorted, others closed.
            TutorialStep.Coach(
                "tutorial.orient.wall.look_hands",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // Same line, reordered by ascending tile type (live-table hand order).
            TutorialStep.Coach(
                "tutorial.orient.wall.sort_hands",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // Same diagram: East must discard to start play.
            TutorialStep.Coach(
                "tutorial.orient.wall.east_discard",
                diagram = TutorialDiagram.WallBuild,
                presentation = SlidePresentation.DiagramIntro,
            ),
            // Live table: simplified wall marker, no dead wall; hands match diagram.
            TutorialStep.Coach(
                "tutorial.orient.wall.app_table",
                diagram = TutorialDiagram.None,
                presentation = SlidePresentation.Standard,
                showsBoard = true,
                focus = TutorialFocus(targetId = "wall.live"),
            ),
            // Same board: center caption is remaining live-wall tiles.
            TutorialStep.Coach(
                "tutorial.orient.wall.live_count",
                diagram = TutorialDiagram.None,
                presentation = SlidePresentation.Standard,
                showsBoard = true,
                focus = TutorialFocus(targetId = "wall.live"),
            ),
            TutorialStep.Complete,
        ),
    )

    /** Campaign scenarios in order. */
    fun all(): List<ScenarioDef> = listOf(
        tiles(),
        numbers(),
        honors(),
        wallDead(),
    )
}
