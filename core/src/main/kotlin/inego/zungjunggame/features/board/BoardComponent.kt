package inego.zungjunggame.features.board

import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.persist.UiPreferences
import inego.zungjunggame.platform.SafeArea
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.table.HandHit
import inego.zungjunggame.render.table.PlayTableView
import inego.zungjunggame.render.ui.CloseButton
import inego.zungjunggame.render.ui.ParkRelease
import inego.zungjunggame.runtime.appModel
import inego.zungjunggame.tutorial.slide.layout.SlideChromeLayout
import inego.zungjunggame.session.hosts.ConnectionStatus
import inego.zungjunggame.session.snapshots.SessionSnapshot
import inego.zungjunggame.session.table.TableViewState
import inego.zungjunggame.state.StateSlot
import inego.zungjunggame.ui.Canvas
import inego.zungjunggame.ui.Component
import inego.zungjunggame.ui.ComponentAction
import inego.zungjunggame.ui.ComponentId
import inego.zungjunggame.ui.Constraints
import inego.zungjunggame.ui.Frame
import inego.zungjunggame.ui.GestureBridge
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.HitRegions
import inego.zungjunggame.ui.Layout
import inego.zungjunggame.ui.Rect
import inego.zungjunggame.ui.stack.Route
import inego.zungjunggame.ui.stack.SessionKind

/** One interactive board target: its world [rect], the [action] it raises, and overlap [rank]. */
data class BoardRegion(val rect: Rect, val action: ComponentAction, val rank: Int)

/**
 * What one board layout produced: the interactive [regions] for the input
 * pipeline and the named [anchors] a layer above reads from the board's
 * `Layout.regions`.
 */
data class BoardMeasurement(
    val regions: List<BoardRegion>,
    val anchors: Map<String, Rect> = emptyMap(),
)

/**
 * Measures the board's interactive targets for a frame without painting.
 *
 * The table arrives as a provider so a fake measure can be injected in headless
 * tests without constructing a GL-backed [PlayTableView]; only the default
 * implementation invokes it.
 */
fun interface BoardMeasure {
    fun measure(
        table: () -> PlayTableView,
        view: BoardView,
        c: Constraints,
        /** The rectangle the table paints into, and its reserved bottom band. */
        area: SlideChromeLayout.BoardArea,
        /** Safe insets the table applies; zeros inside a provider's content space. */
        safeInsets: SafeArea.Insets,
    ): BoardMeasurement
}

/**
 * The pane a tutorial board lesson hands the live table, for a content space of
 * [width] × [height].
 *
 * A board lesson keeps one pane for the whole lesson — the coach's copy band and
 * Back / Next row cut it out of the shell's content space — and the table must
 * get it in the frame it draws, including the first frame of a lesson and every
 * frame of a live resize. The derivation is therefore a synchronous query on the
 * viewport, not a value read back from a previous frame.
 */
fun interface BoardPane {
    fun areaFor(width: Float, height: Float): SlideChromeLayout.BoardArea

    companion object {
        /** The whole content space and no bottom reserve. */
        val Plain: BoardPane = BoardPane { width, height ->
            SlideChromeLayout.BoardArea(width, height, 0f)
        }
    }
}

/**
 * Everything [BoardComponent.draw] needs from the host beyond the session
 * snapshot: whether the center turn-status line may paint, the online decision
 * timer slack/base, and the last rejected-action feedback string.
 */
data class BoardRenderInput(
    val showTurnStatus: Boolean = true,
    val decisionSlackMs: Long? = null,
    val decisionBaseMs: Long? = null,
    val statusFeedback: String? = null,
)

/**
 * Extra chrome the legacy board paint drew around the table: the menu version
 * stamp and the hand-status badge. A seam so the board component stays
 * host-agnostic while remaining the paint owner.
 */
fun interface BoardChrome {
    fun draw(
        canvas: Canvas,
        constraints: Constraints,
        session: SessionSnapshot,
        boardDrawn: Boolean,
    )
}

