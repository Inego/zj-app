package inego.zungjunggame.features.score

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.engine.HandScoreDetails
import inego.zungjunggame.engine.HandScoreRow
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.render.assets.WhitePixel
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.handend.HandEndAction
import inego.zungjunggame.render.handend.HandEndActionChrome
import inego.zungjunggame.render.handend.HandEndActionHit
import inego.zungjunggame.render.online.RatingLine
import inego.zungjunggame.render.seats.NameTone
import inego.zungjunggame.render.seats.PlayerLabels
import inego.zungjunggame.render.ui.CollapseButton
import inego.zungjunggame.render.ui.InfoButton
import inego.zungjunggame.render.ui.OverlayBodyScroll
import inego.zungjunggame.render.ui.OverlayViewport
import inego.zungjunggame.render.ui.ParkDrag
import inego.zungjunggame.render.ui.ParkRelease
import inego.zungjunggame.render.ui.ParkableChrome
import inego.zungjunggame.ui.GestureSurface
import inego.zungjunggame.ui.Rect

/** GL-free snapshot of a pattern row's reference deep-link control. */
data class ScoreBreakdownInfoGeometry(val rect: Rect, val pageId: String)

/** GL-free snapshot of the hand-end score-breakdown card's bounds and deep-links. */
data class ScoreBreakdownGeometry(
    val panel: Rect?,
    val block: Rect?,
    val patternInfos: List<ScoreBreakdownInfoGeometry>,
)

/**
 * One seat's payment line under the hand Total: name, score before this hand,
 * delta, and running total after.
 */
data class SettlementSeatLine(
    val name: String,
    val before: Int,
    val delta: Int,
    val after: Int,
    /** True for the human seat — row is highlighted like the Scores "You" column. */
    val isHuman: Boolean,
    val isBot: Boolean = false,
    val relativeSeat: Int = 0,
) {
    companion object {
        val COMPARATOR: Comparator<SettlementSeatLine> =
            compareByDescending<SettlementSeatLine> { it.after }
                .thenBy { it.relativeSeat }

        /**
         * Builds the sorted settlement lines for the hand-end score breakdown.
         *
         * Ordered uniformly for local and online games:
         * 1. Cumulative score (`after`) decreasing.
         * 2. Counterclockwise position relative to the player, starting from the player
         *    (0 = You, 1 = Right, 2 = Opposite, 3 = Left).
         */
        fun build(
            deltas: IntArray,
            runningTotals: IntArray,
            humanSeat: Int,
        ): List<SettlementSeatLine> {
            if (deltas.size < NUM_PLAYERS || runningTotals.size < NUM_PLAYERS) return emptyList()
            return (0 until NUM_PLAYERS).map { seat ->
                val delta = deltas[seat]
                val after = runningTotals[seat]
                val rel = (seat - humanSeat + NUM_PLAYERS) % NUM_PLAYERS
                SettlementSeatLine(
                    name = PlayerLabels.nameForAbsoluteSeat(seat, humanSeat),
                    before = after - delta,
                    delta = delta,
                    after = after,
                    isHuman = seat == humanSeat,
                    isBot = PlayerLabels.isBot(seat),
                    relativeSeat = rel,
                )
            }.sortedWith(COMPARATOR)
        }

        fun build(
            row: HandScoreRow?,
            humanSeat: Int,
        ): List<SettlementSeatLine> =
            if (row != null) build(row.deltas, row.runningTotals, humanSeat) else emptyList()

        fun build(
            deltas: List<Int>,
            runningTotals: List<Int>,
            humanSeat: Int,
        ): List<SettlementSeatLine> =
            build(deltas.toIntArray(), runningTotals.toIntArray(), humanSeat)
    }
}


