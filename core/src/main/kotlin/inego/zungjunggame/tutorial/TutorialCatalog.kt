package inego.zungjunggame.tutorial

import inego.zungjunggame.tutorial.samples.ClaimScenarios
import inego.zungjunggame.tutorial.samples.HandsScenarios
import inego.zungjunggame.tutorial.samples.KongScenarios
import inego.zungjunggame.tutorial.samples.MoveOrderScenarios
import inego.zungjunggame.tutorial.samples.OrientationScenarios
import inego.zungjunggame.tutorial.slide.CopyBand
import inego.zungjunggame.tutorial.slide.SlideScene

/**
 * Ordered FTUE campaign spine + linear nav tree.
 *
 * Chapters: tiles / wall (`OrientationScenarios`), move order
 * (`MoveOrderScenarios`), claiming (`ClaimScenarios`), kongs
 * (`KongScenarios`), then hands and scoring (`HandsScenarios`).
 */
object TutorialCatalog {

    data class Chapter(
        val id: String,
        val titleKey: String,
    )

    /** Chapter headers in display order. */
    val chapters: List<Chapter> = listOf(
        Chapter(LessonDef.CHAPTER_ORIENT, "tutorial.chapter.orient"),
        Chapter(LessonDef.CHAPTER_MOVES, "tutorial.chapter.moves"),
        Chapter(LessonDef.CHAPTER_CLAIMS, "tutorial.chapter.claims"),
        Chapter(LessonDef.CHAPTER_KONGS, "tutorial.chapter.kongs"),
        Chapter(LessonDef.CHAPTER_HANDS, "tutorial.chapter.hands"),
    )

    private class BuiltCampaign(
        val lessons: List<LessonDef>,
        val boardScenes: List<SlideScene>,
    )

    private val built: BuiltCampaign = buildCampaign()

    /** Full campaign in play order. */
    val campaign: List<LessonDef> = built.lessons

    /**
     * Copy band shared by every board-overlay coach in the campaign, or null
     * when no lesson draws the live table.
     *
     * The live table is one open subject for the whole tutorial, not for one
     * lesson: Next can step from the last board beat of a lesson straight into
     * the first board beat of the next, and the table must not resize on the
     * way. A band measured over one lesson's run made the board's pane — and so
     * its height — change at every lesson boundary, which also snapped the
     * table's tiles mid-animation. Measuring every board-overlay coach once
     * gives them all the same band, and therefore the same board.
     *
     * The scenes come from the scenarios [campaign] already constructs, so the
     * band costs no extra lesson construction.
     */
    val boardCopyBand: CopyBand? =
        built.boardScenes.takeIf { it.isNotEmpty() }?.let { CopyBand(it) }

    private fun buildCampaign(): BuiltCampaign {
        val lessons = ArrayList<LessonDef>()
        val boardScenes = ArrayList<SlideScene>()

        fun add(
            scenario: () -> ScenarioDef,
            chapterId: String,
            requires: List<String> = emptyList(),
        ) {
            val s = scenario()
            for (step in s.steps) {
                if (step is TutorialStep.Coach && step.showsBoard) {
                    boardScenes += TutorialSlideNav.coachScene(s, step)
                }
            }
            lessons += LessonDef(
                id = s.id,
                titleKey = s.titleKey,
                requires = requires,
                chapterId = chapterId,
                scenario = scenario,
            )
        }

        fun addOrient(scenario: () -> ScenarioDef, requires: List<String> = emptyList()) =
            add(scenario, LessonDef.CHAPTER_ORIENT, requires)

        fun addMoves(scenario: () -> ScenarioDef, requires: List<String> = emptyList()) =
            add(scenario, LessonDef.CHAPTER_MOVES, requires)

        fun addClaims(scenario: () -> ScenarioDef, requires: List<String> = emptyList()) =
            add(scenario, LessonDef.CHAPTER_CLAIMS, requires)

        fun addKongs(scenario: () -> ScenarioDef, requires: List<String> = emptyList()) =
            add(scenario, LessonDef.CHAPTER_KONGS, requires)

        fun addHands(scenario: () -> ScenarioDef, requires: List<String> = emptyList()) =
            add(scenario, LessonDef.CHAPTER_HANDS, requires)

        addOrient(OrientationScenarios::tiles)
        addOrient(OrientationScenarios::numbers, listOf("orient-tiles"))
        addOrient(OrientationScenarios::honors, listOf("orient-numbers"))
        addOrient(OrientationScenarios::wallDead, listOf("orient-honors"))

        addMoves(MoveOrderScenarios::eastPlayer, listOf("orient-wall-dead"))
        addMoves(MoveOrderScenarios::discarding, listOf("moves-east-player"))
        addMoves(MoveOrderScenarios::counterclockwise, listOf("moves-discarding"))

        addClaims(ClaimScenarios::pung, listOf("moves-counterclockwise"))
        addClaims(ClaimScenarios::lastDiscard, listOf("claims-pung"))
        addClaims(ClaimScenarios::chow, listOf("claims-last-discard"))
        addClaims(ClaimScenarios::hu, listOf("claims-chow"))
        addClaims(ClaimScenarios::priority, listOf("claims-hu"))

        addKongs(KongScenarios::meldedKong, listOf("claims-priority"))
        addKongs(KongScenarios::concealedKong, listOf("kongs-melded"))
        addKongs(KongScenarios::promotedKong, listOf("kongs-concealed"))

        addHands(HandsScenarios::winningHand, listOf("kongs-promoted"))
        addHands(HandsScenarios::chickenHand, listOf("hands-winning"))
        addHands(HandsScenarios::concealedHand, listOf("hands-chicken"))
        addHands(HandsScenarios::allSequences, listOf("hands-concealed"))
        addHands(HandsScenarios::noTerminals, listOf("hands-all-sequences"))
        addHands(HandsScenarios::kongs, listOf("hands-no-terminals"))
        addHands(HandsScenarios::valueHonor, listOf("hands-kongs"))
        addHands(HandsScenarios::allTriplets, listOf("hands-value-honor"))
        addHands(HandsScenarios::nineTileStraight, listOf("hands-all-triplets"))
        addHands(HandsScenarios::sevenPairs, listOf("hands-nine-tile-straight"))
        addHands(HandsScenarios::twoIdenticalSequences, listOf("hands-seven-pairs"))
        addHands(HandsScenarios::twoConcealedTriplets, listOf("hands-two-identical-sequences"))
        addHands(HandsScenarios::threeSimilarTriplets, listOf("hands-two-concealed-triplets"))
        addHands(HandsScenarios::oneSuit, listOf("hands-three-similar-triplets"))
        addHands(HandsScenarios::morePatterns, listOf("hands-one-suit"))

        return BuiltCampaign(lessons, boardScenes)
    }

