package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.GameState
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.tutorial.samples.SampleScenarios
import kotlin.random.Random

/**
 * Synthetic scenarios for testing tutorial **machinery** (runner, quiz, nav).
 * Not campaign content — ids and copy keys are arbitrary probe strings.
 */
object TutorialTestFixtures {

    fun dummyLayout(seed: Int = 0): ScenarioLayoutSpec = ScenarioLayoutSpec.Factory {
        GameState.fromWallOrder(
            wallOrder = SampleScenarios.canonicalWall(Random(seed)),
            eastSeat = 0,
            allowChickenHands = false,
        )
    }

    fun coachThenComplete(
        id: String = "tool-coach",
        coachKey: String = "coach.body",
    ): ScenarioDef = ScenarioDef(
        id = id,
        titleKey = "tool.title",
        layout = dummyLayout(),
        steps = listOf(
            TutorialStep.Coach(coachKey),
            TutorialStep.Complete,
        ),
        humanSeat = 0,
        showsBoard = false,
    )

    fun multiCoach(
        id: String = "tool-multi-coach",
        coachKeys: List<String> = listOf("coach.a", "coach.b", "coach.c"),
    ): ScenarioDef = ScenarioDef(
        id = id,
        titleKey = "tool.title",
        layout = dummyLayout(),
        steps = coachKeys.map { TutorialStep.Coach(it) } + TutorialStep.Complete,
        humanSeat = 0,
        showsBoard = false,
    )

    fun multiChoiceQuiz(
        id: String = "tool-multi-quiz",
        correctId: String = "right",
        wrongId: String = "wrong",
    ): ScenarioDef = ScenarioDef(
        id = id,
        titleKey = "tool.title",
        layout = dummyLayout(),
        steps = listOf(
            TutorialStep.Coach("coach.intro"),
            TutorialStep.Quiz(
                promptKey = "quiz.prompt",
                kind = QuizKind.MultiChoice,
                options = listOf(
                    QuizOption(wrongId, labelKey = "opt.wrong"),
                    QuizOption(correctId, labelKey = "opt.right"),
                ),
                correctIds = setOf(correctId),
            ),
            TutorialStep.Coach("coach.after"),
            TutorialStep.Complete,
        ),
        humanSeat = 0,
        showsBoard = false,
    )

    fun dynamicTileSelectQuiz(
        id: String = "tool-dyn-quiz",
        factory: QuizContentFactory = QuizContentFactories.honorsHand,
        retryAfterCorrect: Boolean = true,
    ): ScenarioDef = ScenarioDef(
        id = id,
        titleKey = "tool.title",
        layout = dummyLayout(1),
        steps = listOf(
            TutorialStep.Coach("coach.intro"),
            TutorialStep.Quiz(
                promptKey = "quiz.prompt",
                kind = QuizKind.TileSelect,
                contentFactory = factory,
                retryAfterCorrect = retryAfterCorrect,
            ),
            TutorialStep.Complete,
        ),
        humanSeat = 0,
        showsBoard = false,
    )

    fun staticTileSelectQuiz(
        options: List<QuizOption> = listOf(
            QuizOption("0", tileType = Tiles.CHAR_5),
            QuizOption("1", tileType = Tiles.EAST),
            QuizOption("2", tileType = Tiles.RED),
        ),
        correctIds: Set<String> = setOf("1", "2"),
        retryAfterCorrect: Boolean = true,
    ): TutorialStep.Quiz = TutorialStep.Quiz(
        promptKey = "quiz.prompt",
        kind = QuizKind.TileSelect,
        options = options,
        correctIds = correctIds,
        retryAfterCorrect = retryAfterCorrect,
    )