/**
 * Inline hand-end win summary: a one-line win headline, pattern points table with
 * a Total footer, per-seat settlement rows, and (free-play) Scores + Next hand
 * (or New game) buttons under the card. Shown automatically when a hand ends
 * with a win — not a dismissible modal. Tutorial keeps the card and hides the
 * free-play buttons.
 *
 * Each pattern row has a rimless [InfoButton] deep-link; [inego.zungjunggame.engine.HandScoreComponent.id]
 * is the reference page id.
 *
 * A corner [CollapseButton] folds the pattern/settlement body down to a two-line
 * block — the winner's name over how the winning tile arrived, plus the total — so
 * the board (hands) stays visible on small phone screens.
 * Size eases and the two layouts cross-fade; Scores / Next hand stay under the
 * card. The card is clamped to its world — the window's safe area, or the board
 * pane it is composed over in the tutorial — and when the pattern/settlement
 * body is taller than the leftover slot its faces and row rhythm shrink
 * together to fit; it scrolls only when even the readable floor cannot (title +
 * action row stay put). In any fold state the card + action row can be dragged
 * around that world from the headline (or from the body when it does not
 * overflow).
 * Same press/slop/release as the Reference/Tutorial pull tab: move parks it,
 * a tap still asks for the fold or hits Scores / Next hand. While held the block uses
 * the pull-tab grab chrome (green fill + ivory rim) so it reads as movable;
 * collapsed hover lights the same chrome. The parked offset applies in both
 * states, so expanding keeps the card where it was parked.
 *
 * That fold and that place are the player's, and neither belongs to the card: both
 * are remembered in `ui.prefs` (`breakdownCollapsed`, `breakdownParkX/Y`), so they
 * survive the card hiding between hands and the app restarting. The component hands
 * them in — [setCollapsed] every frame, [restorePark] when the card appears — and
 * the card reports what a release resolved back through [PointerUpResult]. The card
 * never flips its own fold: a second owner would cancel the player's press, and a
 * card that started expanded every hand would lose the fold on every restart. A
 * [show] refresh of a card that is already up resets neither the fold nor the park.
 *
 * The gesture itself — press point, slop, accumulated park, clamp — is the
 * shared [inego.zungjunggame.render.ui.ParkDrag] the board's HINT button and
 * claim row use too: this card was the original, and its shape is now a
 * [ParkableChrome] implementation rather than a private copy of the state
 * machine.
 */
class ScoreBreakdownOverlay : Disposable, ParkableChrome, GestureSurface<ScoreBreakdownOverlay.PointerUpResult> {
    private val glyphLayout = GlyphLayout()
    private val painter = ScoreBreakdownPainter(glyphLayout)
    private val patternInfoButtons = ArrayList<InfoButton>(8)
    private val patternInfoPageIds = ArrayList<String>(8)
    private val collapseButton = CollapseButton()
    private val body = OverlayBodyScroll()
    private var pressBodyScroll: Boolean = false

    /**
     * Park gesture: press the card, drag past the slop, and the card moves;
     * release without moving and the tap hits whatever is under it. Shared with
     * the board's HINT / claim chrome (see [ParkDrag]).
     */
    private val park = ParkDrag(ScoreBreakdownLayout.DRAG_SLOP_DP)

    private fun whitePixel(): Texture = WhitePixel.get()

    /**
     * True when the pattern/settlement body is hidden so the board is visible. The
     * owner's remembered fold, rendered by the card and set through [setCollapsed]:
     * the fold control reports the press ([PointerUpResult.foldToggle]) instead of
     * flipping this, so there is one writer.
     */
    var collapsed: Boolean = false
        private set

    /**
     * Visual fold: 0 = fully expanded, 1 = fully collapsed. Eases toward
     * [collapsed] each [render]; toggle only flips the target.
     */
    internal var foldProgress: Float = 0f
        private set

    /**
     * First [setCollapsed] of this instance snaps [foldProgress] (restore-snap);
     * a later target eases. View-local, next to the phase it gates.
     */
    private var firstApply: Boolean = true

    /**
     * Offset of the collapsed block from the centered rest pose, in world
     * pixels. Applied with [foldProgress] so expand slides back to center.
     */
    internal val parkX: Float get() = park.parkX
    internal val parkY: Float get() = park.parkY

    /** True after the finger/mouse moved past slop on the block. */
    internal val dragMoved: Boolean get() = park.moved

    /** Finger/mouse is down on the block (before or after slop), any fold state. */
    internal val dragging: Boolean get() = park.active

    /**
     * Press started on the fold chevron. The tap then commits the toggle on
     * release ([onPointerUp]) — same release-commit as every other control,
     * so press-and-hold never acts and release off the chevron cancels.
     */
    internal var pressExpandedToggle: Boolean = false
        private set

    /** The finished-game result line currently shown, or null (tests). */
    internal val gameResultLine: String?
        get() = gameResult

