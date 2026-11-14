package inego.zungjunggame.features.tutorial

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.platform.UiCursor
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.ui.AppPalette
import inego.zungjunggame.render.seatwinds.SeatWindsView
import inego.zungjunggame.render.wallbuild.WallBuildDeal
import inego.zungjunggame.render.wallbuild.WallBuildHandoffSnapshot
import inego.zungjunggame.render.wallbuild.WallBuildView
import inego.zungjunggame.tutorial.TutorialCatalog
import inego.zungjunggame.tutorial.TutorialDiagram
import inego.zungjunggame.tutorial.TutorialFocus
import inego.zungjunggame.tutorial.slide.SlideChannel
import inego.zungjunggame.tutorial.slide.SlideFrame
import inego.zungjunggame.tutorial.slide.SlidePresenter
import inego.zungjunggame.tutorial.slide.SlideScene
import inego.zungjunggame.tutorial.slide.layout.DiagramLayout
import inego.zungjunggame.tutorial.slide.layout.SlideChromeLayout
import inego.zungjunggame.tutorial.slide.layout.SlideMode
import inego.zungjunggame.tutorial.slide.layout.SlideViewport
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Tutorial coach / end-panel chrome **on the green table** — not a floating dialog.
 *
 * - [TutorialCoachSnapshot.Mode.Coach]: title + body + optional tile diagram + **Back** / **Next**
 * - Diagram drawers register [TutorialFocus] anchors (`tile.<type>`, …); focus
 *   draws a highlight + optional callout bubble near the target
 * - [TutorialCoachSnapshot.Mode.Complete]: end panel — fail restart, opt-in mid-campaign summary, or
 *   campaign finished (Back + Exit to main menu)
 * - Motion via the view-local [SlidePresenter] (geometry fixed; alpha / offset / scale /
 *   item stagger only); the scene is applied from the frame's [TutorialCoachSnapshot]
 *   in [applyScene]
 * - Layout via [SlideChromeLayout] + [DiagramLayout]: fit-first, adaptive to
 *   portrait/landscape, compact/comfortable, touch/desktop
 *
 * Mid-lesson leave also via game menu **Exit to main menu** (the shell's
 * top-right ☰, Esc, system back, or free-space tap).
 *
 * **Its gestures are a [GestureSurface].** [onPointerDown] claims a press that
 * starts on one of the panel's own controls (Back / Next-Restart or a body
 * hyperlink) and declines the felt — a board-overlay beat keeps the live table
 * underneath playable — and [onPointerUp] resolves the release into a
 * [PointerUpResult] the owning component maps to an action.
  *
  * **This is the orchestrator.** It owns the beat state machine, the gestures,
  * the chrome layout, and the order of the paint. The drawing is owned by
  * collaborators, each of which owns its own geometry, ink and GL resources:
  * [CoachDiagramTiles] (plan → draw for the tile diagrams, plus the anchors it
  * publishes), [CoachFocusChrome] (the highlight and callout over a focus),
  * [CoachCallout] (the balloon itself) and [CoachCopyScrim] (the board-overlay
  * dim bands and the side column veil). Wall-build and seat-winds keep their own
  * views, which own their own clocks.
  */
class TutorialCoachOverlay : Disposable, GestureSurface<TutorialCoachOverlay.PointerUpResult> {
    private val glyphLayout = GlyphLayout()

    /** The localized title and rich-text body of the beat. */
    private val copy = CoachCopyText()

    /** Tile diagrams: plan → draw, plus the anchors they publish for the focus. */
    private val diagramTiles = CoachDiagramTiles(glyphLayout)

    /** Highlight + callout over a focused tile, diagram or live-board. */
    private val focusChrome = CoachFocusChrome()

    /** Board-overlay dim bands and the side column veil. */
    private val copyScrim = CoachCopyScrim()

    /**
     * Not-a-sequence peel animation: 0 = static four-run, 1 = peel 3 left,
     * 2 = keep peel-3 + peel 6 right on a second row.
     */
    private var notASequencePhase: Int = 0
    /** 0 = clustered four-run, 1 = spare fully aside (eased in [update]). */
    private var notASequencePeelT: Float = 1f

    /** Live-table control under overlay focus (board overlay). */
    private var liveFocusAnchor: AnchorRect? = null

    /** True when the pointer is over a live-hand tile (hover cursor during overlay play). */
    private var handPointerActive: Boolean = false

    /** UI-only slide clock; view-local animation phase. */
    private val slidePresenter = SlidePresenter()

    /** View-local animation phase of the slide presenter, for verification. */
    internal val slidePhase: SlidePresenter.Phase get() = slidePresenter.phase

    /** Last scene applied, for continuity across a beat change. */
    private var lastScene: SlideScene? = null

    /** Identity of the scene and in-scene target the presenter is currently showing. */
    private var appliedKey: String? = null

    /** Scene identity excluding in-scene focus, for gating target eases. */
    private var appliedSceneKey: String? = null

    /** Lesson whose scene is applied; a change clears the wall diagram + continuity. */
    private var appliedLessonId: String? = null
    private var hasAppliedLesson: Boolean = false

    /** True when the new scene is arriving as the completion of an in-flight slide leave. */
    private var completedSlideLeave: Boolean = false

    private var leaveRequested: Boolean = false
    private var requestedToward: SlideScene? = null
    private var requestedPreserve: Boolean = true
    private var leaveComplete: Boolean = false

    /** Parked deal last installed / committed, so a fresh roll is reported once. */
    private var installedDeal: WallBuildDeal? = null

    /** Deal the view committed and the presenter has not reported yet. */
    private var committedDeal: WallBuildDeal? = null

