package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.engine.WALL_SIZE
import kotlin.random.Random

/**
 * Recognition / quiz content for orientation (Chapter 0) and similar non-play steps.
 * Answers are checked by the director; UI only presents options.
 */
enum class QuizKind {
    /** Single option id is correct. */
    MultiChoice,

    /** Selected option ids must equal [TutorialStep.Quiz.correctIds]. */
    TileSelect,

    /** Each option id maps to a bin id ([TutorialStep.Quiz.correctBins]). */
    BinAssign,
}

/** One tappable answer or tile. */
data class QuizOption(
    val id: String,
    /** L10n key for text button; null when only [tileType] is shown. */
    val labelKey: String? = null,
    /** Engine tile type 0..33 for face display. */
    val tileType: Int? = null,
) {
    init {
        require(id.isNotBlank()) { "quiz option id blank" }
        require(labelKey != null || tileType != null) {
            "quiz option $id needs labelKey and/or tileType"
        }
    }
}

/** Grouping target for [QuizKind.BinAssign]. */
data class QuizBin(
    val id: String,
    val labelKey: String,
) {
    init {
        require(id.isNotBlank()) { "quiz bin id blank" }
        require(labelKey.isNotBlank()) { "quiz bin $id label blank" }
    }
}

/**
 * Concrete options/answers produced for a [TutorialStep.Quiz] (static or runtime).
 */
data class QuizContent(
    val options: List<QuizOption>,
    val correctIds: Set<String> = emptySet(),
    val correctBins: Map<String, String> = emptyMap(),
) {
    init {
        require(options.isNotEmpty()) { "quiz content has no options" }
    }
}

/**
 * Builds [QuizContent] when a quiz step is entered or retried, and rebuilds it
 * from a persisted hand on mid-lesson restore.
 */
interface QuizContentFactory {
    fun generate(rng: Random): QuizContent

    /** Rematerialize options + answer key from saved tile order (same indices). */
    fun rematerialize(tileTypes: IntArray): QuizContent
}

/** Runtime generators for orientation quizzes. */
object QuizContentFactories {
    private const val HAND_SIZE = 13
    private const val MIN_HONORS = 3
    /** Number faces only (Characters / Dots / Bamboos). */
    private const val NUM_NUMBER_TYPES = Tiles.EAST

    /** Winds and dragons (tile types 27..33). */
    fun isHonor(tileType: Int): Boolean = tileType in Tiles.EAST..Tiles.WHITE

    /**
     * Draw 13 tiles from a shuffled 136-tile wall until the hand has at least
     * [MIN_HONORS] honors. Duplicates are allowed (full wall has four of each).
     * Correct answers = every honor face in the hand.
     */
    val honorsHand: QuizContentFactory = object : QuizContentFactory {
        override fun generate(rng: Random): QuizContent =
            tileSelectFromHand(drawHandWithMinHonors(rng), correctIf = ::isHonor)

        override fun rematerialize(tileTypes: IntArray): QuizContent =
            tileSelectFromHand(tileTypes, correctIf = ::isHonor)
    }

    /**
     * Draw 13 unsorted number tiles with exactly one sequence (three consecutive
     * same-suit ranks, each appearing once). Correct answers = those three tiles.
     */
    val oneSequenceHand: QuizContentFactory = object : QuizContentFactory {
        override fun generate(rng: Random): QuizContent =
            handToSequenceTileSelect(drawHandWithExactlyOneSequence(rng))

        override fun rematerialize(tileTypes: IntArray): QuizContent =
            handToSequenceTileSelect(tileTypes)
    }

    fun drawHandWithMinHonors(
        rng: Random,
        handSize: Int = HAND_SIZE,
        minHonors: Int = MIN_HONORS,
        maxAttempts: Int = 64,
    ): IntArray {
        require(handSize in 1..WALL_SIZE)
        require(minHonors in 0..handSize)
        repeat(maxAttempts) {
            val wall = fullShuffledWall(rng)
            val hand = wall.copyOf(handSize)
            if (hand.count { isHonor(it) } >= minHonors) return hand
        }
        // Deterministic fallback: force enough honors then pad with non-honors.
        val forced = IntArray(handSize)
        var i = 0
        while (i < minHonors) {
            forced[i] = Tiles.EAST + (i % 7)
            i++
        }
        while (i < handSize) {
            forced[i] = i % Tiles.EAST
            i++
        }
        return forced
    }