/**
 * The Mahjong table as a screen component: wraps [PlayTableView] for pixels and
 * publishes its hit regions freshly for the input pipeline.
 *
 * Layout delegates to a [BoardMeasure]; painting delegates to
 * [PlayTableView.render] so the board's own layer is unchanged. Screen
 * decorations the legacy board paint drew on this layer arrive through [chrome].
 */
class BoardComponent(
    route: Route,
    private val table: () -> PlayTableView,
    /**
     * The display preferences that own the board chrome's player-arranged places.
     * Read-only here: the component hands the parks in when the board appears, and
     * a released drag is reported back through the effect.
     */
    private val preferences: StateSlot<UiPreferences>,
    private val measure: BoardMeasure = DefaultBoardMeasure,
    /** The tutorial board lesson's pane; [BoardPane.Plain] outside one. */
    private val pane: BoardPane = BoardPane.Plain,
    private val chrome: BoardChrome = BoardChrome { _, _, _, _ -> },
    /**
     * The park surface the gesture bridge forwards to. Defaults to the live
     * [PlayTableView] that owns the press/drag/release machine; a headless test
     * injects a fake so the conditional release is exercised without constructing
     * GL, for the same reason [measure] is a seam.
     */
    surface: () -> GestureSurface<ParkRelease> = { table() },
) : GestureBridge<ParkRelease>(
    surface = surface,
    actionFor = BoardActions::actionFor,
    consumeFor = ParkRelease::consumed,
), Component {
    override val id: ComponentId = ComponentId(route.key.value)

    /**
     * The top-right menu control on a local or online board; geometry comes from
     * [BoardMenuChrome]. A tutorial board lesson draws no button here — the
     * tutorial shell owns the context's menu control.
     */
    private val menuButton = CloseButton(CloseButton.Style.Menu)

    override fun motion(frame: Frame) {
        val view = boardView(frame.appModel())
        val area = tutorialBoardArea(frame)
        val snapshot = view.session
        val menuPresent = BoardMenuChrome.presentIn(snapshot.kind)
        table().bottomReservePx = area.bottomReserve
        table().topRightChromePx = boardMenuFootprint(menuPresent)
        table().safeInsets = BoardMenuChrome.tableInsets(
            area.width,
            area.height,
            boardSafeInsets(frame),
            menuPresent || menuOverlapsTable(area, frame.constraints.worldHeight),
        )
        val tableView = if (snapshot.boardHosted) boardTableView(snapshot, view.inputs.revealedSeats) else null
        table().motion(tableView, area.width, area.height, view.inputs)
        // Only real motion asks for a present. This used to read
        // `tableView != null || table().needsFrames()`, which held the gate open for
        // the whole game: a seat is always active, and the active seat's label used
        // to pulse, so a hosted board never stopped requesting frames. That defeated
        // `setContinuousRendering(false)` for every hand — and the symptom was the
        // label visibly freezing mid-pulse whenever input stopped. The label is
        // static now, so `needsFrames()` is honest and a settled board costs nothing.
        //
        // The right-hand term still covers tile flights while this surface is covered
        // (a board under the tutorial coach, a menu): the clock belongs to the view,
        // not to whether this component is the one drawing it.
        if (table().needsFrames()) frame.keepPainting()
    }

    override fun layout(frame: Frame): Layout {
        val constraints = frame.constraints
        val view = boardView(frame.appModel())
        val area = tutorialBoardArea(frame)
        val measurement = measure.measure(
            table,
            view,
            constraints,
            area,
            boardSafeInsets(frame),
        )
        val hitRegions = HitRegions<ComponentAction>()
        for ((rect, action, rank) in measurement.regions.sortedBy { it.rank }) {
            hitRegions.add(rect, action, rank)
        }
        // Gesture regions are the HINT button and each measured claim button. The
        // claim row's *whole-row* grab area — the gaps between buttons that
        // `ClaimActionPanel.hitsParkChrome` already treats as the row — is not
        // published, so a press in a gap starts no park yet. Exposing it needs the
        // row rect carried out of the widget; left as its own change.
        val gestureRegions = measurement.regions
            .filter {
                it.rank == DefaultBoardMeasure.RANK_HINT ||
                    it.rank == DefaultBoardMeasure.RANK_CLAIM
            }
            .map { it.rect }
        // The playable rect this frame laid the table into. A layer above that is
        // composed into the same space (the hand-end card) reads it from here
        // instead of deriving a pane of its own.
        val paneRect = Rect(
            left = 0f,
            bottom = area.bottomReserve,
            width = area.width,
            height = (area.height - area.bottomReserve).coerceAtLeast(0f),
        )
        val regions = measurement.anchors + (BoardAnchors.PANE to paneRect)
        return Layout(
            hitRegions = hitRegions,
            panel = Rect(0f, 0f, constraints.worldWidth, constraints.worldHeight),
            gestureRegions = gestureRegions,
            regions = regions,
        )
    }

    /**
     * The area the live table may paint and measure into, and the band its bottom
     * edge keeps clear for the coach Back / Next row.
     *
     * **The table owns one pane for the whole tutorial board lesson.** Its
     * [pane] derives it from the campaign-wide copy band, which does not depend
     * on the beat on screen, so the board gets the right pane in the frame it
     * draws: the first frame of a lesson, a play beat where the coach is down,
     * and every frame of a live resize. One value feeds paint, motion and hit
     * measurement, so the pixels and the regions cannot disagree.
     *
     * A board with no board-overlay lesson (free play, online, a diagram-only
     * lesson) gets the whole content space and no reserve.
     */
    private fun tutorialBoardArea(frame: Frame): SlideChromeLayout.BoardArea {
        val c = frame.constraints
        if (c.worldWidth > 1f && c.worldHeight > 1f &&
            frame.appModel().tutorial.boardLesson
        ) {
            return pane.areaFor(c.worldWidth, c.worldHeight)
        }
        return BoardPane.Plain.areaFor(c.worldWidth, c.worldHeight)
    }

    // The HINT button / claim row park come from [GestureBridge]: the widget owns
    // the press/drag/release machine and `BoardActions.actionFor` maps the release
    // it resolved, so the movable block is no longer forwarded by hand here.

    override fun draw(frame: Frame, canvas: Canvas) {
        val c = frame.constraints
        val view = boardView(frame.appModel())
        val snapshot = view.session
        val menuPresent = BoardMenuChrome.presentIn(snapshot.kind)
        var drawn = false
        var menuRect: Rect? = null
        if (snapshot.boardHosted) {
            val tableView = boardTableView(snapshot, view.inputs.revealedSeats)
            if (tableView != null) {
                val area = tutorialBoardArea(frame)
                val safe = boardSafeInsets(frame)
                val board = table()
                board.bottomReservePx = area.bottomReserve
                board.topRightChromePx = boardMenuFootprint(menuPresent)
                board.safeInsets = BoardMenuChrome.tableInsets(
                    area.width,
                    area.height,
                    safe,
                    menuPresent || menuOverlapsTable(area, c.worldHeight),
                )
                val input = view.renderInput
                // The player's remembered HINT / claim-row places live in
                // `ui.prefs`; the board hands them in before painting so the
                // pixels and the next frame's hit regions agree. A live park
                // gesture keeps its own place until the release reports it.
                val prefs = preferences.requireValue()
                board.restoreParkedChrome(
                    prefs.hintParkX,
                    prefs.hintParkY,
                    prefs.claimParkX,
                    prefs.claimParkY,
                )
                board.render(
                    batch = canvas.batch,
                    view = tableView,
                    screenW = area.width,
                    screenH = area.height,
                    pointerWorldX = c.pointerX,
                    pointerWorldY = c.pointerY,
                    inputs = view.inputs,
                    showTurnStatus = input.showTurnStatus,
                    decisionTimeLeftMs = snapshot.table?.decisionTimeLeftMs,
                    decisionSlackMs = input.decisionSlackMs,
                    decisionBaseMs = input.decisionBaseMs,
                    connectionStatus = snapshot.table?.connectionStatus ?: ConnectionStatus.Local,
                    statusFeedback = input.statusFeedback,
                    shutdownPhase = snapshot.table?.shutdownPhase,
                    shutdownRemainingMs = snapshot.table?.shutdownRemainingMs,
                    shutdownMessage = snapshot.table?.shutdownMessage,
                )
                drawn = true
                // Board chrome, not a route: the same rect the measure pass
                // publishes, painted after the table so parked HINT / claim chrome
                // cannot cover it. The HUD in [chrome] keeps its text left of it.
                if (menuPresent) menuRect = BoardMenuChrome.rect(area.width, area.height, safe)
            }
        }
        chrome.draw(canvas, c, snapshot, drawn)
        menuRect?.let { rect ->
            menuButton.layoutAt(rect.left, rect.bottom, rect.width)
            menuButton.render(
                batch = canvas.batch,
                whitePixel = WhitePixel.get(),
                pointerWorldX = c.pointerX,
                pointerWorldY = c.pointerY,
            )
        }
    }
}