    /** Wall→table handoff poses captured while the wall view still had them. */
    private var committedHandoff: WallBuildHandoffSnapshot? = null
    private var lastHandoff: WallBuildHandoffSnapshot? = null

    private fun whitePixel(): Texture = WhitePixel.get()

    var visible: Boolean = false
        private set

    var mode: TutorialCoachSnapshot.Mode = TutorialCoachSnapshot.Mode.Coach
        private set

    /**
     * When [mode] is [Mode.Complete] from a success summary: primary advances to
     * the next unit. Fail panels leave this false (Restart only).
     */
    var offersNext: Boolean = false
        private set

    /**
     * When [mode] is [Mode.Complete] after the last campaign lesson: primary
     * leaves the tutorial (main menu). Mutually exclusive with [offersNext].
     */
    var offersExit: Boolean = false
        private set

    /** Show the Back control (previous slide / previous lesson). */
    var offersBack: Boolean = false
        private set

    private var titleKey: String = ""
    private var bodyKey: String = ""
    /**
     * The beats that share this beat's copy band, including the current one.
     * Their titles and bodies are measured together so the content below the
     * copy keeps one position while the learner pages through the band.
     */
    private var copyBandScenes: List<SlideScene> = emptyList()

    /** [copyBandScenes] with its copy resolved; rebuilt only when the band changes. */
    private var resolvedBandScenes: List<SlideScene> = emptyList()
    private var resolvedBandTitles: List<String> = emptyList()
    private var resolvedBandBodies: List<String> = emptyList()
    private var resolvedBandLocale: String = ""

    /** Tallest band title at the last measured width / scale. */
    private var bandTitleWidth = -1f
    private var bandTitleScale = -1f
    private var bandTitleHeight = 0f
    private var primaryKey: String = "menu.restart_tutorial"
    private var diagram: TutorialDiagram = TutorialDiagram.None
    private var focus: TutorialFocus? = null
    /**
     * Live table under this coach: skip opaque felt, dock title/body/buttons,
     * leave the center clear for [PlayTableView].
     */
    private var boardOverlay: Boolean = false

    private var primaryLeft = 0f
    private var primaryBottom = 0f
    private var primaryW = 0f
    private var primaryH = 0f

    private var backLeft = 0f
    private var backBottom = 0f
    private var backW = 0f
    private var backH = 0f

    /**
     * The pane a board-overlay beat leaves the live table, derived for the last
     * viewport / locale [boardAreaFor] was asked about.
     */
    private var paneViewport: SlideViewport? = null
    private var paneLocale: String = ""
    private var paneArea: SlideChromeLayout.BoardArea? = null

    /**
     * The campaign board copy band's copy, resolved once per locale. The band
     * spans every board-overlay beat in the campaign, so it is a fact about the
     * viewport rather than about the beat on screen — which is what lets
     * [boardAreaFor] answer before any coach has been drawn.
     */
    private var boardBandTitles: List<String> = emptyList()
    private var boardBandBodies: List<String> = emptyList()
    private var boardBandLocale: String = ""

    /** Tallest board-band title at the last measured width / scale. */
    private var boardBandTitleWidth = -1f
    private var boardBandTitleScale = -1f
    private var boardBandTitleHeight = 0f

    /**
     * Latest motion sample from the view-local [SlidePresenter]. Set each frame
     * by [applyScene]; read by [render].
     */
    private var slideFrame: SlideFrame = SlideFrame.SETTLED

    /** True only while the slide is idle (not entering, exiting, or hidden). */
        private var allowBodyLinks: Boolean = false

    /** Wall-build lesson diagram (set → ×4 → square → flip → shuffle → wall). */
    private val wallBuildView = WallBuildView()

    /** East-player seat-winds diagram (still assignment / looping deal-pass). */
    private val seatWindsView = SeatWindsView()

    /**
     * What a completed press on the panel resolved, in the coach's own
     * vocabulary: the control it landed on, or the body hyperlink under the
     * release. [TutorialCoachActions.actionFor] maps it to the action.
     *
     * The default — every field false / null — is a release that resolved
     * nothing (the felt, or unlinked copy).
     */
    data class PointerUpResult(
        /** Next / Restart, or the complete panel's primary control. */
        val primary: Boolean = false,
        /** Previous slide / previous lesson. */
        val back: Boolean = false,
        /** Body hyperlink under the release. */
        val linkHref: String? = null,
    )

    /**
     * Apply one frame's [TutorialCoachSnapshot]: show / hide the panel, install
     * the parked wall deal, start a requested leave, and advance the slide and
     * diagram clocks.
     *
     * The presenter calls this once with the ticking `dt` from `motion` and again
     * with `dt == 0` from `draw`, so a scene the paint layout carries is applied
     * before it paints even when motion saw the pre-tick model (or was skipped for
     * a zero delta). The call is keyed, so an unchanged scene is a no-op and `dt ==
     * 0` does not advance a clock.
     *
     * Returns true on the ticking frame an in-flight leave's exit finished, so the
     * presenter can report it and the host can land the destination. A `dt == 0`
     * call never returns true: the upward leave signal stays with `motion`.
     */
    /**
     * One frame's "the picture is still changing" gate.
     *
     * Every clock [update] advances must appear here, or it does not animate: with
     * `setContinuousRendering(false)` nothing presents unless something asks, so an
     * unreported clock advances only when unrelated input delivers a frame. That
     * was the frozen-tutorial bug — [seatWindsView] ran a live loop and a 1.1 s
     * East pulse while this returned `false`.
     *
     * The sub-views own their own clocks and report them ([SeatWindsView.needsFrames],
     * [WallBuildView.needsFrames], [SlidePresenter.needsFrames]); this only
     * aggregates, so a new diagram is covered by adding its own report.
     */
    fun needsFrames(): Boolean =
        visible && (
            slidePresenter.needsFrames() ||
                wallBuildView.needsFrames() ||
                seatWindsView.needsFrames() ||
                // The spare peel-aside, a finite ease — not the static run.
                (notASequencePhase > 0 && notASequencePeelT < 1f)
            )

