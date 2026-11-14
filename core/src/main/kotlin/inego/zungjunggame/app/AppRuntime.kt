package inego.zungjunggame.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.TimeUtils
import com.badlogic.gdx.utils.viewport.ScreenViewport
import inego.zungjunggame.input.FrameModel
import inego.zungjunggame.input.GestureArbiter
import inego.zungjunggame.input.HitResolver
import inego.zungjunggame.input.InputEvent
import inego.zungjunggame.input.InputNormalizer
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.input.Intent
import inego.zungjunggame.input.KeyboardArbiter
import inego.zungjunggame.input.KeyboardContext
import inego.zungjunggame.input.ResolvedHit
import inego.zungjunggame.input.ScrollRouter
import inego.zungjunggame.input.TouchDiagnostics
import inego.zungjunggame.online.AccountController
import inego.zungjunggame.online.OnlineCoordinator
import inego.zungjunggame.platform.PresenceScheduler
import inego.zungjunggame.platform.UiCursor
import inego.zungjunggame.features.board.FreePlayPresenter
import inego.zungjunggame.features.online.OnlineSettlementPresenter
import inego.zungjunggame.render.RulesCreditLabel
import inego.zungjunggame.render.TableFelt
import inego.zungjunggame.render.seats.PlayerLabels
import inego.zungjunggame.render.table.PlayTableView
import inego.zungjunggame.render.VersionLabel
import inego.zungjunggame.render.assets.ManagedGpuResources
import inego.zungjunggame.render.assets.TextureRecovery
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.runtime.ComponentHost
import inego.zungjunggame.runtime.RouteIds
import inego.zungjunggame.session.hosts.FreePlayHost
import inego.zungjunggame.session.hosts.PlayHost
import inego.zungjunggame.session.online.OnlinePlayHost
import inego.zungjunggame.ui.stack.SessionKind
import inego.zungjunggame.session.SessionRegistry
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.state.StateStore
import inego.zungjunggame.tutorial.TutorialHost
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.RouteUiState
import inego.zungjunggame.ui.Scope
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.Router
import inego.zungjunggame.ui.stack.Stack
import inego.zungjunggame.ui.stack.StackState

/**
 * App entry point: drives input through the input pipeline
 * (normalize → layout → hit-resolve → dispatch).
 *
 * [renderFrame] is the paint authority: it draws the stack bottom→top through
 * [ComponentHost.draw] with a [Canvas] over the shared [SpriteBatch]. The
 * tutorial shell keeps a dedicated composition branch (its content layers paint
 * inside the shell content transform); the stack alone owns z-order otherwise.
 *
 * - **Effects** are applied by [AppDispatcher] through the per-domain effect
 *   classes in `app/`.
 * - **Session/automation housekeeping** (host ticks, settlement, AI, auto-play)
 *   is owned here.
 */