    private var details: HandScoreDetails = HandScoreDetails()
    /** The header's verdict and the collapsed bar's title: winner name, or draw label. */
    private var headline: String = ""
    /** The muted run before [headline] in the header ("Winner:"); empty for a draw. */
    private var headlineLabel: String = ""
    /** How the header paints [headline]: green when the human won the hand. */
    private var headlineTone = NameTone.Neutral
    /** The header's second line: the seat the winning tile came from, or empty. */
    private var headlineDetail: String = ""
    /** The muted run before [headlineDetail] ("From:"), or the whole self-draw phrase. */
    private var headlineDetailLabel: String = ""
    /** How the "From:" name is painted: red when the human discarded to the winner. */
    private var headlineDetailTone = NameTone.Neutral

    /** The collapsed bar's small second line: "from Left" / "self-draw". Null on a draw. */
    private var winSourceLabel: String? = null

    /**
     * The finished **game**'s result ("1st place (shared by 2)"), or null while the
     * game continues. A line of its own because the headline is the hand's verdict:
     * swapping the two hid who won the hand the player just watched.
     */
    private var gameResult: String? = null
    /** Finished-game span as `mm:ss`, or null while the game continues. */
    private var durationLabel: String? = null
    private var settlement: List<SettlementSeatLine> = emptyList()
    private var ratingLines: List<RatingLine> = emptyList()
    /** The Shadow Ranked comparison section, or null. */
    private var comparison: ShadowRankedComparison? = null
    private var primaryAction: HandEndAction = HandEndAction.NextHand
    private var primaryLabel: String = ""
    private var secondaryAction: HandEndAction? = null
    private var secondaryLabel: String = ""
    /** Free-play Scores / Next hand row. Tutorial omits it (coach Next continues). */
    private var showActions: Boolean = true
    /** False hides the Scores chip (a reopened attempt card has no session ledger). */
    private var showScoreTable: Boolean = true
    /** False after this seat has pressed Next hand and is waiting for others. */
    private var primaryEnabled: Boolean = true
    private var primaryTimeLeftMs: Long? = null

    private var panelLeft = 0f
    private var panelBottom = 0f
    private var panelW = 0f
    private var panelH = 0f

    /** Inclusive bounds for the card + button row (hit testing / empty-board guard). */
    private var blockLeft = 0f
    private var blockBottom = 0f
    private var blockW = 0f
    private var blockH = 0f

    private var lastScreenW = 0f
    private var lastScreenH = 0f

    /**
     * The rect the card was last laid out in, or null for the window. The layout
     * reads it at the same time as the screen size, and the park clamp uses it so
     * the drag stops where the layout placed the block.
     */
    private var lastBounds: Rect? = null

    /** Press started on the card body (not Scores / Next hand). */
    private var pressOnCard = false

    private val buttonHits = ArrayList<HandEndActionHit>(2)

    /**
     * Apply (or clear) the hand-end content. Null is hide: the same fields
     * [show] fills, emptied. Fold / park / scroll are prefs-driven and stay.
     *
     * The component drives this from [inego.zungjunggame.runtime.AppModel]
     * each layout and draw so a host never writes the card.
     */
    fun apply(content: ScoreBreakdownContent?) {
        if (content == null) {
            details = HandScoreDetails()
            headline = ""
            headlineLabel = ""
            headlineTone = NameTone.Neutral
            headlineDetail = ""
            headlineDetailLabel = ""
            headlineDetailTone = NameTone.Neutral
            winSourceLabel = null
            gameResult = null
            durationLabel = null
            settlement = emptyList()
            ratingLines = emptyList()
            comparison = null
            primaryAction = HandEndAction.NextHand
            primaryLabel = ""
            secondaryAction = null
            secondaryLabel = ""
            showActions = true
            showScoreTable = true
            primaryEnabled = true
            primaryTimeLeftMs = null
            return
        }
        details = content.details
        headline = content.headline
        headlineLabel = content.headlineLabel
        headlineTone = content.headlineTone
        headlineDetail = content.headlineDetail
        headlineDetailLabel = content.headlineDetailLabel
        headlineDetailTone = content.headlineDetailTone
        winSourceLabel = content.winSourceLabel?.takeIf { it.isNotBlank() }
        gameResult = content.gameResult?.takeIf { it.isNotBlank() }
        durationLabel = content.durationLabel?.takeIf { it.isNotBlank() }
        settlement = content.settlement
        primaryAction = content.primaryAction
        primaryLabel = content.primaryLabel
        showActions = content.showActions
        showScoreTable = content.showScoreTable
        primaryEnabled = content.primaryEnabled
        primaryTimeLeftMs = content.primaryTimeLeftMs
        ratingLines = content.ratingLines
        comparison = content.comparison
        secondaryAction = content.secondaryAction
        secondaryLabel = content.secondaryLabel.orEmpty()
    }

