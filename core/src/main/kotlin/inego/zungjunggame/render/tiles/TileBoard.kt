package inego.zungjunggame.render.tiles

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.render.ui.AppPalette

/**
 * Persistent visual tiles that always ease from [VisualTile.current] toward
 * [VisualTile.target]. Layout only supplies desired slots; this board owns
 * identity matching, spawn-from-wall, deal stagger, and drawing.
 */
class TileBoard {
    var moveDurationSec: Float = 0.3f
    var dealStaggerSec: Float = 0.005f

    /** Where a burst of new tiles appears (live-wall center, hand size). */
    var wallSpawnPose: TilePose = TilePose(0f, 0f, 0f, 40f, 54f, open = false)

    /**
     * Front face of the live-wall stack (1.5×, center). Set for a reconcile
     * where one tile leaves the wall; that spawn starts here. Null keeps
     * [wallSpawnPose].
     */
    var wallDeparturePose: TilePose? = null

    /** Tiles spawned from the wall by the last [reconcile]. */
    var wallSpawnCount: Int = 0
        private set

    /**
     * When set before [reconcile], prefer this visual id for the next same-type
     * assignment to a river end slot (human discard click). Cleared after use.
     */
    var preferredVisualId: Int = -1

    /** Preferred discard origin slot for opponent / networked discards. Cleared after use. */
    var preferredDiscardOrigin: SlotKey? = null

    /**
     * 0..1 extra intensity on [TileFocusGlow.Prompt] outlines
     * (PlayTableView drives a sine pulse).
     */
    var promptPulse: Float = 0f

    private var nextId: Int = 1
    private val tiles = ArrayList<VisualTile>(80)
    private val highlightBoxes = ArrayList<TileHighlightBox>(16)
    private val styleBoxes = ArrayList<TileHighlightBox>(16)
    private val readyBoxes = ArrayList<TileHighlightBox>(16)
    private val outlineColor = Color()

    fun tilesSnapshot(): List<VisualTile> = tiles.toList()

    fun isBusy(epsilon: Float = 0.01f): Boolean =
        tiles.any { !it.isSettled(moveDurationSec, epsilon) }

    fun clear() {
        tiles.clear()
        highlightBoxes.clear()
        readyBoxes.clear()
        preferredVisualId = -1
        preferredDiscardOrigin = null
        promptPulse = 0f
        nextId = 1
        wallSpawnCount = 0
    }

    /**
     * Replace all visuals with [seeds] already at their start poses. Next
     * [reconcile] retargets them toward live layout slots (min-distance /
     * slot-key match) so tiles ease instead of spawning from the wall marker.
     *
     * Used for wall-build → live table handoff.
     */
    fun seedAtPoses(seeds: List<SeededTile>) {
        tiles.clear()
        preferredVisualId = -1
        preferredDiscardOrigin = null
        nextId = 1
        for ((pose, tileType, seatIndex, slotKey, selectableHand) in seeds) {
            tiles += VisualTile(
                id = nextId++,
                tileType = tileType,
                seatIndex = seatIndex,
                slotKey = slotKey,
                selectableHand = selectableHand,
                isDrawnSlot = false,
                focusGlow = TileFocusGlow.None,
                tutorialOutline = TileFocusGlow.None,
                tutorialGroupKey = 0,
                current = pose,
                from = pose,
                target = pose,
                elapsed = moveDurationSec,
                startDelay = 0f,
            )
        }
    }

    /** One visual to inject before the first layout reconcile (handoff morph). */
    data class SeededTile(
        val pose: TilePose,
        val tileType: Int,
        val seatIndex: Int,
        val slotKey: SlotKey,
        val selectableHand: Boolean = false,
    )

    /**
     * Instantly place every visual at its current target (no flight, no deal delay).
     * Used when the viewport resizes so layout remaps do not ease across the screen.
     */
    fun snapAllToTargets() {
        val settled = moveDurationSec.coerceAtLeast(1e-4f)
        for (v in tiles) {
            v.from = v.target
            v.current = v.target
            v.elapsed = settled
            v.startDelay = 0f
        }
    }

