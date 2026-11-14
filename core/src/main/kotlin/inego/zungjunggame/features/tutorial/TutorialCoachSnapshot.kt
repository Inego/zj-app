package inego.zungjunggame.features.tutorial

import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.features.board.BoardAnchors
import inego.zungjunggame.render.wallbuild.WallBuildDeal
import inego.zungjunggame.tutorial.TutorialCatalog
import inego.zungjunggame.tutorial.TutorialDiagram
import inego.zungjunggame.tutorial.TutorialDirector
import inego.zungjunggame.tutorial.TutorialFocus
import inego.zungjunggame.tutorial.TutorialLiveFocus
import inego.zungjunggame.tutorial.TutorialRunner
import inego.zungjunggame.tutorial.TutorialSlideNav
import inego.zungjunggame.tutorial.TutorialStep
import inego.zungjunggame.tutorial.TutorialTargets
import inego.zungjunggame.tutorial.slide.CopyBand
import inego.zungjunggame.tutorial.slide.SlidePresentation
import inego.zungjunggame.tutorial.slide.SlideScene
import inego.zungjunggame.ui.Rect

/**
 * One frame's coach / complete scene: the content the host used to push through
 * `showCoach` / `showComplete`, plus the live-table lesson facts the coach parked
 * its callouts from through `TutorialCoachLessonPort`.
 *
 * Assembled by [inego.zungjunggame.tutorial.TutorialHost] from the live
 * [TutorialRunner] (never from the overlay's own fields) and put on the
 * [TutorialSnapshot] the frame carries. A presenter reads it; it keeps no copy.
 *
 * [wallDeal], [leaving] and the leave target are host state layered on top of
 * the runner-derived content — the lesson model and the parked wall deal live on
 * the host, so [tutorialCoachContent] cannot derive them.
 */
data class TutorialCoachSnapshot(
    val visible: Boolean = false,
    val mode: Mode = Mode.Coach,
    val titleKey: String = "",
    val bodyKey: String = "",
    val primaryKey: String = "tutorial.next",
    val diagram: TutorialDiagram = TutorialDiagram.None,
    val focus: TutorialFocus? = null,
    val offersBack: Boolean = false,
    val offersNext: Boolean = false,
    val offersExit: Boolean = false,
    /** Live table under this coach: the panel docks and leaves the center clear. */
    val boardOverlay: Boolean = false,
    val awaitingCoach: Boolean = false,
    val isPreambleToHumanWait: Boolean = false,
    val waitKind: ChoiceKind? = null,
    val waitPayload: Int? = null,
    /** Parked wall-build deal to apply before this scene presents; null on pre-roll beats. */
    val wallDeal: WallBuildDeal? = null,
    val lessonId: String? = null,
    val scene: SlideScene? = null,
    /**
     * Coach beats that share this beat's visual context. The coach measures
     * every title and body in the band and reserves the tallest, so the content
     * below the copy keeps one position while the learner pages through it. A
     * diagram band is its consecutive run; a board overlay's band is every
     * board-overlay coach in the campaign, so the live table's pane is the same
     * in every board lesson. Null when the caller has no band; the coach then
     * uses [scene] alone.
     */
    val copyBand: CopyBand? = null,
    val presentation: SlidePresentation = SlidePresentation.Standard,
    /** A leave is in flight: the presenter exits toward [leaveToward] before the host lands. */
    val leaving: Boolean = false,
    val leaveToward: SlideScene? = null,
    val leavePreserveContinuity: Boolean = true,
) {
    enum class Mode { Coach, Complete }

    /**
     * Identity of the scene itself (slide content), excluding in-scene target ([focus]).
     * Unchanged when only the target/spotlight within the scene moves.
     */
    val sceneKey: String
        get() = if (!visible) {
            "hidden"
        } else {
            listOf(
                mode.name,
                titleKey,
                bodyKey,
                primaryKey,
                diagram.name,
                offersBack,
                offersNext,
                offersExit,
                boardOverlay,
            ).joinToString("|")
        }

    /**
     * Identity of the scene and in-scene target a slide presenter should apply.
     * Unchanged between frames of the same beat, so a no-op frame does not restart
     * the enter animation. Includes everything [visible] paint reads.
     *
     * An absent focus renders as the literal `"-"`, produced by [identityPart].
     *
     * ## Why the focus is not splatted into the join
     *
     * This used to be `listOf(sceneKey, focus?.targetId, focus?.calloutKey,
     * focus?.highlight?.describe()).joinToString("|")`. That is legal Kotlin and
     * prints `"a|null"` on the JVM, but on TeaVM `StringBuilder.append(null)`
     * dereferences a null pointer. So it ran from `524bb6ea` (2026-09-21) and
     * threw on the web the moment a beat carried a focus with a null
     * `calloutKey`/`highlight` — the first tutorial lesson, so prod web's Tutorial
     * was unreachable.
     *
     * The lesson is *not* "add a test". No JVM test can catch this: the two
     * backends disagree, so the JVM half of the suite is green either way. The
     * fix is the shape — [identityPart] collapses the nullable focus to one
     * non-null part before the join, and [joinIdentity] takes `String`, so the
     * compiler rejects the old call. Nothing to guard, so nothing to test.
     */
    val key: String
        get() = if (!visible) {
            "hidden"
        } else {
            joinIdentity(sceneKey, focus.identityPart())
        }

    companion object {
        val Absent: TutorialCoachSnapshot = TutorialCoachSnapshot()
    }
}

