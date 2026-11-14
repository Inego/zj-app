package inego.zungjunggame.tutorial

import inego.zungjunggame.tutorial.slide.CopyBand
import inego.zungjunggame.tutorial.slide.SlideScene

/**
 * Linear slide navigation for the tutorial campaign.
 *
 * Coach and Quiz steps are user-facing slides; board waits / gates / scripts
 * are not. Next and Back both resolve a [SlideTarget] (or “ack current coach”)
 * and the host leaves toward that scene with the same continuity rules.
 */
object TutorialSlideNav {

    /** Coach / Quiz only — the beats the learner steps through with Next / Back. */
    fun isUserSlide(step: TutorialStep): Boolean = when (step) {
        is TutorialStep.Coach, is TutorialStep.Quiz -> true
        else -> false
    }

    /**
     * Whether [scenario] draws a live table at [stepIndex] (scenario-wide board
     * **or** a coach step with [TutorialStep.Coach.showsBoard]).
     */
    fun stepShowsBoard(scenario: ScenarioDef, stepIndex: Int): Boolean {
        if (scenario.showsBoard) return true
        val step = scenario.steps.getOrNull(stepIndex) as? TutorialStep.Coach ?: return false
        return step.showsBoard
    }

    /**
     * Step index of the active [TutorialStep.Coach.layout] at or before [stepIndex],
     * or -1 when [ScenarioDef.layout] is active.
     */
    fun activeLayoutIndex(scenario: ScenarioDef, stepIndex: Int): Int {
        val end = stepIndex.coerceAtMost(scenario.steps.lastIndex)
        for (i in end downTo 0) {
            val step = scenario.steps[i]
            if (step is TutorialStep.Coach && step.layout != null) {
                return i
            }
        }
        return -1
    }

    /**
     * Active [ScenarioLayoutSpec] at [stepIndex] — step-level [TutorialStep.Coach.layout]
     * override if any, otherwise [ScenarioDef.layout].
     */
    fun activeLayout(scenario: ScenarioDef, stepIndex: Int): ScenarioLayoutSpec {
        val i = activeLayoutIndex(scenario, stepIndex)
        if (i >= 0) {
            return (scenario.steps[i] as TutorialStep.Coach).layout!!
        }
        return scenario.layout
    }

    /**
     * True when this unit has a wall-build diagram: overlay beats must bind
     * the parked [inego.zungjunggame.render.wallbuild.WallBuildDeal], never
     * the dummy scenario layout.
     */
    fun usesWallDeal(scenario: ScenarioDef): Boolean =
        scenario.steps.any { it is TutorialStep.Coach && it.diagram == TutorialDiagram.WallBuild }

    /**
     * Shared family id for consecutive board-overlay coaches in one lesson.
     * [SlidePresenter] holds title / table chrome when this matches.
     */
    fun boardOverlayContinuityKey(scenarioId: String): String = "board:$scenarioId"

    /** Coach beat as a [SlideScene] — same builder for Next peek, Back peek, and present. */
    fun coachScene(scenario: ScenarioDef, step: TutorialStep.Coach): SlideScene {
        val boardOverlay = step.showsBoard
        return SlideScene(
            titleKey = scenario.titleKey,
            bodyKey = step.textKey,
            diagram = if (boardOverlay) TutorialDiagram.None else step.diagram,
            focus = step.resolvedFocus(),
            continuityKey = if (boardOverlay) {
                boardOverlayContinuityKey(scenario.id)
            } else {
                null
            },
        )
    }

    /**
     * Scene + that step’s [inego.zungjunggame.tutorial.slide.SlidePresentation.preserveContinuity]
     * when [stepIndex] is a coach; null for quiz / non-slides (no tile continuity).
     */
    fun coachSceneAt(scenario: ScenarioDef, stepIndex: Int): Pair<SlideScene, Boolean>? {
        val step = scenario.steps.getOrNull(stepIndex) as? TutorialStep.Coach ?: return null
        return coachScene(scenario, step) to step.presentation.preserveContinuity
    }

    /**
     * The coach beats that share [stepIndex]'s continuity family — the same
     * diagram or board overlay. Their bodies are measured together, so the
     * content below the copy keeps one position across the family.
     *
     * A diagram band is the **consecutive** run: it stops at any non-coach step
     * (quiz, wait, complete), so it never spans a different kind of slide. A
     * coach with no continuity key is a band of one: there is no shared content
     * below its copy to hold still.
     *
     * A **board overlay** takes the campaign-wide band instead
     * ([TutorialCatalog.boardCopyBand]). The live table is one open subject for
     * the whole tutorial, and Next can step from a lesson's last board beat
     * straight into the next lesson's first one. A per-lesson band made the
     * board's pane change at that boundary, which resized the table and snapped
     * its tiles mid-animation. One band for every board beat keeps the table
     * exactly where it was.
     */
    fun copyBandFor(scenario: ScenarioDef, stepIndex: Int): CopyBand? {
        val step = scenario.steps.getOrNull(stepIndex) as? TutorialStep.Coach ?: return null
        val scene = coachScene(scenario, step)
        val key = scene.resolvedContinuityKey() ?: return CopyBand.of(scene)
        if (step.showsBoard) {
            return TutorialCatalog.boardCopyBand ?: CopyBand.of(scene)
        }
        val scenes = ArrayDeque<SlideScene>()
        scenes.addLast(scene)
        var i = stepIndex - 1
        while (i >= 0) {
            val prev = scenario.steps[i] as? TutorialStep.Coach ?: break
            val prevScene = coachScene(scenario, prev)
            if (prevScene.resolvedContinuityKey() != key) break
            scenes.addFirst(prevScene)
            i--
        }
        i = stepIndex + 1
        while (i <= scenario.steps.lastIndex) {
            val next = scenario.steps[i] as? TutorialStep.Coach ?: break
            val nextScene = coachScene(scenario, next)
            if (nextScene.resolvedContinuityKey() != key) break
            scenes.addLast(nextScene)
            i++
        }
        return CopyBand(scenes.toList())
    }