    /**
     * Show (or refresh) the hand-end breakdown for a winning hand.
     *
     * @param headline the header's verdict and the collapsed bar's title: the
     *   winner's name, or the draw label
     * @param headlineLabel the muted label before [headline] in the header
     *   ("Winner:"), ending on the shared label axis; empty for a draw
     * @param headlineTone how the header paints [headline] — green when the human
     *   player won the hand
     * @param headlineDetail the header's second line: the seat the winning tile
     *   came from; empty on a self-draw or a draw
     * @param headlineDetailLabel the muted label before [headlineDetail] ("From:"),
     *   or the whole self-draw phrase (right-aligned in the label column); empty on
     *   a draw
     * @param headlineDetailTone how the header paints the discarder's name — red
     *   when the human player discarded to the winner
     * @param settlement four seats in display order (by cumulative score decreasing,
     *   then counterclockwise relative to the player)
     * @param primaryAction [HandEndAction.NextHand], [HandEndAction.NewGame],
     *   or [HandEndAction.PlayAgain]
     * @param primaryLabel button text for [primaryAction]
     * @param secondaryAction extra non-primary chip after the CTA (online finish
     *   leaves the table with Back to Online home)
     * @param showActions Scores + Next hand / New game under the card. False in
     *   tutorial: the card is the same win summary, coach Next continues.
     * @param primaryEnabled false keeps the Next hand button visible but inert
     *   after this seat has already ready'd.
     * @param primaryTimeLeftMs countdown until auto-ready; null when uncapped
     *   or already ready.
     * @param gameResult the finished game's result line, or null while it continues.
     *   Its own line under the headline, which stays the hand's verdict.
     * @param winSourceLabel the collapsed bar's small second line ("from Left",
     *   "self-draw"); null on a draw.
     */
    fun show(
        details: HandScoreDetails,
        headline: String,
        settlement: List<SettlementSeatLine>,
        primaryAction: HandEndAction,
        primaryLabel: String,
        showActions: Boolean = true,
        primaryEnabled: Boolean = true,
        primaryTimeLeftMs: Long? = null,
        ratingLines: List<RatingLine> = emptyList(),
        secondaryAction: HandEndAction? = null,
        secondaryLabel: String? = null,
        gameResult: String? = null,
        durationLabel: String? = null,
        winSourceLabel: String? = null,
        headlineLabel: String = "",
        headlineTone: NameTone = NameTone.Neutral,
        headlineDetail: String = "",
        headlineDetailLabel: String = "",
        headlineDetailTone: NameTone = NameTone.Neutral,
    ) {
        apply(
            ScoreBreakdownContent(
                details = details,
                headline = headline,
                headlineLabel = headlineLabel,
                headlineTone = headlineTone,
                headlineDetail = headlineDetail,
                headlineDetailLabel = headlineDetailLabel,
                headlineDetailTone = headlineDetailTone,
                winSourceLabel = winSourceLabel,
                settlement = settlement,
                primaryAction = primaryAction,
                primaryLabel = primaryLabel,
                showActions = showActions,
                primaryEnabled = primaryEnabled,
                primaryTimeLeftMs = primaryTimeLeftMs,
                ratingLines = ratingLines,
                secondaryAction = secondaryAction,
                secondaryLabel = secondaryLabel,
                gameResult = gameResult,
                durationLabel = durationLabel,
            ),
        )
    }

    /**
     * Hand in the place the player parked the card.
     *
     * The arrangement is model state (`ui.prefs`), so the card never learns it
     * itself: the component reads the stored offset and hands it in every frame,
     * and every drag the card accepts is reported back ([PointerUpResult.parked])
     * for the model to keep. The next render clamps it through the same
     * [inego.zungjunggame.render.ui.ParkGeometry] the drag uses, so an offset saved on a different window size
     * cannot park the card off-screen.
     */
    fun restorePark(x: Float, y: Float) {
        park.setPark(x, y)
    }