/**
 * Safe insets for a board: the window [SafeArea] in world space, or zeros when
 * the board is laid out inside a provider's space (the tutorial shell's content
 * zone). That zone already sits inside the cutout, so applying the window
 * insets again would double the top rim and leave a blank band above the
 * opposite seat. Zeroing them makes a hosted board occupy the same edges as a
 * full-screen Local / Online board.
 */
private fun boardSafeInsets(frame: Frame): SafeArea.Insets =
    if (frame.space != null) SafeArea.Insets() else SafeArea.current()

/** The table view a board frame paints and measures from, or null when none is hosted. */
internal fun boardTableView(snapshot: SessionSnapshot, revealedSeats: Set<Int>): TableViewState? =
    if (snapshot.kind == SessionKind.Online) {
        snapshot.table?.tableView
    } else {
        snapshot.table?.tableView
            ?: snapshot.hand?.state?.let {
                TableViewState.fromGameState(it, snapshot.hand.humanSeat, revealedSeats)
            }
    }

/**
 * True when the table's pane reaches the top of this layer's space, where the
 * context's top-right menu floats — the board's own in free play / online, the
 * shell's in a tutorial lesson. A coach hands the table a pane below its copy
 * band, so the corner is out of the table's reach and needs no reserve.
 */
private fun menuOverlapsTable(area: SlideChromeLayout.BoardArea, spaceHeight: Float): Boolean =
    area.height >= spaceHeight - 0.5f