class AppRuntime internal constructor(
    private val scope: Scope,
    private val camera: OrthographicCamera,
    private val viewport: ScreenViewport,
    private val input: PlatformInput,
    private val table: PlayTableView,
    private val freePlayHost: FreePlayHost,
    private val freePlayPresenter: FreePlayPresenter,
    private val tutorialHost: TutorialHost,
    private val onlineCoordinator: OnlineCoordinator,
    private val onlineSettlementPresenter: OnlineSettlementPresenter,
    private val accountController: AccountController,
    private val batch: SpriteBatch,
    private val onExit: () -> Unit,
    private val router: Router,
    private val componentHost: ComponentHost,
    private val dispatcher: AppDispatcher,
    private val rawInput: GdxRawInput,
    private val normalizer: InputNormalizer,
    private val inputProfile: InputProfile,
    private val sessionRegistry: SessionRegistry,
    private val stateStore: StateStore,
    private val stack: StateSlot<StackState>,
    private val routeUiState: RouteUiState,
    private val soundPlayer: SoundPlayer,
    /** The app's platform timer; one-shot wakes and periodic presence ticks share it. */
    private val presenceScheduler: PresenceScheduler,
    /** Refresh the notice inbox and present a fresh notice. */
    private val pollNotices: () -> Unit = {},
    /** The one intent a web launch deep link asked for; the runtime fires it once. */
    startupIntent: Intent? = null,
) : Disposable {
    private var disposed = false

    /** Paint executor over the legacy batch; the stack draw authority. */
    private val canvas = scope.own("canvas") { Canvas(batch) { WhitePixel.get() } }

    /** Pointer lifecycle (slop, touch-cancel, park/scroll ownership). */
    private val arbiter = GestureArbiter()

    /** Global accelerators and top-down per-overlay key bindings. */
    private val keyboard = KeyboardArbiter()

    /**
     * The platform's declared answer to "did the GL context survive?", plus the
     * one coalescing entry point every rebuild is requested through.
     *
     * [gpuRecovery] is what collapses the two mechanisms that used to both fire
     * for one backgrounding (a long frame gap, and `resume()`) into a single
     * rebuild, and it is also where a real context recreation from
     * [gpuContext.onContextRecreated] is turned into a request.
     */
    private val gpuContext = inego.zungjunggame.platform.GpuContextPolicy.create()
    private val gpuRecovery = GpuRecoveryCoordinator(FrameStallRecovery(BACKGROUND_GAP_MS))

    /** Holds [startupIntent] until the online session can honor it. */
    private val pendingStartupIntent = PendingStartupIntent(startupIntent)

    /**
     * The stack instance the last durable write captured. Identity is the dirty
     * check: [Router] rebuilds the stack on every mutation and leaves it alone
     * between them, so this costs one comparison per frame.
     */
    private var flushedStack: Stack? = null

    /**
     * The route UI store revision the last durable write captured. The store
     * mutates without replacing the stack, so its revision is the second dirty
     * source the safety net watches.
     */
    private var flushedRouteUiRevision = -1L
    private var lastStackFlushMs = 0L

    private companion object {
        const val BACKGROUND_GAP_MS = 1500L

        /**
         * How often the notice inbox is polled while the app runs. A later-play
         * result shows up within this window; the hub entry refreshes it
         * immediately.
         */
        const val NOTICES_POLL_MS = 30_000L

        /**
         * The rebuild reason for a freshly-constructed runtime.
         *
         * Named, and compared by name in [rebuildFaces], because it is the one
         * reason that must **not** run GL-object recovery: a cold start inherits a
         * live context, so there is nothing dead to repair and invalidating working
         * objects only breaks the first frame.
         */
        const val COLD_START_REASON = "new runtime"

        /**
         * How often a *changed* stack may be written between lifecycle edges.
         *
         * Durable writes are required at [pause]/[exitApp]/[dispose]; this timer is
         * the safety net for the edges that never arrive — a killed run, a crash, a
         * browser tab closed without unload — so the worst case is losing the last
         * minute of navigation instead of all of it.
         */
        const val STACK_FLUSH_INTERVAL_MS = 60_000L
    }

    init {
        scope.own("presencePeriodic") {
            presenceScheduler.schedulePeriodic(1000L) {
                val now = TimeUtils.millis()
                onlineCoordinator.tick(now)
                // Token refresh used to run only from the present loop. An idle
                // picture no longer presents, so the same one-second clock owns it.
                accountController.tick(now)
            }
        }
        scope.own("noticesPeriodic") {
            presenceScheduler.schedulePeriodic(NOTICES_POLL_MS) { pollNotices() }
        }
        scope.own("presenceVisibility") {
            presenceScheduler.hookVisibility(
                onVisible = {
                    onlineCoordinator.onAppResumed()
                    onlineCoordinator.reconcilePresence()
                },
                onHidden = {
                    onlineCoordinator.onAppPaused()
                },
            )
        }
        scope.own("gpuContextRecreated") {
            gpuContext.onContextRecreated {
                // Live on the web since Stage W (measured durable)
                // and on Android since Stage C (a second `onSurfaceCreated` on the
                // same graphics object); still inert on desktop, whose windowed
                // context cannot be lost.
                gpuRecovery.request("contextRecreated")
                // **The request is spent by the next frame, so ask for one.**
                //
                // This is not a nicety: with `setContinuousRendering(false)` a settled
                // app schedules *no* frames, so on a durable platform — where the gap
                // heuristic is off — a pending rebuild sits there indefinitely. W4
                // measured exactly that on the web: a forced `loseContext`/`restoreContext`
                // left the rebuild unserved for **40 s**, until a synthetic mouse move
                // happened to schedule a frame and the rebuild ran immediately
                // (`fontRebuild:start requested contextRecreated totalMs=1548`).
                //
                // The same is true on Android, and there it is worse: a context
                // re-entry happens on the GL thread with the app already foreground,
                // and if nothing requests a frame the app is a live process drawing
                // nothing. `GLSurfaceView` keeps rendering while it has a request
                // outstanding, so this call is what turns the restore into a frame.
                //
                // A real driver reset is not a moment the user will touch the screen.
                // The restored context is unusable until something rebuilds into it,
                // so the only safe reading is "the next frame must exist, and be now".
                //
                // One frame, not a loop: the rebuild happens inside it, and nothing
                // here re-arms, so the cost is a single present.
                Gdx.graphics.requestRendering()
            }
        }
    }

    private val session get() = freePlayHost.session
    private val kind get() = sessionRegistry.kind

    /**
     * Requests a glyph-atlas rebuild on the next frame.
     *
     * Called by a freshly-created runtime. On a cold process start there is no
     * previous context to have inherited faces from, so this call was measured
     * redundant on desktop (218 ms of a 1026 ms boot, an identical first
     * frame without it). It is kept deliberately, not by oversight:
     *
     *  - the Android activity-recreation path *does* build a second runtime inside
     *    one process and genuinely inherits faces across a context change, and that
     *    is the case the call was written for;
     *  - the desktop A/B cannot cover it (one platform, one process).
     *
     * **Stage C did not remove this, and the reason is worth keeping distinct
     * from the one above.** A second runtime is a *new* `AndroidGraphics`, so the
     * re-entry signal a loss produces — a second `onSurfaceCreated` on the
     * *same* graphics object — starts from a cleared flag and does not fire. The
     * event and this call therefore cover different cases and both stay.
     * (A rotation is not that path: the manifest lists `orientation|screenSize`
     * on `configChanges`, so it arrives as `onSurfaceChanged`.)
     *
     * What Stage C did change is what this call may throw away: see
     * [rebuildFaces]'s `new runtime` branch, which now also drops the shared
     * [WhitePixel] — a process-wide name, and the one object here that can
     * outlive the context that made it.
     */
    fun requestFontRebuild() {
        gpuRecovery.request(COLD_START_REASON)
    }

    private fun rebuildFaces(reason: String) {
        // Order matters, and the reason is not obvious from the names.
        //
        // The GL-object recovery must come **first**. It is what makes a real
        // context loss survivable at all: without it the batch's mesh and shader
        // still hold names from the dead context, every draw call fails with
        // "object does not belong to this context", and the frame paints nothing —
        // the felt-only screen Stage G exists to remove. Fonts and textures would
        // be rebuilt perfectly into a context that cannot draw them.
        //
        // *Which* rebuilds get it is [shouldRecoverManagedGpuResources]'s whole
        // subject, and the reasoning is not obvious from the names — read it
        // there rather than here. The font/texture rebuild below it, by contrast,
        // is unconditional: on a cold start that is what compiles the atlases once.
        if (shouldRecoverManagedGpuResources(reason)) {
            ManagedGpuResources.recoverAll(reason)
        } else {
            // The shared pixel is the one thing a cold start must still drop, and
            // it is here because it is a *process-wide* name rather than a
            // runtime-owned one. `recoverAll` would also drop the
            // batch shader and invalidate every mesh, which a live context must
            // not be asked to survive; the pixel can be dropped on its own
            // because nothing caches it anywhere except [WhitePixel] itself —
            // every holder is a getter, so the next draw builds a fresh one.
            //
            // Safe on a genuine cold process start, where the cache is empty
            // anyway, and required on a second runtime in one process (the
            // Android activity-recreation path), where the pixel is the one
            // object here that outlived the context that made it.
            WhitePixel.invalidate()
        }
        Fonts.recoverAfterBackgrounding(reason)
        TextureRecovery.recoverAll(reason)
    }

    fun renderFrame() {
        val nowNs = System.nanoTime()
        // One decision per frame from one place. A durable platform passes
        // `gapTriggers = false`: its GL objects survive backgrounding, so a hole
        // in the frame clock says nothing about the context — and on-demand
        // rendering makes an untouched idle menu produce 1.7–5.9 s gaps *by
        // design*, which a threshold cannot tell apart from a real absence.
        // A volatile platform keeps the gap as its only signal.
        val outcome = gpuRecovery.consume(nowNs, gapTriggers = !gpuContext.contextsAreDurable)
        if (outcome.rebuild) {
            rebuildFaces(outcome.reason)
            gpuRecovery.chargedRebuild(System.nanoTime())
        }
        if (outcome.capTripped) {
            Gdx.app?.error(
                "AppRuntime",
                "glyph recovery suppressed after ${outcome.consecutive} " +
                    "consecutive rebuilds (latest gap ${outcome.gapMs}ms)",
            )
        }
        if (Fonts.consumeForceRebuildMarker()) {
            rebuildFaces("forced marker")
            gpuRecovery.chargedRebuild(System.nanoTime())
        }

        val screenW = viewport.worldWidth
        val screenH = viewport.worldHeight
        camera.update()
        rawInput.snapshot()

        // A touch screen has no pointer between gestures, but the backend keeps
        // the last sample after a finger-up; reading it back would leave a hover
        // (and the magnifier) stuck on whatever tile slid under it. The desktop
        // cursor, by contrast, is present without a press. Absent reads as NaN -
        // the pipeline's one "no pointer" convention.
        val pointerPresent = inputProfile.usesHover || rawInput.isTouched
        val pointerX = if (pointerPresent) rawInput.touchX() else Float.NaN
        val pointerY = if (pointerPresent) rawInput.touchY() else Float.NaN
        val dt = FrameDelta.clamp(Gdx.graphics.deltaTime)
        // The frame's device samples travel with the frame: the single pointer as
        // before, and the whole down set for a component whose gesture needs more
        // than one finger. Both are the same snapshot later steps read.
        val constraints = Constraints(
            worldWidth = screenW,
            worldHeight = screenH,
            pointerX = pointerX,
            pointerY = pointerY,
            deltaSec = dt,
            pointers = rawInput.pointers,
        )
        val advanceFrame = sessionRegistry.frame()
        val advanceState = advanceFrame.liveHand.state
        if (advanceFrame.boardHosted) {
            advanceFrame.playHost.advance(dt)
            table.advance(dt)
            if (advanceFrame.kind == SessionKind.FreePlay && advanceState != null) {
                freePlayPresenter.tickMcHintUi(dt, advanceFrame.liveHand)
            }
        }
        // One frame's one-shot discard origin; the model assembler projects it into
        // the board view and paint hands it to the table.
        val boardOrigin = if (advanceFrame.boardHosted) {
            advanceFrame.onlineHost?.consumePendingDiscardOrigin()
        } else {
            null
        }
        // One model for motion and the first layout. A later stack change
        // reassembles: the intent that pushed also mutated hosts.
        val frameModel = componentHost.assembleModel(boardOrigin)
        // Inertia coasts through the store before the frame is laid out, so the
        // values layout and paint read are the owner's, never a widget's.
        componentHost.motion(constraints, frameModel)
        var frames = componentHost.beginFrame(constraints, frameModel)
        var resolver = HitResolver(frames)
        val sink = GestureArbiter.Sink { x, y, touchStartedOnTarget, suppressMenuOpen -> handleTap(x, y, resolver, touchStartedOnTarget, suppressMenuOpen) }
        for (event in normalizer.normalize(rawInput)) {
            val stackBeforeEvent = router.stack
            handleEvent(event, frames, sink)
            // Dispatching an event can push/pop/replace routes; relayout so the
            // remaining events hit-test the stack they are actually dispatched to.
            if (router.stack !== stackBeforeEvent) {
                frames = componentHost.beginFrame(constraints, componentHost.assembleModel(boardOrigin))
                resolver = HitResolver(frames)
            }
        }

        val nowMs = TimeUtils.millis()
        accountController.tick(nowMs)
        onlineCoordinator.tick(nowMs)
        onlineCoordinator.reconcilePresence()
        emitStartupIntent()

        val frame = sessionRegistry.frame()
        val snap = frame.playHost.snapshot
        PlayerLabels.setPlayerNames(
            snap.playerNames.takeIf { it.isNotEmpty() }?.toTypedArray(),
            snap.isBot.takeIf { it.isNotEmpty() }?.toBooleanArray(),
        )
        val localAutomationBlocked = router.stack.anyBlocksAutomation
        // Deliberately **not** [scheduleNextPresent]. A minimized window gets
        // no frames anyway, so asking for one only burns a present the
        // compositor will not show. Skipping it also leaves any pending paint
        // request unconsumed, which guarantees the first frame back repaints —
        // `Main.resume` asks for that frame independently, so the return edge
        // never depends on this path having run.
        when (frame.kind) {
            SessionKind.FreePlay if frame.liveHand.active -> {
                session.maybeRecordFinishedHand()
                freePlayPresenter.syncHandEndBreakdown(frame.liveHand)
            }

            SessionKind.Online if frame.onlineHost != null -> {
                frame.onlineHost.let { onlineSettlementPresenter.sync(it.snapshot) }
            }

            else -> {
                freePlayPresenter.syncHandEndBreakdown(frame.liveHand)
            }

            // A zero-sized world is a window the platform has taken away — minimized,
            // or an occluded 0x0 framebuffer. This frame cannot paint anything, and
            // **it must not clear either**.
            //
            // The guard used to sit *below* `ScreenUtils.clear`, so the order was
            // "clear to felt, then bail", and libGDX swaps the buffer when `render()`
            // returns whether or not anything was drawn. That presented a felt-only
            // frame: the return edge's whole cost was a rebuild, but the picture the
            // user actually saw on return was this clear, held in the swapchain by
            // the compositor while the window was away.
            //
            // Measured 2026-09-29: 28 s minimized produced exactly **two** frames,
            // both this path, and zero afterwards — the platform stops delivering
            // frames entirely, so these two were the only thing that ever touched
            // the swapchain. Leaving it intact is the whole fix; there is nothing to
            // re-render, because a settled app also has nothing new to draw.
        }

        // A zero-sized world is a window the platform has taken away — minimized,
        // or an occluded 0x0 framebuffer. This frame cannot paint anything, and
        // **it must not clear either**.
        //
        // The guard used to sit *below* `ScreenUtils.clear`, so the order was
        // "clear to felt, then bail", and libGDX swaps the buffer when `render()`
        // returns whether or not anything was drawn. That presented a felt-only
        // frame: the return edge's whole cost was a rebuild, but the picture the
        // user actually saw on return was this clear, held in the swapchain by
        // the compositor while the window was away.
        //
        // Measured 2026-09-29: 28 s minimized produced exactly **two** frames,
        // both this path, and zero afterwards — the platform stops delivering
        // frames entirely, so these two were the only thing that ever touched
        // the swapchain. Leaving it intact is the whole fix; there is nothing to
        // re-render, because a settled app also has nothing new to draw.
        if (shouldSkipPaint(screenW, screenH)) {
            // Deliberately **not** [scheduleNextPresent]. A minimized window gets
            // no frames anyway, so asking for one only burns a present the
            // compositor will not show. Skipping it also leaves any pending paint
            // request unconsumed, which guarantees the first frame back repaints —
            // `Main.resume` asks for that frame independently, so the return edge
            // never depends on this path having run.
            return
        }
        ScreenUtils.clear(TableFelt.R, TableFelt.G, TableFelt.B, TableFelt.A)

        syncOnlineConnect()
        // Paint owns a layout assembled after input and host ticks: a director
        // advance this frame is in this frame's snapshot, even when the stack
        // instance is unchanged. The input layout above stays the hit-test
        // authority for the events already dispatched.
        val paintFrames = componentHost.beginFrame(constraints, componentHost.assembleModel(boardOrigin))

        // The cursor is frame-scoped: each frame starts on the default and a
        // painted surface raises the hand for its own hover targets. A surface
        // that left the paint range cannot leave its cursor behind.
        UiCursor.reset()
        paint(paintFrames, screenW, screenH)

        // Both steppers run after motion has reconciled. A true result changed the
        // board this frame, and the flight that shows it cannot start until the
        // next present. [needsAiFrame] does not cover the apply that makes the
        // decision the human's: it goes false on that same frame, and with the
        // pointer idle nothing else asks, so the draw into the bottom seat waits
        // for the next stray wake and then jumps.
        val freePlayStepped = !localAutomationBlocked &&
            frame.kind == SessionKind.FreePlay &&
            frame.liveHand.active &&
            freePlayHost.tryStepAi(frame.liveHand)
        // A server batch is several snapshots. The idle gate holds the next one
        // until the step just shown has had a chance to move, and [tryPresent]
        // returns false for that hold while the queue is still owed a present.
        // Read [OnlinePlayHost.needsPresentFrame] after that call: it stays true
        // until the hold is spent and the batch has caught up.
        val onlineHost = frame.onlineHost?.takeIf { frame.kind == SessionKind.Online }
        // sync() already ran, and it only opens the score card once the sequencer
        // is caught up. tryPresent is what gets it there, after paint, and Hu has
        // cleared the decision clock — so this edge has to ask for the frame that
        // actually shows the card and a prompt latched in the same call.
        val caughtUpBefore = onlineHost?.sequencer?.isCaughtUp == true
        onlineHost?.tryPresent(!table.isAnimating())
        val justCaughtUp = onlineHost != null && !caughtUpBefore && onlineHost.sequencer.isCaughtUp
        val presentationPending = onlineHost?.needsPresentFrame() == true ||
            onlineHost?.needsClockFrame() == true ||
            justCaughtUp
        // A bot decision still owing a frame. Read after the step above so a step
        // taken this frame is not re-requested on its own account.
        val aiFramePending = !localAutomationBlocked &&
            frame.kind == SessionKind.FreePlay &&
            frame.liveHand.active &&
            freePlayHost.needsAiFrame(frame.liveHand)
        // Local applies are paced by the same sequencer online uses, and its queued
        // steps owe frames exactly as [OnlinePlayHost.needsPresentFrame] does: the
        // frame that spends the idle pause applies nothing, so the rest of the queue
        // waits unless pending work still names it. Ungated like the cue drain
        // below — a lesson drives the same board, so it paces the same way.
        freePlayHost.tryPresent(!table.isAnimating())
        val localPresentationPending = freePlayHost.needsPresentFrame()
        // The one place cues become sound: hosts only describe what happened, the
        // player owns the samples and the enabled/level gate.
        frame.onlineHost?.consumePendingSoundCues()?.forEach(soundPlayer::play)
        freePlayHost.consumePendingSoundCues().forEach(soundPlayer::play)
        // A cue may defer a layer (the stolen sting runs before the claim it
        // interrupts), and dispatch rides the frame loop — so the queue is drained
        // here, and `pendingWork` below keeps frames coming for it. Without that a
        // deferred layer would sit unsounded until some unrelated input woke a
        // frame, which on an idle table is never.
        soundPlayer.tick()

        flushStackIfDirty()
        scheduleNextPresent(
            frame.playHost,
            pendingWork = aiFramePending || freePlayStepped || presentationPending ||
                localPresentationPending || soundPlayer.hasScheduled(),
        )
    }

    /**
     * One present per display blank while something is still moving, and none
     * while the picture is settled. Input and posted work wake a frame on
     * their own; this schedules the next one a coasting animation still needs.
     *
     * **A pacer that owes the loop a frame belongs in [pendingWork].** The
     * non-human stepper skips one idle frame after each apply so the table can start
     * tile motion, and its think delay is wall-clock — so both the apply it defers
     * and the deadline it waits for need a frame that nothing else would ask for.
     * While the board was pinned open by an unrelated always-on clock, that frame
     * appeared by accident; without it a bot simply never moved until the player
     * touched something. Bounded: only while a bot decision is genuinely outstanding.
     *
     * The apply itself is also pending work, including the one that ends bot play.
     * It lands after motion, so [PlayTableView.needsFrames] is still false, and
     * [FreePlayHost.needsAiFrame] is false once the human owes the decision.
     * Pointer motion used to hide that hole by asking for presents on its own.
     *
     * Online batches hide the same hole one step later. The frame that spends the
     * idle pause applies nothing, so the rest of the queue waits unless
     * [pendingWork] still names it. Catching up is pending work too: the card and
     * the latched prompt are painted on the *next* frame, and a settlement has
     * no decision clock left to ask for it. A next-hand countdown or a reconnect
     * delay is the same kind of clock once the batch is quiet.
     */
    private fun scheduleNextPresent(host: PlayHost, pendingWork: Boolean) {
        val clocks = host.decisionTimeLeftMs != null ||
            host.shutdownRemainingMs != null ||
            table.needsFrames()
        val paint = componentHost.consumePaintRequest()
        if (clocks || paint || pendingWork) {
            Gdx.graphics?.requestRendering()
        }
    }

    /**
     * The single paint authority for the frame.
     *
     * The fundamental overlay — the felt, the rules credit and the version stamp in
     * its corner — goes down first, and is skipped only when a layer on the stack
     * supersedes it ([Stack.onFelt]): a board, a lesson, the browser or the rating
     * chart paints a surface of its own. Bottom→top the stack is then drawn through
     * [ComponentHost], which composes any [inego.zungjunggame.ui.SpaceProvider] with
     * the layers in its bands; the runtime knows only the paint range.
     */
    private fun paint(frames: FrameModel, screenW: Float, screenH: Float) {
        batch.projectionMatrix = camera.combined
        batch.begin()
        try {
            if (router.stack.onFelt) {
                RulesCreditLabel.render(batch, screenW, screenH)
                VersionLabel.render(batch, screenW, screenH)
            }
            componentHost.draw(canvas, frames)
        } finally {
            batch.end()
        }
    }

    /**
     * Reconciles the online connecting card route against the connection state.
     *
     * The card is a stack route, so it is pushed/popped here and its component
     * presents the overlay from the live connection state. A layer whose
     * `LayerSpec` suppresses the card occludes it rather than killing the route.
     */
    private fun syncOnlineConnect() {
        syncOnlineConnect(router, kind, onlineCoordinator.onlineHost)
    }

    /**
     * Fires the launch deep link's intent once, the first frame it can be honored.
     *
     * The Shadow Ranked tab needs a token, and it opens over the hub — so it waits
     * for a live session and never displaces a game the player reloaded into
     * ([SessionRegistry.sessionOwnsDisplay]).
     */
    private fun emitStartupIntent() {
        val intent = pendingStartupIntent.take(
            signedIn = accountController.token != null,
            sessionOwnsDisplay = sessionRegistry.sessionOwnsDisplay,
        ) ?: return
        input.logLifecycle("launch deep link: $intent")
        dispatcher.emit(intent)
    }

    private fun handleEvent(
        event: InputEvent,
        frames: FrameModel,
        sink: GestureArbiter.Sink,
    ) {
        when (event) {
            is InputEvent.PointerDown,
            is InputEvent.PointerMove,
            is InputEvent.PointerUp,
            is InputEvent.PointerCancel,
            -> {
                arbiter.handle(event, router.stack, frames, sink)
            }

            is InputEvent.Tap -> Unit

            is InputEvent.Scroll -> {
                // A wheel sample carries both axes and both travel together: a
                // chart pans on the horizontal delta, and a surface that reads
                // only the vertical one is reached through the one-axis form.
                //
                // The gate now admits a horizontal-only sample too (an X-only
                // trackpad swipe). That is safe because [GestureBridge] emits a
                // scroll action only when the surface's offset actually changed:
                // a vertical-only scroll list answers an X-only sample with its
                // unchanged offset, which writes nothing. Before this gate, an
                // X-only swipe reached no surface at all — so a chart could not
                // pan on it, and nothing else lost anything.
                if (event.amountY != 0f || event.amountX != 0f) {
                    ScrollRouter.route(
                        router.stack,
                        frames,
                        event.amountY,
                        event.x,
                        event.y,
                        event.amountX,
                    )
                }
            }

            is InputEvent.KeyDown -> {
                // A focused text field owns the raw key first: its Enter is the
                // form's submit, which is an action and so travels the owning
                // entry's frame rather than the global intent table.
                componentHost.textKeyDown(frames, event.keycode)
                for (intent in keyboard.intentsFor(
                    event.keycode,
                    event.ctrl,
                    event.shift,
                    router.stack,
                    keyboardContext(),
                )) {
                    dispatcher.emit(intent)
                }
            }

            InputEvent.SystemBack -> dispatcher.emit(Intent.Back)
        }
    }

    private fun handleTap(
        x: Float,
        y: Float,
        resolver: HitResolver,
        touchStartedOnTarget: Boolean,
        suppressMenuOpen: Boolean,
    ) {
        val hit = resolver.resolve(router.stack, x, y)
        // Gate diagnostics are touch-only; desktop logs nothing. Each branch logs
        // beside its own dispatch so the log and the decision cannot disagree.
        val touch = inputProfile.usesPressHover
        val layer = router.stack.top.route.key.value
        when {
            hit is ResolvedHit.On -> {
                if (touch) {
                    TouchDiagnostics.log(
                        "menu-skip release-on-target target=${TouchDiagnostics.describeHit(hit)} " +
                            TouchDiagnostics.tapPrefix(x, y, layer),
                    )
                }
                componentHost.dispatch(hit)
            }
            // The press missed every control inside a layer the player can back out
            // of, so it pressed that layer's chrome: its declared outside intent owns
            // the answer (a card dismisses, a confirm answers No). Without this the
            // resolved layer was discarded, so a modal with no control of its own —
            // Settings — could not be dismissed by the mouse at all.
            hit is ResolvedHit.Outside && hit.instance.spec.dismissOnBack &&
                !suppressMenuOpen && !touchStartedOnTarget -> {
                if (touch) {
                    TouchDiagnostics.log(
                        "menu-skip layer-outside layer=${hit.instance.route.key.value} " +
                            TouchDiagnostics.tapPrefix(x, y, layer),
                    )
                }
                componentHost.dispatch(hit)
            }
            // A press that began on a control is never the empty-felt menu.
            touchStartedOnTarget -> {
                if (touch) {
                    TouchDiagnostics.log(
                        "menu-skip started-on-target target=${TouchDiagnostics.describeHit(hit)} " +
                            TouchDiagnostics.tapPrefix(x, y, layer),
                    )
                }
            }
            // A swipe or a multi-finger gesture is not the empty-felt menu.
            suppressMenuOpen -> {
                if (touch) {
                    TouchDiagnostics.log(
                        "menu-skip not-a-tap target=${TouchDiagnostics.describeHit(hit)} " +
                            TouchDiagnostics.tapPrefix(x, y, layer),
                    )
                }
            }
            // The empty-felt tap opens the menu only on touch; desktop reaches it
            // through the board's top-right menu button or Esc.
            !touch -> Unit
            else -> {
                TouchDiagnostics.log("menu-show target=floor " + TouchDiagnostics.tapPrefix(x, y, layer))
                componentHost.dispatch(ResolvedHit.Outside(router.stack.floor))
            }
        }
    }

    private fun keyboardContext(): KeyboardContext {
        val view = sessionRegistry.activeHost?.tableView
        val allowNextHand = kind == SessionKind.FreePlay || kind == SessionKind.Online
        val scoreTableVisible = router.stack.contains(Route.Modal(RouteIds.SCORE_TABLE).key)
        return KeyboardContext(
            textFieldActive = textFieldActive(),
            gameActive = kind == SessionKind.Tutorial || sessionRegistry.boardHosted,
            allowHint = kind == SessionKind.FreePlay,
            allowNextHand = allowNextHand,
            referenceVisible = isReferenceOpen(),
            scoreTableVisible = scoreTableVisible,
            canDiscardDrawnTile = view?.canDiscardDrawnTile == true,
            unambiguousClaim = view?.unambiguousClaim,
            nonHuDecline = view?.nonHuDecline,
            // Enter advances only at hand end: mid-hand the same call asks to
            // abandon the hand, which a stray Enter must never do.
            nextHandReady = allowNextHand && view?.isTerminal == true && !scoreTableVisible,
        )
    }
    /** True while the reference browser route is on the stack. */
    private fun isReferenceOpen(): Boolean =
        router.stack.contains(Route.Reference(StackState.EMPTY).key)

    private fun textFieldActive(): Boolean =
        router.stack.paintRange.asReversed().any {
            (it.component as? inego.zungjunggame.ui.TextFieldHost)?.textFieldFocused == true
        }

    /**
     * Writes the router's persistable stack to the `stack` key and flushes the
     * store.
     *
     * This is the runtime's one **batched** flush site: every write between two
     * lifecycle edges (boot, pause, exit, dispose) is allowed to sit in the
     * backend's memory and is persisted here. Cold start replays the stack in
     * [AppGraph]; the stack is the sole authority for what is up.
     *
     * A credential is not this site's business: a `StateKey.secret` value is
     * committed by the store when it is written, because a rotation is a
     * server-side one-shot event and must not wait for a navigation.
     */
    private fun persistStack() {
        // The route UI store is the source of every persisted surface's state
        // text; the snapshot projects it per entry and never reads a component.
        stack.set(router.snapshot { entry -> routeUiState.text(entry.route.key) })
        flushedStack = router.stack
        flushedRouteUiRevision = routeUiState.revision
        lastStackFlushMs = TimeUtils.millis()
        stateStore.flush()
    }

    /**
     * Captures the stack when navigation moved it **or** the route UI store changed,
     * at most once per [STACK_FLUSH_INTERVAL_MS].
     *
     * The stack is *required* to be durable at the lifecycle edges; this only
     * covers the edges that never arrive. Writing also drains whatever else the
     * store is holding, so the batched keys share one publication. The first
     * frame writes once, which makes the boot's own pending writes durable.
     */
    private fun flushStackIfDirty() {
        val due = stackFlushDue(
            stackChanged = router.stack !== flushedStack,
            routeUiChanged = routeUiState.revision != flushedRouteUiRevision,
            nowMs = TimeUtils.millis(),
            lastFlushMs = lastStackFlushMs,
            intervalMs = STACK_FLUSH_INTERVAL_MS,
        )
        if (due) persistStack()
    }

    fun pause() {
        input.logLifecycle(
            "pause screen=${Gdx.graphics.width}x${Gdx.graphics.height} " +
                "sessionKind=$kind",
        )
        persistStack()
        freePlayHost.persist()
        tutorialHost.persistSession()
        onlineCoordinator.onAppPaused()
    }

    fun resume() {
        input.logLifecycle(
            "resume screen=${Gdx.graphics.width}x${Gdx.graphics.height}",
        )
        onlineCoordinator.onAppResumed()
        onlineCoordinator.reconcilePresence()
        pollNotices()
        requestRecovery("resume")
    }

    /**
     * Ask for a glyph/texture rebuild, unless this platform's declared durability
     * says the resources are still there.
     *
     * **A `resume` carries no information on a durable platform** — that is the
     * whole reason the flag exists. The context survived the backgrounding, so
     * there is nothing to repair, and rebuilding is 260–1200 ms of pure cost on
     * the first frame back. Only the *events* still ask (Stage C's
     * `onSurfaceCreated`, Stage W's `contextrestored`), because those report an
     * actual loss rather than a guess that the window closed.
     *
     * **A volatile platform still asks**, and deliberately so. There the frame
     * gap is a heuristic and `resume` is a second, independent one; suppressing
     * either would risk skipping a rebuild that was genuinely needed, which is the
     * black-box-text failure `Fonts.recoverAfterBackgrounding` exists to prevent.
     *
     * Shipped in Stage A ahead of the platform that needs it, so that W and B were
     * each a one-line launcher flip and the suppression was unit-tested before
     * anything depended on it. **It became live behaviour in W1** (2026-09-29):
     * the web is durable, so a tab return now costs nothing, which is the whole
     * point of Stage W. The decision itself is
     * [shouldRequestOnResume] so that "durable platforms do not rebuild on resume"
     * is a tested statement about the platform table rather than a private branch.
     */
    private fun requestRecovery(reason: String) {
        if (!shouldRequestOnResume(gpuContext.contextsAreDurable)) return
        gpuRecovery.request(reason)
    }

    fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
    }

    fun exitApp() {
        persistStack()
        freePlayHost.persist()
        tutorialHost.persistSession()
        onExit()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        input.close()
        persistStack()
        input.logLifecycle("presence stop dispose")
        freePlayHost.persist()
        tutorialHost.persistSession()
        scope.dispose()
    }
}

