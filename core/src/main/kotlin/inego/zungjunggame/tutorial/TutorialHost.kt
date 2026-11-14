package inego.zungjunggame.tutorial

import inego.zungjunggame.engine.Choice
import inego.zungjunggame.engine.ChoiceKind
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.persist.TutorialSessionSnapshot
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.render.table.BoardHighlight
import inego.zungjunggame.render.reference.CatalogLinkPolicy
import inego.zungjunggame.render.table.TableApply
import inego.zungjunggame.render.table.TableApplyKind
import inego.zungjunggame.render.table.TableInteract
import inego.zungjunggame.render.reference.ReferenceCatalog
import inego.zungjunggame.features.tutorial.TutorialCoachSnapshot
import inego.zungjunggame.features.tutorial.TutorialQuizSnapshot
import inego.zungjunggame.features.tutorial.TutorialSnapshot
import inego.zungjunggame.features.tutorial.TutorialTableChrome
import inego.zungjunggame.features.tutorial.tutorialCoachContent
import inego.zungjunggame.features.tutorial.tutorialQuizContent
import inego.zungjunggame.render.wallbuild.WallBuildBeat
import inego.zungjunggame.render.wallbuild.WallBuildBeats
import inego.zungjunggame.render.wallbuild.WallBuildDeal
import inego.zungjunggame.render.wallbuild.WallBuildHandoffSnapshot
import inego.zungjunggame.render.wallbuild.WallBuildToGameState
import inego.zungjunggame.session.board.BoardController
import inego.zungjunggame.session.board.ChoiceGate
import inego.zungjunggame.session.board.EngineNonHumanPicker
import inego.zungjunggame.session.hosts.FreePlayMenuPort
import inego.zungjunggame.session.board.NonHumanTurnStepper
import inego.zungjunggame.ui.stack.SessionKind
import inego.zungjunggame.platform.newRandom
import inego.zungjunggame.tutorial.slide.SlideScene

/**
 * Owns interactive tutorial scenarios, step progression, and slide/coach
 * overlays, and the stack membership of the shell context they are hosted in.
 * Cleans up the hosted lesson on exit.
 *
 * **Independent of free-play.** A lesson owns its own live board and its own
 * mid-lesson save (`session.tutorial`), so entering the tutorial leaves a parked
 * local game — memory and the `session.freeplay` key — exactly where it was, and
 * leaving the lesson hands the table back to it. Starting free-play drops the
 * live tutorial runner but **keeps** the mid-lesson save so Tutorial can resume
 * that beat and board.
 */