    /**
     * Match existing visuals to [desired] slots; spawn missing tiles at the wall
     * marker; drop extras. When [dealStagger] is true, new spawns get 5 ms delays.
     */
    fun reconcile(desired: List<DesiredSlot>, dealStagger: Boolean = false) {
        wallSpawnCount = 0
        if (desired.isEmpty()) {
            tiles.clear()
            preferredVisualId = -1
            preferredDiscardOrigin = null
            return
        }

        val context = TileBoardReconcileContext(
            tiles = tiles,
            desired = desired,
            dealStagger = dealStagger,
            retarget = ::retarget,
        )
        val preferredId = preferredVisualId
        preferredVisualId = -1
        val preferredOrigin = preferredDiscardOrigin
        preferredDiscardOrigin = null

        val preferred = PreferredDiscardMatchStrategy(preferredId, preferredOrigin)
        listOf(
            preferred,
            ExactSlotMatchStrategy,
            DistanceMatchStrategy,
            HandSlideMatchStrategy,
        ).forEach { it.apply(context) }
        if (!preferred.visualHintUsed) preferredVisualId = preferredId
        if (!preferred.originHintUsed) preferredDiscardOrigin = preferredOrigin

        // Phase 4: drop unmatched visuals (instant — rare without fade).
        val nVis = tiles.size
        val nDes = desired.size
        if (context.hasUnusedVisuals) {
            val kept = ArrayList<VisualTile>(nVis)
            for (vi in 0 until nVis) {
                if (context.isVisualUsed(vi)) kept += tiles[vi]
            }
            tiles.clear()
            tiles.addAll(kept)
        }

        // Phase 5: spawn unmatched desired at wall marker.
        val spawnSlots = ArrayList<DesiredSlot>(8)
        for (di in 0 until nDes) {
            if (!context.isDesiredUsed(di)) spawnSlots += desired[di]
        }
        if (spawnSlots.isEmpty()) return
        wallSpawnCount = spawnSlots.size

        val departure = wallDeparturePose
        if (!dealStagger && spawnSlots.size == 1 && departure != null) {
            tiles += spawn(spawnSlots[0], startDelay = 0f, from = departure)
            return
        }

        if (dealStagger) {
            // Deterministic deal order: seat, then hand index, drawn last within seat.
            spawnSlots.sortWith(
                compareBy<DesiredSlot> { it.seatIndex }
                    .thenBy {
                        when (it.slotKey.kind) {
                            SlotKind.Hand -> 0
                            SlotKind.Drawn -> 1
                            SlotKind.Meld -> 2
                            SlotKind.River -> 3
                        }
                    }
                    .thenBy { it.slotKey.index }
                    .thenBy { it.slotKey.subIndex },
            )
            for ((i, d) in spawnSlots.withIndex()) {
                tiles += spawn(d, startDelay = i * dealStaggerSec)
            }
        } else {
            for (d in spawnSlots) {
                tiles += spawn(d, startDelay = 0f)
            }
        }
    }

    fun advance(dt: Float) {
        if (dt <= 0f) return
        val duration = moveDurationSec.coerceAtLeast(1e-4f)
        for (v in tiles) {
            if (v.startDelay > 0f) {
                v.startDelay -= dt
                // Stay parked on the live-wall marker until takeoff (tracks resize).
                val parked = wallSpawnPose.copy(open = false)
                v.from = parked
                v.current = parked
                if (v.startDelay > 0f) continue
                v.startDelay = 0f
                v.elapsed = 0f
            }
            if (v.elapsed >= duration) {
                v.current = v.target
                continue
            }
            v.elapsed = (v.elapsed + dt).coerceAtMost(duration)
            val u = TileEasing.easeOutCubic(v.elapsed / duration)
            v.current = interpolatePose(v.from, v.target, u)
        }
    }