/**
 * Whether a `pause`/`resume` pair should ask for a glyph/texture rebuild.
 *
 * Kept as a named top-level function rather than an inline `if` inside
 * [AppRuntime] because the *whole* of Stage W rests on it, and an inline
 * condition can only be verified by booting a browser. As a function the
 * platform table is a test: durable says no, volatile says yes, and neither can
 * drift without a red test.
 *
 * **The asymmetry is the point, so do not "simplify" it into a single rule.**
 * A `resume` reports that the window came back — nothing about whether the GL
 * context is still valid. On a durable platform that carries no information, and
 * the rebuild it used to buy is 260–1200 ms of pure cost on the first frame back
 * (measured ~1.2 s of it on the web against a 6–21 ms paint; A5
 * measured 438–493 ms of it on every Android background/return). On a volatile
 * one the same rebuild is the *only* protection standing between a backgrounded
 * app and black-box text, because there the platform cannot say whether anything
 * survived. So the two platforms answer the same question differently, and the
 * difference is the platform fact rather than a preference.
 *
 * **Since Stage C no shipping launcher answers `true`.** Desktop (B1), the web
 * (W1) and Android (C1) are all durable, and all three still wire a real loss
 * event — which is the combination that makes the suppression safe. This
 * function is therefore no longer a live branch on a measured platform; it is
 * the answer for a *new* one, and `GpuContextPolicy.factory` still hands out
 * the volatile default until a launcher says otherwise.
 */