    /**
     * The pane a board-overlay beat leaves the live table, for a lesson content
     * space of [width] × [height].
     *
     * The coach paints above the board, so the board cannot read the pane from
     * the frame in which the coach lays it out. It does not have to: a board
     * pane is a fact about the viewport and the **campaign-wide** board copy
     * band ([TutorialCatalog.boardCopyBand]), never about the beat on screen.
     * Deriving it here answers before any coach has drawn, while the coach is
     * down (a play beat), and on the first frame after a resize — the same pane
     * the coach's own plan hands the table on a board beat.
     */
    fun boardAreaFor(width: Float, height: Float): SlideChromeLayout.BoardArea {
        val vp = SlideViewport(
            width = width,
            height = height,
            touch = InputProfile.detect() == InputProfile.Touch,
            uiScale = Fonts.uiScale,
        )
        val locale = L10n.currentLocale.tag
        val cached = paneArea
        if (cached != null && paneViewport == vp && paneLocale == locale) return cached

        Fonts.ensureCreated()
        Fonts.ensureDocFonts()
        resolveBoardBandCopy(locale)
        val titleFont = Fonts.labelActive
        // The current beat's copy is measured as empty: the band already holds
        // every board beat's title and body, and it is the taller of the two, so
        // the pane cannot depend on which beat is up. `titleEmpty` stays false —
        // a board beat has its lesson's title, and the band's title gap is part
        // of the pane the coach lays out.
        val plan = SlideChromeLayout.plan(
            vp = vp,
            hasTileDiagram = false,
            titleEmpty = false,
            bodyEmpty = false,
            titleLineHeight = titleFont.lineHeight,
            bodyLineHeight = copy.bodyLineAdvance(),
            buttonLineHeight = Fonts.button.lineHeight,
            measureTitleH = { _, _ -> 0f },
            measureBodyH = { _, _ -> 0f },
            measureCopyBandH = { scale, maxW -> copy.measureBandH(boardBandBodies, maxW, scale) },
            measureCopyBandTitleH = { scale, maxW ->
                measureBoardBandTitleH(titleFont, scale, maxW)
            },
            fitBodyScale = { base, _, _ -> base },
            measurePrimaryLabelW = { 0f },
            boardOverlay = true,
        )
        val area = SlideChromeLayout.boardArea(vp, plan)
        paneViewport = vp
        paneLocale = locale
        paneArea = area
        return area
    }

    /**
     * Localize the campaign board band once. Its scenes are static, so only a
     * locale change re-resolves them; the measured heights are cached by width
     * and scale in [CoachCopyText] and [measureBoardBandTitleH].
     */
    private fun resolveBoardBandCopy(locale: String) {
        if (locale == boardBandLocale) return
        val band = TutorialCatalog.boardCopyBand ?: return
        boardBandLocale = locale
        boardBandTitles = band.scenes.map { copy.title(it.titleKey) }
        boardBandBodies = band.scenes.map {
            copy.body(bodyKey = it.bodyKey, focus = it.focus, diceFaces = null)
        }
        boardBandTitleWidth = -1f
        boardBandTitleScale = -1f
        boardBandTitleHeight = 0f
    }

    fun applyScene(values: TutorialCoachSnapshot, dtSec: Float): Boolean {
        val isNewLesson = !hasAppliedLesson || values.lessonId != appliedLessonId
        if (isNewLesson) {
            hasAppliedLesson = true
            appliedLessonId = values.lessonId
            lastScene = null
            appliedKey = null
            appliedSceneKey = null
            completedSlideLeave = false
            wallBuildView.clearLesson()
            installedDeal = null
            committedDeal = null
            committedHandoff = null
            lastHandoff = null
        }
        applyLeave(values)
        if (values.visible) {
            // The band belongs to the step, not to the visible scene key: a
            // step change that keeps the same copy can still move between
            // bands (for example across a non-coach step).
            values.copyBand?.let { band ->
                if (copyBandScenes != band.scenes) copyBandScenes = band.scenes
            }
        }
        // A beat the model says is up must be *presented*, not merely
        // remembered. The route can leave the stack (a quiz, a play beat)
        // without the widget being told, so [visible] and the last painted frame
        // survive; Back onto that same slide then matches [appliedKey] again, and
        // a key-only check would leave the panel on the frame its exit left
        // behind — diagram up, copy and buttons gone. A presenter that has gone
        // Hidden is the fact that the beat is no longer presented. The exception
        // is the leave that hid it: that snapshot must not resurrect the beat
        // while the host is landing the next one.
        val presenterGone =
            slidePresenter.phase == SlidePresenter.Phase.Hidden && !values.leaving
        if (!values.visible) {
            if (visible) hideInternal()
        } else if (values.key != appliedKey || !visible || presenterGone) {
            val wasVisible = visible
            appliedKey = values.key
            showInternal(values, isNewLesson = isNewLesson, wasVisible = wasVisible)
        }
        if (visible) {
            slidePresenter.update(dtSec)
            slideFrame = slidePresenter.frame()
            allowBodyLinks = slidePresenter.phase == SlidePresenter.Phase.Idle
            update(dtSec)
            // The wall mapping may only be complete after layout; keep watching so a
            // deal committed past the present frame is still reported once.
            if (diagram == TutorialDiagram.WallBuild && !boardOverlay) {
                captureCommittedDeal()
                captureHandoff()
            }
        }
        // A paint application (`dt == 0`) applies the scene but never reports the
        // leave: only the ticking motion call owns that upward signal.
        if (dtSec <= 0f) return false
        val done = leaveComplete
        leaveComplete = false
        return done
    }