    /**
     * Draw settled tiles first, then in-flight (so flights pass over the table).
     * Hover / recommend are draw-time modifiers for selectable hand tiles.
     * Play [TileFocusGlow] halos (pending discard, hover face match,
     * winning discard / hand) are drawn **under** each face — same in free play
     * and tutorial. Tutorial outlines ([Prompt]/[Deny]) are an extra ring;
     * adjacent copies in the same [inego.zungjunggame.render.table.HighlightGroup] share one outline, drawn
     * **after** faces so a neighbor cannot cover the stroke.
     *
     * When [drawSettled] / [drawFlying] are selective, the caller can insert the
     * live-wall marker between tile passes, then draw outlines in a last pass
     * ([drawTiles] false) so they sit above every tile.
     */
    fun draw(
        batch: SpriteBatch,
        drawer: TileDrawer,
        hoveredVisualId: Int = -1,
        recommendedTileTypes: Set<Int> = emptySet(),
        readyDiscardTypes: Set<Int> = emptySet(),
        hoverLiftFraction: Float = 0.14f,
        hoverScale: Float = 1.08f,
        drawSettled: Boolean = true,
        drawFlying: Boolean = true,
        drawTiles: Boolean = true,
        drawOutlines: Boolean = true,
        filter: ((VisualTile) -> Boolean)? = null,
    ) {
        val duration = moveDurationSec.coerceAtLeast(1e-4f)
        highlightBoxes.clear()
        readyBoxes.clear()
        if (drawOutlines) {
            for (v in tiles) {
                if (filter != null && !filter(v)) continue
                val settled = v.isSettled(duration)
                if (settled && !drawSettled) continue
                if (!settled && !drawFlying) continue
                collectHighlight(
                    v, hoveredVisualId, recommendedTileTypes,
                    hoverLiftFraction, hoverScale,
                )
                collectReady(
                    v, readyDiscardTypes, hoveredVisualId, recommendedTileTypes,
                    hoverLiftFraction, hoverScale,
                )
            }
        }
        if (drawTiles) {
            for (v in tiles) {
                if (filter != null && !filter(v)) continue
                val settled = v.isSettled(duration)
                if (settled && !drawSettled) continue
                if (!settled && !drawFlying) continue
                drawOne(
                    batch, drawer, v, hoveredVisualId, recommendedTileTypes,
                    hoverLiftFraction, hoverScale,
                )
            }
        }
        if (drawOutlines) {
            drawFocusOutlines(batch, drawer)
        }
    }

    /**
     * Selectable hand hits from **target** poses (stable for click while idle).
     * This is the *affordance* list — where a discard can be clicked, and where
     * MC labels and tutorial anchors attach. Face matching uses [hitAt].
     */
    fun buildHandHits(): List<HandHitInfo> {
        val out = ArrayList<HandHitInfo>(16)
        for (v in tiles) {
            if (!v.selectableHand) continue
            out += hitOf(v, v.target)
        }
        return out
    }

    /**
     * Topmost **visible** tile under the pointer (target poses), or null on empty
     * board. Reverse spawn order, so the drawn slot wins over the sorted hand and
     * later melds win over earlier ones.
     *
     * Hidden faces — opponents' concealed hands, concealed kongs — are never
     * returned: their identity must not leak through a highlight.
     */
    fun hitAt(worldX: Float, worldY: Float): HandHitInfo? {
        for (i in tiles.indices.reversed()) {
            val v = tiles[i]
            val p = v.target
            if (!p.open) continue
            val hw = p.w * 0.5f
            val hh = p.h * 0.5f
            if (worldX < p.x - hw || worldX > p.x + hw) continue
            if (worldY < p.y - hh || worldY > p.y + hh) continue
            return hitOf(v, p)
        }
        return null
    }

    /**
     * Current pose of the pending claim discard (river tile with a play [Soft]
     * / [WinDiscard] halo). Null when that halo is not on the board.
     */
    fun pendingDiscardHit(): HandHitInfo? {
        for (v in tiles) {
            if (v.slotKey.kind != SlotKind.River) continue
            if (v.focusGlow != TileFocusGlow.Soft && v.focusGlow != TileFocusGlow.WinDiscard) {
                continue
            }
            return hitOf(v, v.current)
        }
        return null
    }

    /**
     * Current pose of [humanSeat]'s copy of [type] (hand / drawn first, then
     * open meld). River copies are skipped.
     */
    fun youCopyHit(humanSeat: Int, type: Int): HandHitInfo? {
        var meld: VisualTile? = null
        for (v in tiles) {
            if (v.tileType != type || v.seatIndex != humanSeat) continue
            when (v.slotKey.kind) {
                SlotKind.Hand, SlotKind.Drawn -> return hitOf(v, v.current)
                SlotKind.Meld -> if (meld == null) meld = v
                SlotKind.River -> Unit
            }
        }
        return meld?.let { hitOf(it, it.current) }
    }