    /**
     * Hand in the fold to render: the remembered display state (`ui.prefs`), pushed
     * by the component every frame and seeded when the card appears.
     *
     * The card has no fold of its own to flip — the fold control asks for the flip
     * ([PointerUpResult.foldToggle]) and the owner writes it — so this is the only
     * way the value changes. The first value applied after construction snaps
     * [foldProgress] (restore starts settled); later changes ease, and an
     * unchanged value leaves an ease in flight alone.
     */
    fun setCollapsed(value: Boolean) {
        if (firstApply) {
            firstApply = false
            collapsed = value
            foldProgress = if (value) 1f else 0f
            return
        }
        if (collapsed == value) return
        collapsed = value
    }

    /**
     * Step [foldProgress] toward the collapsed/expanded target.
     * [render] calls this with [Gdx.graphics] delta; tests pass a fake dt.
     */
    /** The fold ease has not reached the collapsed or expanded target. */
    fun foldAnimating(): Boolean = foldProgress != (if (collapsed) 1f else 0f)

    internal fun advanceFold(deltaSec: Float) {
        val target = if (collapsed) 1f else 0f
        if (foldProgress == target) return
        val step = (deltaSec / ScoreBreakdownLayout.FOLD_ANIM_SEC).coerceAtLeast(0f)
        foldProgress =
            if (foldProgress < target) {
                (foldProgress + step).coerceAtMost(target)
            } else {
                (foldProgress - step).coerceAtLeast(target)
            }
    }

    /** True when the pointer is over the card or its button row. */
    fun containsPanel(worldX: Float, worldY: Float): Boolean {
        return worldX >= blockLeft && worldX <= blockLeft + blockW &&
            worldY >= blockBottom && worldY <= blockBottom + blockH
    }

    /**
     * Reference page id for the pattern-row info control under the pointer,
     * if any. [inego.zungjunggame.engine.HandScoreComponent.id] is the page id.
     */
    fun patternInfoAt(worldX: Float, worldY: Float): String? {
        if (body.height > 1f && !body.contains(worldX, worldY)) return null
        for (i in patternInfoButtons.lastIndex downTo 0) {
            if (i >= patternInfoPageIds.size) continue
            if (patternInfoButtons[i].contains(worldX, worldY)) {
                return patternInfoPageIds[i]
            }
        }
        return null
    }

    /** Terminal button under the pointer, if any. */
    fun actionAt(worldX: Float, worldY: Float): HandEndAction? {
        return HandEndActionChrome.actionAt(buttonHits, worldX, worldY)
    }

    /** GL-free snapshot of the card's bounds and its pattern-row deep-links. */
    fun measureGeometry(): ScoreBreakdownGeometry {
        val panel = if (panelW > 0f && panelH > 0f) {
            Rect(panelLeft, panelBottom, panelW, panelH)
        } else {
            null
        }
        val block = if (blockW > 0f && blockH > 0f) {
            Rect(blockLeft, blockBottom, blockW, blockH)
        } else {
            null
        }
        val infos = ArrayList<ScoreBreakdownInfoGeometry>(patternInfoPageIds.size)
        for (i in patternInfoPageIds.indices) {
            val button = patternInfoButtons.getOrNull(i) ?: continue
            if (button.side <= 0f) continue
            infos += ScoreBreakdownInfoGeometry(
                Rect(button.leftX, button.bottomY, button.side, button.side),
                patternInfoPageIds[i],
            )
        }
        return ScoreBreakdownGeometry(panel, block, infos)
    }

    /**
     * True when the pointer is on the collapse/expand control, or on the
     * collapsed card body (whole bar expands — larger phone target).
     */
    fun hitsCollapseToggle(worldX: Float, worldY: Float): Boolean {
        return collapseButton.contains(worldX, worldY) || collapsed && hitsCardBody(worldX, worldY)
    }

    /** True when the pointer is on the card plate (not the Scores / Next hand row). */
    private fun hitsCardBody(worldX: Float, worldY: Float): Boolean {
        return !(panelW <= 0f || panelH <= 0f) && worldX >= panelLeft && worldX <= panelLeft + panelW &&
                worldY >= panelBottom && worldY <= panelBottom + panelH
    }

    /**
     * Pull-tab grab chrome: block is held in any fold state (collapsed hover
     * lights the same chrome on desktop / Android press-hover). Button-row
     * press alone does not light the card until the block actually moves.
     */
    internal fun grabHighlightActive(pointerWorldX: Float, pointerWorldY: Float): Boolean {
        if (pressBodyScroll) return false
        if (dragging && (pressOnCard || dragMoved || pressExpandedToggle)) return true
        if (collapsed) {
            return !(pointerWorldX.isNaN() || pointerWorldY.isNaN()) && hitsCardBody(pointerWorldX, pointerWorldY)
        }
        return false
    }

