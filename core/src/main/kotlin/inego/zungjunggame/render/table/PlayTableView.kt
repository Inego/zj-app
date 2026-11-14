package inego.zungjunggame.render.table

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.engine.*
import inego.zungjunggame.hints.McHintArmSnapshot
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.platform.SafeArea
import inego.zungjunggame.render.assets.TileAssets
import inego.zungjunggame.render.claim.ClaimActionPanel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.hint.HintButtonOverlay
import inego.zungjunggame.render.hint.HintUi
import inego.zungjunggame.render.hint.McHintLabels
import inego.zungjunggame.render.seats.PlayerAreaLayout
import inego.zungjunggame.render.seats.SeatLabelDrawer
import inego.zungjunggame.render.seats.SeatSide
import inego.zungjunggame.render.tiles.*
import inego.zungjunggame.render.ui.ParkChromeGroup
import inego.zungjunggame.render.ui.ParkRelease
import inego.zungjunggame.render.ui.ParkableChrome
import inego.zungjunggame.render.ui.UiChip
import inego.zungjunggame.render.wallbuild.WallBuildHandoffFx
import inego.zungjunggame.render.wallbuild.WallBuildHandoffSnapshot
import inego.zungjunggame.session.hosts.ConnectionStatus
import inego.zungjunggame.session.table.DiscardOrigin
import inego.zungjunggame.session.table.TableViewState
import inego.zungjunggame.ui.GestureSurface
import kotlin.math.*

/** The board's current input boxes, all measured on demand from the table's state. */
data class BoardHitBoxes(
    val hand: List<HandHit>,
    val claim: List<ClaimActionPanel.ButtonHit>,
    val hint: HintButtonOverlay.Hit?,
)

/**
 * Renders a full [TableViewState] as a four-sided table:
 * - human seat along the bottom (open hand)
 * - opponents on left / top / right (closed hands)
 * - discards above each hand toward the center
 * - text-only seat-wind label at each player (never a tile graphic)
 * - live-wall remaining count as a stacked closed-tile marker in the center
 *
 * Also tracks human hand hit boxes for hover highlight / click-to-discard,
 * a claim / action panel for human reactions and special ActOnHand moves,
 * a cross-platform HINT button (same as desktop H) on interactive turns
 * ([HintUi]: UCB-MC best avgΔ; unique → second press plays; multi greys),
 * per-option MC labels (avgΔ + playout count) above choices.
 * Every terminal hand — a win or an exhaustive draw — is summarized by
 * [ScoreBreakdownOverlay], so the board draws no hand-end banner or buttons.
 *
 * [interact] is first-class: [TableInteract.ReadOnly] still draws engine chrome
 * (including Hu) but freezes hover and hits. [overlayLeaderActive] only swaps
 * the prompt outline for an overlay leader — it is not a lock. Hosts publish
 * those values on [TableRenderInputs]; [applyFrom] copies them each frame.
 */
