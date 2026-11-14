package inego.zungjunggame.render.wallbuild

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.engine.WALL_SIZE
import inego.zungjunggame.render.fonts.Fonts
import inego.zungjunggame.render.tiles.TileDrawer
import inego.zungjunggame.render.tiles.TileLabels
import inego.zungjunggame.render.tiles.TileHighlightBox
import inego.zungjunggame.render.ui.UiPlate
import inego.zungjunggame.tutorial.slide.layout.DiagramLayout
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import inego.zungjunggame.render.ui.AppPalette

/**
 * Wall-orientation lesson diagram: 34 faces → ×4 in square → face-down →
 * physics shuffle → 4×17×2 wall → walls pushed in (pinwheel joint) →
 * seat winds / dice / count / break / take-7 / dead wall / deal hands.
 *
 * **Navigation model (first-class):**
 * - [present] applies a pure [WallBuildBeat] for the coach body key.
 * - [WallBuildLessonState] holds the dice roll **and** the wall slot→tile map
 *   across [suspend] and same-lesson Back. The map is claimed from the shuffle
 *   heap when leaving shuffle, or a random permutation on first cold need —
 *   never an identity “sorted wall.”
 * - [clearLesson] drops that state when the host starts another lesson,
 *   restarts, or exits the tutorial.
 *
 * Tile indices are stable (4 of each type). Which index sits in which wall slot
 * is lesson state. Non-shuffle motion is current→target ease. Face-down uses
 * fixed 40:800 mm scale (docs/shuffle-sim-model.md).
 *
 * Layouts: [WallBuildPackLayouts] (faces / copies / face-down),
 * [WallBuildWallLayouts] (formed / push / deal / lined / dead),
 * [WallSlotLayout] (slot geometry). Motion: [WallBuildAnimTile],
 * [WallBuildShuffleSession]. Overlays: [WallBuildStackHighlights],
 * [WallSeatWindLabels], [DiceRollView].
 */
class WallBuildView {

    companion object {
        /** Physical square table side used by the shuffle sim (mm). */
        const val SHUFFLE_TABLE_MM = 800f
        /** Physical tile long side (mm). Short side uses [DiagramLayout.TILE_ASPECT]. */
        const val SHUFFLE_TILE_MM = 40f


        /** Flight time for heap → formed wall (all tiles ease together). */
        const val WALL_BUILD_MOVE_DURATION = 0.85f

        /** Ease duration when opening / closing the break gap (same phase). */
        private const val WALL_BREAK_MOVE_DURATION = 0.55f
        /** Ease duration when the take-7 stacks slide out into the dead wall. */
        private const val WALL_DEAD_MOVE_DURATION = 0.65f
        /** Ease when unstacking deal squares into a lined hand (or reverse). */
        private const val WALL_LOOK_HANDS_DURATION = 0.65f
    }

    /** @see WallBuildPhase */
    typealias Phase = WallBuildPhase

    private val countGlowColor = AppPalette.tint(AppPalette.accent, 0.90f)
    /** Orange outline for the 7 stacks taken to the right of the gap. */
    private val take7GlowColor = AppPalette.tint(AppPalette.liveMarker, 0.90f)
    private val countBoxes = ArrayList<TileHighlightBox>(24)
    private val take7Boxes = ArrayList<TileHighlightBox>(24)
    private val seatWinds = WallSeatWindLabels()
    private val highlights = WallBuildStackHighlights()

    private val tiles: Array<WallBuildAnimTile> = Array(WALL_SIZE) { i ->
        WallBuildAnimTile(type = i / 4, copy = i % 4)
    }

    /**
     * Next [WallBuildWallLayouts.layoutWall] should claim slots from current
     * tile positions (the shuffle heap) into [lesson]. Set when entering
     * [WallBuildPhase.Wall] from [WallBuildPhase.Shuffle]; sticky until consumed
     * so deferred [ensureArea] still claims.
     */
    private var claimWallFromHeap = false