    /**
     * Build a hand with exactly one sequence shape and unique sequence tile instances
     * (each of the three ranks appears once). Hand is shuffled (unsorted).
     * Number tiles only — suitable before the Honors lesson.
     */
    fun drawHandWithExactlyOneSequence(
        rng: Random,
        handSize: Int = HAND_SIZE,
        maxAttempts: Int = 64,
    ): IntArray {
        require(handSize in 3..WALL_SIZE)
        repeat(maxAttempts) {
            val built = tryBuildOneSequenceHand(rng, handSize)
            if (built != null) return built
        }
        return fallbackOneSequenceHand(handSize)
    }

    @Deprecated("Renamed to drawHandWithExactlyOneSequence", ReplaceWith("drawHandWithExactlyOneSequence(rng, handSize, maxAttempts)"))
    fun drawHandWithExactlyOneChow(
        rng: Random,
        handSize: Int = HAND_SIZE,
        maxAttempts: Int = 64,
    ): IntArray = drawHandWithExactlyOneSequence(rng, handSize, maxAttempts)

    /**
     * Sequence shapes present in [counts]: each entry is suit*7 + startRank (0..6).
     * A shape is present when each of the three consecutive ranks has count ≥ 1.
     */
    fun sequenceShapesPresent(counts: IntArray): List<Int> {
        require(counts.size >= NUM_NUMBER_TYPES)
        val shapes = ArrayList<Int>(3)
        for (suit in 0..2) {
            val base = suit * 9
            for (start in 0..6) {
                if (counts[base + start] >= 1 &&
                    counts[base + start + 1] >= 1 &&
                    counts[base + start + 2] >= 1
                ) {
                    shapes += suit * 7 + start
                }
            }
        }
        return shapes
    }

    @Deprecated("Renamed to sequenceShapesPresent", ReplaceWith("sequenceShapesPresent(counts)"))
    fun chowShapesPresent(counts: IntArray): List<Int> = sequenceShapesPresent(counts)

    private fun tryBuildOneSequenceHand(rng: Random, handSize: Int): IntArray? {
        val suit = rng.nextInt(3)
        val start = rng.nextInt(7)
        val base = suit * 9
        val sequenceTiles = intArrayOf(base + start, base + start + 1, base + start + 2)

        // Remaining copies per type (wall has four of each).
        val remaining = IntArray(NUM_TILE_TYPES) { if (it < NUM_NUMBER_TYPES) 4 else 0 }
        val counts = IntArray(NUM_TILE_TYPES)
        val hand = ArrayList<Int>(handSize)
        for (t in sequenceTiles) {
            remaining[t]--
            // Keep sequence ranks unique so the three correct indices are unambiguous.
            remaining[t] = 0
            counts[t]++
            hand += t
        }
        if (sequenceShapesPresent(counts).size != 1) return null

        repeat(handSize - 3) {
            val candidates = ArrayList<Int>(NUM_NUMBER_TYPES)
            for (t in 0 until NUM_NUMBER_TYPES) {
                if (remaining[t] <= 0) continue
                counts[t]++
                val ok = sequenceShapesPresent(counts).size == 1
                counts[t]--
                if (ok) candidates += t
            }
            if (candidates.isEmpty()) return null
            val pick = candidates[rng.nextInt(candidates.size)]
            remaining[pick]--
            counts[pick]++
            hand += pick
        }

        // Unsorted display order.
        for (i in hand.lastIndex downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = hand[i]
            hand[i] = hand[j]
            hand[j] = tmp
        }
        return hand.toIntArray()
    }

    /** Deterministic 13-tile hand: 2-3-4 Characters + non-sequencing fillers. */
    private fun fallbackOneSequenceHand(handSize: Int): IntArray {
        val hand = ArrayList<Int>(handSize)
        hand += Tiles.CHAR_2
        hand += Tiles.CHAR_3
        hand += Tiles.CHAR_4
        // Isolated pairs / singles that do not complete another sequence with 2-3-4.
        val fillers = listOf(
            Tiles.CHAR_6, Tiles.CHAR_6, Tiles.CHAR_8, Tiles.CHAR_9,
            Tiles.DOT_1, Tiles.DOT_1, Tiles.DOT_3, Tiles.DOT_5,
            Tiles.BAMBOO_2, Tiles.BAMBOO_5, Tiles.BAMBOO_8, Tiles.BAMBOO_9,
        )
        var i = 0
        while (hand.size < handSize && i < fillers.size) {
            hand += fillers[i]
            i++
        }
        while (hand.size < handSize) {
            hand += Tiles.DOT_9
        }
        return hand.toIntArray()
    }