class PlayTableView(
    private val assets: TileAssets = TileAssets(),
    private val drawer: TileDrawer = TileDrawer(assets),
) : ParkableChrome, GestureSurface<ParkRelease>, Disposable {
    private val font get() = Fonts.body
    private val glyphLayout = GlyphLayout()
    private val claimPanel = ClaimActionPanel(drawer)
    private val winCurveDrawer = ConnectorCurveDrawer()
    private val liveWallMarker = LiveWallMarker(drawer)
    private val activeSeatSweep = ActiveSeatSweep()
    private val activeSeatTriangle = ActiveSeatTriangle()
    private val board = TileBoard()
    private val uiChip = UiChip()
    private val hintButton = HintButtonOverlay(uiChip)
    private val seatLabels = SeatLabelDrawer()
    private val handoffFx = WallBuildHandoffFx()

    /**
     * Board chrome the player can drag out of the way: the HINT button and the
     * claim/action row, in tap order (the HINT button is what a tap there hits
     * first — see the board tap handling), so a press grabs exactly the
     * block a tap would have acted on.
     */
    private val parkChrome = ParkChromeGroup(
        listOf(hintButton, claimPanel),
    )
    /** Accent outline on the live-wall marker (tutorial focus). */
    var highlightLiveWall: Boolean = false
    /**
     * Tutorial tile outlines for this frame. Empty = none. Any types, any
     * groups, anywhere [BoardHighlight] can match. Prompt groups skip their
     * stroke while [overlayLeaderActive] (discard-preamble leader).
     */
    var boardHighlight: BoardHighlight = BoardHighlight.None
    /**
     * Hand tile types whose discard leaves a scorable wait. Warm halo only; the
     * wait row is painted by [TouchTileMagnifier].
     */
    private var readyDiscardTypes: Set<Int> = emptySet()
    /**
     * [TableInteract.Play] (default) or [TableInteract.ReadOnly] lecture snapshot.
     */
    var interact: TableInteract = TableInteract.Play
    /**
     * Overlay is pointing at a prompted hand tile with a leader popup. Skip
     * Prompt outlines on the board; hover still applies so the leader can track.
     */
    var overlayLeaderActive: Boolean = false
    /** Elapsed time while a Prompt group is active; drives the outline pulse. */
    private var highlightPulseSec: Float = 0f
    private var lastLiveWallCx: Float = 0f
    private var lastLiveWallCy: Float = 0f
    private var lastLiveWallW: Float = 0f
    private var lastLiveWallH: Float = 0f

    /**
     * Absolute seat indices whose concealed hands should be drawn face-up
     * (e.g. for tutorial lessons demonstrating claim precedence or opponent hands).
     */
    var revealedSeats: Set<Int> = emptySet()

    /**
     * Last consume-once [TableApply.id] this widget applied. Empty until the
     * first non-none apply.
     */
    private var lastApplyId: String = ""

    /**
     * Copy this frame's model-derived chrome onto the widget and consume a
     * new [TableRenderInputs.apply] once. Call from motion, measure, and paint
     * so every phase sees the same snapshot.
     */
    fun applyFrom(inputs: TableRenderInputs) {
        consumeApply(inputs.apply)
        interact = inputs.interact
        highlightLiveWall = inputs.highlightLiveWall
        overlayLeaderActive = inputs.overlayLeaderActive
        revealedSeats = inputs.revealedSeats
        boardHighlight = inputs.boardHighlight
        readyDiscardTypes = inputs.discardWaits.readyTypes
        if (boardHighlight.isEmpty) {
            highlightPulseSec = 0f
            board.promptPulse = 0f
        }
    }

    private fun consumeApply(apply: TableApply) {
        if (apply.id.isEmpty() || apply.kind == TableApplyKind.None || apply.id == lastApplyId) {
            return
        }
        lastApplyId = apply.id
        when (apply.kind) {
            TableApplyKind.Reset -> resetForNewHand()
            TableApplyKind.Restored -> prepareRestoredHand()
            TableApplyKind.WallHandoff -> prepareWallBuildHandoff(
                snapshot = apply.wallHandoff ?: WallBuildHandoffSnapshot.EMPTY,
                humanSeat = apply.humanSeat,
            )
        }
    }

    fun clearTileHighlight() {
        boardHighlight = BoardHighlight.None
        highlightPulseSec = 0f
        board.promptPulse = 0f
    }

    init {
        Fonts.ensureCreated()
    }

    /**
     * Aspect of source tiles (width / height). FluffyStuff Regular is 3:4.
     */
    var tileAspect: Float = 3f / 4f

    /** Hand tile height as a fraction of the shorter screen side. */
    var handTileHeightFraction: Float = 0.085f

    /** Discard tiles are slightly smaller than hand tiles. */
    var discardScale: Float = 0.78f

    var tileGapFraction: Float = 0.08f
    var meldGapFraction: Float = 0.35f
    var rowGapFraction: Float = 0.14f

    /** Preferred number of discards per river row (adjusted down if space is tight). */
    var preferredDiscardsPerRow: Int = 6

    /** Lift factor for the hovered hand tile (fraction of tile height). */
    var hoverLiftFraction: Float = 0.14f

    /** Scale multiplier for the hovered hand tile. */
    var hoverScale: Float = 1.08f

    /** World-space hit boxes for the human's selectable hand tiles (measured on demand). */
    private val humanHandHits = ArrayList<HandHit>(16)

    /** Index into [humanHandHits] under the pointer, or -1. */
    var hoveredHandIndex: Int = -1
        private set

    /** Visual id under the pointer among selectable hand tiles, or -1. */
    var hoveredVisualId: Int = -1
        private set

    /**
     * Tile type of the visible tile under the pointer — any region, any seat,
     * any turn — or -1. Drives the [TileFocusGlow.Match] halo.
     */
    private var hoveredMatchType: Int = -1

    /**
     * When true, the next [render] reconcile treats all spawns as a fresh deal
     * (5 ms stagger from the wall marker). Set by [resetForNewHand].
     */
    private var pendingDealStagger: Boolean = false

    /**
     * When true, the next [render] snaps every tile to its target after reconcile
     * (no flight from the wall marker). Used when the board rematerializes an
     * existing position — a restored save, or a running online table that was
     * just joined — so the board appears as it was, not as a fresh deal.
     */
    private var pendingSnapLayout: Boolean = false

    /** Last layout viewport size; used to snap tiles on resize instead of flying. */
    private var lastLayoutW: Float = -1f
    private var lastLayoutH: Float = -1f

    /**
     * Best arm(s) from UCB flat MC ([HintUi]).
     * Cleared when the human acts, a new hand starts, or no highlighted choice remains legal.
     */
    var hintUi: HintUi = HintUi.Off
        private set

    /**
     * Latest MC arm stats for labels above options (refreshed ~1 s by [inego.zungjunggame.Main]).
     * Empty when HINT is off.
     */
    var mcHintArms: List<McHintArmSnapshot> = emptyList()
        private set

    /**
     * Once the board is fully idle on a human claim turn, keep the claim panel
     * visible even if a later reconcile briefly restarts a micro-animation.
     * Cleared when the human is no longer deciding a claim.
     */
    private var claimPanelLatched: Boolean = false

    /** True when this frame's tile reconcile has already run via [motion]. */
    private var motionReconciled: Boolean = false

    /**
     * True when the human should interactively discard: own turn and act-on-hand
     * (after draw, dealer open, or claim that requires a discard).
     */
    fun isHumanDiscardTurn(view: TableViewState): Boolean = view.isHumanDiscardTurn

    /**
     * True when the human must pick a discard reaction (pass / hu / pung / kong /
     * chow) and at least one non-pass option exists. Pass-only reactions stay auto.
     */
    fun isHumanClaimTurn(view: TableViewState): Boolean = view.isHumanClaimTurn

    /**
     * True when ActOnHand choices include panel actions (Hu / concealed kong /
     * promote kong / Keep) rather than only hand discards. Driven by the engine
     * choice list (including sequential concealed-kong gates).
     */
    fun isHumanActOnHandPanel(view: TableViewState): Boolean = view.isHumanActOnHandPanel

    /** AI must not advance while the human is choosing (discard or claim). */
    fun isHumanInteractiveTurn(view: TableViewState): Boolean = view.isHumanInteractiveTurn

    /**
     * True while any tile is still easing (deal stagger, draw, discard flight, etc.).
     * AI should not apply the next decision until this is false.
     */
    fun isAnimating(): Boolean = board.isBusy()

    /**
     * True while a present is still owed: tiles in flight, the wall handoff,
     * or a prompt outline pulse.
     *
     * The active-seat label used to be a fourth source, and it was why the board
     * never settled: a seat is *always* active, so a label that animated made
     * [inego.zungjunggame.features.board.BoardComponent] ask for a frame on every
     * present, and the picture only advanced when unrelated input happened to
     * deliver one. The label is static now
     * ([inego.zungjunggame.render.seats.SeatLabelDrawer.activeScaleBoost]).
     * [ActiveSeatSweep] is included because a turn onto a new seat is finite: once
     * the panel has landed, a settled board stops. It is not part of [isAnimating],
     * so tiles and bots do not wait for it.
     *
     * The live-wall stack is the same kind of finite motion. A take's flight is
     * treated as finished a few milliseconds early, and the card stepping into
     * the front slot is still on its last sample then. That sample has to ask
     * for its own present: once the flight has reported done, nothing else will.
     */
    fun needsFrames(): Boolean =
        isAnimating() ||
            liveWallMarker.isShifting() ||
            handoffFx.isRunning() ||
            activeSeatSweep.isTurning() ||
            (boardHighlight.hasPrompt() && !overlayLeaderActive)

    fun hitAt(worldX: Float, worldY: Float): HandHit? {
        if (!interact.allowsHits) return null
        // Prefer later tiles (drawn slot is registered last among hand tiles).
        for (i in humanHandHits.lastIndex downTo 0) {
            if (humanHandHits[i].contains(worldX, worldY)) return humanHandHits[i]
        }
        return null
    }

    /**
     * Recompute every input box for [view] at the current constraints without
     * painting. The human hand boxes come from the board's live tile layout; the
     * claim / HINT boxes are pure functions of the view and this widget's
     * view-local park and claim-latch state. No box is read from a previous
     * [render].
     */
    fun refreshHits(
        view: TableViewState?,
        screenW: Float,
        screenH: Float,
        pointerX: Float,
        pointerY: Float,
        inputs: TableRenderInputs,
    ): BoardHitBoxes {
        applyFrom(inputs)
        humanHandHits.clear()
        hoveredHandIndex = -1
        hoveredVisualId = -1
        val live = interact == TableInteract.Play
        updateHandHitsAndHover(
            if (live) pointerX else Float.NaN,
            if (live) pointerY else Float.NaN,
        )
        if (view == null || screenW <= 0f || screenH <= 0f) {
            return BoardHitBoxes(humanHandHits.toList(), emptyList(), null)
        }

        // The claim row's *existence* follows [claimPanelLatched], advanced in
        // [motion] once the board is idle after reconcile.
        val showClaimPanel = view.isHumanClaimTurn && claimPanelLatched
        val panelChoices = if (showClaimPanel || view.isHumanActOnHandPanel) view.myChoices else null

        val layout = computeTableLayout(screenW, screenH)
        val panelTileH = layout.handH * 0.72f
        val anchorY =
            layout.edgeInsetBottom + layout.handH * 0.5f + layout.rowGap + panelTileH * 1.15f
        val claimed = if (view.isPendingDiscard) view.lastDiscarded else -1
        val claim = if (live && panelChoices != null) {
            claimPanel.buttons(claimed, panelChoices, screenW, screenH, anchorY, panelTileH, tileAspect)
        } else {
            emptyList()
        }

        val seats = collectSeatSlots(
            view = view,
            layout = layout,
            screenW = screenW,
            screenH = screenH,
            humanDiscarding = view.isHumanDiscardTurn && live,
            winnerSeat = view.winnerSeat,
        )
        val hint = if (inputs.hintsEnabled && live && view.isHumanInteractiveTurn) {
            hintButton.hitBox(
                rowRightX = seats.humanRowRightX,
                rowCenterY = seats.humanRowCenterY,
                handH = layout.handH,
                screenW = screenW,
                screenH = screenH,
                edgeInsetBottom = layout.edgeInsetBottom,
            )
        } else {
            null
        }
        return BoardHitBoxes(humanHandHits.toList(), claim, hint)
    }

    /**
     * World pose of the prompted hand tile (first match), for overlay chrome
     * drawn on top of board-overlay dim bands. Null when nothing is highlighted.
     * When that tile is hovered, matches [TileBoard] hover lift/scale so the
     * leader stays on the face.
     */
    fun highlightedHandAnchor(): HandHit? {
        val types = boardHighlight.handAnchorTypes()
        if (types.isEmpty()) return null
        val hit = humanHandHits.firstOrNull { it.tileType in types } ?: return null
        if (hit.visualId != hoveredVisualId) return hit
        return HandHit(
            worldX = hit.worldX,
            worldY = hit.worldY + hit.h * hoverLiftFraction,
            w = hit.w * hoverScale,
            h = hit.h * hoverScale,
            tileType = hit.tileType,
            isDrawnSlot = hit.isDrawnSlot,
            visualId = hit.visualId,
            slotIndex = hit.slotIndex,
        )
    }

    /** Pending claim discard (accent play halo). Null when that tile is not on the board. */
    fun pendingDiscardAnchor(): HandHit? = board.pendingDiscardHit()?.toHandHit()

    /** Front live-wall marker tile from the last render. */
    fun liveWallAnchor(): HandHit? {
        if (lastLiveWallW <= 0f) return null
        return HandHit(
            worldX = lastLiveWallCx,
            worldY = lastLiveWallCy,
            w = lastLiveWallW,
            h = lastLiveWallH,
            tileType = -1,
            isDrawnSlot = false,
        )
    }

    /**
     * World pose for a tutorial [targetId]: `river.last`, `wall.live`, or
     * `tile.N` (You copies, else the pending discard if it is N).
     */
    fun anchorForTarget(targetId: String, humanSeat: Int): HandHit? {
        when (targetId) {
            "river.last" -> return pendingDiscardAnchor()
            "wall.live" -> return liveWallAnchor()
        }
        val type = tileTypeFromTarget(targetId) ?: return highlightedHandAnchor()
        val prompted = highlightedHandAnchor()
        if (prompted != null && prompted.tileType == type) return prompted
        board.youCopyHit(humanSeat, type)?.let { return it.toHandHit() }
        return pendingDiscardAnchor()?.takeIf { it.tileType == type }
    }

    private fun TileBoard.HandHitInfo.toHandHit(): HandHit =
        HandHit(
            worldX = worldX,
            worldY = worldY,
            w = w,
            h = h,
            tileType = tileType,
            isDrawnSlot = isDrawnSlot,
            visualId = visualId,
            slotIndex = slotIndex,
        )

    private fun tileTypeFromTarget(targetId: String): Int? {
        if (!targetId.startsWith("tile.")) return null
        return targetId.removePrefix("tile.").toIntOrNull()?.takeIf { it in 0 until NUM_TILE_TYPES }
    }

    /**
     * Pure claim / action-row geometry for [view] at this screen size, or null
     * when [view] offers no panel. Computed from the table layout and the
     * panel's inputs; no prior render is needed and nothing is mutated.
     */
    fun claimRowLayout(
        view: TableViewState,
        screenW: Float,
        screenH: Float,
    ): ClaimActionPanel.RowLayout? {
        if (screenW <= 0f || screenH <= 0f) return null
        val layout = computeTableLayout(screenW, screenH)
        val panelTileH = layout.handH * 0.72f
        val anchorY =
            layout.edgeInsetBottom + layout.handH * 0.5f + layout.rowGap + panelTileH * 1.15f
        val claimed = if (view.isPendingDiscard) view.lastDiscarded else -1
        return claimPanel.layoutRow(
            claimedTile = claimed,
            choices = view.myChoices,
            screenW = screenW,
            screenH = screenH,
            anchorY = anchorY,
            tileH = panelTileH,
            tileAspect = tileAspect,
        )
    }

    // ── ParkableChrome ───────────────────────────────────────────────────────
    // HINT button + claim/action row: press either and drag to move it out of the
    // way; a press that never moves is still a tap, which the host dispatches.

    override val parkChromeLabel: String get() = parkChrome.parkChromeLabel

    override fun hitsParkChrome(worldX: Float, worldY: Float): Boolean =
        parkChrome.hitsParkChrome(worldX, worldY)

    override fun onParkPointerDown(worldX: Float, worldY: Float): Boolean =
        parkChrome.onParkPointerDown(worldX, worldY)

    override fun onParkPointerDrag(worldX: Float, worldY: Float) =
        parkChrome.onParkPointerDrag(worldX, worldY)

    override fun onParkPointerUp(worldX: Float, worldY: Float): ParkRelease =
        parkChrome.onParkPointerUp(worldX, worldY)

    override fun cancelPark() = parkChrome.cancelPark()

    // ── GestureSurface<ParkRelease> ──────────────────────────────────────────
    // The pipeline's four calls are the park machine's own: the press claims the
    // block, the drag follows the pointer, and a release resolves what happened.
    // A press that never moved reports [ParkRelease.Tap], which the bridge reads
    // as "not consumed", so the release is re-dispatched as an ordinary tap and
    // the HINT / claim hit regions beneath still resolve.

    override fun onPointerDown(x: Float, y: Float): Boolean = onParkPointerDown(x, y)

    override fun onPointerDrag(x: Float, y: Float): ParkRelease? {
        onParkPointerDrag(x, y)
        return null
    }

    override fun onPointerUp(x: Float, y: Float): ParkRelease = onParkPointerUp(x, y)

    override fun cancelGesture() = cancelPark()

    /**
     * Hand the player's remembered movable-chrome places in (`ui.prefs`). Each
     * block clamps whatever it is given on its next render. A block that owns a
     * live press keeps its own place until the release reports it; handing the
     * stored value in mid-drag would snap the block back under the finger.
     */
    fun restoreParkedChrome(hintX: Float, hintY: Float, claimX: Float, claimY: Float) {
        if (parkChrome.parking) return
        hintButton.setPark(hintX, hintY)
        claimPanel.setPark(claimX, claimY)
    }

    fun clearHover() {
        hoveredHandIndex = -1
        hoveredVisualId = -1
        hoveredMatchType = -1
        // The chrome is being replaced (new hand / restored board): drop any
        // park press that was in flight over it. The parks themselves stay.
        parkChrome.cancelPark()
        claimPanel.clear()
        hintButton.clear()
    }

    fun clearRecommendation() {
        hintUi = HintUi.Off
        mcHintArms = emptyList()
    }

    /**
     * Apply MC highlight set: unique best → [HintUi.Active] (second press plays);
     * multi-tie → [HintUi.Exhausted] (button greys). Empty clears highlights only
     * (stats labels may still be present).
     */
    fun activateHint(choices: List<Choice>) {
        hintUi = when {
            choices.isEmpty() -> HintUi.Off
            choices.size == 1 -> HintUi.Active(choices)
            else -> HintUi.Exhausted(choices)
        }
    }

    /** Replace per-option MC labels (main thread; from search snapshot). */
    fun setMcHintArms(arms: List<McHintArmSnapshot>) {
        mcHintArms = arms
    }

    /**
     * Call when starting a new hand so the next render spawns tiles from the
     * wall marker with deal stagger.
     */
    fun resetForNewHand() {
        board.clear()
        liveWallMarker.reset()
        handoffFx.clear()
        pendingDealStagger = true
        pendingSnapLayout = false
        claimPanelLatched = false
        motionReconciled = false
        activeSeatSweep.snap()
        clearHover()
        clearRecommendation()
        clearTileHighlight()
    }

    /**
     * Call when rematerializing an existing position — a restored save, or the
     * first view of an online table that was already running. Next render places
     * tiles at their layout targets immediately (no deal-from-center animation).
     */
    fun prepareRestoredHand() {
        board.clear()
        liveWallMarker.reset()
        board.moveDurationSec = 0.3f
        handoffFx.clear()
        pendingDealStagger = false
        pendingSnapLayout = true
        claimPanelLatched = false
        motionReconciled = false
        activeSeatSweep.snap()
        clearHover()
        clearRecommendation()
    }

    /**
     * Seed hand tiles at diagram poses, ease to live layout, and run residual
     * wall FX (dead fade + live collapse → marker). [humanSeat] controls which
     * seat is open/selectable.
     *
    * Call after installing the matching table view into the live hand.
     */
    fun prepareWallBuildHandoff(
        snapshot: WallBuildHandoffSnapshot,
        humanSeat: Int,
        wallCx: Float = Float.NaN,
        wallCy: Float = Float.NaN,
    ) {
        board.clear()
        liveWallMarker.reset()
        handoffFx.clear()
        pendingDealStagger = false
        pendingSnapLayout = false
        claimPanelLatched = false
        motionReconciled = false
        activeSeatSweep.snap()
        clearHover()
        clearRecommendation()
        val hands = snapshot.hands
        if (hands.isEmpty()) {
            pendingSnapLayout = true
            return
        }
        val seeds = hands.map { t ->
            val open = t.seat == humanSeat
            TileBoard.SeededTile(
                pose = TilePose(
                    x = t.cx,
                    y = t.cy,
                    rotationDeg = t.rot,
                    w = t.w,
                    h = t.h,
                    open = open,
                ),
                tileType = t.tileType,
                seatIndex = t.seat,
                slotKey = SlotKey(
                    kind = SlotKind.Hand,
                    seat = t.seat,
                    index = t.handIndex,
                ),
                selectableHand = open,
            )
        }
        board.seedAtPoses(seeds)
        // Slightly longer ease so diagram → table handoff reads as continuous.
        board.moveDurationSec = WallBuildHandoffFx.DURATION_SEC
        handoffFx.start(snapshot, wallCx, wallCy)
    }

    /** Advance current→target motion, handoff FX, and the prompt outline pulse. */
    fun advance(deltaSec: Float) {
        motionReconciled = false
        board.advance(deltaSec)
        activeSeatSweep.advance(deltaSec)
        liveWallMarker.advance(deltaSec, board.moveDurationSec)
        if (boardHighlight.hasPrompt() && !overlayLeaderActive && deltaSec > 0f) {
            highlightPulseSec += deltaSec
            board.promptPulse =
                (0.5f + 0.5f * sin(highlightPulseSec * PI.toFloat() * 2f / 1.15f))
                    .coerceIn(0f, 1f)
        } else {
            highlightPulseSec = 0f
            board.promptPulse = 0f
        }
        if (handoffFx.advance(deltaSec)) {
            board.moveDurationSec = 0.3f
        }
    }

    /**
     * Prefer this visual for the next discard→river match (human click).
     * Cleared automatically after the following reconcile.
     */
    fun preferVisualForNextMatch(visualId: Int) {
        board.preferredVisualId = visualId
    }

    /**
     * Prefer this discard origin slot for the next discard→river match (opponent / network).
     * Cleared automatically after the following reconcile.
     */
    fun preferDiscardOrigin(origin: DiscardOrigin) {
        board.preferredDiscardOrigin = origin.toSlotKey()
    }

    private fun reconcileInternal(
        view: TableViewState,
        layout: TableLayout,
        screenW: Float,
        screenH: Float,
        live: Boolean,
    ): SeatSlots {
        val wallCx = screenW * 0.5f
        val wallCy = layout.feltCenterY
        board.wallSpawnPose = TilePose(
            x = wallCx,
            y = wallCy,
            rotationDeg = 0f,
            w = layout.handW,
            h = layout.handH,
            open = false,
        )
        // One tile leaving the wall starts as the stack's front card.
        board.wallDeparturePose = if (liveWallMarker.willDepartOne(view.liveWallSize)) {
            TilePose(
                x = wallCx,
                y = wallCy,
                rotationDeg = 0f,
                w = layout.handW * LiveWallMarker.TILE_SCALE,
                h = layout.handH * LiveWallMarker.TILE_SCALE,
                open = false,
            )
        } else {
            null
        }
        handoffFx.retargetToCenter(wallCx, wallCy)

        val seats = collectSeatSlots(
            view = view,
            layout = layout,
            screenW = screenW,
            screenH = screenH,
            humanDiscarding = view.isHumanDiscardTurn && live,
            winnerSeat = view.winnerSeat,
        )

        val dealStagger = pendingDealStagger
        pendingDealStagger = false
        board.reconcile(seats.desired, dealStagger = dealStagger)
        liveWallMarker.sync(view.liveWallSize, board.wallSpawnCount)
        applySnapIfNeeded(screenW, screenH)

        if (!view.isHumanClaimTurn) {
            claimPanelLatched = false
        } else if (!board.isBusy()) {
            claimPanelLatched = true
        }

        return seats
    }

    /**
     * Advance board tile reconciliation, layout retargets, and the claim panel latch
     * during the frame's motion phase, before layout measures hit regions.
     */
    fun motion(
        view: TableViewState?,
        screenW: Float,
        screenH: Float,
        inputs: TableRenderInputs,
    ) {
        applyFrom(inputs)
        inputs.discardOrigin?.let { preferDiscardOrigin(it) }
        if (view == null || screenW <= 0f || screenH <= 0f) {
            claimPanelLatched = false
            motionReconciled = false
            return
        }

        val layout = computeTableLayout(screenW, screenH)
        val live = interact == TableInteract.Play
        reconcileInternal(view, layout, screenW, screenH, live)
        motionReconciled = true
    }

    /**
     * @param pointerWorldX world X of the mouse/pointer (for hover); NaN to skip
     * @param pointerWorldY world Y of the mouse/pointer (for hover); NaN to skip
     * @param showTurnStatus when false, skip the center action line (board-overlay
     *        coach already owns that space)
     */
    fun render(
        batch: SpriteBatch,
        view: TableViewState,
        screenW: Float,
        screenH: Float,
        inputs: TableRenderInputs,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        showTurnStatus: Boolean = true,
        decisionTimeLeftMs: Long? = null,
        decisionSlackMs: Long? = null,
        decisionBaseMs: Long? = null,
        connectionStatus: ConnectionStatus = ConnectionStatus.Local,
        statusFeedback: String? = null,
        shutdownPhase: String? = null,
        shutdownRemainingMs: Long? = null,
        shutdownMessage: String? = null,
    ) {
        if (screenW <= 0f || screenH <= 0f) return

        applyFrom(inputs)
        inputs.discardOrigin?.let { preferDiscardOrigin(it) }
        humanHandHits.clear()
        hoveredHandIndex = -1
        hoveredVisualId = -1
        claimPanel.clear()
        hintButton.clear()
        refreshRecommendation(view.myChoices.asList(), view.isHumanInteractiveTurn, inputs.hintsEnabled)

        val layout = computeTableLayout(screenW, screenH)
        val live = interact == TableInteract.Play
        val hoverX = if (live) pointerWorldX else Float.NaN
        val hoverY = if (live) pointerWorldY else Float.NaN
        val humanDiscarding = view.isHumanDiscardTurn
        val humanClaiming = view.isHumanClaimTurn
        val humanActPanel = view.isHumanActOnHandPanel
        val winnerSeat = view.winnerSeat
        val wallCx = screenW * 0.5f
        val wallCy = layout.feltCenterY

        val wasMotionReconciled = motionReconciled
        motionReconciled = false

        val seats = if (!wasMotionReconciled) {
            reconcileInternal(view, layout, screenW, screenH, live)
        } else {
            collectSeatSlots(
                view = view,
                layout = layout,
                screenW = screenW,
                screenH = screenH,
                humanDiscarding = humanDiscarding && live,
                winnerSeat = winnerSeat,
            )
        }

        // Under the active seat's own area, before any tile: a brighter felt panel so
        // that seat's hand, melds and label read as one group, and so the eye is
        // carried to whoever must act. Drawn first so every tile, glow, curve and
        // outline above it composites over it.
        //
        // `activeSeat` is not `currentPlayer` while a claim is open — the discarder
        // keeps the emphasis until the claim resolves — which is the same rule the
        // seat label's weight already follows, so the two cues never disagree.
        //
        // The panel wraps the seat's **measured** tile bounds (hand, drawn slot and
        // melds), and runs from there out to that seat's own screen edge, so it covers
        // the gutter the seat label sits in without asking the label for a single
        // font metric. Toward the table, and off both ends of the row, the air is
        // one fifth of the tile height, read from those bounds. Nothing here
        // re-derives the geometry the tiles were laid out with — that is what made
        // an earlier version land beside the hand instead of under it on the side seats.
        //
        // [ActiveSeatSweep] turns that rectangle about the table's centre when the
        // seat changes. The wedge's base is the rectangle's inner edge, so the two
        // move together. The apex is the felt centre, the same point the wall
        // marker sits on.
        val activeSide = SeatSide.forRelativeSeat(view.activeSeat - view.mySeat)
        seats.seatBounds[view.activeSeat]?.let { activeBounds ->
            val safe = safeInsetSnapshot()
            val panel = ActiveSeatFelt.band(activeSide, screenW, screenH, activeBounds, safe)
            if (panel != null) {
                activeSeatTriangle.draw(
                    batch,
                    activeSeatSweep.sample(
                        activeSide,
                        panel,
                        wallCx,
                        wallCy,
                        board.moveDurationSec,
                    ),
                    wallCx,
                    wallCy,
                )
            }
        }

        val showClaimPanel = humanClaiming && claimPanelLatched
        val humanChoices: ChoiceList? =
            if (showClaimPanel || humanActPanel) view.myChoices else null

        updateHandHitsAndHover(hoverX, hoverY)
        if (overlayLeaderActive) {
            highlightedHandAnchor()?.visualId?.takeIf { it >= 0 }?.let { preferVisualForNextMatch(it) }
        }

        val recChoices = if (inputs.hintsEnabled) hintUi.highlightedChoices() else emptyList()
        val recDiscardTypes = HashSet<Int>()
        for (c in recChoices) {
            if (c.kind == ChoiceKind.Discard) recDiscardTypes.add(c.tile())
        }

        val isWinOnDiscard = view.winnerSeat in 0 until NUM_PLAYERS &&
            view.winSource in 0 until NUM_PLAYERS &&
            view.winnerSeat != view.winSource &&
            view.handScore > 0

        val hasWinCurve = isWinOnDiscard &&
            !seats.pendingDiscardX.isNaN() &&
            !seats.pendingDiscardY.isNaN() &&
            !seats.winnerHandAnchorX.isNaN() &&
            !seats.winnerHandAnchorY.isNaN() &&
            !board.isBusy()

        val panelTileH = layout.handH * 0.72f
        val anchorY =
            layout.edgeInsetBottom + layout.handH * 0.5f + layout.rowGap + panelTileH * 1.15f
        val hasClaimCurve = showClaimPanel &&
            !seats.pendingDiscardX.isNaN() &&
            !seats.pendingDiscardY.isNaN() &&
            !board.isBusy() &&
            humanChoices != null &&
            humanChoices.size > 0

        val hasDiscardCurve = hasWinCurve || hasClaimCurve

        val isDiscardTile: (VisualTile) -> Boolean = { v ->
            v.focusGlow == TileFocusGlow.Soft || v.focusGlow == TileFocusGlow.WinDiscard
        }
        val isNonDiscardTile: (VisualTile) -> Boolean = { v ->
            !isDiscardTile(v)
        }

        if (hasDiscardCurve) {
            // ── Pass 1: Render all board layers EXCEPT the pending discarded tile ──
            drawBoardLayers(batch, recDiscardTypes, drawOutlines = false, filter = isNonDiscardTile)
            handoffFx.draw(batch, drawer)
            lastLiveWallCx = wallCx
            lastLiveWallCy = wallCy
            lastLiveWallW = layout.handW * LiveWallMarker.TILE_SCALE
            lastLiveWallH = layout.handH * LiveWallMarker.TILE_SCALE
            liveWallMarker.render(
                batch,
                view.liveWallSize,
                wallCx,
                wallCy,
                layout.handW,
                layout.handH,
                alpha = handoffFx.markerAlpha,
                highlight = highlightLiveWall && handoffFx.markerAlpha > 0.4f,
                labelScale = layout.chromeScale,
            )
            drawBoardLayers(batch, recDiscardTypes, drawSettled = false, drawFlying = true, drawOutlines = false, filter = isNonDiscardTile)
            drawBoardLayers(
                batch,
                recDiscardTypes,
                drawSettled = true,
                drawFlying = true,
                drawTiles = false,
                drawOutlines = true,
                filter = isNonDiscardTile,
            )

            // ── Pass 2: Render connector curve OVER all other tiles ──
            if (hasWinCurve) {
                val winTileH = layout.handH * 0.72f
                val winStyle = ConnectorCurveDrawer.CurveStyle.forWinOnDiscard(
                    winnerSeat = view.winnerSeat,
                    winSource = view.winSource,
                    playerSeat = view.mySeat,
                )
                winCurveDrawer.drawArc(
                    batch = batch,
                    x0 = seats.pendingDiscardX,
                    y0 = seats.pendingDiscardY,
                    x1 = seats.winnerHandAnchorX,
                    y1 = seats.winnerHandAnchorY,
                    centerRefX = screenW * 0.5f,
                    centerRefY = layout.feltCenterY,
                    style = winStyle,
                    overrideThickness = max(2.5f, winTileH * 0.08f),
                )
            } else if (hasClaimCurve) {
                // Start at the row's *parked* center: the connector has to follow
                // a claim row the player moved (see ClaimActionPanel.parkX).
                val startX = screenW * 0.5f + claimPanel.parkX
                val startY = anchorY + claimPanel.parkY
                val chordLen = hypot(seats.pendingDiscardX - startX, seats.pendingDiscardY - startY)
                val tableCx = screenW * 0.5f
                val tableCy = anchorY + chordLen * 0.5f
                winCurveDrawer.drawArc(
                    batch = batch,
                    x0 = startX,
                    y0 = startY,
                    x1 = seats.pendingDiscardX,
                    y1 = seats.pendingDiscardY,
                    centerRefX = tableCx,
                    centerRefY = tableCy,
                    style = ConnectorCurveDrawer.CurveStyle.CLAIM_DISCARD,
                    overrideThickness = max(2f, panelTileH * 0.07f),
                )
            }

            // ── Pass 3: Render the pending discarded tile ON TOP OF the curve ──
            drawBoardLayers(
                batch,
                recDiscardTypes,
                drawSettled = true,
                drawFlying = true,
                drawTiles = true,
                drawOutlines = true,
                filter = isDiscardTile,
            )
        } else {
            // Settled hands → residual wall FX → marker → in-flight draws →
            // outlines last so a neighbor tile cannot cover the stroke.
            drawBoardLayers(batch, recDiscardTypes, drawOutlines = false)
            handoffFx.draw(batch, drawer)
            lastLiveWallCx = wallCx
            lastLiveWallCy = wallCy
            lastLiveWallW = layout.handW * LiveWallMarker.TILE_SCALE
            lastLiveWallH = layout.handH * LiveWallMarker.TILE_SCALE
            liveWallMarker.render(
                batch,
                view.liveWallSize,
                wallCx,
                wallCy,
                layout.handW,
                layout.handH,
                alpha = handoffFx.markerAlpha,
                highlight = highlightLiveWall && handoffFx.markerAlpha > 0.4f,
                labelScale = layout.chromeScale,
            )
            drawBoardLayers(batch, recDiscardTypes, drawSettled = false, drawFlying = true, drawOutlines = false)
            drawBoardLayers(
                batch,
                recDiscardTypes,
                drawSettled = true,
                drawFlying = true,
                drawTiles = false,
                drawOutlines = true,
            )
        }

        val safe = safeInsetSnapshot()
        for ((seat, side, alongCenter) in seats.metas) {
            val seatView = view.seats[seat]
            val safeInset = when (side) {
                SeatSide.Left -> safe.left
                SeatSide.Right -> safe.right
                SeatSide.Opposite -> safe.top
                SeatSide.Bottom -> safe.bottom
            }
            seatLabels.draw(
                batch = batch,
                windTile = seatView.seatWindTile,
                // Turn holder, not the seat currently deciding on a claim
                // ([TableViewState.activeSeat]).
                active = seat == view.activeSeat,
                seat = seat,
                side = side,
                screenW = screenW,
                screenH = screenH,
                edgeInset = layout.edgeInsetFor(side),
                alongCenter = alongCenter,
                handH = layout.handH,
                tileGap = layout.tileGap,
                seatScores = inputs.seatScores,
                humanSeat = inputs.humanSeat,
                safeInset = safeInset,
                bottomReserve = layout.bottomLabelReserve,
            )
        }

        if (humanChoices != null) {
            claimPanel.render(
                batch = batch,
                view = view,
                choices = humanChoices,
                screenW = screenW,
                screenH = screenH,
                anchorY = anchorY,
                tileH = panelTileH,
                tileAspect = tileAspect,
                pointerWorldX = hoverX,
                pointerWorldY = hoverY,
                recommended = if (inputs.hintsEnabled) recChoices else emptyList(),
                mcHintArms = if (inputs.hintsEnabled && live) mcHintArms else emptyList(),
                discardedTileX = Float.NaN,
                discardedTileY = Float.NaN,
                interactive = live,
                decisionTimeLeftMs = decisionTimeLeftMs,
                decisionSlackMs = decisionSlackMs,
                decisionBaseMs = decisionBaseMs,
            )
        }

        if (inputs.hintsEnabled && live) {
            McHintLabels.drawAboveHands(batch, mcHintArms, humanHandHits, glyphLayout)
        }

        if (PatternlessNotice.isApplicable(view, inputs.allowChickenHands) && live) {
            val handCenterX = if (humanHandHits.isNotEmpty()) {
                (humanHandHits.minOf { it.worldX - it.w * 0.5f } + humanHandHits.maxOf { it.worldX + it.w * 0.5f }) * 0.5f
            } else {
                screenW * 0.5f
            }
            val rowCenterY = if (seats.humanRowCenterY.isNaN()) {
                layout.edgeInsetBottom + layout.handH * 0.5f
            } else {
                seats.humanRowCenterY
            }
            val handTop = rowCenterY + layout.handH * 0.5f
            val gap = if (inputs.hintsEnabled && mcHintArms.isNotEmpty()) Fonts.px(26f) else Fonts.px(10f)
            PatternlessNotice.draw(
                batch = batch,
                font = font,
                handCenterX = handCenterX,
                bottomY = handTop + gap,
                screenW = screenW,
                scale = layout.chromeScale,
            )
        }

        if (inputs.hintsEnabled && live && view.isHumanInteractiveTurn) {
            hintButton.render(
                batch = batch,
                rowRightX = seats.humanRowRightX,
                rowCenterY = seats.humanRowCenterY,
                handH = layout.handH,
                screenW = screenW,
                screenH = screenH,
                edgeInsetBottom = layout.edgeInsetBottom,
                pointerWorldX = hoverX,
                pointerWorldY = hoverY,
                mode = when (hintUi) {
                    HintUi.Off -> HintButtonOverlay.Mode.Idle
                    is HintUi.Active -> HintButtonOverlay.Mode.Active
                    is HintUi.Exhausted -> HintButtonOverlay.Mode.Exhausted
                },
            )
        }

        if (showTurnStatus) {
            TurnStatusBanner.draw(
                batch = batch,
                font = font,
                view = view,
                screenW = screenW,
                humanSeat = inputs.humanSeat,
                humanDiscarding = humanDiscarding,
                humanClaiming = showClaimPanel,
                // Bottom-anchored above the wall count, capped by the opposite
                // hand's inner edge. Both are table geometry, so the banner never
                // has to re-derive them; the count may be covered by the card
                // backs above it, so the landing point is the number's top, not
                // the stack's. Measured from where the marker really is
                // (feltCenterY), not the raw pane centre.
                wallCountTopY = liveWallMarker.countTopY(wallCy, layout.handH, layout.chromeScale),
                feltTopY = screenH - layout.edgeInsetOpposite - layout.handH,
                labelScale = layout.chromeScale,
                decisionTimeLeftMs = decisionTimeLeftMs,
                decisionSlackMs = decisionSlackMs,
                decisionBaseMs = decisionBaseMs,
                connectionStatus = connectionStatus,
                feedback = statusFeedback,
                shutdownPhase = shutdownPhase,
                shutdownRemainingMs = shutdownRemainingMs,
                shutdownMessage = shutdownMessage,
            )
        }

        val hoveredHand = humanHandHits.getOrNull(hoveredHandIndex)
        if (hoveredHand != null && !hoverX.isNaN() && !hoverY.isNaN()) {
            val waits = inputs.discardWaits.waitsFor(hoveredHand.tileType)
            if (InputProfile.detect() == InputProfile.Touch) {
                TouchTileMagnifier.draw(
                    batch = batch,
                    drawer = drawer,
                    tileType = hoveredHand.tileType,
                    anchorX = pointerWorldX,
                    anchorY = pointerWorldY,
                    screenW = screenW,
                    screenH = screenH,
                    handW = layout.handW,
                    handH = layout.handH,
                    handGap = layout.tileGap,
                    waits = waits,
                    showDiscardFace = true,
                )
            } else if (waits.isNotEmpty()) {
                TouchTileMagnifier.draw(
                    batch = batch,
                    drawer = drawer,
                    tileType = hoveredHand.tileType,
                    anchorX = hoveredHand.worldX,
                    anchorY = hoveredHand.worldY +
                        hoveredHand.h * (hoverLiftFraction + 0.5f * hoverScale),
                    screenW = screenW,
                    screenH = screenH,
                    handW = layout.handW,
                    handH = layout.handH,
                    handGap = layout.tileGap,
                    waits = waits,
                    showDiscardFace = false,
                )
            }
        }
    }

    // ── Layout + seat slot collection ────────────────────────────────────────

    private data class TableLayout(
        val handW: Float,
        val handH: Float,
        val tileGap: Float,
        val rowGap: Float,
        /**
         * The shared felt-label scale for these dimensions. Seat labels, the center
         * turn line, the HUD counters and the live-wall count all paint at this one
         * factor, so none of them can stay desktop-sized while the seat labels have
         * already shrunk.
         */
        val chromeScale: Float,
        val edgeInsetLeft: Float,
        val edgeInsetRight: Float,
        val edgeInsetOpposite: Float,
        val edgeInsetBottom: Float,
        /**
         * Centre of the felt between the top and bottom hand rows. The wall
         * marker and the side seats live here, not on the raw pane centre: the
         * bottom edge also carries the safe inset, the coach reserve, and the
         * portrait credit strip, so the play area is not vertically symmetric.
         */
        val feltCenterY: Float,
        val metrics: PlayerAreaLayout.Metrics,
        val alongEdgePad: Float,
        /**
         * Bottom band the human seat label must clear: the tutorial coach reserve.
         * Zero outside that coach.
         */
        val bottomLabelReserve: Float,
    ) {
        fun edgeInsetFor(side: SeatSide): Float = when (side) {
            SeatSide.Bottom -> edgeInsetBottom
            SeatSide.Left -> edgeInsetLeft
            SeatSide.Right -> edgeInsetRight
            SeatSide.Opposite -> edgeInsetOpposite
        }

    }

    private data class SeatMeta(
        val seat: Int,
        val side: SeatSide,
        /** World position of the seat's row centre along its edge. */
        val alongCenter: Float,
    )

    private data class SeatSlots(
        val metas: List<SeatMeta>,
        val desired: ArrayList<DesiredSlot>,
        /**
         * Absolute seat → its measured `minX, minY, maxX, maxY` over hand, drawn
         * slot and melds. Feeds [ActiveSeatFelt] so the panel wraps the tiles that
         * were actually laid out instead of a re-derived approximation of them.
         */
        val seatBounds: Map<Int, FloatArray> = emptyMap(),
        val pendingDiscardX: Float,
        val pendingDiscardY: Float,
        val humanRowRightX: Float,
        val humanRowCenterY: Float,
        val winnerHandAnchorX: Float = Float.NaN,
        val winnerHandAnchorY: Float = Float.NaN,
    )

    /**
     * Pixels the lesson keeps clear at the bottom of this viewport for the
     * tutorial Back / Next row. The hand is anchored to the bottom edge, so
     * without this the buttons paint over it. Zero outside that coach.
     */
    var bottomReservePx: Float = 0f

    /**
     * Safe insets for this board's viewport. Null reads the window [SafeArea]
     * (a full-screen board). A host that lays the board out in a space that
     * already excludes the cutout (the tutorial shell's content zone) sets zeros
     * so the cutout is not inset a second time.
     */
    var safeInsets: SafeArea.Insets? = null

    private fun safeInsetSnapshot(): SafeArea.Insets = safeInsets ?: SafeArea.current()

    /**
     * The shared felt-label scale for a table laid out at [screenW] x [screenH].
     *
     * Callers outside the paint path (the free-play HUD badge) use this instead of
     * their own size rule, so every label on the felt shrinks together. It is the
     * scale the seat labels paint at for this layout — hand-tile cap and gutter fit
     * included — not just the tile cap, which stays at 1 until the window is nearly
     * at its narrowest.
     */
    fun chromeScaleFor(screenW: Float, screenH: Float): Float =
        computeTableLayout(screenW, screenH).chromeScale

    private fun computeTableLayout(screenW: Float, screenH: Float): TableLayout {
        val shortSide = min(screenW, screenH)
        val handH = shortSide * handTileHeightFraction
        val handW = handH * tileAspect
        val discardH = handH * discardScale
        val discardW = discardH * tileAspect
        val tileGap = handW * tileGapFraction
        val meldGap = handW * meldGapFraction
        val rowGap = handH * rowGapFraction
        val drawnGap = handW * 0.55f

        val isAndroid = Gdx.app?.type == Application.ApplicationType.Android
        val safe = safeInsetSnapshot()
        val baseSide = handH * 0.55f + shortSide * 0.045f
        val minGutter = if (isAndroid) {
            max(handH * 1.05f, shortSide * 0.09f)
        } else {
            handH * 0.05f + shortSide * 0.045f
        }
        val edgeInsetLeft = max(baseSide, safe.left + handH * 0.5f + minGutter)
        val edgeInsetRight = max(baseSide, safe.right + handH * 0.5f + minGutter)
        val edgeInsetOpposite = max(baseSide, safe.top + handH * 0.5f + minGutter)

        // Every fixed-size label on the felt shares the seat labels' scale. The
        // seat fit has two parts, and the gutter is the one that moves as a window
        // narrows: it is the band between the hand's outer face and the screen
        // edge, proportional to the short side. Fitting only the hand tile left
        // the other labels at desktop size until the very last sizes, while the
        // seat labels had already been shrinking for half the resize.
        val labelGutter =
            (min(edgeInsetLeft - safe.left, edgeInsetRight - safe.right) - handH * 0.5f)
                .coerceAtLeast(0f)
        val chromeScale = SeatLabelDrawer.tableLabelScale(
            handH = handH,
            gutter = labelGutter,
            tileGap = tileGap,
            activeScaleBoost = seatLabels.activeScaleBoost,
        )
        // The tutorial coach's Back / Next row is the only bottom band the hand
        // and the side labels must clear.
        val bottomReserve = bottomReservePx.coerceAtLeast(0f)
        // The human hand keeps the same gutter as the other edges. Its seat
        // label fits that gutter; only the coach adds a strip below.
        val edgeInsetBottom =
            max(baseSide, safe.bottom + bottomReserve + handH * 0.5f + minGutter)

        val maxReachTowardCenter = shortSide * 0.28f
        val firstDiscardRowY = handH * 0.5f + rowGap + discardH * 0.5f
        val maxDiscardRows = if (firstDiscardRowY > maxReachTowardCenter) {
            0
        } else {
            val extra = maxReachTowardCenter - firstDiscardRowY
            1 + (extra / (discardH + rowGap)).toInt().coerceAtLeast(0)
        }.coerceAtLeast(1)

        val maxRowWidth = screenW * 0.72f
        val discardsPerRow = run {
            var n = preferredDiscardsPerRow
            while (n > 3) {
                val w = n * discardW + (n - 1) * tileGap
                if (w <= maxRowWidth) break
                --n
            }
            n
        }

        return TableLayout(
            handW = handW,
            handH = handH,
            tileGap = tileGap,
            rowGap = rowGap,
            chromeScale = chromeScale,
            edgeInsetLeft = edgeInsetLeft,
            edgeInsetRight = edgeInsetRight,
            edgeInsetOpposite = edgeInsetOpposite,
            edgeInsetBottom = edgeInsetBottom,
            // The hands' outer edges span edgeInsetBottom … screenH − edgeInsetOpposite;
            // both rows are handH thick, so their inner edges share this midpoint.
            feltCenterY = (edgeInsetBottom + screenH - edgeInsetOpposite) * 0.5f,
            metrics = PlayerAreaLayout.Metrics(
                handTileW = handW,
                handTileH = handH,
                discardTileW = discardW,
                discardTileH = discardH,
                tileGap = tileGap,
                meldGap = meldGap,
                rowGap = rowGap,
                discardsPerRow = discardsPerRow,
                maxDiscardRows = maxDiscardRows,
                drawnGap = drawnGap,
            ),
            alongEdgePad = shortSide * 0.02f,
            bottomLabelReserve = bottomReserve,
        )
    }

    private fun collectSeatSlots(
        view: TableViewState,
        layout: TableLayout,
        screenW: Float,
        screenH: Float,
        humanDiscarding: Boolean,
        winnerSeat: Int,
    ): SeatSlots {
        val seatMetas = ArrayList<SeatMeta>(NUM_PLAYERS)
        val desired = ArrayList<DesiredSlot>(80)
        // World bounds per absolute seat, over that seat's hand / drawn slot / melds
        // only. Measured from the placements themselves, so whatever the tiles end
        // up doing (fit scaling, melds, the parked draw) the box already matches.
        // Indexed by absolute seat; absent entry = that seat laid out nothing.
        val seatBounds = HashMap<Int, FloatArray>(NUM_PLAYERS)
        var pendingDiscardX = Float.NaN
        var pendingDiscardY = Float.NaN
        var humanRowRightX = Float.NaN
        var humanRowCenterY = Float.NaN
        var winnerHandMinX = Float.POSITIVE_INFINITY
        var winnerHandMaxX = Float.NEGATIVE_INFINITY
        var winnerHandMinY = Float.POSITIVE_INFINITY
        var winnerHandMaxY = Float.NEGATIVE_INFINITY
        var winnerHandCount = 0

        for (relative in 0 until NUM_PLAYERS) {
            val seat = (view.mySeat + relative) % NUM_PLAYERS
            val side = SeatSide.forRelativeSeat(relative)
            val showFaces = relative == 0 || seat == winnerSeat || seat in revealedSeats
            val seatView = view.seats[seat]
            val river = seatView.river
            val (screenAlong, screenOrtho) = when (side) {
                SeatSide.Left, SeatSide.Right -> screenH to screenW
                SeatSide.Bottom, SeatSide.Opposite -> screenW to screenH
            }
            // A longer-side row lives between the two hands that face it. Its
            // budget is that band — the hands' inner edges — not the edge minus
            // the *taller* of the two insets at both ends: the bottom inset
            // must only hold back the bottom end, never shorten the row at the
            // top as well.
            val (nearEdge, farEdge) = when (side) {
                SeatSide.Left, SeatSide.Right ->
                    (layout.edgeInsetBottom + layout.handH * 0.5f) to
                        (screenH - layout.edgeInsetOpposite - layout.handH * 0.5f)
                SeatSide.Bottom, SeatSide.Opposite ->
                    (layout.edgeInsetLeft + layout.handH * 0.5f) to
                        (screenW - layout.edgeInsetRight - layout.handH * 0.5f)
            }
            val span = PlayerAreaLayout.availableAlongEdgeSpan(
                screenAlong = screenAlong,
                screenOrtho = screenOrtho,
                nearEdge = nearEdge,
                farEdge = farEdge,
                pad = layout.alongEdgePad,
                // A vertical seat shares its edge with the tutorial coach's
                // Back / Next band; the bottom hand does not, so only the
                // left/right rows keep that chrome clear.
                alongFloor = when (side) {
                    SeatSide.Left, SeatSide.Right -> layout.bottomLabelReserve
                    SeatSide.Bottom, SeatSide.Opposite -> 0f
                },
            )
            seatMetas += SeatMeta(seat, side, span.center)
            val edgeInset = layout.edgeInsetFor(side)
            val placements = PlayerAreaLayout.layoutFittingAlongEdge(
                seatView = seatView,
                showHandFaces = showFaces,
                metrics = layout.metrics,
                maxHalfSpan = span.half,
                ownerSeat = seat,
                handSelectable = relative == 0 && humanDiscarding,
            )
            val isWinDiscarder = view.handScore > 0 &&
                view.winSource in 0 until NUM_PLAYERS &&
                view.winnerSeat in 0 until NUM_PLAYERS &&
                view.winSource != view.winnerSeat &&
                seat == view.winSource
            val pendingRiverIndex =
                if ((view.isPendingDiscard || isWinDiscarder) &&
                    (seat == view.lastDiscarder || isWinDiscarder) &&
                    river.isNotEmpty()
                ) {
                    val byType = river.indexOfLast { it == view.lastDiscarded }
                    if (byType >= 0) byType else river.lastIndex
                } else {
                    -1
                }
            val pendingDiscardGlow = pendingRiverIndex >= 0
            val winOnDiscardGlow =
                pendingDiscardGlow &&
                    view.handScore > 0 &&
                    view.lastDiscarded in 0 until NUM_TILE_TYPES
            for (p in placements) {
                val (wx, wy) = side.localToWorld(
                    p.lx, p.ly, screenW, screenH, edgeInset, span.center,
                )
                val rot = side.rotationDeg + p.rotationOffsetDeg
                val riverGlow =
                    pendingDiscardGlow &&
                        p.region == PlayerAreaLayout.Region.River &&
                        p.regionIndex == pendingRiverIndex
                if (p.region != PlayerAreaLayout.Region.River) {
                    // World AABB of the rotated tile, so a sideways claim tile inside
                    // a meld widens the seat's area by the amount it really occupies.
                    // The empty draw reservation is included: it is the slot the area
                    // is defined by, and it is not a tile to reconcile below.
                    val rad = Math.toRadians((rot % 360f).toDouble())
                    val cos = abs(cos(rad)).toFloat()
                    val sin = abs(sin(rad)).toFloat()
                    val halfX = (p.w * 0.5f) * cos + (p.h * 0.5f) * sin
                    val halfY = (p.w * 0.5f) * sin + (p.h * 0.5f) * cos
                    val box = seatBounds[seat]
                    if (box == null) {
                        seatBounds[seat] = floatArrayOf(
                            wx - halfX,
                            wy - halfY,
                            wx + halfX,
                            wy + halfY,
                        )
                    } else {
                        box[0] = min(box[0], wx - halfX)
                        box[1] = min(box[1], wy - halfY)
                        box[2] = max(box[2], wx + halfX)
                        box[3] = max(box[3], wy + halfY)
                    }
                }
                if (p.emptyDrawReservation) continue
                if (riverGlow) {
                    pendingDiscardX = wx
                    pendingDiscardY = wy
                }
                if (relative == 0 && p.region != PlayerAreaLayout.Region.River) {
                    val right = wx + p.w * 0.5f
                    humanRowRightX =
                        if (humanRowRightX.isNaN()) right else max(humanRowRightX, right)
                    humanRowCenterY = wy
                }
                if (seat == winnerSeat && p.region != PlayerAreaLayout.Region.River) {
                    winnerHandMinX = min(winnerHandMinX, wx - p.w * 0.5f)
                    winnerHandMaxX = max(winnerHandMaxX, wx + p.w * 0.5f)
                    winnerHandMinY = min(winnerHandMinY, wy - p.h * 0.5f)
                    winnerHandMaxY = max(winnerHandMaxY, wy + p.h * 0.5f)
                    winnerHandCount++
                }
                val focusGlow = when {
                    riverGlow && winOnDiscardGlow -> TileFocusGlow.WinDiscard
                    seat == winnerSeat &&
                        p.region != PlayerAreaLayout.Region.River -> TileFocusGlow.WinHand
                    riverGlow -> TileFocusGlow.Soft
                    p.isDrawnSlot -> TileFocusGlow.Soft
                    else -> TileFocusGlow.None
                }
                val hit = boardHighlight.match(
                    human = relative == 0,
                    region = p.region,
                    tileType = p.tileType,
                    pendingDiscard = riverGlow,
                    open = p.open,
                )
                val tutorialOutline = when {
                    hit == null -> TileFocusGlow.None
                    (hit.glow == TileFocusGlow.Prompt || hit.glow == TileFocusGlow.Secondary) && overlayLeaderActive ->
                        TileFocusGlow.None
                    else -> hit.glow
                }
                desired += DesiredSlot(
                    pose = TilePose(wx, wy, rot, p.w, p.h, open = p.open),
                    tileType = p.tileType,
                    seatIndex = seat,
                    slotKey = slotKeyFor(seat, p),
                    selectableHand = p.selectableHand,
                    isDrawnSlot = p.isDrawnSlot,
                    focusGlow = focusGlow,
                    tutorialOutline = tutorialOutline,
                    tutorialGroupKey =
                        if (tutorialOutline == TileFocusGlow.None || hit == null) 0
                        else hit.groupIndex,
                )
            }
        }

        val (winnerHandAnchorX, winnerHandAnchorY) = if (winnerHandCount > 0 && winnerSeat in 0 until NUM_PLAYERS) {
            val relWinner = (winnerSeat - view.mySeat + NUM_PLAYERS) % NUM_PLAYERS
            val winSide = SeatSide.forRelativeSeat(relWinner)
            val midX = (winnerHandMinX + winnerHandMaxX) * 0.5f
            val midY = (winnerHandMinY + winnerHandMaxY) * 0.5f
            when (winSide) {
                SeatSide.Bottom -> midX to winnerHandMaxY
                SeatSide.Opposite -> midX to winnerHandMinY
                SeatSide.Left -> winnerHandMaxX to midY
                SeatSide.Right -> winnerHandMinX to midY
            }
        } else {
            Float.NaN to Float.NaN
        }

        return SeatSlots(
            metas = seatMetas,
            desired = desired,
            seatBounds = seatBounds,
            pendingDiscardX = pendingDiscardX,
            pendingDiscardY = pendingDiscardY,
            humanRowRightX = humanRowRightX,
            humanRowCenterY = humanRowCenterY,
            winnerHandAnchorX = winnerHandAnchorX,
            winnerHandAnchorY = winnerHandAnchorY,
        )
    }

    // ── Frame helpers ────────────────────────────────────────────────────────

    private fun refreshRecommendation(
        legalChoices: List<Choice>,
        isHumanInteractive: Boolean,
        hintsEnabled: Boolean,
    ) {
        if (!hintsEnabled || interact != TableInteract.Play || !isHumanInteractive) {
            hintUi = HintUi.Off
            mcHintArms = emptyList()
            return
        }
        val current = hintUi
        if (current is HintUi.Off && mcHintArms.isEmpty()) return

        if (mcHintArms.isNotEmpty()) {
            mcHintArms = mcHintArms.filter { arm ->
                legalChoices.any { it.kind == arm.choice.kind && it.payload == arm.choice.payload }
            }
            if (mcHintArms.isEmpty()) {
                hintUi = HintUi.Off
                return
            }
        }

        if (current is HintUi.Off) return

        val source = current.highlightedChoices()
        val still = source.filter { rec ->
            legalChoices.any { it.kind == rec.kind && it.payload == rec.payload }
        }
        hintUi = when {
            still.isEmpty() -> HintUi.Off
            current is HintUi.Exhausted -> HintUi.Exhausted(still)
            else -> HintUi.Active(still)
        }
    }

    private fun applySnapIfNeeded(screenW: Float, screenH: Float) {
        val sizeChanged =
            lastLayoutW >= 0f && (screenW != lastLayoutW || screenH != lastLayoutH)
        lastLayoutW = screenW
        lastLayoutH = screenH
        val snap = pendingSnapLayout || sizeChanged
        pendingSnapLayout = false
        if (snap) {
            board.snapAllToTargets()
            activeSeatSweep.snap()
        }
    }

    private fun updateHandHitsAndHover(pointerWorldX: Float, pointerWorldY: Float) {
        for ((visualId, worldX, worldY, w, h, tileType, isDrawnSlot, slotIndex) in board.buildHandHits()) {
            humanHandHits += HandHit(
                worldX = worldX,
                worldY = worldY,
                w = w,
                h = h,
                tileType = tileType,
                isDrawnSlot = isDrawnSlot,
                visualId = visualId,
                slotIndex = slotIndex,
            )
        }
        // One hit authority for the whole board: the topmost visible tile under the
        // pointer, whatever its region or whose turn it is.
        val hit = if (pointerWorldX.isNaN() || pointerWorldY.isNaN()) {
            null
        } else {
            board.hitAt(pointerWorldX, pointerWorldY)
        }
        // Only a selectable hand tile lifts / tints / magnifies / clicks.
        if (hit != null && hit.selectableHand) {
            hoveredVisualId = hit.visualId
            hoveredHandIndex = humanHandHits.indexOfFirst { it.visualId == hit.visualId }
        }
        // Light every other copy of the face under the pointer — own hand, own
        // melds, opponents' open melds, every river. Soft / Win halos keep priority.
        hoveredMatchType = hit?.tileType ?: -1
        if (hoveredMatchType >= 0) board.highlightMatches(hoveredMatchType)
    }

    private fun drawBoardLayers(
        batch: SpriteBatch,
        recommendedTileTypes: Set<Int>,
        drawSettled: Boolean = true,
        drawFlying: Boolean = false,
        drawTiles: Boolean = true,
        drawOutlines: Boolean = true,
        filter: ((VisualTile) -> Boolean)? = null,
    ) {
        board.draw(
            batch = batch,
            drawer = drawer,
            hoveredVisualId = hoveredVisualId,
            recommendedTileTypes = recommendedTileTypes,
            readyDiscardTypes = readyDiscardTypes,
            hoverLiftFraction = hoverLiftFraction,
            hoverScale = hoverScale,
            drawSettled = drawSettled,
            drawFlying = drawFlying,
            drawTiles = drawTiles,
            drawOutlines = drawOutlines,
            filter = filter,
        )
    }

    override fun dispose() {
        activeSeatTriangle.dispose()
        winCurveDrawer.dispose()
        claimPanel.dispose()
        uiChip.dispose()
        assets.dispose()
    }
}

/**
 * Maps the session-owned [DiscardOrigin] onto the render table's slot vocabulary.
 * A negative slot is the [DiscardOrigin.DRAWN] sentinel.
 */
internal fun DiscardOrigin.toSlotKey(): SlotKey =
    if (slot < 0) {
        SlotKey(SlotKind.Drawn, seat, 0)
    } else {
        SlotKey(SlotKind.Hand, seat, slot)
    }