    /** Deal the wall view committed since the presenter last reported one. */
    fun consumeCommittedDeal(): WallBuildDeal? {
        val deal = committedDeal ?: return null
        committedDeal = null
        return deal
    }

    /**
     * Wall→table handoff poses the view held while a wall beat was up. The host
     * parks them and seeds the live table from the park; the widget keeps no
     * live getter.
     */
    fun consumeWallHandoff(): WallBuildHandoffSnapshot? {
        val handoff = committedHandoff ?: return null
        committedHandoff = null
        return handoff
    }

    private fun captureHandoff() {
        val handoff = wallBuildView.exportHandoffSnapshot()
        if (handoff.isEmpty || handoff == lastHandoff) return
        lastHandoff = handoff
        committedHandoff = handoff
    }

    private fun applyLeave(values: TutorialCoachSnapshot) {
        if (!visible) {
            // No scene on screen yet; a leave request is picked up once it is.
            leaveRequested = false
            requestedToward = null
            return
        }
        if (!values.leaving) {
            leaveRequested = false
            requestedToward = null
            return
        }
        val changed = !leaveRequested ||
            requestedToward != values.leaveToward ||
            requestedPreserve != values.leavePreserveContinuity
        if (!changed) return
        leaveRequested = true
        requestedToward = values.leaveToward
        requestedPreserve = values.leavePreserveContinuity
        if (slidePresenter.phase == SlidePresenter.Phase.Exiting) slidePresenter.abortLeave()
        if (slidePresenter.requestAdvance(values.leaveToward, values.leavePreserveContinuity) ==
            SlidePresenter.AdvanceResult.Ignored
        ) {
            // Nothing on screen to exit: the host lands immediately, next frame.
            leaveComplete = true
            leaveRequested = false
        }
    }

    private fun showInternal(
        values: TutorialCoachSnapshot,
        isNewLesson: Boolean,
        wasVisible: Boolean,
    ) {
        // Leaving a wall beat: capture the handoff poses before [suspend] clears
        // the view state the live-table seed needs.
        if (diagram == TutorialDiagram.WallBuild && !boardOverlay) captureHandoff()
        mode = values.mode
        offersNext = values.offersNext
        offersExit = values.offersExit
        offersBack = values.offersBack
        titleKey = values.titleKey
        bodyKey = values.bodyKey
        primaryKey = values.primaryKey
        diagram = values.diagram
        focus = values.focus
        boardOverlay = values.boardOverlay
        visible = true
        syncNotASequenceAnimation(values.bodyKey, values.diagram)
        if (values.diagram == TutorialDiagram.WallBuild && !boardOverlay) {
            values.wallDeal?.let { deal ->
                if (deal != installedDeal) {
                    wallBuildView.installDeal(deal)
                    installedDeal = deal
                }
            }
            // Present beat; lesson roll survives suspend/Back within the wall unit.
            wallBuildView.present(values.bodyKey)
            captureCommittedDeal()
        } else {
            // Other diagrams / complete chrome / board overlay: stop drawing,
            // keep lesson roll for Back.
            wallBuildView.suspend()
        }
        if (values.diagram == TutorialDiagram.SeatWinds && !boardOverlay) {
            seatWindsView.present(values.bodyKey)
        } else {
            seatWindsView.suspend()
        }
        val scene = values.scene ?: SlideScene(
            titleKey = values.titleKey,
            bodyKey = values.bodyKey,
            diagram = values.diagram,
            focus = values.focus,
            continuityKey = null,
        )
        copyBandScenes = values.copyBand?.scenes ?: listOf(scene)
        val fromSlideLeave = completedSlideLeave
        completedSlideLeave = false
        val isTargetChangeOnly = wasVisible && !isNewLesson && !fromSlideLeave && values.sceneKey == appliedSceneKey
        val settleImmediately = when {
            isNewLesson -> true
            fromSlideLeave -> false
            isTargetChangeOnly -> false
            else -> true
        }
        slidePresenter.show(
            scene = scene,
            presentation = values.presentation,
            previous = lastScene,
            onExitComplete = {
                leaveComplete = true
                completedSlideLeave = true
            },
            settleImmediately = settleImmediately,
        )
        lastScene = scene
        appliedSceneKey = values.sceneKey
    }

    private fun captureCommittedDeal() {
        val deal = wallBuildView.exportDeal() ?: return
        if (deal != installedDeal) {
            installedDeal = deal
            committedDeal = deal
        }
    }

    /** Full overlay teardown; the host drives it by publishing an absent scene. */
    private fun hideInternal() {
        visible = false
        offersNext = false
        offersExit = false
        offersBack = false
        titleKey = ""
        bodyKey = ""
        copyBandScenes = emptyList()
        primaryKey = "menu.restart_tutorial"
        diagram = TutorialDiagram.None
        focus = null
        boardOverlay = false
        slideFrame = SlideFrame.SETTLED
        allowBodyLinks = false
        notASequencePhase = 0
        notASequencePeelT = 1f
        copy.reset()
        // The resolved band and its measured height stay: a board band spans
        // the campaign, and a play beat between two board beats must not make
        // the next beat re-resolve and re-measure a hundred bodies.
        // Suspend display; lesson roll stays for same-lesson Back / re-present.
        wallBuildView.suspend()
        seatWindsView.suspend()
        diagramTiles.clearAnchors()
        liveFocusAnchor = null
        handPointerActive = false
        backW = 0f
        backH = 0f
        slidePresenter.hide()
        lastScene = null
        appliedKey = null
        appliedSceneKey = null
        completedSlideLeave = false
    }