    private fun hitOf(v: VisualTile, p: TilePose): HandHitInfo {
        return HandHitInfo(
            visualId = v.id,
            worldX = p.x,
            worldY = p.y,
            w = p.w,
            h = p.h,
            tileType = v.tileType,
            isDrawnSlot = v.isDrawnSlot,
            slotIndex = if (v.isDrawnSlot) null else v.slotKey.index,
            selectableHand = v.selectableHand,
        )
    }

    /**
     * After [reconcile], mark every **visible** tile of [tileType] with
     * [TileFocusGlow.Match] so the hover preview can light every other copy of the
     * face the pointer is on — own hand, own melds, opponents' open melds, every
     * river, any seat. Region and turn do not matter.
     *
     * Does not override Soft / Win play halos (priority stays with those). Hidden
     * faces are skipped so a highlight cannot leak a concealed tile's identity.
     * Tutorial outlines are a separate field and are left alone.
     * Call once per frame after hover is known; next [reconcile] clears it.
     */
    fun highlightMatches(tileType: Int) {
        if (tileType < 0) return
        for (v in tiles) {
            if (!v.target.open) continue
            if (v.tileType != tileType) continue
            if (v.focusGlow != TileFocusGlow.None) continue
            v.focusGlow = TileFocusGlow.Match
        }
    }

    private fun spawn(d: DesiredSlot, startDelay: Float, from: TilePose? = null): VisualTile {
        val spawnPose = (from ?: wallSpawnPose).copy(open = false)
        return VisualTile(
            id = nextId++,
            tileType = d.tileType,
            seatIndex = d.seatIndex,
            slotKey = d.slotKey,
            selectableHand = d.selectableHand,
            isDrawnSlot = d.isDrawnSlot,
            focusGlow = d.focusGlow,
            tutorialOutline = d.tutorialOutline,
            tutorialGroupKey = d.tutorialGroupKey,
            current = spawnPose,
            from = spawnPose,
            target = d.pose,
            elapsed = 0f,
            startDelay = startDelay,
        )
    }

    private fun retarget(v: VisualTile, d: DesiredSlot) {
        v.tileType = d.tileType
        v.seatIndex = d.seatIndex
        v.slotKey = d.slotKey
        v.selectableHand = d.selectableHand
        v.isDrawnSlot = d.isDrawnSlot
        v.focusGlow = d.focusGlow
        v.tutorialOutline = d.tutorialOutline
        v.tutorialGroupKey = d.tutorialGroupKey

        if (v.current.nearlyEquals(d.pose) && v.startDelay <= 0f) {
            v.from = d.pose
            v.current = d.pose
            v.target = d.pose
            v.elapsed = moveDurationSec
            v.startDelay = 0f
            return
        }

        // Already flying to the same target — keep progress.
        if (v.target.nearlyEquals(d.pose) && v.startDelay <= 0f) {
            return
        }

        v.from = v.current
        v.target = d.pose
        v.elapsed = 0f
        // Do not re-apply deal delay on retarget mid-flight.
        if (v.startDelay > 0f && !v.from.nearlyEquals(wallSpawnPose, posEps = 2f)) {
            v.startDelay = 0f
        }
    }

    private fun interpolatePose(from: TilePose, to: TilePose, u: Float): TilePose {
        // Face: discrete mid-flip; pose carries open flag for the *display* side.
        val faceFrom = if (from.open) 1f else 0f
        val faceTo = if (to.open) 1f else 0f
        val face = TileEasing.lerp(faceFrom, faceTo, u)
        val openNow = face >= 0.5f
        return TilePose(
            x = TileEasing.lerp(from.x, to.x, u),
            y = TileEasing.lerp(from.y, to.y, u),
            rotationDeg = TileEasing.angleLerpDeg(from.rotationDeg, to.rotationDeg, u),
            w = TileEasing.lerp(from.w, to.w, u),
            h = TileEasing.lerp(from.h, to.h, u),
            open = openNow,
        )
    }

    private fun faceScaleX(from: TilePose, to: TilePose, elapsed: Float): Float {
        val duration = moveDurationSec.coerceAtLeast(1e-4f)
        if (from.open == to.open || elapsed >= duration) return 1f
        val u = TileEasing.easeOutCubic((elapsed / duration).coerceIn(0f, 1f))
        val faceFrom = if (from.open) 1f else 0f
        val faceTo = if (to.open) 1f else 0f
        val face = TileEasing.lerp(faceFrom, faceTo, u)
        val (_, scaleX) = TileEasing.faceDrawParams(face)
        return scaleX.coerceAtLeast(0.02f)
    }