    private var phase: WallBuildPhase? = null
    private var areaLeft = 0f
    private var areaBottom = 0f
    private var areaW = 0f
    private var areaH = 0f
    private var hasArea = false

    /**
     * When true (seats / east beats after push-in), draw large 東/南/西/北 +
     * English on the table-square edges: bottom East, right South, top West,
     * left North.
     */
    private var showSeatWinds = false
    /** When true, pulse East and dim the other three seat labels. */
    private var highlightEast = false
    /** When true, show two d6 tumbling/settled in the table hollow. */
    private var showDice = false
    private val diceRoll = DiceRollView()
    /**
     * Lesson-scoped dice roll + wall slot→tile map. Survives [suspend];
     * cleared only by [clearLesson].
     */
    private val lesson = WallBuildLessonState()
    /**
     * When true (break / take-7 / dead / hands), uncounted stacks on East’s wall
     * (left of the counted stacks) are shifted further left, opening a gap.
     */
    private var showWallBreak = false
    /**
     * When true (dead / hands), the take-7 stacks ease to an axis-aligned
     * horizontal row below the table ([WallSlotLayout.DEAD_WALL_DOWN_TILES]).
     */
    private var showDeadWall = false
    /**
     * When true (hands beat), 24 live stacks ease into three 2-stack squares
     * per seat just inside each rim.
     */
    private var showDealHands = false
    /** When true (hands_13 beat), 13th tile is dealt to each seat. */
    private var showDeal13thTile = false
    /** When true (east_14 beat), East draws the opening 14th tile. */
    private var showDeal14thEast = false
    /**
     * When true (look_hands / sort_hands), dealt hands re-form a line of 13/14;
     * East faces open, other seats stay closed. Unsorted deal order unless
     * [showSortHands].
     */
    private var showLookHands = false
    /**
     * When true with [showLookHands], line order is ascending tile type
     * (same as normal-table [inego.zungjunggame.render.seats.PlayerAreaLayout.expandHandWithDrawn]).
     */
    private var showSortHands = false
    /** Body key last applied by [present]; null after [suspend]/[clearLesson]. */
    private var presentedBodyKey: String? = null

    private var showSquareRim = false
    private var squareLeft = 0f
    private var squareBottom = 0f
    private var squareSide = 0f

    /** Physics shuffle + sequential hand strokes (mm sim, diagram display map). */
    private val shuffle = WallBuildShuffleSession(
        tableMm = SHUFFLE_TABLE_MM,
        tileMm = SHUFFLE_TILE_MM,
        tileAspect = DiagramLayout.TILE_ASPECT,
    )

    /**
     * Stop drawing and forget the current beat’s display flags, but **keep**
     * [lesson] (committed roll) and wall tile mapping so Back / re-present
     * restores the same break count and deal layout.
     *
     * Called from coach hide, complete panel, and non-wall diagrams.
     */
    fun suspend() {
        phase = null
        hasArea = false
        presentedBodyKey = null
        showSquareRim = false
        showSeatWinds = false
        highlightEast = false
        seatWinds.reset()
        showDice = false
        diceRoll.reset()
        highlights.clearCount()
        highlights.clearTake7()
        showWallBreak = false
        showDeadWall = false
        showDealHands = false
        showDeal13thTile = false
        showDeal14thEast = false
        showLookHands = false
        showSortHands = false
        shuffle.stop("suspend")
        for (t in tiles) {
            t.elapsed = 1f
        }
    }

    /**
     * Drop lesson-scoped state (dice + wall map) and display. Host calls this
     * when starting a different lesson, restarting, or exiting the tutorial —
     * not on ordinary slide hide / complete chrome.
     */
    fun clearLesson() {
        lesson.clear()
        suspend()
        claimWallFromHeap = false
        for (t in tiles) {
            t.open = true
        }
    }