    /**
     * Advance local diagram animations (Not-a-sequence peels, wall-build ease,
     * seat-winds deal-pass). Called from [applyScene] while the coach is visible.
     */
    private fun update(dtSec: Float) {
        if (!visible || dtSec <= 0f) return
        if (diagram == TutorialDiagram.NotASequenceRun && notASequencePhase > 0 && notASequencePeelT < 1f) {
            notASequencePeelT = min(1f, notASequencePeelT + dtSec / NOT_A_SEQUENCE_PEEL_SEC)
        }
        if (diagram == TutorialDiagram.WallBuild) {
            wallBuildView.update(dtSec)
        }
        if (diagram == TutorialDiagram.SeatWinds) {
            seatWindsView.update(dtSec)
        }
    }

    private fun syncNotASequenceAnimation(bodyKey: String, diagram: TutorialDiagram) {
        if (diagram != TutorialDiagram.NotASequenceRun) {
            notASequencePhase = 0
            notASequencePeelT = 1f
            return
        }
        val phase = notASequencePhaseFor(bodyKey)
        if (phase != notASequencePhase) {
            notASequencePhase = phase
            // Static run needs no peel; aside beats start clustered and open.
            notASequencePeelT = if (phase == 0) 1f else 0f
        }
    }

    private fun notASequencePhaseFor(bodyKey: String): Int = when (bodyKey) {
        "tutorial.orient.numbers.four_aside3" -> 1
        "tutorial.orient.numbers.four_aside6" -> 2
        else -> 0
    }

    companion object {
        /** Duration of spare peel-aside after an aside beat is shown. */
        private const val NOT_A_SEQUENCE_PEEL_SEC = 0.42f

            /** The diagrams that occupy the tile slot between the body and the buttons. */
            private val TILE_DIAGRAMS: Set<TutorialDiagram> = setOf(
                TutorialDiagram.ThreeSuits,
                TutorialDiagram.Honors,
                TutorialDiagram.TileTypes,
                TutorialDiagram.SequenceExample,
                TutorialDiagram.NotASequenceRun,
                TutorialDiagram.WallBuild,
                TutorialDiagram.RegularHand,
                TutorialDiagram.SetKinds,
                TutorialDiagram.KongAsTriplet,
                TutorialDiagram.SeatWinds,
                TutorialDiagram.TileTags,
            )
        }

    private fun a(channel: SlideChannel): Float = slideFrame.alphaOf(channel)

    /**
     * Pure hit-test: primary / back action buttons and body hyperlinks. Empty
     * felt and unlinked copy are not controls (touch free-space menu may open
     * there).
     */
    private fun hitsControl(worldX: Float, worldY: Float): Boolean {
        if (!visible) return false
        if (offersBack && backW > 0f &&
            inRect(worldX, worldY, backLeft, backBottom, backW, backH)
        ) {
            return true
        }
        if (inRect(worldX, worldY, primaryLeft, primaryBottom, primaryW, primaryH)) {
            return true
        }
        return allowBodyLinks && copy.linkAt(worldX, worldY) != null
    }

    /** Null when the slide is not Idle, or no href under the point. */
    private fun linkAt(worldX: Float, worldY: Float): String? {
        if (!visible || !allowBodyLinks) return null
        return copy.linkAt(worldX, worldY)
    }

    /**
     * Claims a press that **starts** on one of the panel's own controls or body
     * links. The felt is declined: a board-overlay beat draws the live table
     * underneath, and the free-space menu still needs its miss.
     */
    override fun onPointerDown(x: Float, y: Float): Boolean = hitsControl(x, y)

    /**
     * Resolve a release this surface owns. Back outranks the primary control it
     * can overlap, then the primary control, then the body link under the point.
     */
    override fun onPointerUp(x: Float, y: Float): PointerUpResult {
        if (!visible) return PointerUpResult()
        if (offersBack && backW > 0f &&
            inRect(x, y, backLeft, backBottom, backW, backH)
        ) {
            return PointerUpResult(back = true)
        }
        if (inRect(x, y, primaryLeft, primaryBottom, primaryW, primaryH)) {
            return PointerUpResult(primary = true)
        }
        return PointerUpResult(linkHref = linkAt(x, y))
    }

    fun render(
        batch: SpriteBatch,
        screenW: Float,
        screenH: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        /**
         * Live-hand tile rect the callout parks on, resolved by the presenter
         * from the board's published layout. Null = no park this frame.
         */
        liveFocus: Rect? = null,
        /** True when the pointer is over a live-hand tile (hover cursor). */
        handPointerActive: Boolean = false,
    ) {
        if (!visible) return
            val plan = planFrame(screenW, screenH)
            paintFrame(
                batch = batch,
                screenW = screenW,
                screenH = screenH,
                pointerWorldX = pointerWorldX,
                pointerWorldY = pointerWorldY,
                liveFocus = liveFocus,
                handPointerActive = handPointerActive,
                plan = plan,
            )
        }

        /**
         * The band's copy, localized once per band instead of once per frame. A
         * board band spans the whole campaign, so re-resolving it every frame
         * would localize a hundred bodies just to measure the same height.
         */
        private fun resolveBandCopy() {
            val locale = L10n.currentLocale.tag
            val sameScenes = resolvedBandScenes === copyBandScenes ||
                resolvedBandScenes == copyBandScenes
            if (sameScenes && resolvedBandLocale == locale) return
            resolvedBandScenes = copyBandScenes
            resolvedBandLocale = locale
            resolvedBandTitles = copyBandScenes.map { copy.title(it.titleKey) }
            resolvedBandBodies = copyBandScenes.map {
                copy.body(bodyKey = it.bodyKey, focus = it.focus, diceFaces = null)
            }
            bandTitleWidth = -1f
            bandTitleScale = -1f
            bandTitleHeight = 0f
        }