    /**
     * Start a block gesture in any fold state (card + Scores / Next hand).
     * Same claim pattern as the Reference/Tutorial pull tab: the host owns
     * press→drag→up. A press on the fold chevron only commits its toggle on
     * release, so press-and-hold never acts and release off it cancels.
     *
     * @return true when the pointer was claimed (gesture owned by this overlay).
     */
    override fun onPointerDown(x: Float, y: Float): Boolean {
        cancelGesture()
        if (!containsPanel(x, y)) return false
        // Pattern-row deep-links stay on the click path; don't swallow them
        // with a drag gesture.
        if (!collapsed && patternInfoAt(x, y) != null) return false
        pressExpandedToggle = collapseButton.contains(x, y)
        if (!pressExpandedToggle && body.overflowing && body.contains(x, y)) {
            pressBodyScroll = true
            body.beginPointer(x, y)
            pressOnCard = true
            // The body scrolled, so this press must never park the card.
            park.begin(x, y, movesBlock = false)
            return true
        }
        pressOnCard = hitsCardBody(x, y)
        // A chevron press is a stationary button: hold it, never park it.
        park.begin(x, y, movesBlock = !pressExpandedToggle)
        return true
    }

    override fun onScroll(amountY: Float, x: Float, y: Float): Boolean {
        if (collapsed) return false
        body.wheel(amountY)
        return true
    }

    /** Advance the body's fling inertia; [deltaSec] comes from the host's frame. */
    fun advance(deltaSec: Float): Float = body.advance(deltaSec)

    /**
     * The offset a consumed wheel settled at, so the bridge can emit the player's
     * scroll through the card's scroll action instead of leaving it in the widget.
     */
    override fun scrollOffset(): Float = body.scrollY

    override fun scrollCoasting(): Boolean = body.isCoasting

    /** Move the parked block with the pointer after slop (8 dp, same as the pull tab). */
    override fun onPointerDrag(x: Float, y: Float): PointerUpResult? {
        if (!dragging || x.isNaN() || y.isNaN()) return null
        if (pressBodyScroll) {
            return PointerUpResult(bodyScrollY = body.dragTo(y))
        }
        park.drag(x, y)
        return null
    }

    /**
     * End the block gesture. Drag keeps the park in any fold state; a tap asks for
     * the fold (chevron — or the collapsed card body) or fires the CTA under the
     * press point.
     */
    override fun onPointerUp(x: Float, y: Float): PointerUpResult {
        if (!dragging) return PointerUpResult()
        val release = park.release()
        if (pressBodyScroll) {
            val scrolled = body.endPointer()
            val settledX = if (release.pressX.isNaN()) x else release.pressX
            val settledY = if (release.pressY.isNaN()) y else release.pressY
            cancelGesture()
            if (scrolled) return PointerUpResult(wasDrag = true, bodyScrollY = body.scrollY)
            if (hitsCollapseToggle(settledX, settledY)) return PointerUpResult(foldToggle = true)
            return PointerUpResult()
        }
        val moved = release.moved
        val chevronToggle = pressExpandedToggle
        val settledX = if (release.pressX.isNaN()) x else release.pressX
        val settledY = if (release.pressY.isNaN()) y else release.pressY
        cancelGesture()
        if (moved) return PointerUpResult(wasDrag = true, parked = true, parkX = parkX, parkY = parkY)
        if (chevronToggle) {
            // Commit the chevron toggle on release. Unlike the collapsed bar —
            // which judges by the press point because the bar follows the
            // finger — the chevron is a stationary button: release off it
            // cancels, like any press-release button.
            if (collapseButton.contains(x, y)) return PointerUpResult(foldToggle = true)
            return PointerUpResult()
        }
        if (hitsCollapseToggle(settledX, settledY)) return PointerUpResult(foldToggle = true)
        val action = actionAt(settledX, settledY)
        if (action != null) {
            return PointerUpResult(
                action = action,
                publicId = if (action == HandEndAction.Comparison) comparison?.publicId else null,
            )
        }
        return PointerUpResult()
    }

    override fun cancelGesture() {
        if (pressBodyScroll) body.cancel()
        park.cancel()
        pressOnCard = false
        pressExpandedToggle = false
        pressBodyScroll = false
    }