    private fun collectHighlight(
        v: VisualTile,
        hoveredVisualId: Int,
        recommendedTileTypes: Set<Int>,
        hoverLiftFraction: Float,
        hoverScale: Float,
    ) {
        if (!v.tutorialOutline.isTutorialOutline) return
        val xform = drawXform(
            v, hoveredVisualId, recommendedTileTypes,
            hoverLiftFraction, hoverScale,
        )
        if (xform.scaleX <= 0.25f) return
        highlightBoxes += TileHighlightBox(
            cx = xform.cx,
            cy = xform.cy,
            w = xform.w * xform.scaleX,
            h = xform.h,
            rotationDeg = v.current.rotationDeg,
            groupKey = v.tutorialGroupKey,
            glow = v.tutorialOutline,
        )
    }

    /**
     * Collect the surround box for a selectable hand tile whose discard leaves a
     * scorable wait. Painted orange in [drawFocusOutlines], the same outline the
     * tutorial prompt uses, one per tile (not clustered).
     */
    private fun collectReady(
        v: VisualTile,
        readyDiscardTypes: Set<Int>,
        hoveredVisualId: Int,
        recommendedTileTypes: Set<Int>,
        hoverLiftFraction: Float,
        hoverScale: Float,
    ) {
        if (!v.selectableHand || v.tileType !in readyDiscardTypes) return
        val xform = drawXform(
            v, hoveredVisualId, recommendedTileTypes,
            hoverLiftFraction, hoverScale,
        )
        if (xform.scaleX <= 0.25f) return
        readyBoxes += TileHighlightBox(
            cx = xform.cx,
            cy = xform.cy,
            w = xform.w * xform.scaleX,
            h = xform.h,
            rotationDeg = v.current.rotationDeg,
            groupKey = 0,
            glow = TileFocusGlow.None,
        )
    }

    private class DrawXform(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val scaleX: Float,
        val hovered: Boolean,
        val recommended: Boolean,
    )

    private fun drawXform(
        v: VisualTile,
        hoveredVisualId: Int,
        recommendedTileTypes: Set<Int>,
        hoverLiftFraction: Float,
        hoverScale: Float,
    ): DrawXform {
        val p = v.current
        val scaleX = faceScaleX(v.from, v.target, v.elapsed)
        val hovered = v.selectableHand && v.id == hoveredVisualId
        val recommended =
            v.selectableHand && recommendedTileTypes.isNotEmpty() && v.tileType in recommendedTileTypes
        var cy = p.y
        var w = p.w
        var h = p.h
        when {
            hovered -> {
                cy += h * hoverLiftFraction
                w *= hoverScale
                h *= hoverScale
            }
            recommended -> {
                cy += h * hoverLiftFraction * 0.85f
                w *= 1.05f
                h *= 1.05f
            }
        }
        return DrawXform(p.x, cy, w, h, scaleX, hovered, recommended)
    }