    private fun handToSequenceTileSelect(tileTypes: IntArray): QuizContent {
        require(tileTypes.isNotEmpty()) { "empty hand" }
        for (t in tileTypes) {
            require(t in 0 until NUM_TILE_TYPES) { "tile type out of range: $t" }
        }
        val counts = IntArray(NUM_TILE_TYPES)
        for (t in tileTypes) counts[t]++
        val shapes = sequenceShapesPresent(counts)
        require(shapes.size == 1) { "hand must have exactly one sequence shape, got ${shapes.size}" }
        val shape = shapes[0]
        val suit = shape / 7
        val start = shape % 7
        val base = suit * 9
        val sequenceTypes = setOf(base + start, base + start + 1, base + start + 2)
        for (t in sequenceTypes) {
            require(counts[t] == 1) { "sequence rank $t must appear once for unique selection" }
        }
        val options = tileTypes.mapIndexed { index, type ->
            QuizOption(id = index.toString(), tileType = type)
        }
        val correctIds = options
            .filter { it.tileType in sequenceTypes }
            .map { it.id }
            .toSet()
        require(correctIds.size == 3) { "expected 3 sequence tiles, got ${correctIds.size}" }
        return QuizContent(options = options, correctIds = correctIds)
    }

    private fun tileSelectFromHand(
        tileTypes: IntArray,
        correctIf: (Int) -> Boolean,
    ): QuizContent {
        require(tileTypes.isNotEmpty()) { "empty hand" }
        for (t in tileTypes) {
            require(t in 0 until NUM_TILE_TYPES) { "tile type out of range: $t" }
        }
        val options = tileTypes.mapIndexed { index, type ->
            QuizOption(id = index.toString(), tileType = type)
        }
        val correctIds = options
            .filter { correctIf(it.tileType!!) }
            .map { it.id }
            .toSet()
        require(correctIds.isNotEmpty()) { "hand has no correct tiles" }
        return QuizContent(options = options, correctIds = correctIds)
    }

    private fun fullShuffledWall(rng: Random): IntArray {
        val wall = IntArray(WALL_SIZE) { it / 4 }
        for (i in WALL_SIZE - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = wall[i]
            wall[i] = wall[j]
            wall[j] = tmp
        }
        return wall
    }
}

/**
 * Optional schematic chrome on the table (quiz or coach; no full [GameState] needed).
 */
enum class TutorialDiagram {
    None,

    /** Live wall vs reserved dead wall (14 tiles). */
    WallDead,

    /** Four seats + counter-clockwise turn hint. */
    SeatsTurns,

    /** Simple table schematic for overview. */
    TableOverview,

    /** All 27 number tiles: Characters / Dots / Bamboos × 1–9. */
    ThreeSuits,

    /** Four winds + three dragons. */
    Honors,

    /**
     * Sample faces for the “34 types” overview: a few number tiles + a few
     * honors (not the full set). Appended last so existing diagram ordinals
     * in mid-lesson blobs stay valid when possible; unknown ordinals still
     * fail the load path cleanly.
     */
    TileTypes,

    /**
     * Valid Sequences: several rows of exactly three consecutive same-suit tiles.
     * Appended after [TileTypes] so older mid-lesson diagram ordinals stay valid.
     */
    SequenceExample,

    /**
     * Four consecutive same-suit tiles — not a single sequence.
     * Coach body key selects phase: static run, peel 3 left, then peel 6 right below.
     */
    NotASequenceRun,

    /**
     * Full set → ×4 in square → face-down → shuffle → 4×17×2 wall → pushed in
     * → optional seat-wind labels / dice / stack-count highlight → break gap
     * (coach body key selects phase; see WallBuildView).
     */
    WallBuild,

    /**
     * Regular winning shape: four sets (triplet, sequence, triplet, sequence)
     * plus a pair. Body key may swap the second triplet for a kong (15 tiles).
     * Appended so older mid-lesson diagram ordinals stay valid.
     */
    RegularHand,

    /**
     * Taxonomy of a set: sequence, triplet, or kong. Appended so older
     * mid-lesson diagram ordinals stay valid.
     */
    SetKinds,

    /**
     * Two regular winning hands identical except one uses a pung and the other
     * a kong of the same tile (kong scores as a triplet). Reserved for a later
     * kong lesson; kept in the enum so older diagram ordinals stay valid.
     */
    KongAsTriplet,

    /**
     * Four seat-wind labels (東/East …) on a table square (You / Right / Opposite / Left).
     * Body key selects a still assignment vs looping ccw deal-pass.
     * Appended so older mid-lesson diagram ordinals stay valid.
     */
    SeatWinds,