        /** Wrapped height of [text] at [scale] in [maxW], through [font]. */
        private fun measureWrapped(
            font: BitmapFont,
            text: String,
            scale: Float,
            maxW: Float,
        ): Float {
            if (text.isEmpty()) return 0f
            val prevSx = font.data.scaleX
            val prevSy = font.data.scaleY
            font.data.setScale(prevSx * scale, prevSy * scale)
            try {
                glyphLayout.setText(font, text, Color.WHITE, maxW, Align.center, true)
                return glyphLayout.height
            } finally {
                font.data.setScale(prevSx, prevSy)
            }
        }

        /**
         * Tallest title in the band at [maxW] / [scale]. A board band spans
         * every board lesson, whose titles differ; reserving the tallest keeps
         * the table's top edge off the lesson's title.
         */
        private fun measureBandTitleH(
            font: BitmapFont,
            scale: Float,
            maxW: Float,
        ): Float {
            if (resolvedBandTitles.isEmpty()) return 0f
            if (abs(maxW - bandTitleWidth) < 0.5f &&
                abs(scale - bandTitleScale) < 0.001f
            ) {
                return bandTitleHeight
            }
            var tallest = 0f
            for (bandTitle in resolvedBandTitles) {
                tallest = max(tallest, measureWrapped(font, bandTitle, scale, maxW))
            }
            bandTitleWidth = maxW
            bandTitleScale = scale
            bandTitleHeight = tallest
            return tallest
        }

        /**
         * Tallest title in the **campaign board band** at [maxW] / [scale]. Its
         * own cache: [measureBandTitleH] answers for the band the current beat
         * belongs to, which is a different band during a diagram beat.
         */
        private fun measureBoardBandTitleH(
            font: BitmapFont,
            scale: Float,
            maxW: Float,
        ): Float {
            if (boardBandTitles.isEmpty()) return 0f
            if (abs(maxW - boardBandTitleWidth) < 0.5f &&
                abs(scale - boardBandTitleScale) < 0.001f
            ) {
                return boardBandTitleHeight
            }
            var tallest = 0f
            for (bandTitle in boardBandTitles) {
                tallest = max(tallest, measureWrapped(font, bandTitle, scale, maxW))
            }
            boardBandTitleWidth = maxW
            boardBandTitleScale = scale
            boardBandTitleHeight = tallest
            return tallest
        }

        /**
         * Everything this frame needs before a pixel is drawn: the viewport, the
         * localized copy, the chrome rect, the button dock, and the veil's edge.
         *
         * Measuring is separated from painting so the layout that the hit tests
         * read ([hitsControl], [linkAt]) is the same one the paint consumed.
         */
        private fun planFrame(screenW: Float, screenH: Float): FramePlan {
            this.liveFocusAnchor = null
            Fonts.ensureCreated()

            val touch = InputProfile.detect() == InputProfile.Touch
            val vp = SlideViewport(
                width = screenW,
                height = screenH,
                touch = touch,
                uiScale = Fonts.uiScale,
            )

            val hasTileDiagram = !boardOverlay && diagram in TILE_DIAGRAMS

            val titleFont = Fonts.labelActive
            Fonts.ensureDocFonts()
            // Body uses rich-text doc faces (bold/italic/tiles); chrome still scales.
        // The band reserves whole body lines, so it must use the advance the
        // rich layout actually consumes (tile-aware), not the face's line height.
        val bodyLineHeight = copy.bodyLineAdvance()
        val btnFont = Fonts.button

        val title = copy.title(titleKey)
                // Count beat uses ShowSettled; present() always commits a roll first.
                val diceFaces = if (bodyKey == "tutorial.orient.wall.count") {
                    val faces = checkNotNull(wallBuildView.diceFacesOrNull()) {
                        "count beat requires a committed dice roll"
                    }
                    Triple(faces.first, faces.second, faces.third)
                } else {
                    null
                }
                val body = copy.body(bodyKey, focus, diceFaces)
                copy.prepare(body)
        // The band's copy, resolved from its own scenes. Transient
        // substitutions (the wall dice roll) swap digits on one line and do not
        // change the band, so the authored pattern is measured; that keeps the
        // band cache stable while paging the family. A board overlay has the
        // live table below the copy, so it holds the band still too; a plain
        // text slide has nothing below the copy and skips it.
        resolveBandCopy()
        val bandMarkups = if (!hasTileDiagram && !boardOverlay) {
            emptyList()
        } else if (resolvedBandBodies.isEmpty()) {
            listOf(body)
        } else {
            resolvedBandBodies
        }
        val primaryLabel = L10n.get(
            when (mode) {
                TutorialCoachSnapshot.Mode.Coach -> "tutorial.next"
                TutorialCoachSnapshot.Mode.Complete -> primaryKey
            },
        )
        val backLabel = if (offersBack) L10n.get("tutorial.back") else ""

        val chrome = SlideChromeLayout.plan(
            vp = vp,
            hasTileDiagram = hasTileDiagram,
            titleEmpty = title.isEmpty(),
            bodyEmpty = body.isEmpty(),
            titleLineHeight = titleFont.lineHeight,
            bodyLineHeight = bodyLineHeight,
            buttonLineHeight = btnFont.lineHeight,
            measureTitleH = { scale, maxW -> measureWrapped(titleFont, title, scale, maxW) },
            measureBodyH = { scale, maxW -> copy.measureBodyH(maxW, scale) },
            measureCopyBandH = { scale, maxW -> copy.measureBandH(bandMarkups, maxW, scale) },
            measureCopyBandTitleH = { scale, maxW ->
                measureBandTitleH(titleFont, scale, maxW)
            },
            fitBodyScale = { base, maxW, maxH -> copy.fitBodyScale(maxW, base, maxH) },
            measurePrimaryLabelW = {
                glyphLayout.setText(btnFont, primaryLabel)
                glyphLayout.width
            },
            boardOverlay = boardOverlay,
        )
        // Dock: optional Back left of primary, pair centered when both shown.
        primaryH = chrome.primaryH
        primaryBottom = chrome.primaryBottom
        backH = chrome.primaryH
        backBottom = chrome.primaryBottom
        val btnGap = Fonts.px(if (touch) 12f else 14f)
        // The plan already sized the primary from its own label; the Back label
        // only shifts the block, it does not re-measure Next. Both widths go
        // through the same row fit the quiz dock uses.
        if (offersBack && backLabel.isNotEmpty()) {
            glyphLayout.setText(btnFont, backLabel)
            val widths = SlideChromeLayout.fitActionRow(
                widths = listOf(
                    SlideChromeLayout.actionButtonWidth(
                        vp, glyphLayout.width, SlideChromeLayout.BACK_BUTTON_MIN_PX,
                    ),
                    chrome.primaryW,
                ),
                gap = btnGap,
                maxW = chrome.rowW,
            )
            backW = widths[0]
            primaryW = widths[1]
            val blockLeft = chrome.rowLeft + (chrome.rowW - (backW + btnGap + primaryW)) * 0.5f
            backLeft = blockLeft
            primaryLeft = blockLeft + backW + btnGap
        } else {
            backW = 0f
            backLeft = 0f
            primaryW = chrome.primaryW
            primaryLeft = chrome.primaryLeft
        }

        // Same edge the top scrim paints to. Callouts stay below it.
        val copyScrimBottom = if (chrome.titleH > 0f || chrome.bodyH > 0f) {
            SlideChromeLayout.copyScrimBottom(chrome).coerceIn(0f, screenH)
        } else {
            screenH
        }
        val buttonBandTop = SlideChromeLayout.buttonBandTop(chrome)

                return FramePlan(
                    vp = vp,
                    chrome = chrome,
                    titleFont = titleFont,
                    title = title,
                    body = body,
                    primaryLabel = primaryLabel,
                    backLabel = backLabel,
                    hasTileDiagram = hasTileDiagram,
                    copyScrimBottom = copyScrimBottom,
                    buttonBandTop = buttonBandTop,
                )
            }