class TutorialHost(
    private val board: BoardController,
    private val nonHumanStepper: NonHumanTurnStepper,
    private val freePlayPicker: () -> EngineNonHumanPicker,
    private val gameMenu: FreePlayMenuPort,
    private val sessionKind: () -> SessionKind,
    private val stopMcHintSearch: () -> Unit,
    /** Drop the shared confirm card; the question it asked left with its route. */
    private val dismissConfirm: () -> Unit,
    private val logLifecycle: (String) -> Unit,
    /**
     * Swap the global route to Reference over the lesson and deep-link [target]
     * (a catalog page id or section id); the place it resolves is stored by the caller.
     *
     * The route swap is what records the lesson as the browser's return
     * destination: opening the browser straight on the overlay would leave
     * its × with nowhere to go, wedging the learner on the reference page.
     */
    private val openReference: (target: String) -> Unit,
    /**
     * Open the Settings panel over the current beat. Dismiss must return
     * here, not to the game menu — this is not a menu child.
     */
    private val openSettings: () -> Unit,
    /**
     * Stack-membership surface for the tutorial: the shell context the session
     * lives in, and the lesson / quiz / coach content layers it hosts. The host
     * announces show/hide; production pushes and removes the corresponding
     * routes, so the surfaces are stack-owned rather than read from overlay
     * visibility, and the shell's layer lives exactly as long as the session.
     */
    private val routes: TutorialRoutesPort,
    /** Campaign progress (completed + started lesson ids); never free-play saves. */
    val progress: TutorialProgress,
    /** Mid-lesson board + director progress (the `session.tutorial` key). */
    val sessionSlot: StateSlot<TutorialSessionSnapshot>,
) {
    /** Coach slide scene published this frame; components project it, never the overlay. */
    private var presentedCoach: TutorialCoachSnapshot = TutorialCoachSnapshot.Absent

    /** Identity of the scene bound to [presentedCoach] (re-sync guard). */
    private var presentedCoachKey: String? = null

    /**
     * Board-overlay state of the last published coach scene. `false` whenever the
     * coach is hidden, mirroring what the overlay's own flag would have been.
     */
    private var lastCoachBoardOverlay: Boolean = false

    /**
     * Destination of the in-flight slide leave. Next and Back both call
     * [leaveCurrentSlide]; the widget reports the exit via
     * `TutorialCoachActions.LEAVE_COMPLETE` and [applySlideLeaveComplete] lands it.
     */
    private var pendingSlideLeave: PendingSlideLeave? = null

    /** Scene the exit is leaving toward (continuity hint), if any. */
    private var leaveTowardScene: SlideScene? = null

    /** Whether the exit may hold unchanged channels toward [leaveTowardScene]. */
    private var leavePreserveContinuity: Boolean = true

    /**
     * Step key for which wall→table handoff already ran (avoid re-seed every
     * sync while the same board-overlay coach is up).
     */
    private var boardHandoffStepKey: String? = null

    /**
     * Wall-build deals parked by lesson id.
     *
     * Last committed deal (dice + map) for each wall lesson. Pre-roll beats
     * ([WallBuildBeat.DicePolicy.ClearRoll]) drop it so the dice beat tumbles
     * a new result; later beats and the overlay recapture / bind that deal.
     * Survives leaving the lesson so Back / recover-on-the-next-unit restore
     * the same hands. Forgotten in memory on a pre-roll beat, a fresh start of
     * that lesson, or leaving the live tutorial (the mid-lesson save still
     * carries the park for restore).
     */
    private val wallDeals = TutorialWallDeals()

    /**
     * Last wall→table handoff poses the coach view published. Parked by the host
     * (like [wallDeals]); [maybeHandoffWallBuildToTable] seeds the table from it.
     */
    private var wallHandoff: WallBuildHandoffSnapshot? = null

    /**
     * Active lesson host, or null outside [SessionKind.Tutorial].
     * Implements [inego.zungjunggame.session.LiveHand] for the same table / AI /
     * input paths as free play.
     */
    var runner: TutorialRunner? = null
        private set

    /** Campaign unit id currently hosted (or last restarted); drives restart / next. */
    var currentLessonId: String? = null
        private set

    private var tableInteract: TableInteract = TableInteract.Play
    private var tableOverlayLeader: Boolean = false
    private var tableHighlightLiveWall: Boolean = false
    private var tableRevealedSeats: Set<Int> = emptySet()
    private var tableBoardHighlight: BoardHighlight = BoardHighlight.None
    private var tableApply: TableApply = TableApply.None
    private var tableApplySeq: Long = 0L

    private fun publishTableApply(
        kind: TableApplyKind,
        handoff: WallBuildHandoffSnapshot? = null,
        humanSeat: Int = 0,
    ) {
        tableApplySeq++
        tableApply = TableApply(
            id = "tut:$tableApplySeq",
            kind = kind,
            wallHandoff = handoff,
            humanSeat = humanSeat,
        )
    }

    /**
     * Immutable lesson slice for this frame: campaign progress, the current
     * lesson, and the chrome facts the shell presenter projects from.
     */
    fun snapshot(): TutorialSnapshot {
        val active = runner
        val coachScene = if (pendingSlideLeave != null) {
            presentedCoach.copy(
                leaving = true,
                leaveToward = leaveTowardScene,
                leavePreserveContinuity = leavePreserveContinuity,
            )
        } else {
            presentedCoach
        }
        val quizScene = active?.let { tutorialQuizContent(it) } ?: TutorialQuizSnapshot.Absent
        return TutorialSnapshot(
            progress = progress.snapshot(),
            currentLessonId = currentLessonId,
            showsBoard = active?.showsBoard == true,
            lastSpotlight = active?.lastSpotlight,
            quizVisible = quizScene.visible,
            coachVisible = coachScene.visible,
            inTutorialSession = sessionKind() == SessionKind.Tutorial,
            coach = coachScene,
            quiz = quizScene,
            table = TutorialTableChrome(
                interact = tableInteract,
                highlightLiveWall = tableHighlightLiveWall,
                overlayLeaderActive = tableOverlayLeader,
                revealedSeats = tableRevealedSeats,
                boardHighlight = tableBoardHighlight,
                apply = tableApply,
            ),
            boardLesson = active?.scenario?.steps?.any {
                it is TutorialStep.Coach && it.showsBoard
            } == true,
        )
    }

    /**
     * True while the hosted lesson shows a live board (including after
     * [TutorialDirector.Status.Completed] until exit).
     */
    fun showsBoard(): Boolean = runner?.showsBoard == true

    /**
     * Center turn-status line stays off under lecture overlays. Click-through
     * action coaches (preamble to [TutorialStep.WaitChoice]) keep it, as do
     * play beats with overlay chrome down.
     */
    fun showTableTurnStatus(): Boolean {
        val active = runner ?: return true
        return !active.awaitingCoach || !active.currentCoachShowsBoard() || active.isPreambleToHumanWait()
    }

    /** Park a deal the wall view committed this frame. */
    fun onWallDealCommitted(deal: WallBuildDeal) {
        val id = currentLessonId ?: return
        wallDeals.put(id, deal)
    }

    /** Park the wall→table handoff poses the coach view published. */
    fun onWallHandoffCommitted(handoff: WallBuildHandoffSnapshot) {
        wallHandoff = handoff
    }

    /** Persist mid-lesson tutorial while a lesson runner is hosted. */
    fun persistSession() {
        val active = runner ?: return
        sessionSlot.set(
            active.captureSnapshot().copy(wallBuildDeals = wallDeals.snapshot()),
        )
    }

    /**
     * Root menu **Tutorial**: open / focus the lesson TOC, or start the campaign.
     *
     * A parked local game is **not** disturbed: the two keep separate live boards
     * and separate saves, so no confirm is asked and nothing is cleared.
     *
     * Mid-tutorial: open the compact drawer (side-by-side rail is already visible).
     * Idle: restore the parked mid-lesson beat if a save exists, otherwise start
     * the resume lesson (shell activates with the lesson).
     */
    fun requestOpen() {
        gameMenu.hide()
        // Mid-tutorial: the shell is already up and its TOC is the frame model.
        // `runner == null` means a half-entered tutorial (route switched to
        // Tutorial.Lesson without a lesson ever loading), which must land a
        // real beat instead of leaving an empty felt under the shell.
        if (sessionKind() == SessionKind.Tutorial && runner != null) {
            return
        }
        // Enter tutorial session; shell hosts the live lesson as content. Mid-run
        // this reads the host's own `session.tutorial` key; the cold start hands
        // the value in instead (see AppState).
        if (!tryRestore(sessionSlot.get())) {
            startLesson(null)
        }
    }

    /**
     * Load a campaign lesson onto the live table.
     *
     * @param lessonId specific lesson, or null to resume the first incomplete
     *   unlocked entry ([TutorialCatalog.resumeTarget])
     * @param atStepIndex optional user-slide step to land on after a fresh start
     *   (cross-lesson Back). Null = begin at the first blocking step.
     *   Same-lesson Back uses [applyBack] → [TutorialRunner.seekToUserSlide].
     *
     * **Independent of free-play:** the lesson builds its own live board and writes
     * only the `session.tutorial` key. A parked local game keeps its board and its
     * `session.freeplay` key, and is what the table returns to when the lesson
     * leaves. Campaign progress prefs are kept. Shares Main [board] with the
     * director so gates apply to human commits.
     *
     * Jump may target **any** catalog lesson (unlock is resume-only).
     */
    fun startLesson(lessonId: String? = null, atStepIndex: Int? = null) {
        val lesson = if (lessonId != null) {
            TutorialCatalog.lesson(lessonId) ?: TutorialCatalog.first()
        } else {
            TutorialCatalog.resumeTarget(progress)
        }
        pendingSlideLeave = null
        leaveTowardScene = null
        val destId = lesson.id
        val resumeMidLesson = atStepIndex != null
        if (!resumeMidLesson) {
            // Fresh playthrough of dest: drop that lesson's deal only.
            wallDeals.forget(destId)
        }
        clearEntryChrome()
        clearCoachMotion()
        closeQuiz()
        stopMcHintSearch()
        boardHandoffStepKey = null

        // Shared BoardController + UI coach blocks.
        // Non-deterministic RNG so orientation quiz hands (and Try again) are not
        // stuck on Random(0)'s first draw after every cold start / restore.
        val scenario = lesson.scenario()
        val started = if (atStepIndex != null) {
            TutorialRunner.startAtStep(
                scenario = scenario,
                targetStepIndex = atStepIndex,
                board = board,
                choiceRng = newRandom(),
                blockOnCoach = true,
            )
        } else {
            TutorialRunner.start(
                scenario = scenario,
                board = board,
                choiceRng = newRandom(),
                blockOnCoach = true,
            )
        }
        hostRunner(started, lessonId = destId, restored = false)
    }

    /**
     * Install [newRunner] as the live tutorial host and sync table / chrome.
     * @param restored true when rebuilt from the `session.tutorial` key
     */
    private fun hostRunner(
        newRunner: TutorialRunner,
        lessonId: String,
        restored: Boolean,
    ) {
        runner = newRunner
        currentLessonId = lessonId
        // Campaign progress: entered (partial) unless this host is already complete.
        if (newRunner.status == TutorialDirector.Status.Completed) {
            progress.markCompleted(newRunner.scenario.id)
        } else {
            progress.markStarted(newRunner.scenario.id)
        }
        // The session's own context comes up first — a restored stack already has
        // it — so the lesson below lands inside it.
        routes.showShell()
        // Bind the live lesson board to the shell as its content layer.
        routes.showLesson()
        // No MC search or HINT chrome mid-lesson.
        stopMcHintSearch()
        tableBoardHighlight = BoardHighlight.None
        nonHumanStepper.picker = freePlayPicker()
        nonHumanStepper.pauseUntilNextIdle = true
        // Wall-deal lesson overlay: bind the parked deal (invent if missing)
        // so the dummy scenario layout is never drawn.
        if (TutorialSlideNav.usesWallDeal(newRunner.scenario) && newRunner.showsBoard) {
            bindWallDealToBoard(newRunner, invent = true)
            publishTableApply(TableApplyKind.Restored)
        } else if (newRunner.showsBoard) {
            publishTableApply(TableApplyKind.Restored)
        }
        maybeOpenReference(newRunner)
        logLifecycle(
            "tutorial ${if (restored) "restore" else "start"} id=${newRunner.scenario.id} " +
                "status=${newRunner.status} step=${newRunner.director.stepIndex} " +
                "coach=${newRunner.awaitingCoach} quiz=${newRunner.awaitingQuiz} " +
                "board=${newRunner.showsBoard} wallDeal=${wallDeals.get(lessonId) != null}",
        )
        // Completed with no opt-in summary: jump to next unit mid-campaign, or
        // show the finished panel when this was the last lesson.
        if (newRunner.status == TutorialDirector.Status.Completed &&
            newRunner.scenario.completeSummaryKey == null
        ) {
            handleUnitCompleted(newRunner)
            return
        }
        syncLessonUi()
        persistSession()
    }

    /**
     * Cold-start: rebuild mid-lesson tutorial from [snapshot], the value the boot
     * read once (`AppState.tutorialSession`).
     */
    fun tryRestore(snapshot: TutorialSessionSnapshot?): Boolean {
        if (snapshot == null) return false
        val lesson = TutorialCatalog.lesson(snapshot.lessonId)
        if (lesson == null) {
            sessionSlot.clear()
            return false
        }
        val restored = TutorialRunner.restore(
            scenario = lesson.scenario(),
            snapshot = snapshot,
            board = board,
            choiceRng = newRandom(),
            blockOnCoach = true,
        )
        if (restored == null) {
            sessionSlot.clear()
            return false
        }
        // Free-play residue only (the revealed seats a handout showed). A parked local
        // game is untouched, and so is the restored stack: a card the player left
        // open over the shell is their own.
        clearFreePlayResidue()
        wallDeals.restore(snapshot.wallBuildDeals)
        hostRunner(
            restored,
            lessonId = lesson.id,
            restored = true,
        )
        return true
    }

    /**
     * Leave the live lesson and return to the main menu. Keeps the mid-lesson save
     * so the next Tutorial open can restore beat + board. Campaign progress
     * (completed lessons) is kept, and so is a parked local game — its live board
     * and its `session.freeplay` key are untouched, so the local menu's Continue
     * picks it back up.
     *
     * The menu is the root floor, so [dropLiveLesson] taking the shell context down
     * **is** the move: no card is raised to stand in for it.
     */
    fun exit() {
        if (sessionKind() != SessionKind.Tutorial && runner == null) return
        val lessonId = runner?.scenario?.id ?: currentLessonId
        persistSession()
        dropLiveLesson()
        stopMcHintSearch()
        // The breakdown/score-table/setup routes went with the shell, so the
        // paint range no longer holds them.
        nonHumanStepper.pauseUntilNextIdle = true
        logLifecycle("tutorial exit id=$lessonId")
    }

    /**
     * Restart the current lesson from step 0 without leaving [SessionKind.Tutorial].
     */
    fun restart() {
        if (sessionKind() != SessionKind.Tutorial && runner == null) return
        val lessonId = currentLessonId ?: runner?.scenario?.id
        gameMenu.hide()
        startLesson(lessonId)
        logLifecycle("tutorial restart id=$lessonId")
    }

    /** Advance to the next campaign lesson after success (Phase 4). */
    fun startNextLesson() {
        val id = currentLessonId ?: runner?.scenario?.id ?: return
        val next = TutorialCatalog.nextAfter(id) ?: return
        gameMenu.hide()
        startLesson(next.id)
        logLifecycle("tutorial next id=${next.id} from=$id")
    }

    /**
     * Previous coach/quiz slide, or last slide of the previous campaign lesson
     * when already on the first slide of the current one.
     *
     * Same-lesson Back uses the same leave-then-land path as Next
     * ([leaveCurrentSlide] + [TutorialRunner.seekToUserSlide]) so unchanged
     * title / diagram / live table are held. Cross-lesson Back still rebuilds
     * via [startLesson].
     */
    fun applyBack() {
        val active = runner ?: return
        if (sessionKind() != SessionKind.Tutorial) return
        val target = TutorialSlideNav.resolveBack(active) ?: return
        gameMenu.hide()
        if (target.lessonId != active.scenario.id) {
            clearCoachMotion()
            closeQuiz()
            startLesson(target.lessonId, atStepIndex = target.stepIndex)
            logLifecycle(
                "tutorial back lesson id=${target.lessonId} step=${target.stepIndex} " +
                    "from=${active.scenario.id} step=${active.director.stepIndex} " +
                    "wallDeal=${wallDeals.get(target.lessonId) != null}",
            )
            return
        }
        if (active.awaitingQuiz || !presentedCoach.visible) {
            applySameLessonSeek(target)
            return
        }
        leaveCurrentSlide(
            dest = PendingSlideLeave.Seek(target),
            toward = TutorialSlideNav.coachSceneAt(active.scenario, target.stepIndex),
        )
    }

    /**
     * Drop the live tutorial runner when free-play setup starts, and with it the
     * shell context it was hosted in. Keeps the mid-lesson save so Tutorial can
     * resume. Does not show the menu or change [SessionKind] — caller sets
     * FreePlay after this.
     */
    fun clearForFreePlayStart() {
        persistSession()
        dropLiveLesson()
    }

    /**
     * Tear down the hosted lesson in memory **and the context it lived in**. Does
     * not touch the `session.tutorial` key (caller [persistSession]s first when
     * leaving for free-play).
     *
     * The shell exists to host a lesson, so the runner leaving takes its route
     * down too; whatever the shell was raised over — the root menu — is revealed.
     */
    private fun dropLiveLesson() {
        runner = null
        currentLessonId = null
        forgetAllWallDeals()
        boardHandoffStepKey = null
        clearCoachMotion()
        closeQuiz()
        routes.hideLesson()
        routes.hideShell()
        board.gate = ChoiceGate.Identity
        tableBoardHighlight = BoardHighlight.None
        unlockTableInteract()
        nonHumanStepper.picker = freePlayPicker()
    }

    /**
     * TOC lesson pick: start another unit, or confirm restart when the same
     * lesson is past its first step.
     * @return true if the caller should open a restart-confirm dialog
     */
    fun onNavSelect(lessonId: String): Boolean {
        when {
            lessonId != currentLessonId -> {
                startLesson(lessonId)
                return false
            }
            else -> {
                // Same lesson: restart only if past the first step (confirm first).
                val active = runner
                val pastFirst =
                    active != null &&
                        (active.director.stepIndex > 0 ||
                            active.status != TutorialDirector.Status.Running)
                return pastFirst
            }
        }
    }

    /**
     * If the director just hit a [TutorialStep.LinkRef], open the Reference
     * browser on that page (Phase 4 deep-link).
     */
    private fun maybeOpenReference(active: TutorialRunner) {
        val pageId = active.director.consumePendingLinkRef() ?: return
        openReference(pageId)
        logLifecycle("tutorial linkRef page=$pageId")
    }

    /** After a successful tutorial human submit, coach Next, or quiz: refresh UI + log. */
    fun onDirectorAdvanced(active: TutorialRunner) {
        maybeOpenReference(active)
        when (active.status) {
            TutorialDirector.Status.Completed -> {
                handleUnitCompleted(active)
                return
            }
            TutorialDirector.Status.Failed -> {
                logLifecycle(
                    "tutorial failed id=${active.scenario.id} msg=${active.director.failureMessage}",
                )
            }
            TutorialDirector.Status.Running -> Unit
        }
        syncLessonUi()
        persistSession()
    }

    /**
     * Mark progress after [TutorialDirector.Status.Completed].
     *
     * Default: no "complete" slide — auto-advance to the next unit when present.
     * Last campaign unit: always show the finished panel (Back + Exit) so the
     * learner never lands on empty green felt. Mid-campaign opt-in summary only
     * when [ScenarioDef.completeSummaryKey] is set.
     */
    private fun handleUnitCompleted(active: TutorialRunner) {
        progress.markCompleted(active.scenario.id)
        val next = TutorialCatalog.nextAfter(active.scenario.id)
        val summaryKey = active.scenario.completeSummaryKey
        logLifecycle(
            "tutorial complete id=${active.scenario.id} next=${next?.id} " +
                "summary=${summaryKey != null}",
        )
        // Auto-advance mid-campaign when there is no opt-in summary.
        if (summaryKey == null && next != null) {
            startNextLesson()
            return
        }
        // Opt-in summary (with or without next), or campaign finished (no next).
        syncLessonUi()
        persistSession()
    }

    /**
     * Coach / quiz chrome from the director. The host derives the coach scene
     * into [presentedCoach] and the quiz scene into the frame snapshot's
     * `tutorialQuizContent`, announces the route, and performs the wall→table
     * handoff; it never writes the overlays. Campaign-finished always shows an end
     * panel (Back + Exit); mid-campaign success summary is opt-in
     * ([ScenarioDef.completeSummaryKey]). Fail uses a restart panel.
     */
    fun syncLessonUi() {
        val active = runner
        if (active == null || sessionKind() != SessionKind.Tutorial) {
            clearCoachMotion()
            closeQuiz()
            unlockTableInteract()
            return
        }
        when {
            active.awaitingQuiz -> {
                clearCoachMotion()
                unlockTableInteract()
                if (active.currentQuiz == null) {
                    closeQuiz()
                    return
                }
                showQuizRoute()
            }
            active.awaitingCoach -> {
                closeQuiz()
                syncBoardTileHighlight(active)
                syncTableInteract(active)
                val body = active.lastCoachKey
                if (body == null) {
                    clearCoachMotion()
                    return
                }
                presentCoach(active, body)
            }
            active.status == TutorialDirector.Status.Completed ||
                active.status == TutorialDirector.Status.Failed -> presentCompletePanel(active)
            else -> {
                // Play beat (WaitChoice, …): drop overlay chrome only. Keep
                // lastCoachBoardOverlay + boardHandoffStepKey so the next overlay
                // (forward discard or Back) does not felt-blink the table.
                clearCoachChrome()
                closeQuiz()
                unlockTableInteract()
                syncBoardTileHighlight(active)
            }
        }
    }

    /**
     * Derive the coach beat from the director, do the live-table handoff, and
     * publish it. The overlay applies the snapshot itself; the host only announces
     * the route and owns the wall-deal park.
     */
    private fun presentCoach(active: TutorialRunner, body: String) {
        val content = tutorialCoachContent(active)
        if (!content.visible) {
            clearCoachMotion()
            return
        }
        if (presentedCoachKey == content.key && presentedCoach.visible) return
        presentedCoachKey = content.key

        // Capture before maybeHandoff parks the streak key.
        val boardOverlay = content.boardOverlay
        if (boardOverlay) {
            maybeHandoffWallBuildToTable(active)
            tableRevealedSeats = active.revealedSeats
        } else {
            boardHandoffStepKey = null
            tableHighlightLiveWall = false
            tableRevealedSeats = emptySet()
        }
        lastCoachBoardOverlay = boardOverlay

        presentedCoach = content.copy(
            wallDeal = parkedWallDeal(active, body),
        )
        routes.showCoach()
    }

    /**
     * Parked deal to apply before this wall-build beat presents. Null on pre-roll
     * beats ([WallBuildBeat.DicePolicy.ClearRoll]) so the dice tumble fresh.
     */
    private fun parkedWallDeal(active: TutorialRunner, body: String): WallBuildDeal? {
        if (active.lastDiagram != TutorialDiagram.WallBuild) return null
        if (wallDicePolicy(body) == WallBuildBeat.DicePolicy.ClearRoll) return null
        return wallDeals.get(active.scenario.id)
    }

    /** End-panel scene from the director; absent for a mid-campaign auto-advance. */
    private fun presentCompletePanel(active: TutorialRunner) {
        val content = tutorialCoachContent(active)
        if (!content.visible) {
            clearCoachMotion()
            return
        }
        if (presentedCoachKey == content.key && presentedCoach.visible) return
        presentedCoachKey = content.key
        closeQuiz()
        unlockTableInteract()
        tableBoardHighlight = BoardHighlight.None
        tableRevealedSeats = emptySet()
        lastCoachBoardOverlay = false
        presentedCoach = content
        routes.showCoach()
    }

    /** Overlay chrome off; live table streak stays. */
    private fun clearCoachChrome() {
        pendingSlideLeave = null
        leaveTowardScene = null
        presentedCoach = TutorialCoachSnapshot.Absent
        presentedCoachKey = null
        lastCoachBoardOverlay = false
        routes.hideCoach()
    }

    /** Full overlay teardown (leave lesson, diagram, end panel). */
    private fun clearCoachMotion() {
        clearCoachChrome()
        tableRevealedSeats = emptySet()
    }

    /**
     * Announce the orientation-quiz route. The component applies the frame's
     * [inego.zungjunggame.features.tutorial.TutorialQuizSnapshot]; the host owns
     * the runner and the held attempt, not the widget.
     */
    private fun showQuizRoute() {
        routes.showQuiz()
    }

    /** Take the orientation-quiz route down. */
    private fun closeQuiz() {
        routes.hideQuiz()
    }

    /**
     * True while this lesson's live table is already on screen. Felt-reveal
     * and snap-from-empty must not run again (Next, click-through, or Back).
     */
    private fun tableOverlayLive(): Boolean =
        lastCoachBoardOverlay || boardHandoffStepKey != null

    /**
     * Board-overlay coach teaching an upcoming discard: ack the coach so the
     * click can apply as a regular discard. Keep overlay chrome up so the next
     * board-overlay beat presents without a felt blink (same as Back).
     */
    fun tryDiscardThroughCoach(tileType: Int): Boolean {
        val active = runner ?: return false
        if (!active.isPreambleToHumanDiscard()) return false
        val coachStep = active.director.currentStep as? TutorialStep.Coach ?: return false
        if (!coachStep.showsBoard) return false
        val wait = active.peekNextWaitChoice() ?: return false
        return wait.match.payload == tileType && active.acknowledgeCoach()
    }

    /**
     * Board-overlay coach teaching an upcoming claim / special action: ack so
     * the click can apply as a regular choice. Chrome stays up (same as
     * [tryDiscardThroughCoach]).
     */
    fun tryChoiceThroughCoach(choice: Choice): Boolean {
        val active = runner ?: return false
        if (!active.isPreambleToHumanWait()) return false
        val coachStep = active.director.currentStep as? TutorialStep.Coach ?: return false
        if (!coachStep.showsBoard) return false
        val wait = active.peekNextWaitChoice() ?: return false
        return wait.match.matches(choice) && active.acknowledgeCoach()
    }

    fun tryHumanDiscard(tileType: Int): Boolean {
        val active = runner ?: return false
        if (!active.showsBoard) return false
        if (active.status != TutorialDirector.Status.Running) return false
        if (active.director.blocksBoardInput) {
            if (!tryDiscardThroughCoach(tileType)) return false
        }
        if (!active.submitDiscard(tileType)) return false
        onDirectorAdvanced(active)
        return true
    }

    fun tryHumanChoice(choice: Choice): Boolean {
        val active = runner ?: return false
        if (!active.showsBoard) return false
        if (active.status != TutorialDirector.Status.Running) return false
        if (active.director.blocksBoardInput) {
            if (!tryChoiceThroughCoach(choice)) return false
        }
        if (!active.submitChoice(choice)) return false
        onDirectorAdvanced(active)
        return true
    }

    /**
     * Lecture overlays are [TableInteract.ReadOnly] snapshots (engine chrome
     * drawn, frozen). Action-preamble overlays stay [TableInteract.Play].
     * Discard preambles swap the prompt outline for a leader; claim preambles keep
     * the live Pung / Pass panel.
     */
    private fun syncTableInteract(active: TutorialRunner) {
        val boardOverlay =
            (active.director.currentStep as? TutorialStep.Coach)?.showsBoard == true
        val preamble = active.isPreambleToHumanWait()
        tableInteract = TableInteract.forOverlay(boardOverlay, preamble)
        tableOverlayLeader = boardOverlay && active.isPreambleToHumanDiscard()
    }

    private fun unlockTableInteract() {
        tableInteract = TableInteract.Play
        tableOverlayLeader = false
    }

    /** Same post-apply pause as a clicked discard so the tile can fly. */
    private fun markHumanBoardApplied() {
        stopMcHintSearch()
        nonHumanStepper.pauseUntilNextIdle = true
    }

    /**
     * Push [TutorialFocus.resolvedHighlight] (or a discard-wait payload) onto
     * the table. `"wall.live"` still lights the center marker separately.
     */
    private fun syncBoardTileHighlight(active: TutorialRunner) {
        tableRevealedSeats = active.revealedSeats
        val focus = if (active.awaitingCoach) active.lastFocus else null
        tableHighlightLiveWall = focus?.targetId == "wall.live"
        if (focus != null) {
            tableBoardHighlight = focus.resolvedHighlight(active.state)
            return
        }
        val wait = active.director.currentStep as? TutorialStep.WaitChoice
        val payload = wait?.match?.payload
        if (wait != null &&
            wait.match.kind == ChoiceKind.Discard &&
            payload != null
        ) {
            tableBoardHighlight = BoardHighlight.prompt(payload)
            return
        }
        tableBoardHighlight = BoardHighlight.None
    }

    /**
     * Once per contiguous board-overlay stay: bind the parked wall deal
     * (invent if this lesson uses one and none exists) and publish a
     * restored / wall-handoff apply. Consecutive overlay coaches keep the
     * table; leaving overlay clears [boardHandoffStepKey].
     */
    private fun maybeHandoffWallBuildToTable(active: TutorialRunner) {
        val layoutIdx = TutorialSlideNav.activeLayoutIndex(active.scenario, active.director.stepIndex)
        val streakKey = "${active.scenario.id}:$layoutIdx"
        val usesDeal = TutorialSlideNav.usesWallDeal(active.scenario)
        if (!usesDeal) {
            if (boardHandoffStepKey == streakKey) return
            boardHandoffStepKey = streakKey
            // Blessing of Heaven etc.: keep the scenario layout.
            publishTableApply(TableApplyKind.Restored)
            logLifecycle(
                "tutorial wall→table keep-state id=${active.scenario.id} " +
                    "layoutIdx=$layoutIdx liveWall=${active.state?.liveWallSize} (no wall deal)",
            )
            return
        }
        val deal = ensureParkedDeal(active.scenario.id)
        val alreadySame = boardMatchesDeal(active, deal)
        val alreadyHandedOff = boardHandoffStepKey == streakKey
        if (alreadyHandedOff && alreadySame) {
            tableHighlightLiveWall = true
            return
        }
        bindWallDealToBoard(active, invent = false)
        if (!alreadyHandedOff) {
            boardHandoffStepKey = streakKey
            val handoff = wallHandoff ?: WallBuildHandoffSnapshot.EMPTY
            if (!handoff.isEmpty) {
                publishTableApply(
                    TableApplyKind.WallHandoff,
                    handoff = handoff,
                    humanSeat = active.humanSeat,
                )
            } else if (!alreadySame) {
                publishTableApply(TableApplyKind.Restored)
            }
            tableHighlightLiveWall = true
            logLifecycle(
                "tutorial wall→table handoff id=${active.scenario.id} " +
                    "hands=${handoff.hands.size} " +
                    "liveFx=${handoff.residualLive.size} deadFx=${handoff.residualDead.size} " +
                    "liveWall=${active.state?.liveWallSize} deal=1",
            )
        } else {
            publishTableApply(TableApplyKind.Restored)
            tableHighlightLiveWall = true
        }
    }

    private fun wallDicePolicy(bodyKey: String?): WallBuildBeat.DicePolicy? =
        bodyKey?.let { WallBuildBeats.of(it)?.dice }

    private fun forgetAllWallDeals() {
        wallDeals.forgetAll()
        wallHandoff = null
    }

    /**
     * Parked deal for [lessonId]; invents and parks one when no committed deal
     * exists yet (cold overlay land). The overlay is handed the park through the
     * frame snapshot, so this touches only host state.
     */
    private fun ensureParkedDeal(lessonId: String): WallBuildDeal {
        wallDeals.get(lessonId)?.let { return it }
        val invented = WallBuildDeal.invent()
        wallDeals.put(lessonId, invented)
        return invented
    }

    private fun boardMatchesDeal(active: TutorialRunner, deal: WallBuildDeal): Boolean {
        val state = active.state ?: return false
        val opening = WallBuildToGameState.openingState(deal)
        for (seat in 0 until NUM_PLAYERS) {
            if (!state.players[seat].concealed.contentEquals(opening.players[seat].concealed)) {
                return false
            }
        }
        return true
    }

    /** Rematerialize [active] from the parked wall deal when the board differs. */
    private fun bindWallDealToBoard(active: TutorialRunner, invent: Boolean): Boolean {
        val deal = if (invent) {
            ensureParkedDeal(active.scenario.id)
        } else {
            wallDeals.get(active.scenario.id) ?: return false
        }
        if (!boardMatchesDeal(active, deal)) {
            active.rematerializeState(WallBuildToGameState.openingState(deal))
        }
        return true
    }

    /** After complete/fail primary exit: next lesson, exit to menu, or restart. */
    private fun onCompleteSlideExitComplete() {
        val active = runner ?: return
        val next = TutorialCatalog.nextAfter(active.scenario.id)
        when {
            active.status == TutorialDirector.Status.Failed -> restart()
            next == null -> exit()
            active.scenario.completeSummaryKey != null -> startNextLesson()
            else -> restart()
        }
    }

    /** Coach Next, or Exit / Restart / Next from a complete or fail panel. */
    fun applyCoachPrimary() {
        val active = runner ?: return
        if (active.awaitingCoach) {
            val steps = active.scenario.steps
            val from = active.director.stepIndex
            val nextIndex = if (active.isPreambleToHumanWait()) {
                TutorialSlideNav.nextCoachAfterPreambleWait(steps, from)
            } else {
                TutorialSlideNav.nextContinuousCoachIndex(steps, from)
            }
            leaveCurrentSlide(
                dest = PendingSlideLeave.Forward,
                toward = nextIndex?.let {
                    TutorialSlideNav.coachSceneAt(active.scenario, it)
                },
            )
        } else if (active.status == TutorialDirector.Status.Completed ||
            active.status == TutorialDirector.Status.Failed
        ) {
            leaveCurrentSlide(dest = PendingSlideLeave.CompletePrimary, toward = null)
        }
    }

    /**
     * Record the leave Next / Back asked for. The overlay runs the exit from the
     * snapshot and reports completion via `TutorialCoachActions.LEAVE_COMPLETE`,
     * which lands here in [applySlideLeaveComplete].
     */
    private fun leaveCurrentSlide(
        dest: PendingSlideLeave,
        toward: Pair<SlideScene, Boolean>?,
    ) {
        pendingSlideLeave = dest
        leaveTowardScene = toward?.first
        leavePreserveContinuity = toward?.second ?: true
    }

    /** The coach slide's exit finished: land the destination recorded by [leaveCurrentSlide]. */
    fun applySlideLeaveComplete() {
        val dest = pendingSlideLeave ?: return
        pendingSlideLeave = null
        leaveTowardScene = null
        when (dest) {
            is PendingSlideLeave.Seek -> applySameLessonSeek(dest.target)
            PendingSlideLeave.CompletePrimary -> onCompleteSlideExitComplete()
            PendingSlideLeave.Forward -> {
                val active = runner ?: return
                if (active.isPreambleToHumanWait()) {
                    if (!active.completePreambleWait()) return
                    markHumanBoardApplied()
                } else if (!active.acknowledgeCoach()) {
                    return
                }
                onDirectorAdvanced(active)
            }
        }
    }

    /**
     * Land on a user slide in the current lesson without tearing down overlay
     * or the live table. Re-binds the parked wall deal when the engine was
     * rebuilt from the dummy layout.
     */
    private fun applySameLessonSeek(target: TutorialSlideNav.SlideTarget) {
        val active = runner ?: return
        if (target.lessonId != active.scenario.id) {
            clearCoachMotion()
            closeQuiz()
            startLesson(target.lessonId, atStepIndex = target.stepIndex)
            return
        }
        val fromIndex = active.director.stepIndex
        val result = active.seekToUserSlide(target.stepIndex)
        if (result == TutorialRunner.SeekResult.Failed) {
            clearCoachMotion()
            closeQuiz()
            startLesson(target.lessonId, atStepIndex = target.stepIndex)
            return
        }
        if (result == TutorialRunner.SeekResult.Rebuilt &&
            TutorialSlideNav.usesWallDeal(active.scenario) &&
            active.showsBoard
        ) {
            bindWallDealToBoard(active, invent = true)
        } else if (result == TutorialRunner.SeekResult.Rebuilt &&
            active.showsBoard &&
            !tableOverlayLive()
        ) {
            // Table not on screen yet: snap into the rematerialized layout.
            // If the overlay streak is live, leave tiles to reconcile (Back
            // flies the discard home) — clearing the board blinks the table.
            publishTableApply(TableApplyKind.Restored)
        }
        closeQuiz()
        syncLessonUi()
        persistSession()
        logLifecycle(
            "tutorial seek ${result.name.lowercase()} " +
                "id=${active.scenario.id} step=$fromIndex→${target.stepIndex} " +
                "wallDeal=${wallDeals.get(active.scenario.id) != null}",
        )
    }

    private sealed class PendingSlideLeave {
        data object Forward : PendingSlideLeave()
        data object CompletePrimary : PendingSlideLeave()
        data class Seek(val target: TutorialSlideNav.SlideTarget) : PendingSlideLeave()
    }

    /**
     * Commit the orientation quiz answer the widget emitted. The runner records
     * the confirmation / fail flags and held attempt; the frame snapshot publishes
     * them to the widget.
     */
    fun applyQuizSubmit(answer: QuizAnswer) {
        val active = runner ?: return
        if (!active.awaitingQuiz || active.quizAwaitingAdvance) return
        if (!active.submitQuiz(answer)) {
            // Non-retryable wrong answer: stay on quiz with hit / miss / FP marks.
            // The runner does not record the fail attempt itself, so hold it.
            active.holdQuizInteraction(
                QuizInteractionState(answer = answer, failVisible = true),
            )
            persistSession()
            logLifecycle("tutorial quiz wrong id=${active.scenario.id}")
            return
        }
        if (active.quizAwaitingAdvance) {
            // Retryable: Confirm → review marks + Try again / Next (any answer).
            persistSession()
            logLifecycle(
                "tutorial quiz confirmed id=${active.scenario.id} " +
                    "fail=${active.quizFeedbackFail}",
            )
            return
        }
        onDirectorAdvanced(active)
    }

    /** Selection changed — hold the emitted attempt and persist from runner state. */
    fun applyQuizStateChanged(attempt: QuizInteractionState) {
        val active = runner ?: return
        if (!active.awaitingQuiz) return
        active.holdQuizInteraction(attempt)
        persistSession()
    }

    /** Next after a correct retryable quiz. */
    fun applyQuizNext() {
        val active = runner ?: return
        if (!active.advanceQuiz()) return
        onDirectorAdvanced(active)
    }

    /** New random hand after a correct retryable quiz. */
    fun applyQuizTryAgain() {
        val active = runner ?: return
        if (!active.retryQuiz()) return
        showQuizRoute()
        persistSession()
        logLifecycle("tutorial quiz retry id=${active.scenario.id}")
    }

    /**
     * Open an inline tutorial href: a catalog page (via [openReference]
     * so × returns to this beat) or an in-app action such as Settings.
     */
    fun openCatalogHref(href: String) {
        if (CatalogLinkPolicy.isAppHref(href)) {
            openSettings()
            logLifecycle("tutorial href settings")
            return
        }
        when (val target = ReferenceCatalog.resolveHref(href)) {
            is ReferenceCatalog.LinkTarget.Page -> {
                openReference(target.pageId)
                logLifecycle("tutorial href page=${target.pageId} section=${target.section.id}")
            }
            is ReferenceCatalog.LinkTarget.SectionTarget -> {
                openReference(target.section.id)
                logLifecycle("tutorial href section=${target.section.id}")
            }
            is ReferenceCatalog.LinkTarget.External -> {
                logLifecycle("tutorial href rejected external=$href")
            }
            null -> {
                if (href.isBlank()) {
                    logLifecycle("tutorial href rejected empty=$href")
                } else {
                    logLifecycle("tutorial href rejected unresolved=$href")
                }
            }
        }
    }

    /**
     * Enter a lesson from another context: drop the chrome a lesson never shows.
     *
     * The score table, breakdown, setup card and language picker are stack-owned —
     * the transitions remove their routes — so what is left here is the game-menu
     * card and a confirm the player had raised. Those belong to the context the
     * lesson replaces: the shell is inserted *below* whatever is already up.
     *
     * **A restore must not call this.** The stack came back verbatim and is the
     * authority for what is up, so a card the player left open is the player's own —
     * [tryRestore] clears the free-play residue only.
     */
    private fun clearEntryChrome() {
        gameMenu.hide()
        clearFreePlayResidue()
        dismissConfirm()
    }

    /** Revealed cells from a free-play hand: no lesson shows them, a restored one included. */
    private fun clearFreePlayResidue() {
        tableRevealedSeats = emptySet()
    }
}