/**
 * Join identity parts with `|`.
 *
 * The parameter is `String`, not `String?`, on purpose: on the JVM `joinToString`
 * renders a null element as `"null"` while on TeaVM `StringBuilder.append(null)`
 * dereferences a null pointer, so a nullable element is a web-only crash no JVM
 * test can see. Every optional caller resolves itself to a non-null part *before*
 * arriving here — see [identityPart] — which makes the null unrepresentable
 * instead of merely avoided. Do not widen this to `String?`.
 */
private fun joinIdentity(vararg parts: String): String = parts.joinToString("|")

/** Placeholder for an absent optional in an identity string. */
private const val NULL_PART = "-"

/**
 * The focus part of [TutorialCoachSnapshot.key], as one non-null part.
 *
 * The snapshot holds a `TutorialFocus?` and renders it through here rather than
 * splatting `focus?.a`, `focus?.b`, `focus?.c` into a list. The shape is the fix:
 * a null cannot reach [joinIdentity] at all, because the optional is resolved
 * *before* the join instead of inside it, so no test needs to watch this call
 * site — the signature does. See [TutorialCoachSnapshot.key] for why the plain
 * version of this threw on TeaVM only.
 */
private fun TutorialFocus?.identityPart(): String =
    this?.let { "${it.targetId}:${it.calloutKey}:${it.highlight?.describe()}" } ?: NULL_PART

/**
 * The runner's coach / complete scene as a snapshot, or [TutorialCoachSnapshot.Absent]
 * when the lesson is on a play beat (no overlay chrome).
 *
 * Pure over the runner's director facts; the host layers the parked wall deal, the
 * reveal flag and the leave request on top.
 */
fun tutorialCoachContent(active: TutorialRunner): TutorialCoachSnapshot {
    if (active.awaitingCoach) {
        val body = active.lastCoachKey ?: return TutorialCoachSnapshot.Absent
        val coachStep = active.director.currentStep as? TutorialStep.Coach
        val boardOverlay = coachStep?.showsBoard == true
        val focus = active.lastFocus
        val wait = if (active.isPreambleToHumanWait()) active.peekNextWaitChoice() else null
        val scene = if (coachStep != null) {
            TutorialSlideNav.coachScene(active.scenario, coachStep)
        } else {
            SlideScene(
                titleKey = active.scenario.titleKey,
                bodyKey = body,
                diagram = active.lastDiagram,
                focus = focus,
                continuityKey = null,
            )
        }
        val copyBand = if (coachStep != null) {
            TutorialSlideNav.copyBandFor(active.scenario, active.stepIndex)
        } else {
            CopyBand.of(scene)
        }
        return TutorialCoachSnapshot(
            visible = true,
            mode = TutorialCoachSnapshot.Mode.Coach,
            titleKey = active.scenario.titleKey,
            bodyKey = body,
            primaryKey = "tutorial.next",
            diagram = if (boardOverlay) TutorialDiagram.None else active.lastDiagram,
            focus = focus,
            offersBack = TutorialSlideNav.canGoBack(active),
            boardOverlay = boardOverlay,
            awaitingCoach = true,
            isPreambleToHumanWait = active.isPreambleToHumanWait(),
            waitKind = wait?.match?.kind,
            waitPayload = wait?.match?.payload,
            lessonId = active.scenario.id,
            scene = scene,
            copyBand = copyBand,
            presentation = coachStep?.presentation ?: SlidePresentation.Standard,
        )
    }
    return when (active.status) {
        TutorialDirector.Status.Completed -> {
            val next = TutorialCatalog.nextAfter(active.scenario.id)
            val summaryKey = active.scenario.completeSummaryKey
            val offersBack = TutorialSlideNav.canGoBack(active)
            when {
                next == null -> completeScene(
                    titleKey = "tutorial.all_finished",
                    bodyKey = summaryKey ?: "tutorial.all_finished_body",
                    primaryKey = "menu.exit",
                    offersExit = true,
                    offersBack = offersBack,
                    lessonId = active.scenario.id,
                )
                summaryKey != null -> completeScene(
                    titleKey = "tutorial.complete",
                    bodyKey = summaryKey,
                    primaryKey = "tutorial.next_lesson",
                    offersNext = true,
                    offersBack = offersBack,
                    lessonId = active.scenario.id,
                )
                else -> TutorialCoachSnapshot.Absent
            }
        }
        TutorialDirector.Status.Failed -> completeScene(
            titleKey = "tutorial.failed",
            bodyKey = "tutorial.failed_body",
            primaryKey = "menu.restart_tutorial",
            offersBack = TutorialSlideNav.canGoBack(active),
            lessonId = active.scenario.id,
        )
        TutorialDirector.Status.Running -> TutorialCoachSnapshot.Absent
    }
}

