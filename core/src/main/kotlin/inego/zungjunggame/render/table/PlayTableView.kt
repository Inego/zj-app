package inego.zungjunggame.render.table

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.engine.*
import inego.zungjunggame.hints.McHintArmSnapshot
import inego.zungjunggame.hints.OmniscientHintArmSnapshot
import inego.zungjunggame.input.InputProfile
import inego.zungjunggame.platform.SafeArea
import inego.zungjunggame.render.assets.TileAssets
import inego.zungjunggame.render.claim.ClaimActionPanel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.hint.HintButtonOverlay
import inego.zungjunggame.render.hint.HintUi
import inego.zungjunggame.render.hint.McHintLabels
import inego.zungjunggame.render.hint.OmniscientBreakdownPopup
import inego.zungjunggame.render.hint.OmniscientHintLabels
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
import inego.zungjunggame.ui.Rect
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
    private val ownsAssets: Boolean = true,
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
     * The hand tile under a held finger while the touch loupe shows its face
     * above the finger; -1 on desktop or when nothing is pressed. That board
     * copy fades and stays at its row pose (no hover lift), so the magnified
     * face is the one the eye reads.
     */
    private var pressedVisualId: Int = -1

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
     * Latest Omniscient per-option best scores and pattern breakdowns (refreshed ~1 s).
     * Empty when Omniscient mode is off.
     */
    var omniscientHintArms: List<OmniscientHintArmSnapshot> = emptyList()
        private set

    /** Latest Omniscient search speed in evals/s (0 when idle or unmeasured). */
    var omniscientEvalsPerSec: Float = 0f
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
     * deliver one. The label is static now — one size, ink only — and the turn is
     * carried by [ActiveSeatSweep] and the felt panel.
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
        val anchorY = layout.humanRowTopY + layout.rowGap + panelTileH * 1.15f
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
                handH = layout.humanMetrics.handTileH,
                screenW = screenW,
                screenH = screenH,
                edgeInsetBottom = layout.humanEdgeInsetBottom,
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
     * leader stays on the face. A tile held under the touch loupe is not lifted
     * (the loupe is the magnified copy), so its anchor is the row pose.
     */
    fun highlightedHandAnchor(): HandHit? {
        val types = boardHighlight.handAnchorTypes()
        if (types.isEmpty()) return null
        val hit = humanHandHits.firstOrNull { it.tileType in types } ?: return null
        if (hit.visualId != hoveredVisualId || hit.visualId == pressedVisualId) return hit
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
        val anchorY = layout.humanRowTopY + layout.rowGap + panelTileH * 1.15f
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
        omniscientHintArms = emptyList()
        omniscientEvalsPerSec = 0f
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

    /** Replace per-option Omniscient score / breakdown snapshots (main thread). */
    fun setOmniscientHintArms(arms: List<OmniscientHintArmSnapshot>, evalsPerSec: Float = 0f) {
        omniscientHintArms = arms
        omniscientEvalsPerSec = if (arms.isEmpty()) 0f else evalsPerSec
    }

    /**
     * Currently hovered legal choice on the board (hand tile discard or claim
     * button), or null when nothing is hovered. Used to prioritize hovered
     * options in Omniscient search.
     */
    fun hoveredChoiceForHint(): Choice? {
        claimPanel.hoveredChoice()?.let { return it }
        return humanHandHits.getOrNull(hoveredHandIndex)?.let { Choice.discard(it.tileType) }
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
        decisionPreparing: Boolean = false,
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
        val anchorY = layout.humanRowTopY + layout.rowGap + panelTileH * 1.15f
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
                // The Opposite label keeps its own gutter below the portrait HUD
                // band, not the bare safe top the band hangs from.
                SeatSide.Opposite -> layout.oppositeGutterTop
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
                handH =
                    if (side == SeatSide.Bottom) layout.humanMetrics.handTileH else layout.handH,
                tileGap = layout.tileGap,
                seatScores = inputs.seatScores,
                humanSeat = inputs.humanSeat,
                safeInset = safeInset,
                bottomReserve = layout.bottomLabelReserve,
                // On a landscape Android window the human's gutter went to the
                // tiles, so its label floats above the row instead of sitting in
                // a gutter that no longer exists (or hanging below the row and
                // cropping at the screen bottom).
                aboveRow = if (side == SeatSide.Bottom && layout.humanLabelAbove) {
                    // Right-align with the row as it is really laid out: the last
                    // meld tile when melds are open, else the parked draw slot
                    // (even while only reserved) or the last hand tile. A wide
                    // window can leave the height-capped row short of the band, so
                    // the nominal band edge is not where the tiles end.
                    val rightX = seats.humanRightmostTileX.takeUnless { it.isNaN() }
                        ?: layout.humanLabelRightX
                    SeatLabelDrawer.AboveRow(
                        rightX = rightX,
                        rowEdgeY = layout.humanRowTopY,
                        // The width budget stays the band's, not the measured edge's:
                        // a half-dealt row is narrow, but the label has the whole
                        // strip above the row to itself and must not be squeezed
                        // (and re-ellipsized) as the hand fills.
                        maxWidth = layout.humanLabelWidthBudget,
                    )
                } else {
                    null
                },
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
                omniscientHintArms = if (inputs.hintsEnabled && live) omniscientHintArms else emptyList(),
                discardedTileX = Float.NaN,
                discardedTileY = Float.NaN,
                interactive = live,
                decisionTimeLeftMs = decisionTimeLeftMs,
                decisionSlackMs = decisionSlackMs,
                decisionBaseMs = decisionBaseMs,
                preparing = decisionPreparing,
            )
        } else {
            claimPanel.clear()
        }

        if (inputs.hintsEnabled && live) {
            if (omniscientHintArms.isNotEmpty()) {
                OmniscientHintLabels.drawAboveHands(
                    batch = batch,
                    arms = omniscientHintArms,
                    hits = humanHandHits,
                    glyphLayout = glyphLayout,
                ) { hit ->
                    when {
                        hit.visualId == pressedVisualId -> hit.worldY + hit.h * 0.5f
                        hit.visualId == hoveredVisualId ->
                            hit.worldY + hit.h * (hoverLiftFraction + 0.5f * hoverScale)
                        hit.tileType in recDiscardTypes ->
                            hit.worldY + hit.h * (hoverLiftFraction * 0.85f + 0.5f * 1.05f)
                        else -> hit.worldY + hit.h * 0.5f
                    }
                }
            } else {
                McHintLabels.drawAboveHands(batch, mcHintArms, humanHandHits, glyphLayout)
            }
        }

        if (PatternlessNotice.isApplicable(view, inputs.allowChickenHands) && live) {
            val handCenterX = if (humanHandHits.isNotEmpty()) {
                (humanHandHits.minOf { it.worldX - it.w * 0.5f } + humanHandHits.maxOf { it.worldX + it.w * 0.5f }) * 0.5f
            } else {
                screenW * 0.5f
            }
            val rowCenterY = if (seats.humanRowCenterY.isNaN()) {
                layout.humanEdgeInsetBottom
            } else {
                seats.humanRowCenterY
            }
            val handTop = if (seats.humanRowCenterY.isNaN()) {
                layout.humanRowTopY
            } else {
                rowCenterY + layout.humanMetrics.handTileH * 0.5f
            }
            val gap = if (inputs.hintsEnabled && (mcHintArms.isNotEmpty() || omniscientHintArms.isNotEmpty())) {
                Fonts.px(26f)
            } else {
                Fonts.px(10f)
            }
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
                handH = layout.humanMetrics.handTileH,
                screenW = screenW,
                screenH = screenH,
                edgeInsetBottom = layout.humanEdgeInsetBottom,
                pointerWorldX = hoverX,
                pointerWorldY = hoverY,
                mode = when (hintUi) {
                    HintUi.Off -> HintButtonOverlay.Mode.Idle
                    is HintUi.Active -> HintButtonOverlay.Mode.Active
                    is HintUi.Exhausted -> HintButtonOverlay.Mode.Exhausted
                },
                metricText = if (omniscientHintArms.isNotEmpty()) {
                    OmniscientHintLabels.formatEvalsPerSec(omniscientEvalsPerSec)
                } else {
                    null
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
                decisionPreparing = decisionPreparing,
                connectionStatus = connectionStatus,
                feedback = statusFeedback,
                shutdownPhase = shutdownPhase,
                shutdownRemainingMs = shutdownRemainingMs,
                shutdownMessage = shutdownMessage,
            )
        }

        val hoveredHand = humanHandHits.getOrNull(hoveredHandIndex)
        var magnifierTopY = Float.NaN
        val isTouch = InputProfile.detect() == InputProfile.Touch
        if (hoveredHand != null && !hoverX.isNaN() && !hoverY.isNaN()) {
            val waits = inputs.discardWaits.waitsFor(hoveredHand.tileType)
            if (isTouch) {
                magnifierTopY = TouchTileMagnifier.draw(
                    batch = batch,
                    drawer = drawer,
                    tileType = hoveredHand.tileType,
                    anchorX = pointerWorldX,
                    anchorY = pointerWorldY,
                    screenW = screenW,
                    screenH = screenH,
                    handW = layout.humanMetrics.handTileW,
                    handH = layout.humanMetrics.handTileH,
                    handGap = layout.tileGap,
                    // The grown Android-landscape row is already large; the loupe
                    // and its wait row must not scale with it (see
                    // TouchTileMagnifier.ENLARGED_SCALE).
                    enlargedHand = layout.humanRowEnlarged,
                    waits = waits,
                    showDiscardFace = true,
                )
            } else if (waits.isNotEmpty()) {
                magnifierTopY = TouchTileMagnifier.draw(
                    batch = batch,
                    drawer = drawer,
                    tileType = hoveredHand.tileType,
                    anchorX = hoveredHand.worldX,
                    anchorY = hoveredHand.worldY +
                        hoveredHand.h * (hoverLiftFraction + 0.5f * hoverScale),
                    screenW = screenW,
                    screenH = screenH,
                    handW = layout.humanMetrics.handTileW,
                    handH = layout.humanMetrics.handTileH,
                    handGap = layout.tileGap,
                    enlargedHand = layout.humanRowEnlarged,
                    waits = waits,
                    showDiscardFace = false,
                )
            }
        }

        if (inputs.hintsEnabled && live && omniscientHintArms.isNotEmpty() &&
            !hoverX.isNaN() && !hoverY.isNaN()
        ) {
            val hoveredClaim = claimPanel.hoveredButtonHit()
            if (hoveredClaim != null) {
                val arm = omniscientHintArms.firstOrNull {
                    it.choice.kind == hoveredClaim.choice.kind &&
                        it.choice.payload == hoveredClaim.choice.payload
                }
                if (arm != null) {
                    OmniscientBreakdownPopup.draw(
                        batch = batch,
                        arm = arm,
                        anchorX = hoveredClaim.cx,
                        anchorTopY = hoveredClaim.cy + hoveredClaim.h * 0.5f + Fonts.px(24f),
                        screenW = screenW,
                        screenH = screenH,
                        glyphLayout = glyphLayout,
                    )
                }
            } else if (hoveredHand != null) {
                val arm = omniscientHintArms.firstOrNull {
                    it.choice.kind == ChoiceKind.Discard && it.choice.payload == hoveredHand.tileType
                }
                if (arm != null) {
                    val baseTop = hoveredHand.worldY +
                        hoveredHand.h * (hoverLiftFraction + 0.5f * hoverScale)
                    val anchorTopY = if (!magnifierTopY.isNaN()) {
                        magnifierTopY
                    } else {
                        baseTop + Fonts.px(24f)
                    }
                    OmniscientBreakdownPopup.draw(
                        batch = batch,
                        arm = arm,
                        anchorX = if (isTouch) pointerWorldX else hoveredHand.worldX,
                        anchorTopY = anchorTopY,
                        screenW = screenW,
                        screenH = screenH,
                        glyphLayout = glyphLayout,
                    )
                }
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
        /**
         * Distance from the top edge to the Opposite seat's own label gutter.
         * Normally the safe top; in a portrait board with a top HUD it starts
         * below the HUD band, so the wind label sits under the rule badge rather
         * than on the same line.
         */
        val oppositeGutterTop: Float,
        /** The portrait top-HUD band, or null in landscape / without a HUD. */
        val hud: HudBand?,
        /**
         * Centre of the felt between the top and bottom hand rows. The wall
         * marker and the side seats live here, not on the raw pane centre: the
         * bottom edge also carries the safe inset, the coach reserve, and the
         * portrait credit strip, so the play area is not vertically symmetric.
         */
        val feltCenterY: Float,
        val metrics: PlayerAreaLayout.Metrics,
        /**
         * The human seat's own metrics. On a landscape Android window the bottom
         * gutter is a whole label band that only the human needs, so it is spent
         * on taller human tiles (see [computeTableLayout]); the three felt seats
         * keep [metrics] and every anchor above the row stays where it was.
         * Equal to [metrics] wherever the row did not grow.
         */
        val humanMetrics: PlayerAreaLayout.Metrics,
        /** The bottom row's centreline. */
        val humanEdgeInsetBottom: Float,
        /**
         * Inner (felt-facing) edge of the human row. The grown row keeps this
         * edge (or stops short of it, when the width band is the limit) and takes
         * the reclaimed gutter below, so the human's river, the claim row, the
         * HINT chip, the wall marker and every opponent row keep their place.
         */
        val humanRowTopY: Float,
        /**
         * True when the human row spent the reclaimed bottom gutter on taller
         * tiles. The touch loupe and its wait row use it to keep their own size
         * instead of scaling with the enlarged row.
         */
        val humanRowEnlarged: Boolean = false,
        /** True when the human label floats above its row instead of in the bottom gutter. */
        val humanLabelAbove: Boolean = false,
        /**
         * Nominal world x for the above-row label's right edge: the band's far
         * edge. The paint path prefers the row's **measured** rightmost tile
         * ([SeatSlots.humanRightmostTileX]); this stands in only when the row laid
         * nothing out. NaN while the label keeps its gutter.
         */
        val humanLabelRightX: Float = Float.NaN,
        /**
         * How far left of the nominal right edge ([humanLabelRightX]) the
         * above-row label may paint: the band's span to the screen centre. Kept
         * off the measured edge, which is narrower while a hand is being dealt,
         * so the width fit does not ellipsize a label for a row that is still
         * filling.
         */
        val humanLabelWidthBudget: Float = 0f,
        val alongEdgePad: Float,
        /**
         * Along-edge pad for the human (Bottom) row: in landscape at least half
         * of the human tile width so the bottom row never crowds the Left and
         * Right hands; otherwise [alongEdgePad].
         */
        val humanAlongEdgePad: Float,
        /**
         * Bottom band the human seat label must clear while it keeps the bottom
         * gutter: the tutorial coach reserve. Zero outside that coach.
         */
        val bottomLabelReserve: Float,
    ) {
        fun edgeInsetFor(side: SeatSide): Float = when (side) {
            SeatSide.Bottom -> humanEdgeInsetBottom
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
        /**
         * Right edge of the human's rightmost tile or slot — the last meld tile
         * when melds are open, else the parked draw slot (even while it is only
         * reserved) or the last hand tile. NaN when the human row laid nothing
         * out. The above-row seat label right-aligns to this, so claiming a meld
         * (which widens the row) carries the label with the tiles.
         */
        val humanRightmostTileX: Float = Float.NaN,
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

    /**
     * The square a top-right control (the board menu) floats in, in this table's
     * coordinates, or null where nothing floats there.
     *
     * The Right seat's column yields to it **horizontally**. Its row runs the
     * full height of the short edge, past the corner the square occupies; if the
     * row ended above the square instead, the whole hand would fit-scale to the
     * shorter span (a waiting hand does not fit what is left of a landscape
     * edge). Moving the column in costs a few dp of felt, which a landscape pane
     * has. A square that sits in the gutter, or wholly left of the column, moves
     * nothing.
     */
    var topRightChrome: Rect? = null

    /**
     * True when this board paints its top HUD line (the rule badge and the hand
     * counter) — free play and online, never a tutorial board. Only then does a
     * portrait layout reserve the HUD's band below the menu and move the
     * Opposite seat under it; the tutorial shell has a menu over the board but
     * no HUD, so reserving a line there would shrink the lesson for nothing.
     */
    var topHud: Boolean = false

    private fun safeInsetSnapshot(): SafeArea.Insets = safeInsets ?: SafeArea.current()

    /**
     * The portrait HUD's reserved band: one line under the menu square and above
     * the Opposite seat's label gutter, running from safe edge to safe edge.
     *
     * [topY] is the cap-top world y the presenter draws at. [bottomY] is the
     * band's lower edge (the full line box, descenders included); the table
     * starts the Opposite seat below it. [leftX] / [rightX] are the window's
     * safe edges, so in portrait the badge spans the screen under the button
     * instead of stopping at the side tile bands.
     */
    data class HudBand(
        val leftX: Float,
        val rightX: Float,
        val topY: Float,
        val bottomY: Float,
    )

    /**
     * Nominal felt geometry for chrome that paints around the table, at
     * [screenW] x [screenH].
     *
     * [labelScale] is the shared felt-label scale: callers outside the paint path
     * (the free-play HUD badge) use it instead of their own size rule, so every
     * label on the felt shrinks together. It is the scale the seat labels paint at
     * for this layout — hand-tile cap and gutter fit included — not just the tile
     * cap, which stays at 1 until the window is nearly at its narrowest.
     *
     * [leftBandInnerX] and [rightBandInnerX] are the screen x of the Left and
     * Right seats' **nominal** tile bands' inner faces, derived from the edge
     * inset and the hand height rather than from the fitted placements. A row is
     * always fit-scaled while a hand is live, so its visible bounds move when a
     * meld is claimed; these anchors do not. Chrome that stops at them is clear
     * of the side columns, and the Opposite row never reaches past them.
     *
     * [hud] is set only in portrait, where the top HUD line needs its own band
     * under the menu. Landscape has no band: the line sits in the strip above
     * the Opposite row and anchors to the two band faces instead.
     */
    data class FeltChrome(
        val labelScale: Float,
        val leftBandInnerX: Float,
        val rightBandInnerX: Float,
        val hud: HudBand? = null,
    )

    fun feltChrome(screenW: Float, screenH: Float): FeltChrome {
        val layout = computeTableLayout(screenW, screenH)
        return FeltChrome(
            labelScale = layout.chromeScale,
            leftBandInnerX = layout.edgeInsetLeft + layout.handH * 0.5f,
            rightBandInnerX = screenW - (layout.edgeInsetRight + layout.handH * 0.5f),
            hud = layout.hud,
        )
    }

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
        var edgeInsetRight = max(baseSide, safe.right + handH * 0.5f + minGutter)

        // ── The corner menu takes width from the Right seat, never height ──
        // A landscape pane puts the Left and Right seats on its short edges, and
        // the Right row then runs the full height of the edge — through the
        // corner the menu floats over. Where the square straddles the tile
        // column, move the column in until it clears: the row keeps its span and
        // its size, and only felt is spent. A square that fits the gutter, or
        // sits wholly left of the column, moves nothing. Portrait answers the
        // same square through the table's top inset instead
        // ([innego.zungjunggame.features.board.BoardMenuChrome.tableInsets]), and
        // its Right seat is on a long edge, where this would eat the felt
        // between the hands for no row height.
        val menu = topRightChrome
        if (screenW > screenH && menu != null) {
            val columnInner = screenW - edgeInsetRight - handH * 0.5f
            val columnOuter = screenW - edgeInsetRight + handH * 0.5f
            if (menu.left < columnOuter && menu.right > columnInner) {
                // Leave the plate's rim a little daylight from the tiles.
                val clear = columnOuter - (menu.left - Fonts.px(3f))
                if (clear > 0f) edgeInsetRight += clear
            }
        }

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
        )

        // ── Portrait: the top HUD gets its own line under the menu ──
        // A portrait pane puts the side seats on its long edges, so the strip
        // above the Opposite row is free from edge to edge — but the menu owns
        // the top-right corner. The badge therefore hangs below the menu (the
        // table's `top`, which tableInsets has already put under that square)
        // and spans the window's safe edges. The Opposite seat's label and row
        // then start below the whole line, so the wind label can never share a
        // line with the rule badge and the counter. Landscape keeps its strip:
        // there the side hands occupy the short edges and the HUD already
        // anchors to their band faces.
        val hud = if (topHud && screenH > screenW) {
            val gap = Fonts.px(8f)
            val edgePad = Fonts.px(16f)
            // The menu band is `safe.top` where tableInsets reserved it, but on a
            // pane only just taller than wide it need not be; take the deeper of
            // the two so the line always hangs below the button and the cutout.
            val topFromTop = max(
                safe.top,
                menu?.let { screenH - it.bottom } ?: 0f,
            ) + gap
            HudBand(
                leftX = max(edgePad, safe.left + edgePad),
                rightX = screenW - max(edgePad, safe.right + edgePad),
                topY = screenH - topFromTop,
                bottomY = screenH - topFromTop - Fonts.label.lineHeight * chromeScale,
            )
        } else {
            null
        }
        // Where the Opposite seat's own gutter starts: below the HUD line when
        // one paints, otherwise the safe top. Its label is centred in the gutter
        // between this and the row's felt-facing edge.
        val oppositeGutterTop = hud?.let { screenH - it.bottomY + Fonts.px(8f) } ?: safe.top
        val edgeInsetOpposite = max(
            baseSide,
            oppositeGutterTop + handH * 0.5f + minGutter,
        )
        // The tutorial coach's Back / Next row is the only bottom band the hand
        // and the side labels must clear.
        val bottomReserve = bottomReservePx.coerceAtLeast(0f)
        val alongEdgePad = shortSide * 0.02f
        // The human hand keeps the same gutter as the other edges, and its seat
        // label fits that gutter while the row does not grow. Only the coach adds
        // a strip below.
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

        val metrics = PlayerAreaLayout.Metrics(
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
        )

        // ── The human row spends the bottom gutter on tiles, not on the label ──
        // On a landscape Android window the bottom gutter is a full label band
        // (`minGutter` ≈ 1.05 × handH) that the other three seats never need: their
        // labels sit in side gutters, and a landscape side gutter is as wide as the
        // screen is tall. The human's label floats above the row instead
        // ([humanLabelAbove]), which leaves the strip below the row as a small edge
        // cushion (or the system bar itself) the tiles can fill. The row never
        // crosses its old **inner** edge,
        // so the river, the claim row, the HINT chip, the wall marker and every
        // opponent row keep the geometry they had.
        val humanRowTopY = edgeInsetBottom + handH * 0.5f
        // A permanent system strip at the bottom (the navigation bar, a caption
        // bar) is itself the margin: the tiles may sit right on top of it, and a
        // felt cushion there would only float the row off the bar. Where the strip
        // is on a side instead (a landscape three-button nav bar lives on the
        // right), the screen edge is the real outer edge, so keep the cushion.
        val humanCushion = if (safe.bottom > 0f) 0f else shortSide * 0.03f
        val humanOuterY = safe.bottom + bottomReserve + humanCushion
        val humanFillsGutter = isAndroid && screenW > screenH
        // The human row lives in the band between the two side hands, exactly as
        // [collectSeatSlots] measures it; its height is capped there so the base
        // thirteen-tile hand never has to fit-scale. Claimed melds still do.
        val humanBand = PlayerAreaLayout.availableAlongEdgeSpan(
            screenAlong = screenW,
            screenOrtho = screenH,
            nearEdge = edgeInsetLeft + handH * 0.5f,
            farEdge = screenW - edgeInsetRight - handH * 0.5f,
            pad = 0f,
        )
        val humanBandW = humanBand.half * 2f
        // 13 sorted tiles + the parked draw slot: 14 tile widths, 12 gaps and the
        // draw gap. In landscape each end also keeps at least half a player tile
        // width clear of the Left and Right hands (+1 tile width total), or
        // `alongEdgePad` on each side if larger.
        val humanByWidth = minOf(
            (humanBandW - 2f * alongEdgePad - 12f * tileGap - drawnGap) / (14f * tileAspect),
            (humanBandW - 12f * tileGap - drawnGap) / (15f * tileAspect),
        )
        val humanFilled = if (humanFillsGutter) {
            minOf(humanRowTopY - humanOuterY, humanByWidth)
        } else {
            handH
        }
        val humanHandH = max(humanFilled, handH)
        val humanGrew = humanHandH > handH
        val humanTopY = if (humanGrew) humanOuterY + humanHandH else humanRowTopY
        val humanMetrics = if (humanGrew) {
            metrics.copy(handTileW = humanHandH * tileAspect, handTileH = humanHandH)
        } else {
            metrics
        }
        val humanAlongEdgePad = if (screenW > screenH) {
            max(alongEdgePad, humanMetrics.handTileW * 0.5f)
        } else {
            alongEdgePad
        }
        val humanLabelAbove = humanGrew
        // The above-row label aligns to the row's **measured** rightmost tile
        // ([SeatSlots.humanRightmostTileX]); this is the nominal band edge it falls
        // back to only when the row laid nothing out.
        val humanLabelRightX =
            if (humanLabelAbove) screenW - edgeInsetRight - handH * 0.5f - humanAlongEdgePad
            else Float.NaN

        return TableLayout(
            handW = handW,
            handH = handH,
            tileGap = tileGap,
            rowGap = rowGap,
            chromeScale = chromeScale,
            edgeInsetLeft = edgeInsetLeft,
            edgeInsetRight = edgeInsetRight,
            edgeInsetOpposite = edgeInsetOpposite,
            oppositeGutterTop = oppositeGutterTop,
            hud = hud,
            // The hands' inner edges are what the felt is measured between: the
            // opposite row's face and the human row's face.
            feltCenterY =
                (humanTopY + (screenH - edgeInsetOpposite - handH * 0.5f)) * 0.5f,
            metrics = metrics,
            humanMetrics = humanMetrics,
            humanEdgeInsetBottom =
                if (humanGrew) humanOuterY + humanHandH * 0.5f else edgeInsetBottom,
            humanRowTopY = humanTopY,
            humanRowEnlarged = humanGrew,
            humanLabelAbove = humanLabelAbove,
            humanLabelRightX = humanLabelRightX,
            humanLabelWidthBudget =
                if (humanLabelAbove) (humanLabelRightX - screenW * 0.5f).coerceAtLeast(0f)
                else 0f,
            alongEdgePad = alongEdgePad,
            humanAlongEdgePad = humanAlongEdgePad,
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
                    layout.humanRowTopY to
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
                pad = if (side == SeatSide.Bottom) layout.humanAlongEdgePad else layout.alongEdgePad,
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
                // The human row may be taller than the felt rows (the reclaimed
                // bottom gutter), so it brings its own metrics.
                metrics = if (relative == 0) layout.humanMetrics else layout.metrics,
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

        // The label above the human row right-aligns to the row's real edge, not
        // the nominal band: the rightmost meld tile when melds are open, else the
        // parked draw slot (even while only reserved), else the last hand tile.
        // [seatBounds] already measured exactly that extent, rotation included.
        val humanRightmostTileX = seatBounds[view.mySeat]?.get(2) ?: Float.NaN

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
            humanRightmostTileX = humanRightmostTileX,
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
            omniscientHintArms = emptyList()
            omniscientEvalsPerSec = 0f
            return
        }
        val current = hintUi
        if (current is HintUi.Off && mcHintArms.isEmpty() && omniscientHintArms.isEmpty()) return

        if (mcHintArms.isNotEmpty()) {
            mcHintArms = mcHintArms.filter { arm ->
                legalChoices.any { it.kind == arm.choice.kind && it.payload == arm.choice.payload }
            }
            if (mcHintArms.isEmpty()) {
                hintUi = HintUi.Off
                return
            }
        }
        if (omniscientHintArms.isNotEmpty()) {
            omniscientHintArms = omniscientHintArms.filter { arm ->
                legalChoices.any { it.kind == arm.choice.kind && it.payload == arm.choice.payload }
            }
            if (omniscientHintArms.isEmpty()) {
                omniscientEvalsPerSec = 0f
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
        // The touch loupe draws that same tile's face above the finger, so the
        // copy under the finger fades. Tied to the loupe's own conditions
        // (selectable hand hit, found in the hit list, press-hover profile) so
        // the source never dims without a magnified face to replace it.
        pressedVisualId =
            if (hit != null && hit.selectableHand && hoveredHandIndex >= 0 &&
                InputProfile.detect().usesPressHover
            ) {
                hit.visualId
            } else {
                -1
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
            pressedVisualId = pressedVisualId,
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
        if (ownsAssets) assets.dispose()
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