/**
 * The corner menu's square for the table's high-end reserve, or 0 where no menu
 * paints. The table adds its own breathing gap and only reserves where the
 * square would reach into the Right seat's column.
 */
private fun boardMenuFootprint(menuPresent: Boolean): Float =
    if (menuPresent) BoardMenuChrome.footprint() else 0f

/** A hand tile's world box as a layout anchor. */
private fun HandHit.toAnchorRect(): Rect =
    Rect(worldX - w * 0.5f, worldY - h * 0.5f, w, h)

/**
 * Default [BoardMeasure]: recomputes every board region from the current view and
 * constraints through [PlayTableView.refreshHits]. The score-breakdown
 * collapse/pattern controls live in a separate overlay and are never produced here.
 */
object DefaultBoardMeasure : BoardMeasure {
    /** Above every movable control: the menu must win an overlap on a tap. */
    const val RANK_MENU = 0
    const val RANK_HINT = 1
    const val RANK_CLAIM = 2
    const val RANK_HAND = 3

    /** Target ids whose anchor the board resolves by name, not by tile type. */
    private val SPECIAL_TARGETS = listOf("river.last", "wall.live")

    override fun measure(
        table: () -> PlayTableView,
        view: BoardView,
        c: Constraints,
        area: SlideChromeLayout.BoardArea,
        safeInsets: SafeArea.Insets,
    ): BoardMeasurement {
        val session = view.session
        if (!session.boardHosted) return BoardMeasurement(emptyList())
        val menuPresent = BoardMenuChrome.presentIn(session.kind)
        val board = table()
        board.bottomReservePx = area.bottomReserve
        board.topRightChromePx = boardMenuFootprint(menuPresent)
        board.safeInsets = BoardMenuChrome.tableInsets(
            area.width,
            area.height,
            safeInsets,
            menuPresent || menuOverlapsTable(area, c.worldHeight),
        )
        val tableView = boardTableView(session, view.inputs.revealedSeats)
        val boxes = board.refreshHits(
            tableView,
            area.width,
            area.height,
            c.pointerX,
            c.pointerY,
            view.inputs,
        )

        val regions = ArrayList<BoardRegion>()
        // Present exactly when the table paints: the board draws its menu only
        // alongside a hosted table view and only on the board contexts that own
        // one, so the region and the pixels agree.
        if (menuPresent && tableView != null) {
            regions += BoardRegion(
                rect = BoardMenuChrome.rect(area.width, area.height, safeInsets),
                action = ComponentAction(BoardActions.MENU),
                rank = RANK_MENU,
            )
        }
        boxes.hint?.let { hit ->
            regions += BoardRegion(
                rect = Rect(hit.cx - hit.w / 2f, hit.cy - hit.h / 2f, hit.w, hit.h),
                action = ComponentAction(BoardActions.HINT),
                rank = RANK_HINT,
            )
        }
        for ((choice, cx, cy, w, h) in boxes.claim) {
            regions += BoardRegion(
                rect = Rect(cx - w / 2f, cy - h / 2f, w, h),
                action = ComponentAction(
                    BoardActions.CLAIM,
                    mapOf(BoardActions.KEY_CHOICE to choice),
                ),
                rank = RANK_CLAIM,
            )
        }
        for ((worldX, worldY, w, h, tileType, isDrawnSlot, visualId) in boxes.hand) {
            regions += BoardRegion(
                rect = Rect(worldX - w / 2f, worldY - h / 2f, w, h),
                action = ComponentAction(
                    BoardActions.DISCARD,
                    mapOf(
                        BoardActions.KEY_TILE_TYPE to tileType,
                        BoardActions.KEY_VISUAL_ID to visualId,
                        BoardActions.KEY_IS_DRAWN to isDrawnSlot,
                    ),
                ),
                rank = RANK_HAND,
            )
        }

        val anchors = LinkedHashMap<String, Rect>()
        if (board.hoveredHandIndex >= 0) {
            boxes.hand.getOrNull(board.hoveredHandIndex)?.let {
                anchors[BoardAnchors.HAND_HOVER] = it.toAnchorRect()
            }
        }
        board.highlightedHandAnchor()?.let {
            anchors[BoardAnchors.HAND_PROMPT] = it.toAnchorRect()
        }
        for (targetId in SPECIAL_TARGETS) {
            board.anchorForTarget(targetId, view.inputs.humanSeat)?.let {
                anchors[BoardAnchors.target(targetId)] = it.toAnchorRect()
            }
        }
        for (type in 0 until NUM_TILE_TYPES) {
            val targetId = "tile.$type"
            board.anchorForTarget(targetId, view.inputs.humanSeat)?.let {
                anchors[BoardAnchors.target(targetId)] = it.toAnchorRect()
            }
        }
        if (tableView != null && (tableView.isHumanClaimTurn || tableView.isHumanActOnHandPanel)) {
            board.claimRowLayout(tableView, area.width, area.height)?.let { row ->
                for ((choice, cx, cy, w, h) in row.buttons) {
                    val rect = Rect(cx - w / 2f, cy - h / 2f, w, h)
                    anchors[BoardAnchors.claim(choice.kind, choice.payload)] = rect
                    anchors.putIfAbsent(BoardAnchors.claimAny(choice.kind), rect)
                }
            }
        }
        return BoardMeasurement(regions, anchors)
    }
}