    fun lesson(id: String): LessonDef? = campaign.firstOrNull { it.id == id }

    fun indexOf(id: String): Int = campaign.indexOfFirst { it.id == id }

    fun nextAfter(id: String): LessonDef? {
        val i = indexOf(id)
        if (i < 0) return null
        return campaign.getOrNull(i + 1)
    }

    /** Previous campaign lesson, or null when [id] is first / unknown. */
    fun previousBefore(id: String): LessonDef? {
        val i = indexOf(id)
        if (i <= 0) return null
        return campaign.getOrNull(i - 1)
    }

    fun first(): LessonDef = campaign.first()

    fun resumeTarget(progress: TutorialProgressView): LessonDef {
        for (lesson in campaign) {
            if (progress.isUnlocked(lesson) && !progress.isCompleted(lesson.id)) {
                return lesson
            }
        }
        return campaign.last()
    }

    /**
     * Linear nav rows for the lesson browser: chapter headers + lessons in
     * campaign order (no synthetic Resume/Continue row).
     */
    fun navEntries(progress: TutorialProgressView): List<TutorialNavEntry> = buildList {
        val byChapter = campaign.groupBy { it.chapterId }
        for ((id1, titleKey1) in chapters) {
            val lessons = byChapter[id1].orEmpty()
            if (lessons.isEmpty()) continue
            add(
                TutorialNavEntry.ChapterHeader(
                    chapterId = id1,
                    titleKey = titleKey1,
                ),
            )
            for ((id, titleKey) in lessons) {
                add(
                    TutorialNavEntry.Lesson(
                        lessonId = id,
                        titleKey = titleKey,
                        status = progress.playStatus(id),
                    ),
                )
            }
        }
        // Any lesson whose chapterId is not in [chapters] still appears (fallback).
        val known = chapters.map { it.id }.toSet()
        val orphan = campaign.filter { it.chapterId !in known }
        if (orphan.isNotEmpty()) {
            add(
                TutorialNavEntry.ChapterHeader(
                    chapterId = "_other",
                    titleKey = "tutorial.chapter.other",
                ),
            )
            for ((id, titleKey) in orphan) {
                add(
                    TutorialNavEntry.Lesson(
                        lessonId = id,
                        titleKey = titleKey,
                        status = progress.playStatus(id),
                    ),
                )
            }
        }
    }
}

/** One row in the linear tutorial navigation tree. */
sealed class TutorialNavEntry {
    abstract val rowId: String

    data class ChapterHeader(
        val chapterId: String,
        val titleKey: String,
    ) : TutorialNavEntry() {
        override val rowId: String get() = "ch:$chapterId"
    }

    data class Lesson(
        val lessonId: String,
        val titleKey: String,
        val status: LessonPlayStatus,
    ) : TutorialNavEntry() {
        override val rowId: String get() = lessonId
    }
}

interface TutorialProgressView {
    fun isCompleted(lessonId: String): Boolean
    fun isUnlocked(lesson: LessonDef): Boolean
    /** Play status for nav chrome: completed wins over started. */
    fun playStatus(lessonId: String): LessonPlayStatus
}