            /** Paint one frame from its [FramePlan], in the panel's fixed layer order. */
            private fun paintFrame(
                batch: SpriteBatch,
                screenW: Float,
                screenH: Float,
                pointerWorldX: Float,
                pointerWorldY: Float,
                liveFocus: Rect?,
                handPointerActive: Boolean,
                plan: FramePlan,
            ) {
                val px = whitePixel()
                this.liveFocusAnchor = liveFocus?.let {
                    AnchorRect(it.left, it.bottom, it.width, it.height)
                }
                this.handPointerActive = handPointerActive
                with(plan) {
                val vp = this.vp
                val chrome = this.chrome
                val titleFont = this.titleFont
                val title = this.title
                val body = this.body
                val primaryLabel = this.primaryLabel
                val backLabel = this.backLabel
                val hasTileDiagram = this.hasTileDiagram
                val copyScrimBottom = this.copyScrimBottom
                val buttonBandTop = this.buttonBandTop

                if (boardOverlay) {
                    if (chrome.mode == SlideMode.Side) {
                        copyScrim.drawColumnDim(
                            batch = batch,
                            px = px,
                            screenW = screenW,
                            screenH = screenH,
                            columnLeft = chrome.columnLeft,
                        )
                    } else {
                        copyScrim.drawFeltDim(
                            batch = batch,
                            px = px,
                            screenW = screenW,
                            screenH = screenH,
                            copyScrimBottom = copyScrimBottom,
                            buttonBandTop = buttonBandTop,
                        )
                    }
                } else {
                    copyScrim.drawSolidFelt(batch, px, screenW, screenH)
                }

        // Draw title
        if (title.isNotEmpty()) {
            withFontScale(titleFont, chrome.titleScale) {
                titleFont.color = Color(AppPalette.accent.r, AppPalette.accent.g, AppPalette.accent.b, 1f)
                titleFont.draw(
                    batch, title, chrome.textLeft, chrome.titleTop,
                    chrome.maxTextW, Align.center, true,
                )
                titleFont.color = Color.WHITE
            }
        }

        // Draw body (rich markup: bold / colors / inline tiles)
        if (body.isNotEmpty()) {
                    copy.drawBody(
                batch = batch,
                left = chrome.textLeft,
                top = chrome.bodyTop,
                maxWidth = chrome.maxTextW,
                scale = chrome.bodyScale,
                pointerWorldX = pointerWorldX,
                pointerWorldY = pointerWorldY,
            )
        }

        diagramTiles.clearAnchors()
                val diagramA = a(SlideChannel.Diagram)
                if (hasTileDiagram && chrome.diagramH > Fonts.px(40f) && diagramA > 0.01f) {
                    val prevBatch = batch.color.cpy()
                    batch.setColor(AppPalette.tint(AppPalette.headerInk, diagramA))
                    when (diagram) {
                        // These two own their own painter and their own clocks.
                        TutorialDiagram.WallBuild -> {
                            wallBuildView.ensureArea(
                                chrome.diagramLeft, chrome.diagramBottom, chrome.diagramW, chrome.diagramH,
                            )
                            wallBuildView.draw(batch, diagramTiles.drawer(), px, diagramA)
                        }
                        TutorialDiagram.SeatWinds -> {
                            seatWindsView.ensureArea(
                                chrome.diagramLeft, chrome.diagramBottom, chrome.diagramW, chrome.diagramH,
                            )
                            seatWindsView.draw(batch, px, diagramA)
                        }
                        else -> diagramTiles.draw(
                            batch = batch,
                            diagram = diagram,
                            vp = vp,
                            left = chrome.diagramLeft,
                            bottom = chrome.diagramBottom,
                            areaW = chrome.diagramW,
                            areaH = chrome.diagramH,
                            frame = slideFrame,
                            peel = CoachDiagramTiles.NotASequencePeel(notASequencePhase, notASequencePeelT),
                            bodyKey = bodyKey,
                        )
                    }
                    batch.color = prevBatch
                    paintDiagramFocus(batch, px, screenW, screenH, chrome)
                } else if (boardOverlay) {
                    paintLiveFocus(batch, px, screenW, screenH, copyScrimBottom, buttonBandTop, chrome)
                }

        // The dock is chrome: it appears with the beat, never after the tiles.
        if (offersBack && backW > 0f && backLabel.isNotEmpty()) {
            TableChip.draw(
                batch, px, backLabel, backLeft, backBottom, backW, backH,
                pointerWorldX, pointerWorldY,
                tone = TableChip.Tone.Idle, alpha = 1f,
            )
        }
        TableChip.draw(
            batch, px, primaryLabel, primaryLeft, primaryBottom, primaryW, primaryH,
            pointerWorldX, pointerWorldY,
            tone = TableChip.Tone.Emphasized, alpha = 1f,
        )

        updateDesktopCursor(pointerWorldX, pointerWorldY)
                }
            }