    /**
     * Apply the wall-build beat for [bodyKey]. Layout and overlays are derived
     * from [WallBuildBeats.of] + [lesson]; safe to call after [suspend] or when
     * advancing/back within the wall lesson.
     *
     * Call again after [ensureArea] if the diagram rect was not ready yet.
     */
    fun present(bodyKey: String) {
        val beat = WallBuildBeats.of(bodyKey) ?: return
        val prevPhase = phase
        val prevBody = presentedBodyKey
        val prevBreak = showWallBreak
        val prevDead = showDeadWall
        val prevDeal = showDealHands
        val prevDeal13 = showDeal13thTile
        val prevDeal14 = showDeal14thEast
        val prevLook = showLookHands
        val prevSort = showSortHands
        val wasShowingDice = showDice && diceRoll.visible

        applyBeatDisplay(beat)
        applyDicePolicy(beat, wasShowingDice)
        presentedBodyKey = bodyKey

        val next = beat.phase
        if (prevPhase == next && hasArea && prevBody != null) {
            // Stay on WallPush: retarget only when layout overlays change.
            // Coach-only body keys that share flags (e.g. sort_hands → east_discard,
            // dead → dead_out) keep the diagram still.
            if (next == WallBuildPhase.WallPush &&
                (prevBreak != showWallBreak ||
                    prevDead != showDeadWall ||
                    prevDeal != showDealHands ||
                    prevDeal13 != showDeal13thTile ||
                    prevDeal14 != showDeal14thEast ||
                    prevLook != showLookHands ||
                    prevSort != showSortHands)
            ) {
                when {
                    // First open deal (dead → hands, or hands_13 entered without hands).
                    showDealHands && !prevDeal && !showLookHands ->
                        animateWallPushRetarget(dealHandsSequential = true)
                    // hands → hands_13: only the four leftover tiles fly, one seat at a time.
                    showDealHands && showDeal13thTile && !prevDeal13 && !showLookHands ->
                        animateWallPushRetarget(dealHandsSequential = true, only13thTiles = true)
                    // hands_13 → east_14: only East's 14th tile flies from the wall.
                    showDealHands && showDeal14thEast && !prevDeal14 && !showLookHands ->
                        animateWallPushRetarget(dealHandsSequential = true, only14thEast = true)
                    // east_14 ↔ look_hands: unstack into a line (or restack) + flip East.
                    // look_hands ↔ sort_hands: reorder the line by tile type.
                    showDealHands &&
                        (showLookHands != prevLook || showSortHands != prevSort) ->
                        animateWallPushRetarget(duration = WALL_LOOK_HANDS_DURATION)
                    // hands_13 → hands: ease the 13th tiles back onto the wall; hands stay put.
                    // east_14 → hands_13: ease East's 14th back; 13s stay put.
                    showDealHands &&
                        ((!showDeal13thTile && prevDeal13) ||
                            (!showDeal14thEast && prevDeal14)) ->
                        animateWallPushRetarget(duration = WallBuildWallLayouts.DEAL_BLOCK_DURATION)
                    else -> {
                        val duration = when {
                            showDeadWall != prevDead || showDeadWall -> WALL_DEAD_MOVE_DURATION
                            else -> WALL_BREAK_MOVE_DURATION
                        }
                        animateWallPushRetarget(duration = duration)
                    }
                }
            }
            return
        }

        val animate = prevPhase != null && hasArea
        val enteringShuffle = next == WallBuildPhase.Shuffle && prevPhase != WallBuildPhase.Shuffle
        if (enteringShuffle) {
            shuffle.reseedRng()
        }
        if (next != WallBuildPhase.Shuffle) {
            if (prevPhase == WallBuildPhase.Shuffle) {
                shuffle.applyPosesToTiles(tiles)
            }
            shuffle.stop("phase=$next from=$prevPhase")
        }
        // Claim wall slots from the heap only when leaving shuffle — not on
        // every animated Wall layout (that used to conflate ease with remap).
        // Sticky until [layoutWall] consumes it so a deferred ensureArea still
        // claims; also covers Shuffle → Wall (no area) → WallPush.
        if (next == WallBuildPhase.Wall && prevPhase == WallBuildPhase.Shuffle) {
            claimWallFromHeap = true
        }
        phase = next
        if (hasArea) {
            applyLayout(animate)
        }
    }