internal fun shouldRequestOnResume(contextsAreDurable: Boolean): Boolean = !contextsAreDurable

/**
 * Whether this rebuild must also repair libGDX's *own* GL objects — the batch's
 * shader and mesh handles, and the shared `WhitePixel`.
 *
 * A rebuild reason arrives for two unrelated reasons and the difference decides
 * whether the managed objects are broken:
 *
 *  - a **context that went away** (`contextRecreated`, and the volatile gap
 *    fallback) — every GL name the batch holds died, so the repair is the whole
 *    point. Without it the frame paints felt and every draw logs
 *    "object does not belong to this context" (Stage G).
 *  - a **freshly-constructed runtime** (`new runtime`) — it inherits a
 *    *live* context with freshly allocated objects. Invalidating them is not a
 *    no-op: dropping the batch's shader and every managed mesh's buffers
 *    mid-boot is enough to make the first frame draw nothing, which is the
 *    felt-only symptom Stage G removed, manufactured at startup. Measured: the
 *    pre-G build loaded its menu and the Stage G build showed green felt with no
 *    loss event ever firing.
 *
 * **But the shared pixel is dropped on the cold start too**, and that asymmetry
 * is the point of the function rather than an exception bolted onto it. The pixel
 * is a *process-wide* name, not a runtime-owned one: it is the one object here
 * that can outlive the context that made it, which is exactly what a second
 * runtime in one process (Android activity recreation) does. It cannot be put
 * behind the gate with the rest, because the rest is what would break at boot.
 *
 * Extracted as a named function for the same reason [shouldRequestOnResume] is:
 * the ordering lives inside a 500-line [renderFrame] that `:core:test` cannot
 * run, so the rule becomes a test instead of a comment.
 */