            /**
             * One frame's measured layout: what [planFrame] resolved and [paintFrame]
             * consumes. Immutable, so the two phases cannot disagree.
             */
            private class FramePlan(
                val vp: SlideViewport,
                val chrome: SlideChromeLayout.Plan,
                val titleFont: BitmapFont,
                val title: String,
                val body: String,
                val primaryLabel: String,
                val backLabel: String,
                val hasTileDiagram: Boolean,
                /** Bottom edge of the dim behind the title and body. */
                val copyScrimBottom: Float,
                val buttonBandTop: Float,
            )

    /**
     * Highlight the diagram's focused target and float its callout.
     *
     * The overlay owns the target and the chrome rect; [CoachFocusChrome] owns
     * the ink, and [CoachDiagramTiles] owns the anchors it frames.
     */
    private fun paintDiagramFocus(
        batch: SpriteBatch,
        px: Texture,
        screenW: Float,
        screenH: Float,
        chrome: SlideChromeLayout.Plan,
    ) {
        val f = focus ?: return
        val anchor = diagramTiles.anchorFor(f.targetId) ?: return
        val side = chrome.mode == SlideMode.Side
        focusChrome.paintDiagramFocus(
            batch = batch,
            px = px,
            screenW = screenW,
            screenH = screenH,
            anchor = anchor,
            focus = f,
            slideFrame = slideFrame,
            tiles = diagramTiles,
            avoidBottom = if (side) 0f else primaryBottom + primaryH,
            avoidRight = if (side) chrome.columnLeft else Float.POSITIVE_INFINITY,
        )
    }

    /**
     * Board-overlay focus: the callout hangs off the live tile with a leader.
     * No halo or second face — the tile stays the board's tile. A side layout
     * keeps the balloon on the board's side of the copy column and frees it from
     * the (absent) bottom button band.
     */
    private fun paintLiveFocus(
        batch: SpriteBatch,
        px: Texture,
        screenW: Float,
        screenH: Float,
        copyScrimBottom: Float,
        buttonBandTop: Float,
        chrome: SlideChromeLayout.Plan,
    ) {
        val f = focus ?: return
        val live = liveFocusAnchor ?: return
        diagramTiles.publishAnchor(f.targetId, live)
        val side = chrome.mode == SlideMode.Side
        focusChrome.paintLiveFocus(
            batch = batch,
            px = px,
            screenW = screenW,
            live = live,
            focus = f,
            slideFrame = slideFrame,
            copyScrimBottom = if (side) screenH else copyScrimBottom,
            buttonBandTop = if (side) 0f else buttonBandTop,
            avoidRight = if (side) chrome.columnLeft else Float.POSITIVE_INFINITY,
        )
    }

                private fun updateDesktopCursor(pointerWorldX: Float, pointerWorldY: Float) {
        if (!InputProfile.detect().usesHover) return
        if (pointerWorldX.isNaN() || pointerWorldY.isNaN()) {
            UiCursor.arrow()
            return
        }
        val overLink = allowBodyLinks && copy.linkAt(pointerWorldX, pointerWorldY) != null
        val overBack = offersBack && backW > 0f && inRect(pointerWorldX, pointerWorldY, backLeft, backBottom, backW, backH)
        val overPrimary = inRect(pointerWorldX, pointerWorldY, primaryLeft, primaryBottom, primaryW, primaryH)
        val overPromptTile = liveFocusAnchor?.let {
            inRect(pointerWorldX, pointerWorldY, it.left, it.bottom, it.w, it.h)
        } == true
        if (overLink || overBack || overPrimary || overPromptTile || handPointerActive) {
            UiCursor.hand()
        } else {
            UiCursor.arrow()
        }
    }

    private inline fun withFontScale(font: BitmapFont, scale: Float, block: () -> Unit) {
        val prevSx = font.data.scaleX
        val prevSy = font.data.scaleY
        font.data.setScale(prevSx * scale, prevSy * scale)
        try {
            block()
        } finally {
            font.data.setScale(prevSx, prevSy)
        }
    }

    override fun dispose() {
            diagramTiles.dispose()
            copy.close()
        }


    private fun inRect(
        x: Float,
        y: Float,
        left: Float,
        bottom: Float,
        w: Float,
        h: Float,
    ): Boolean = x >= left && x <= left + w && y >= bottom && y <= bottom + h
}