    /** @deprecated Use [present]. Kept so any leftover call sites compile during refactor. */
    fun sync(bodyKey: String) = present(bodyKey)

    /** @deprecated Use [suspend] / [clearLesson]. */
    fun reset(clearCommittedDice: Boolean = false) {
        if (clearCommittedDice) clearLesson() else suspend()
    }

    /** Copy pure beat flags onto display state (no dice side effects). */
    private fun applyBeatDisplay(beat: WallBuildBeat) {
        showSeatWinds = beat.showSeatWinds
        highlightEast = beat.highlightEast
        showWallBreak = beat.showWallBreak
        showDeadWall = beat.showDeadWall
        showDealHands = beat.showDealHands
        showDeal13thTile = beat.showDeal13thTile
        showDeal14thEast = beat.showDeal14thEast
        showLookHands = beat.showLookHands
        showSortHands = beat.showSortHands

        if (beat.showCountHighlight) {
            highlights.beginCount(
                when {
                    lesson.hasRoll -> lesson.stackCount
                    diceRoll.allAtRest -> diceRoll.faceSum.coerceIn(2, 12)
                    else -> 0
                },
            )
        } else {
            highlights.clearCount()
        }

        when (beat.take7) {
            WallBuildBeat.Take7Policy.Off -> highlights.clearTake7()
            WallBuildBeat.Take7Policy.Reveal -> highlights.beginTake7Reveal()
            WallBuildBeat.Take7Policy.Full -> highlights.setTake7Full()
        }
    }

    /**
     * Apply [WallBuildBeat.DicePolicy] against [lesson]. Ensures a roll exists
     * whenever the beat needs a break count (including cold re-entry after
     * [clearLesson] then land on hands via Back from another lesson).
     */
    private fun applyDicePolicy(beat: WallBuildBeat, wasShowingDice: Boolean) {
        when (beat.dice) {
            WallBuildBeat.DicePolicy.ClearRoll -> {
                // Pre-roll beats: drop dice only. Wall mapping is independent.
                lesson.clearRoll()
                showDice = false
                diceRoll.reset()
            }
            WallBuildBeat.DicePolicy.RollOrRestore -> {
                if (!lesson.hasRoll) {
                    diceRoll.startRoll()
                    lesson.commit(diceRoll.faceA, diceRoll.faceB)
                    showDice = true
                } else if (wasShowingDice && diceRoll.visible) {
                    // Mid-tumble on dice beat — leave the roll view alone.
                    showDice = true
                } else {
                    diceRoll.showSettled(lesson.faceA!!, lesson.faceB!!)
                    showDice = true
                }
            }
            WallBuildBeat.DicePolicy.ShowSettled -> {
                ensureLessonRoll(snap = true)
                diceRoll.showSettled(lesson.faceA!!, lesson.faceB!!)
                showDice = true
            }
            WallBuildBeat.DicePolicy.KeepRollHidden -> {
                ensureLessonRoll(snap = true)
                diceRoll.reset()
                showDice = false
            }
        }
    }

    /**
     * If this lesson has no committed roll yet, snap a new one into [lesson].
     * Used when landing on post-roll beats (count…hands) without having visited
     * the dice beat in this lesson session.
     */
    private fun ensureLessonRoll(snap: Boolean) {
        if (lesson.hasRoll) return
        diceRoll.startRoll()
        if (snap) diceRoll.snapToRest()
        lesson.commit(diceRoll.faceA, diceRoll.faceB)
    }