    /**
     * Same faces with tags forced on, forced off, and a 13-tile hand that
     * follows the Show tile labels setting. Appended so older mid-lesson
     * diagram ordinals stay valid.
     */
    TileTags;
}

/**
 * Answer payload from UI or headless auto-solve.
 *
 * - [MultiChoice] / [TileSelect]: use [selectedIds]
 * - [BinAssign]: use [binByOptionId]
 */
data class QuizAnswer(
    val selectedIds: Set<String> = emptySet(),
    val binByOptionId: Map<String, String> = emptyMap(),
)

/**
 * Full mid-attempt quiz interaction state — **kind-agnostic**, reusable for every
 * [TutorialStep.Quiz] (multi-choice, tile-select, bin-assign, future kinds).
 *
 * Pure data (no rendering). Survives app restart via
 * [inego.zungjunggame.persist.TutorialSessionSnapshot.quizInteraction].
 *
 * Per-option review marks are **not** stored; they recompute from [answer] +
 * flags + the live quiz key ([QuizAnswerReviews]).
 *
 * @property answer current selection / bin mapping
 * @property selectedForBinId BinAssign only: option staged for the next bin tap
 * @property failVisible wrong Confirm latched on a non-retryable quiz (banner + review)
 * @property answerRevealed attempt locked after Confirm (selection frozen)
 * @property showAnswerConsumed legacy wire field (Show answer removed; always false)
 * @property passed post-Confirm dock (Next / Try again);
 *   director [TutorialDirector.quizAwaitingAdvance]
 */
data class QuizInteractionState(
    val answer: QuizAnswer = QuizAnswer(),
    val selectedForBinId: String? = null,
    val failVisible: Boolean = false,
    val answerRevealed: Boolean = false,
    val showAnswerConsumed: Boolean = false,
    val passed: Boolean = false,
) {
    companion object {
        val Fresh: QuizInteractionState = QuizInteractionState()
    }

    /**
     * Collapse illegal / legacy combos so restore never shows review marks with
     * Confirm still active (e.g. old Show-answer saves: revealed + correct
     * selection + !passed → green hits + Confirm).
     *
     * Director flags win when provided: [directorAwaitingAdvance] forces the
     * post-Confirm dock on retryable quizzes; [directorFail] forces fail review
     * only while still editing.
     */
    fun normalized(
        quiz: TutorialStep.Quiz,
        directorAwaitingAdvance: Boolean = false,
        directorFail: Boolean = false,
    ): QuizInteractionState {
        val onRetryableDock = quiz.retryAfterCorrect &&
            (directorAwaitingAdvance || passed || answerRevealed)
        val failOut = when {
            onRetryableDock -> false
            directorFail || failVisible -> true
            else -> false
        }
        return copy(
            // Lock only after Confirm (dock); fail still allows re-edit.
            answerRevealed = onRetryableDock,
            showAnswerConsumed = false,
            passed = onRetryableDock,
            failVisible = failOut,
        )
    }
}

fun TutorialStep.Quiz.isCorrect(answer: QuizAnswer): Boolean = when (kind) {
    QuizKind.MultiChoice ->
        answer.selectedIds.size == 1 && answer.selectedIds == correctIds
    QuizKind.TileSelect ->
        answer.selectedIds == correctIds
    QuizKind.BinAssign -> {
        correctBins.isNotEmpty() && correctBins.all { (opt, bin) -> answer.binByOptionId[opt] == bin } &&
                answer.binByOptionId.keys.containsAll(correctBins.keys)
    }
}

/** Canonical correct payload for auto-solve / “Show answer”. */
fun TutorialStep.Quiz.correctAnswer(): QuizAnswer = when (kind) {
    QuizKind.MultiChoice, QuizKind.TileSelect ->
        QuizAnswer(selectedIds = correctIds)
    QuizKind.BinAssign ->
        QuizAnswer(binByOptionId = correctBins)
}

/**
 * Review marks for a learner [answer]: the configured
 * [QuizAnswerReviews.review], else set-based marks for [QuizKind.MultiChoice] /
 * [QuizKind.TileSelect] so review UI stays consistent. Null when the quiz has no
 * per-option review ([QuizKind.BinAssign]).
 */
fun TutorialStep.Quiz.answerReview(answer: QuizAnswer): QuizAnswerReview? {
    QuizAnswerReviews.review(this, answer)?.let { return it }
    return when (kind) {
        QuizKind.MultiChoice, QuizKind.TileSelect ->
            QuizAnswerReviews.forIdSelection(
                optionIds = options.map { it.id },
                selectedIds = answer.selectedIds,
                correctIds = correctIds,
            )
        QuizKind.BinAssign -> null
    }
}