    /**
     * Next coach that Next can land on **without** hitting a wait / quiz / complete.
     * Used so exit holds a shared diagram / board across continuous FocusBeat, etc.
     */
    fun nextContinuousCoachIndex(steps: List<TutorialStep>, fromIndex: Int): Int? {
        var i = fromIndex + 1
        while (i < steps.size) {
            when (steps[i]) {
                is TutorialStep.Coach -> return i
                is TutorialStep.Quiz,
                TutorialStep.Complete,
                is TutorialStep.WaitChoice,
                -> return null
                is TutorialStep.Gate,
                is TutorialStep.ScriptChoice,
                is TutorialStep.AssertState,
                is TutorialStep.LinkRef,
                -> i++
            }
        }
        return null
    }

    /**
     * Next coach after Next auto-completes one wait (plus the scripted steps
     * [TutorialDirector.pumpAuto] would run). A second wait, quiz, or complete
     * still stops the peek — those are not auto-played.
     */
    fun nextCoachAfterPreambleWait(steps: List<TutorialStep>, fromIndex: Int): Int? {
        var i = fromIndex + 1
        var skippedWait = false
        while (i < steps.size) {
            when (steps[i]) {
                is TutorialStep.Coach -> return i
                is TutorialStep.Quiz,
                TutorialStep.Complete,
                -> return null
                is TutorialStep.WaitChoice -> {
                    if (skippedWait) return null
                    skippedWait = true
                    i++
                }
                is TutorialStep.Gate,
                is TutorialStep.ScriptChoice,
                is TutorialStep.AssertState,
                is TutorialStep.LinkRef,
                -> i++
            }
        }
        return null
    }

    /**
     * True when landing on [fromIndex] from [toIndex] (or the reverse) requires
     * replaying board mutations ([TutorialStep.WaitChoice] / [TutorialStep.ScriptChoice]).
     */
    fun mutatesBoardBetween(steps: List<TutorialStep>, fromIndex: Int, toIndex: Int): Boolean {
        val start = minOf(fromIndex, toIndex)
        val end = maxOf(fromIndex, toIndex)
        if (start < 0 || end > steps.size) return true
        for (i in start until end) {
            when (steps[i]) {
                is TutorialStep.WaitChoice, is TutorialStep.ScriptChoice -> return true
                else -> Unit
            }
        }
        return false
    }

    /**
     * Index of the nearest user slide strictly before [fromIndex], or null.
     * [fromIndex] may be a wait / complete / mid-step index.
     */
    fun previousUserSlideIndex(steps: List<TutorialStep>, fromIndex: Int): Int? {
        val start = (fromIndex - 1).coerceAtMost(steps.lastIndex)
        for (i in start downTo 0) {
            if (isUserSlide(steps[i])) return i
        }
        return null
    }

    fun nextUserSlideIndex(steps: List<TutorialStep>, fromIndex: Int): Int? {
        val start = (fromIndex + 1).coerceAtLeast(0)
        for (i in start..steps.lastIndex) {
            if (isUserSlide(steps[i])) return i
        }
        return null
    }

    fun lastUserSlideIndex(steps: List<TutorialStep>): Int? {
        for (i in steps.lastIndex downTo 0) {
            if (isUserSlide(steps[i])) return i
        }
        return null
    }

    fun firstUserSlideIndex(steps: List<TutorialStep>): Int? {
        for (i in steps.indices) {
            if (isUserSlide(steps[i])) return i
        }
        return null
    }

    /**
     * Where Back should land from the live [cursor], or null when already on the
     * first slide of the first campaign lesson.
     */
    fun resolveBack(cursor: SlideCursor): SlideTarget? {
        val steps = cursor.scenario.steps
        val fromIndex = cursor.stepIndex
        val prevInLesson = previousUserSlideIndex(steps, fromIndex)
        if (prevInLesson != null) {
            return SlideTarget(cursor.scenario.id, prevInLesson)
        }
        val prevLesson = TutorialCatalog.previousBefore(cursor.scenario.id) ?: return null
        val last = lastUserSlideIndex(prevLesson.scenario().steps) ?: return null
        return SlideTarget(prevLesson.id, last)
    }

    /** True when [resolveBack] would navigate somewhere. */
    fun canGoBack(cursor: SlideCursor): Boolean = resolveBack(cursor) != null

    data class SlideTarget(
        val lessonId: String,
        val stepIndex: Int,
    )
}