    /**
     * Re-run pinwheel targets (break gap, dead wall, and/or deal hands) and ease
     * from the current poses. Used when staying on [WallBuildPhase.WallPush]
     * while those overlays toggle.
     *
     * When [dealHandsSequential] is true, dealt tiles fly in deal order
     * ([WallBuildWallLayouts.startDealHandsStaggeredMotion]); other tiles snap.
     * [only13thTiles] limits that stagger to the four leftover tiles after the
     * 12-tile deal; [only14thEast] to East's opening draw.
     */
    private fun animateWallPushRetarget(
        duration: Float = WALL_BREAK_MOVE_DURATION,
        dealHandsSequential: Boolean = false,
        only13thTiles: Boolean = false,
        only14thEast: Boolean = false,
    ) {
        layoutWallPush()
        if (dealHandsSequential && showDealHands) {
            WallBuildWallLayouts.startDealHandsStaggeredMotion(
                tiles = tiles,
                lesson = lesson,
                breakStackCount = committedStackCount(),
                overlays = pushOverlays(),
                only13thTiles = only13thTiles,
                only14thEast = only14thEast,
            )
            return
        }
        for (t in tiles) {
            t.beginEaseFromCurrent(duration)
        }
    }

    /**
     * Dice faces for the count caption (`x+y=z`). Prefers the lesson roll so the
     * caption stays stable even mid-tumble / after Back.
     */
    fun diceFacesOrNull(): Triple<Int, Int, Int>? {
        lesson.facesOrNull()?.let { return it }
        if (!showDice || !diceRoll.visible) return null
        val a = diceRoll.faceA
        val b = diceRoll.faceB
        if (a !in 1..6 || b !in 1..6) return null
        return Triple(a, b, a + b)
    }

    fun ensureArea(left: Float, bottom: Float, w: Float, h: Float) {
        val moved = !hasArea ||
            abs(left - areaLeft) > 0.5f ||
            abs(bottom - areaBottom) > 0.5f ||
            abs(w - areaW) > 0.5f ||
            abs(h - areaH) > 0.5f
        areaLeft = left
        areaBottom = bottom
        areaW = w
        areaH = h
        val ready = w > 1f && h > 1f
        val firstArea = ready && !hasArea
        hasArea = ready
        if (!hasArea || phase == null) return
        if (firstArea) {
            applyLayout(animate = false)
        } else if (moved) {
            if (phase == WallBuildPhase.Shuffle) {
                // Sim stays in fixed mm; only remount the display transform.
                val sq = computeSquare()
                squareLeft = sq.left
                squareBottom = sq.bottom
                squareSide = sq.side
                if (shuffle.active) {
                    shuffle.setDisplayMap(sq.left, sq.bottom, sq.side)
                    shuffle.applyPosesToTiles(tiles)
                } else {
                    shuffle.start(
                        squareLeft = sq.left,
                        squareBottom = sq.bottom,
                        squareSide = sq.side,
                        tiles = tiles,
                        seedFromCurrentTiles = WallBuildAnimTile.anyHasPose(tiles),
                    )
                }
            } else {
                applyLayout(animate = false)
            }
        }
    }

    /** Shuffle, in-flight tiles, tumbling dice, or a beat that pulses. */
    fun needsFrames(): Boolean {
        if (phase == null) return false
        if (phase == WallBuildPhase.Shuffle) return true
        if (tiles.any { !it.motionSettled }) return true
        if (showDice && diceRoll.visible && !diceRoll.allAtRest) return true
        if (showSeatWinds && highlightEast) return true
        if (highlights.showCount || highlights.showTake7) return true
        return false
    }

    fun update(dtSec: Float) {
        if (dtSec <= 0f || phase == null) return
        if (showSeatWinds) {
            seatWinds.update(dtSec, highlightEast)
        }
        if (showDice) {
            diceRoll.update(dtSec)
        }
        if (highlights.showCount) {
            highlights.updateCount(dtSec, diceRoll.allAtRest, diceRoll.faceSum)
        }
        if (highlights.showTake7) {
            highlights.updateTake7(dtSec, committedStackCount())
        }
        if (phase == WallBuildPhase.Shuffle) {
            shuffle.update(dtSec, squareLeft, squareBottom, squareSide, tiles)
            return
        }
        for (t in tiles) t.advance(dtSec)
        // Face-down: square only after tiles finish scaling to 40:800.
        if (phase == WallBuildPhase.FaceDown && !showSquareRim && tiles.all { it.motionSettled }) {
            showSquareRim = true
        }
    }