/**
 * Review marks a UI shows for [attempt]: only after Confirm (passed, or a latched
 * fail), never while Confirm is still the primary action. Null otherwise.
 */
fun TutorialStep.Quiz.reviewFor(attempt: QuizInteractionState): QuizAnswerReview? =
    if (attempt.passed || attempt.failVisible) answerReview(attempt.answer) else null

// ── Multiselect answer review (reusable across tile-select / multi-choice) ─

/**
 * How a quiz presents feedback after Confirm.
 *
 * Use [SelectionReview] for multiselect (and single-select id sets) so UI can
 * highlight hits, false positives, and misses consistently.
 */
enum class QuizFeedbackMode {
    /** Only the fail / correct message banner. */
    MessageOnly,

    /**
     * Per-option marks from [QuizAnswerReviews] plus correct/fail banner.
     * Supported for [QuizKind.MultiChoice] and [QuizKind.TileSelect].
     */
    SelectionReview,
    ;

    companion object {
        fun defaultFor(kind: QuizKind): QuizFeedbackMode = when (kind) {
            QuizKind.MultiChoice, QuizKind.TileSelect -> SelectionReview
            QuizKind.BinAssign -> MessageOnly
        }
    }
}

/**
 * Per-option outcome for a set-based (multiselect) answer.
 *
 * - [Hit]: selected and correct
 * - [FalsePositive]: selected but not correct
 * - [Miss]: correct but not selected
 * - [Idle]: neither selected nor required
 */
enum class QuizOptionMark {
    Hit,
    FalsePositive,
    Miss,
    Idle,
}

/**
 * Result of comparing a learner answer to the key — pure data for any UI.
 *
 * @property fullyCorrect true when the answer matches the quiz key exactly
 * @property marksByOptionId mark for every option id present on the quiz
 */
data class QuizAnswerReview(
    val fullyCorrect: Boolean,
    val marksByOptionId: Map<String, QuizOptionMark>,
) {
    fun markOf(optionId: String): QuizOptionMark =
        marksByOptionId[optionId] ?: QuizOptionMark.Idle

    val hitIds: Set<String>
        get() = marksByOptionId.filterValues { it == QuizOptionMark.Hit }.keys

    val falsePositiveIds: Set<String>
        get() = marksByOptionId.filterValues { it == QuizOptionMark.FalsePositive }.keys

    val missIds: Set<String>
        get() = marksByOptionId.filterValues { it == QuizOptionMark.Miss }.keys
}

/**
 * Pure multiselect review helpers. Keep rendering out of this object so
 * headless tests and future quiz UIs share one definition of “correct /
 * wrong / missed”.
 */
object QuizAnswerReviews {
    /**
     * Classic set comparison for id-based multiselect (and single-select).
     *
     * @param optionIds all option ids shown (order irrelevant)
     * @param selectedIds learner selection
     * @param correctIds answer key
     */
    fun forIdSelection(
        optionIds: Iterable<String>,
        selectedIds: Set<String>,
        correctIds: Set<String>,
    ): QuizAnswerReview {
        val marks = LinkedHashMap<String, QuizOptionMark>()
        for (id in optionIds) {
            val selected = id in selectedIds
            val correct = id in correctIds
            marks[id] = when {
                selected && correct -> QuizOptionMark.Hit
                selected && !correct -> QuizOptionMark.FalsePositive
                !selected && correct -> QuizOptionMark.Miss
                else -> QuizOptionMark.Idle
            }
        }
        return QuizAnswerReview(
            fullyCorrect = selectedIds == correctIds,
            marksByOptionId = marks,
        )
    }

    /**
     * Review for a quiz step when [TutorialStep.Quiz.feedbackMode] is
     * [QuizFeedbackMode.SelectionReview]; otherwise null (caller uses message only).
     */
    fun review(quiz: TutorialStep.Quiz, answer: QuizAnswer): QuizAnswerReview? {
        if (quiz.feedbackMode != QuizFeedbackMode.SelectionReview) return null
        return when (quiz.kind) {
            QuizKind.MultiChoice, QuizKind.TileSelect ->
                forIdSelection(
                    optionIds = quiz.options.map { it.id },
                    selectedIds = answer.selectedIds,
                    correctIds = quiz.correctIds,
                )
            QuizKind.BinAssign -> null
        }
    }
}