    private fun drawOne(
        batch: SpriteBatch,
        drawer: TileDrawer,
        v: VisualTile,
        hoveredVisualId: Int,
        recommendedTileTypes: Set<Int>,
        hoverLiftFraction: Float,
        hoverScale: Float,
    ) {
        val xform = drawXform(
            v, hoveredVisualId, recommendedTileTypes,
            hoverLiftFraction, hoverScale,
        )
        val cx = xform.cx
        val cy = xform.cy
        val w = xform.w
        val h = xform.h
        val scaleX = xform.scaleX
        val p = v.current

        when {
            xform.hovered -> batch.setColor(AppPalette.tileHoverWash)
            xform.recommended -> batch.setColor(AppPalette.tileRecommendWash)
        }

        // Play halo under the tile so the ring sits outside the opaque face.
        // Tutorial Prompt/Deny are extra outlines drawn after all faces.
        when (v.focusGlow) {
            TileFocusGlow.Soft -> drawer.drawGlow(
                batch, cx, cy, w, h, p.rotationDeg,
                scaleX = scaleX,
                color = TileDrawer.FOCUS_GLOW_COLOR,
            )
            TileFocusGlow.Match -> drawer.drawGlow(
                batch, cx, cy, w, h, p.rotationDeg,
                scaleX = scaleX,
                color = TileDrawer.MATCH_GLOW_COLOR,
                expand = 1.9f,
            )
            TileFocusGlow.WinDiscard -> {
                drawer.drawGlow(
                    batch, cx, cy, w, h, p.rotationDeg,
                    scaleX = scaleX,
                    color = AppPalette.tint(AppPalette.warning, 0.55f),
                    expand = 2.45f,
                )
                drawer.drawGlow(
                    batch, cx, cy, w, h, p.rotationDeg,
                    scaleX = scaleX,
                    color = TileDrawer.WIN_DISCARD_GLOW_COLOR,
                    expand = 2.05f,
                )
            }
            TileFocusGlow.WinHand -> drawer.drawGlow(
                batch, cx, cy, w, h, p.rotationDeg,
                scaleX = scaleX,
                color = TileDrawer.WIN_HAND_GLOW_COLOR,
                expand = 1.95f,
            )
            else -> Unit
        }

        // Rivers default to a muted face; only match-on-hover (and Soft / Win
        // focus) stay full white so the copies that matter read clearly.
        val riverBright =
            v.focusGlow == TileFocusGlow.Match ||
                v.focusGlow == TileFocusGlow.Soft ||
                v.focusGlow == TileFocusGlow.WinDiscard
        val riverDim =
            v.slotKey.kind == SlotKind.River && !riverBright
        if (riverDim) {
            batch.setColor(AppPalette.tileRiverDim)
        }

        if (p.open && v.tileType in 0 until NUM_TILE_TYPES) {
            drawer.drawOpen(
                batch,
                v.tileType,
                cx,
                cy,
                w,
                h,
                p.rotationDeg,
                scaleX = scaleX,
                // Learner aid; `tagOrNull` folds the preference check in, so the
                // default (off) path resolves no text and costs one null test.
                label = TileLabels.tagOrNull(v.tileType),
            )
        } else {
            drawer.drawClosed(
                batch,
                cx,
                cy,
                w,
                h,
                p.rotationDeg,
                scaleX = scaleX,
            )
        }
        if (xform.hovered || xform.recommended || riverDim) {
            batch.setColor(Color.WHITE)
        }
    }

    private fun drawFocusOutlines(batch: SpriteBatch, drawer: TileDrawer) {
        if (highlightBoxes.isNotEmpty()) {
            for (glow in TileFocusGlow.entries) {
                if (!glow.isTutorialOutline) continue
                styleBoxes.clear()
                for (b in highlightBoxes) {
                    if (b.glow == glow) styleBoxes += b
                }
                if (styleBoxes.isEmpty()) continue
                val intensity =
                    if (glow == TileFocusGlow.Prompt || glow == TileFocusGlow.Secondary) {
                        0.78f + 0.22f * promptPulse.coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                drawer.drawGlowOutlines(batch, styleBoxes, colorForGlow(glow), intensity)
            }
        }
        // Ready discards wear a green surround, one box per tile so each
        // physical copy reads independently. Tutorial prompts stay orange.
        for ((cx, cy, w, h, rotationDeg) in readyBoxes) {
            drawer.drawGlowOutline(
                batch, cx, cy, w, h, rotationDeg,
                TileDrawer.READY_GLOW_COLOR,
            )
        }
    }

    private fun colorForGlow(glow: TileFocusGlow): Color {
        val src = when (glow) {
            TileFocusGlow.Prompt -> TileDrawer.PROMPT_GLOW_COLOR
            TileFocusGlow.Secondary -> TileDrawer.SECONDARY_GLOW_COLOR
            TileFocusGlow.Deny -> TileDrawer.DENY_GLOW_COLOR
            else -> return outlineColor
        }
        outlineColor.set(src)
        return outlineColor
    }

    data class HandHitInfo(
        val visualId: Int,
        val worldX: Float,
        val worldY: Float,
        val w: Float,
        val h: Float,
        val tileType: Int,
        val isDrawnSlot: Boolean,
        val slotIndex: Int? = null,
        /** True on a human hand tile offered as a discard (click / lift / labels). */
        val selectableHand: Boolean = false,
    )
}