    fun draw(batch: SpriteBatch, drawer: TileDrawer, whitePixel: Texture, alpha: Float) {
        if (!hasArea || phase == null || alpha < 0.01f) return

        if (showSquareRim && squareSide > 1f) {
            val border = Fonts.px(2.5f).coerceAtLeast(1.5f)
            UiPlate.drawFrame(
                batch, whitePixel, squareLeft, squareBottom, squareSide, squareSide,
                AppPalette.tint(AppPalette.accent, 0.55f * alpha), border,
            )
        }

        // Seat winds under tiles so wall / hand stacks paint over labels where they overlap.
        if (showSeatWinds) {
            val pxPerMm = (squareSide / SHUFFLE_TABLE_MM).coerceAtLeast(1e-4f)
            val tileH = SHUFFLE_TILE_MM * pxPerMm
            val tileW = tileH * DiagramLayout.TILE_ASPECT
            // Hands beat: pull labels past the hand squares (1 tile radial + HAND_IN).
            val inwardShift = if (showDealHands) {
                tileH + WallSlotLayout.HAND_IN_TILES * tileW + Fonts.px(8f)
            } else {
                0f
            }
            seatWinds.draw(
                batch = batch,
                squareLeft = squareLeft,
                squareBottom = squareBottom,
                squareSide = squareSide,
                tableMm = SHUFFLE_TABLE_MM,
                tileMm = SHUFFLE_TILE_MM,
                highlightEast = highlightEast,
                alpha = alpha,
                inwardShiftPx = inwardShift,
            )
        }

        val prev = batch.color.cpy()
        // Painter: all bottom floors, then all top floors; within a floor,
        // lower Y first, then copy, closed under open.
        val order = tiles.indices.sortedWith(
            compareBy<Int> { tiles[it].stackFloor }
                .thenBy { tiles[it].cy }
                .thenBy { tiles[it].copy }
                .thenBy { if (tiles[it].open) 1 else 0 },
        )
        val countPulse = if (highlights.showCount) {
            1f + 0.12f * sin(highlights.countPulsePhase)
        } else {
            1f
        }
        val take7Pulse = if (highlights.showTake7) {
            1f + 0.12f * sin(highlights.take7PulsePhase)
        } else {
            1f
        }
        val tileInSlot = { slot: Int -> WallBuildWallLayouts.tileInSlot(lesson, slot) }
        val breakCount = committedStackCount()
        countBoxes.clear()
        take7Boxes.clear()
        for (i in order) {
            val t = tiles[i]
            if (t.w < 1f || t.h < 1f) continue
            when {
                highlights.isCountHighlighted(i, tileInSlot) -> {
                    countBoxes += TileHighlightBox(t.cx, t.cy, t.w, t.h, t.rot, groupKey = 0)
                }
                highlights.isTake7Highlighted(i, breakCount, tileInSlot) -> {
                    take7Boxes += TileHighlightBox(t.cx, t.cy, t.w, t.h, t.rot, groupKey = 1)
                }
            }
        }
        for (i in order) {
            val t = tiles[i]
            if (t.w < 1f || t.h < 1f) continue
            // Highlight is outline only — non-lit tiles stay full brightness.
            batch.setColor(prev.r, prev.g, prev.b, prev.a * alpha)
            if (t.open) {
                drawer.drawOpen(
                    batch, t.type, t.cx, t.cy, t.w, t.h, t.rot,
                    label = TileLabels.tagOrNull(t.type),
                )
            } else {
                drawer.drawClosed(batch, t.cx, t.cy, t.w, t.h, t.rot)
            }
        }
        // After faces so a neighbor stack cannot cover the stroke.
        if (countBoxes.isNotEmpty()) {
            countGlowColor.a = (0.90f * countPulse * alpha).coerceIn(0.25f, 1f)
            drawer.drawGlowOutlines(batch, countBoxes, countGlowColor)
        }
        if (take7Boxes.isNotEmpty()) {
            take7GlowColor.a = (0.90f * take7Pulse * alpha).coerceIn(0.25f, 1f)
            drawer.drawGlowOutlines(batch, take7Boxes, take7GlowColor)
        }
        batch.color = prev

        if (showDice && diceRoll.visible && squareSide > 8f) {
            val cx = squareLeft + squareSide * 0.5f
            val cy = squareBottom + squareSide * 0.5f
            val pxPerMm = (squareSide / SHUFFLE_TABLE_MM).coerceAtLeast(1e-4f)
            val tileH = SHUFFLE_TILE_MM * pxPerMm
            val dieSize = min(tileH * 1.55f, squareSide * 0.12f).coerceAtLeast(Fonts.px(22f))
            diceRoll.draw(batch, whitePixel, cx, cy, dieSize, alpha)
        }
    }