    // ── ParkableChrome ───────────────────────────────────────────────────────
    // The card is the block the board's HINT / claim chrome also are: the host
    // owns press → drag → release, and a release that never became a drag is the
    // card's own tap (fold toggle / CTA), never a board tap underneath.

    override val parkChromeLabel: String get() = "hand-end-card"

    override fun hitsParkChrome(worldX: Float, worldY: Float): Boolean =
        containsPanel(worldX, worldY) &&
            // Pattern-row deep-links stay click-only.
            (collapsed || patternInfoAt(worldX, worldY) == null)

    override fun onParkPointerDown(worldX: Float, worldY: Float): Boolean =
        onPointerDown(worldX, worldY)

    override fun onParkPointerDrag(worldX: Float, worldY: Float) {
        onPointerDrag(worldX, worldY)
    }

    override fun onParkPointerUp(worldX: Float, worldY: Float): ParkRelease {
        val up = onPointerUp(worldX, worldY)
        // The card swallows every release it claimed: the action it decided on a
        // tap is reported for the host to map, not re-dispatched as a board tap.
        return ParkRelease(
            consumed = true,
            handEndAction = up.action,
            detail = "wasDrag=${up.wasDrag} foldToggle=${up.foldToggle}",
        )
    }

    override fun cancelPark() = cancelGesture()

    /**
     * What a release resolved to: a drag (the card moved, or its body scrolled), the
     * fold control, a footer chip, or nothing.
     *
     * [foldToggle] is a request, not a state: the fold is remembered model state
     * (`ui.prefs`), so the card reports that the player pressed the control and the
     * owner flips the value it hands back in ([setCollapsed]).
     *
     * [parkX]/[parkY] are the applied offset when the *card* moved, so the component
     * can hand the player's arrangement to the model. A body scroll reports no park:
     * it did not move the card, and reporting zero would undo the place it is at.
     * [bodyScrollY] is the offset the body scroll settled at, or null when the
     * release did not scroll the body. [publicId] is the challenge whose hand tables
     * a [HandEndAction.Comparison] chip opens, read off the card's comparison.
     */
    data class PointerUpResult(
        val wasDrag: Boolean = false,
        val foldToggle: Boolean = false,
        val action: HandEndAction? = null,
        val parked: Boolean = false,
        val parkX: Float = 0f,
        val parkY: Float = 0f,
        val bodyScrollY: Float? = null,
        val publicId: String? = null,
    )

    /**
     * Paint the card and refresh its geometry.
     *
     * [bounds] is the rect the card may occupy: null means the window's safe
     * area, while a component composed into a provider's space hands in the pane
     * it belongs to (the tutorial's board pane). The pane is used flush — it is
     * already inside the safe area, and the chrome beside it is not the card's.
     */
    fun render(
        batch: SpriteBatch,
        camera: Camera,
        screenW: Float,
        screenH: Float,
        scrollY: Float,
        pointerWorldX: Float = Float.NaN,
        pointerWorldY: Float = Float.NaN,
        bounds: Rect? = null,
    ) {
        buttonHits.clear()
        patternInfoPageIds.clear()
        Fonts.ensureCreated()

        advanceFold(Gdx.graphics.deltaTime)
        val compactHeadline = headline
        val compactDetail = winSourceLabel.orEmpty()
        val layout = ScoreBreakdownLayout.measure(
            ScoreBreakdownMeasureInput(
                details = details,
                headline = headline,
                headlineLabel = headlineLabel,
                headlineDetail = headlineDetail,
                headlineDetailLabel = headlineDetailLabel,
                compactHeadline = compactHeadline,
                compactDetail = compactDetail,
                gameResult = gameResult,
                durationLabel = durationLabel,
                settlement = settlement,
                ratingLines = ratingLines,
                comparison = comparison,
                primaryAction = primaryAction,
                primaryLabel = primaryLabel,
                secondaryAction = secondaryAction,
                secondaryLabel = secondaryLabel,
                showActions = showActions,
                showScoreTable = showScoreTable,
                primaryEnabled = primaryEnabled,
                primaryTimeLeftMs = primaryTimeLeftMs,
                screenW = screenW,
                screenH = screenH,
                bounds = bounds,
                foldProgress = foldProgress,
                parkX = parkX,
                parkY = parkY,
            ),
            glyphLayout,
        )
        // The park geometry (rest pose + clamp bounds) is read off this layout,
        // so [applyLayout] must see the current screen size and card bounds.
        lastScreenW = screenW
        lastScreenH = screenH
        lastBounds = bounds
        applyLayout(layout, scrollY)

        painter.draw(
            batch = batch,
            camera = camera,
            px = whitePixel(),
            layout = layout,
            headline = headline,
            headlineLabel = headlineLabel,
            headlineTone = headlineTone,
            headlineDetail = headlineDetail,
            headlineDetailLabel = headlineDetailLabel,
            headlineDetailTone = headlineDetailTone,
            compactHeadline = compactHeadline,
            compactDetail = compactDetail,
            details = details,
            settlement = settlement,
            ratingLines = ratingLines,
            comparison = comparison,
            collapsed = collapsed,
            grabbing = grabHighlightActive(pointerWorldX, pointerWorldY),
            pointerWorldX = pointerWorldX,
            pointerWorldY = pointerWorldY,
            collapseButton = collapseButton,
            body = body,
            patternInfoButtons = patternInfoButtons,
            patternInfoPageIds = patternInfoPageIds,
            buttonHits = buttonHits,
        )
    }