private fun completeScene(
    titleKey: String,
    bodyKey: String,
    primaryKey: String,
    offersNext: Boolean = false,
    offersExit: Boolean = false,
    offersBack: Boolean = false,
    lessonId: String,
): TutorialCoachSnapshot = TutorialCoachSnapshot(
    visible = true,
    mode = TutorialCoachSnapshot.Mode.Complete,
    titleKey = titleKey,
    bodyKey = bodyKey,
    primaryKey = primaryKey,
    offersBack = offersBack,
    offersNext = offersNext,
    offersExit = offersExit,
    lessonId = lessonId,
    scene = SlideScene(titleKey = titleKey, bodyKey = bodyKey),
    presentation = SlidePresentation.None,
)

/**
 * Live-table focus rect this coach parks on, from the board's published regions
 * and the frame's coach snapshot — pure, so the geometry mapping is testable
 * headless.
 *
 * The board resolves `river.last` / `wall.live` by name; a claim-wait preamble
 * parks on the matching claim button; a discard preamble (or no callout) keeps a
 * hand tile / prompt. Null when no park applies.
 */
fun tutorialCoachLiveFocus(
    values: TutorialCoachSnapshot,
    regions: Map<String, Rect>?,
): Rect? {
    if (!values.visible || !values.boardOverlay) return null
    val focus = if (values.awaitingCoach) values.focus else null
    val park = TutorialLiveFocus.resolve(
        targetId = focus?.targetId,
        calloutKey = focus?.calloutKey,
        waitKind = values.waitKind,
        waitPayload = values.waitPayload,
    )
    return when (park) {
        is TutorialLiveFocus.Park.ClaimButton -> claimAnchor(regions, park.kind, park.payload)
        is TutorialLiveFocus.Park.Target -> targetAnchor(regions, park.targetId)
        TutorialLiveFocus.Park.HandPrompt -> regions?.get(BoardAnchors.HAND_PROMPT)
        TutorialLiveFocus.Park.None -> null
    }
}

private fun claimAnchor(regions: Map<String, Rect>?, kind: ChoiceKind, payload: Int?): Rect? {
    if (regions == null) return null
    return if (payload != null) {
        regions[BoardAnchors.claim(kind, payload)]
    } else {
        regions[BoardAnchors.claimAny(kind)]
    }
}

/**
 * The board resolves `river.last` and `wall.live` by name; every other id is a
 * tile copy or falls back to the prompted hand anchor, matching
 * `PlayTableView.anchorForTarget`.
 */
private fun targetAnchor(regions: Map<String, Rect>?, targetId: String): Rect? {
    if (regions == null) return null
    if (targetId == "river.last" || targetId == "wall.live") {
        return regions[BoardAnchors.target(targetId)]
    }
    return regions[BoardAnchors.target(targetId)]
        ?: if (TutorialTargets.parseTile(targetId) == null) {
            regions[BoardAnchors.HAND_PROMPT]
        } else {
            null
        }
}