internal fun shouldRecoverManagedGpuResources(reason: String): Boolean = reason != "new runtime"

/**
 * Whether this frame must bail out without clearing and without painting.
 *
 * > A presented frame is a complete frame.
 *
 * A zero-sized world means the platform has taken the window away (minimized, or
 * a 0x0 occluded framebuffer). libGDX swaps the buffer when `render()` returns,
 * so a frame that clears and then bails **presents** a felt-only picture, and
 * that picture is what the compositor shows when the window comes back. The
 * guard has to sit above the clear, not below it.
 *
 * Extracted as a named function for the same reason [shouldRequestOnResume] is:
 * the ordering bug lives in a `renderFrame` this size that `:core:test` cannot
 * run, so the rule becomes a test instead of a comment.
 *
 * Note this is deliberately *only* about the world size. A real 0x0 framebuffer
 * is not a reason to clear: there is nothing to draw into, and the buffer it
 * would clear is the one the last good frame is sitting in.
 */
internal fun shouldSkipPaint(worldWidth: Float, worldHeight: Float): Boolean =
    worldWidth <= 0f || worldHeight <= 0f

/**
 * Reconciles the [RouteIds.ONLINE_CONNECT] card route against the connection state.
 *
 * While the online table layer is up ([kind] is [SessionKind.Online]) and the seat
 * is not yet resolved, the connecting card is wanted without requiring an attached
 * host. The seat is resolved when a host is attached, its table view is
 * ready, and no reconnect is required.
 */
internal fun syncOnlineConnect(
    router: Router,
    kind: SessionKind,
    onlineHost: OnlinePlayHost?,
) {
    val isTableUp = kind == SessionKind.Online
    val seatResolved = onlineHost?.tableView != null && !onlineHost.reconnectRequired
    val wanted = isTableUp && !seatResolved
    val key = Route.Modal(RouteIds.ONLINE_CONNECT).key
    when {
        wanted && router.stack.entries.none { it.spec.suppressesConnectCard } -> {
            if (!router.isOpen(key)) router.push(Route.Modal(RouteIds.ONLINE_CONNECT))
        }

        !wanted && router.stack.top.route.key == key -> router.pop()
    }
}