    /**
     * Board-overlay coach, then a discard wait. The overlay is a click-through
     * preamble — not a lecture sitting on a discard-turn snapshot.
     */
    fun overlayDiscardPreambleScenario(seed: Int = 42): ScenarioDef {
        val wall = SampleScenarios.canonicalWall(Random(seed))
        val preview = GameState.fromWallOrder(wall, eastSeat = 0)
        val targetTile = preview.enumerateChoices().asList()
            .first { it.kind == ChoiceKind.Discard }
            .tile()
        return ScenarioDef(
            id = "tool-overlay-discard-preamble",
            titleKey = "tool.title",
            humanSeat = 0,
            layout = ScenarioLayoutSpec.WallOrder(wall = wall, eastSeat = 0),
            showsBoard = true,
            steps = listOf(
                TutorialStep.Coach("coach.click", showsBoard = true),
                TutorialStep.WaitChoice(ChoiceMatch.discard(targetTile)),
                TutorialStep.Coach("coach.after", showsBoard = true),
                TutorialStep.Complete,
            ),
        )
    }

    /**
     * Board-overlay coach, then a pung wait. Same click-through preamble as
     * [overlayDiscardPreambleScenario], for a claim button instead of a hand tile.
     */
    fun overlayPungPreambleScenario(): ScenarioDef {
        val built = SampleScenarios.buildPungPosition(humanSeat = 0)
        return ScenarioDef(
            id = "tool-overlay-pung-preamble",
            titleKey = "tool.title",
            humanSeat = 0,
            layout = ScenarioLayoutSpec.Factory { built.state.copy() },
            showsBoard = true,
            steps = listOf(
                TutorialStep.Coach("coach.click", showsBoard = true),
                TutorialStep.WaitChoice(ChoiceMatch.pung(built.tile)),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.Coach("coach.after", showsBoard = true),
                TutorialStep.Complete,
            ),
        )
    }

    /** Two board-overlay coaches with no wait between them (lecture, not play). */
    fun overlayLectureScenario(): ScenarioDef = ScenarioDef(
        id = "tool-overlay-lecture",
        titleKey = "tool.title",
        humanSeat = 0,
        layout = dummyLayout(),
        showsBoard = true,
        steps = listOf(
            TutorialStep.Coach("coach.lecture_a", showsBoard = true),
            TutorialStep.Coach("coach.lecture_b", showsBoard = true),
            TutorialStep.Complete,
        ),
    )

    fun discardWaitScenario(seed: Int = 42): ScenarioDef {
        val wall = SampleScenarios.canonicalWall(Random(seed))
        val preview = GameState.fromWallOrder(wall, eastSeat = 0)
        val targetTile = preview.enumerateChoices().asList()
            .first { it.kind == ChoiceKind.Discard }
            .tile()
        return ScenarioDef(
            id = "tool-discard",
            titleKey = "tool.title",
            humanSeat = 0,
            layout = ScenarioLayoutSpec.WallOrder(wall = wall, eastSeat = 0),
            steps = listOf(
                TutorialStep.Coach("coach.intro"),
                TutorialStep.Gate.kinds(ChoiceKind.Discard),
                TutorialStep.WaitChoice(ChoiceMatch.discard(targetTile)),
                TutorialStep.AssertState("human discarded target") { s ->
                    s.lastDiscarded == targetTile && s.lastDiscarder == 0
                },
                TutorialStep.Coach("coach.after"),
                TutorialStep.Complete,
            ),
        )
    }

    fun pungWaitScenario(): ScenarioDef {
        val built = SampleScenarios.buildPungPosition(humanSeat = 0)
        return ScenarioDef(
            id = "tool-pung",
            titleKey = "tool.title",
            humanSeat = 0,
            layout = ScenarioLayoutSpec.Factory { built.state.copy() },
            steps = listOf(
                TutorialStep.Coach("coach.intro"),
                TutorialStep.Gate.kinds(ChoiceKind.Pung),
                TutorialStep.WaitChoice(ChoiceMatch.pung(built.tile)),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.ScriptChoice(Choice.pass()),
                TutorialStep.Complete,
            ),
        )
    }

    fun lesson(
        id: String,
        requires: List<String> = emptyList(),
        scenario: () -> ScenarioDef = { coachThenComplete(id = id) },
    ): LessonDef = LessonDef(
        id = id,
        titleKey = "tool.$id",
        requires = requires,
        scenario = scenario,
    )
}