    private fun applyLayout(layout: ScoreBreakdownLayout, scrollY: Float) {
        panelLeft = layout.panel.left
        panelBottom = layout.panel.bottom
        panelW = layout.panel.w
        panelH = layout.panel.h
        blockLeft = layout.block.left
        blockBottom = layout.block.bottom
        blockW = layout.block.w
        blockH = layout.block.h
        park.setPark(layout.parkX, layout.parkY)
        // Hand the drag the same geometry the layout just clamped against:
        // `block` is already parked, so its rest pose is the placed pose minus
        // the applied offset.
        park.setGeometry(
            restLeft = layout.block.left - layout.parkX,
            restBottom = layout.block.bottom - layout.parkY,
            blockW = layout.block.w,
            blockH = layout.block.h,
            bounds = clampBounds(),
        )
        if (layout.expandedAlpha > 0.01f && layout.body.height > 1f) {
            body.layout(
                panelLeft,
                layout.body.bottom,
                panelW,
                layout.body.height,
                layout.body.contentH,
                scrollY,
            )
        } else {
            body.layout(panelLeft, layout.body.bottom, panelW, 0f, 0f, 0f)
        }
    }

    /**
     * The rect the park clamps against. A card handed a pane (the tutorial's
     * board) uses it flush; otherwise it is the full safe-area board with no
     * outer margin so the card and its buttons can sit flush against any edge.
     */
    private fun clampBounds(): Rect =
        lastBounds ?: OverlayViewport.available(lastScreenW, lastScreenH, marginPx = 0f)

    /**
     * Place a collapsed block without [render] (unit tests; no Gdx / fonts).
     */
    internal fun seedLayoutForTest(
        screenW: Float = 800f,
        screenH: Float = 480f,
        blockW: Float = 240f,
        blockH: Float = 100f,
        chromeLeft: Float = -1f,
        chromeBottom: Float = -1f,
    ) {
        lastScreenW = screenW
        lastScreenH = screenH
        lastBounds = null
        this.blockW = blockW
        this.blockH = blockH
        blockLeft = (screenW - blockW) * 0.5f
        blockBottom = (screenH - blockH) * 0.5f
        panelW = blockW
        panelH = 48f
        panelLeft = blockLeft
        panelBottom = blockBottom + (blockH - panelH)
        park.setGeometry(blockLeft, blockBottom, blockW, blockH, clampBounds())
        buttonHits.clear()
        buttonHits += HandEndActionHit(
            HandEndAction.NextHand,
            blockLeft,
            blockBottom,
            110f,
            40f,
        )
        if (chromeLeft >= 0f && chromeBottom >= 0f) {
            collapseButton.layoutAt(chromeLeft, chromeBottom, 32f)
        }
    }

    /**
     * Overflowing body viewport for scroll-vs-park tests. Call after
     * [seedLayoutForTest].
     */
    internal fun seedBodyOverflowForTest(
        contentH: Float = 400f,
        viewH: Float = 80f,
        scrollY: Float = 0f,
    ) {
        body.layout(panelLeft, panelBottom, panelW, viewH, contentH, scrollY)
    }

    internal val bodyOverflowingForTest: Boolean get() = body.overflowingForTest
    internal val bodyScrollYForTest: Float get() = body.scrollY
    internal val pressBodyScrollForTest: Boolean get() = pressBodyScroll


    override fun dispose() {
    }
}