    private fun applyLayout(animate: Boolean) {
        val p = phase ?: return
        val duration = when (p) {
            WallBuildPhase.Faces -> 0.35f
            WallBuildPhase.Copies -> 0.55f
            WallBuildPhase.FaceDown -> 0.4f
            WallBuildPhase.Shuffle -> 0f
            WallBuildPhase.Wall -> WALL_BUILD_MOVE_DURATION
            WallBuildPhase.WallPush -> 1.0f
        }
        val sq = computeSquare()
        squareLeft = sq.left
        squareBottom = sq.bottom
        squareSide = sq.side

        // Square only after face-down is at true 40:800 scale (not while easing
        // down). Shuffle/wall keep the rim; earlier phases never show it.
        showSquareRim = when (p) {
            WallBuildPhase.FaceDown -> !animate // snap → already correct scale; ease → wait
            WallBuildPhase.Shuffle, WallBuildPhase.Wall, WallBuildPhase.WallPush -> true
            else -> false
        }

        // Default: single layer (faces / copies / face-down / shuffle).
        // Wall layouts set 0=bottom, 1=top per stack.
        for (t in tiles) t.stackFloor = 0

        when (p) {
            WallBuildPhase.Faces -> layoutPackFaces()
            WallBuildPhase.Copies -> layoutPackCopies()
            WallBuildPhase.FaceDown -> layoutPackFaceDown()
            WallBuildPhase.Shuffle -> {
                shuffle.start(
                    squareLeft = sq.left,
                    squareBottom = sq.bottom,
                    squareSide = sq.side,
                    tiles = tiles,
                    seedFromCurrentTiles = animate || WallBuildAnimTile.anyHasPose(tiles),
                )
                for (t in tiles) {
                    t.toOpen = false
                    t.open = false
                    t.duration = 0.01f
                    t.elapsed = t.duration
                }
                return
            }
            // fromHeap only after shuffle (see [claimWallFromHeap]); snap/resize
            // and later re-entry keep the same slot→tile map.
            WallBuildPhase.Wall -> {
                WallBuildWallLayouts.layoutWall(
                    tiles = tiles,
                    lesson = lesson,
                    m = wallMetrics(),
                    fromHeap = claimWallFromHeap,
                )
                claimWallFromHeap = false
            }
            WallBuildPhase.WallPush -> {
                // Pending claim if formed never laid out (area not ready yet).
                if (claimWallFromHeap) {
                    WallBuildWallLayouts.layoutWall(
                        tiles = tiles,
                        lesson = lesson,
                        m = wallMetrics(),
                        fromHeap = true,
                    )
                    claimWallFromHeap = false
                }
                layoutWallPush()
            }
        }

        // Deal hands: sequential stack flights even on a fresh WallPush layout.
        // look_hands uses a simultaneous ease (already at lined targets).
        if (animate && p == WallBuildPhase.WallPush && showDealHands && !showLookHands) {
            WallBuildWallLayouts.startDealHandsStaggeredMotion(
                tiles = tiles,
                lesson = lesson,
                breakStackCount = committedStackCount(),
                overlays = pushOverlays(),
            )
            return
        }

        for (t in tiles) {
            if (animate) {
                t.beginEaseFromCurrent(duration)
            } else {
                t.duration = duration
                t.snapToTarget()
            }
        }
    }

    private val packTargetSink = WallBuildPackLayouts.TargetSink { tileIndex, cx, cy, w, h, rot, open ->
        tiles[tileIndex].setTarget(cx, cy, w, h, rot, open)
    }

    private fun layoutPackFaces() {
        WallBuildPackLayouts.layoutFaces(
            areaLeft = areaLeft,
            areaBottom = areaBottom,
            areaW = areaW,
            areaH = areaH,
            tileAspect = DiagramLayout.TILE_ASPECT,
            setTarget = packTargetSink,
        )
    }

    private fun layoutPackCopies() {
        WallBuildPackLayouts.layoutCopies(
            areaLeft = areaLeft,
            areaBottom = areaBottom,
            areaW = areaW,
            areaH = areaH,
            inSquare = false,
            open = true,
            tileAspect = DiagramLayout.TILE_ASPECT,
            setTarget = packTargetSink,
        )
    }

    private fun layoutPackFaceDown() {
        val sq = computeSquare()
        WallBuildPackLayouts.layoutFaceDown(
            squareLeft = sq.left,
            squareBottom = sq.bottom,
            squareSide = sq.side,
            tableMm = SHUFFLE_TABLE_MM,
            tileMm = SHUFFLE_TILE_MM,
            tileAspect = DiagramLayout.TILE_ASPECT,
            setTarget = packTargetSink,
        )
    }

    private fun layoutWallPush() {
        WallBuildWallLayouts.layoutWallPush(
            tiles = tiles,
            lesson = lesson,
            m = wallMetrics(),
            breakStackCount = if (showWallBreak) committedStackCount() else 0,
            overlays = pushOverlays(),
        )
    }

    private fun pushOverlays() = WallBuildWallLayouts.PushOverlays(
        showDeadWall = showDeadWall,
        showDealHands = showDealHands,
        showDeal13thTile = showDeal13thTile,
        showDeal14thEast = showDeal14thEast,
        showLookHands = showLookHands,
        showSortHands = showSortHands,
    )

    private fun wallMetrics(): WallSlotLayout.Metrics {
        val sq = computeSquare()
        return WallSlotLayout.metrics(
            sq = WallSlotLayout.TableSquare(sq.left, sq.bottom, sq.side),
            tableMm = SHUFFLE_TABLE_MM,
            tileMm = SHUFFLE_TILE_MM,
            tileAspect = DiagramLayout.TILE_ASPECT,
        )
    }

    private data class Square(val left: Float, val bottom: Float, val side: Float)

    private fun computeSquare(): Square {
        val pad = Fonts.px(6f)
        val side = (min(areaW, areaH) - pad * 2f).coerceAtLeast(8f)
        return Square(
            left = areaLeft + (areaW - side) * 0.5f,
            bottom = areaBottom + (areaH - side) * 0.5f,
            side = side,
        )
    }

    /**
     * Portable deal snapshot, or null when dice/map are incomplete.
     * Does **not** invent a roll or mapping.
     */
    fun exportDeal(): WallBuildDeal? = lesson.exportDeal()

    /**
     * Reinstall a durable deal (same-lesson Back, cold restore, host re-sync).
     * Safe while suspended; the next [present] / layout reads this map.
     */
    fun installDeal(deal: WallBuildDeal) {
        lesson.installDeal(deal)
    }


    /**
     * Hands plus residual live/dead wall poses for the fluid table handoff.
     * Empty when lined hands are not laid out yet.
     */
    fun exportHandoffSnapshot(): WallBuildHandoffSnapshot =
        WallBuildWallLayouts.exportHandoffSnapshot(
            tiles = tiles,
            lesson = lesson,
            hasArea = hasArea,
            showLookHands = showLookHands,
            overlays = pushOverlays(),
        )

    /** Dice-sum stack count for the break gap (2..12 when lesson has a roll). */
    private fun committedStackCount(): Int = when {
        lesson.hasRoll -> lesson.stackCount
        highlights.countStacksTarget in 2..12 -> highlights.countStacksTarget
        else -> 0
    }
}
