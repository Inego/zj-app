package inego.zungjunggame.render.wallbuild

import inego.zungjunggame.engine.WALL_SIZE

/**
 * Formed-wall and later overlays: 4×17×2 wall, pinwheel push, break gap,
 * dead wall, deal squares, lined hands, and sequential deal flight.
 *
 * Writes target poses on [WallBuildAnimTile]; the view owns easing. Slot→tile
 * mapping lives on [WallBuildLessonState] (heap claim or cold shuffle).
 */
internal object WallBuildWallLayouts {

    /** Flight time for one dealt 2-stack square (or a leftover 13th/14th). */
    const val DEAL_BLOCK_DURATION = 0.55f

    /**
     * Overlay flags that change pinwheel targets after the wall is pushed in.
     * Break gap is encoded by [breakStackCount] (0 = no gap).
     */
    data class PushOverlays(
        val showDeadWall: Boolean,
        val showDealHands: Boolean,
        val showDeal13thTile: Boolean,
        val showDeal14thEast: Boolean,
        val showLookHands: Boolean,
        val showSortHands: Boolean,
    )

    /** Diagram tile index currently assigned to formed-wall [slot]. */
    fun tileInSlot(lesson: WallBuildLessonState, slot: Int): Int {
        lesson.ensureShuffledWallMapping()
        return lesson.tileAtSlot(slot)
    }

    /**
     * 4×17×2 wall at the same fixed 40:800 mm scale as face-down / shuffle.
     *
     * When [fromHeap] is true (shuffle → formed): each slot takes the closest
     * remaining heap tile; the resulting permutation is committed to [lesson].
     *
     * When [fromHeap] is false: reuse [lesson] mapping (cold-filling a random
     * permutation only if none exists yet).
     */
    fun layoutWall(
        tiles: Array<WallBuildAnimTile>,
        lesson: WallBuildLessonState,
        m: WallSlotLayout.Metrics,
        fromHeap: Boolean,
    ) {
        if (!fromHeap) {
            lesson.ensureShuffledWallMapping()
            for (side in 0 until WallSlotLayout.SIDE_COUNT) {
                for (alongI in 0 until WallSlotLayout.STACKS_PER_SIDE) {
                    val upper = WallSlotLayout.formedUpperPose(side, alongI, m)
                    for (floor in 0 until WallSlotLayout.FLOORS_PER_STACK) {
                        val slot = WallSlotLayout.slotId(side, alongI, floor)
                        assignWallTile(tiles[lesson.tileAtSlot(slot)], upper, floor, m)
                    }
                }
            }
            return
        }

        val mapping = IntArray(WALL_SIZE)
        val used = BooleanArray(WALL_SIZE)
        // Bottom floor of every stack, then top floor — each along-index is a
        // round of four (one tile per player), starting at each player's left.
        for (floor in 0 until WallSlotLayout.FLOORS_PER_STACK) {
            for (alongI in 0 until WallSlotLayout.STACKS_PER_SIDE) {
                for (side in 0 until WallSlotLayout.SIDE_COUNT) {
                    val upper = WallSlotLayout.formedUpperPose(side, alongI, m)
                    val (tx, ty) = WallSlotLayout.floorTarget(upper, floor, m)
                    var best = -1
                    var bestD = Float.MAX_VALUE
                    for (i in 0 until WALL_SIZE) {
                        if (used[i]) continue
                        val t = tiles[i]
                        val dx = t.cx - tx
                        val dy = t.cy - ty
                        val d = dx * dx + dy * dy
                        if (d < bestD) {
                            bestD = d
                            best = i
                        }
                    }
                    check(best >= 0) { "wall build: no free tile for slot" }
                    used[best] = true
                    val slot = WallSlotLayout.slotId(side, alongI, floor)
                    mapping[slot] = best
                    assignWallTile(tiles[best], upper, floor, m)
                }
            }
        }
        lesson.commitWallMapping(mapping)
    }

    /**
     * Push-in: four 17×2 segments in a 4-fold pinwheel about the table center.
     * Optional break gap, dead-wall row, deal squares / 13ths / East 14th, or
     * lined look/sort hands.
     */
    fun layoutWallPush(
        tiles: Array<WallBuildAnimTile>,
        lesson: WallBuildLessonState,
        m: WallSlotLayout.Metrics,
        breakStackCount: Int,
        overlays: PushOverlays,
    ) {
        lesson.ensureShuffledWallMapping()
        var eastOriginX = 0f
        var eastOriginY = 0f
        var eastUx = 0f
        var eastUy = 0f
        var eastVx = 0f
        var eastVy = 0f
        WallSlotLayout.forEachPushedSide(m) { side, originX, originY, ux, uy, vx, vy ->
            if (side == WallSlotLayout.EAST_SIDE) {
                eastOriginX = originX
                eastOriginY = originY
                eastUx = ux
                eastUy = uy
                eastVx = vx
                eastVy = vy
            }
            for (alongI in 0 until WallSlotLayout.STACKS_PER_SIDE) {
                val upper = WallSlotLayout.pushedUpperPose(
                    side = side,
                    alongI = alongI,
                    m = m,
                    breakStackCount = breakStackCount,
                    originX = originX,
                    originY = originY,
                    ux = ux,
                    uy = uy,
                    vx = vx,
                    vy = vy,
                )
                for (floor in 0 until WallSlotLayout.FLOORS_PER_STACK) {
                    val slot = WallSlotLayout.slotId(side, alongI, floor)
                    assignWallTile(tiles[tileInSlot(lesson, slot)], upper, floor, m)
                }
            }
        }
        if (overlays.showDeadWall && breakStackCount > 0) {
            layoutDeadWallStacks(
                tiles = tiles,
                lesson = lesson,
                m = m,
                breakStackCount = breakStackCount,
                originX = eastOriginX,
                originY = eastOriginY,
                ux = eastUx,
                uy = eastUy,
                vx = eastVx,
                vy = eastVy,
            )
        }
        if (overlays.showLookHands && breakStackCount > 0) {
            layoutLinedHands(tiles, lesson, m, breakStackCount, overlays)
        } else {
            if (overlays.showDealHands && breakStackCount > 0) {
                layoutDealHandStacks(tiles, lesson, m, breakStackCount)
            }
            if (overlays.showDeal13thTile && breakStackCount > 0) {
                for ((seat, liveStackIndex, floor) in WallSlotLayout.DEAL_13TH_SOURCES) {
                    val sideAlong =
                        WallSlotLayout.liveWallStack(breakStackCount, liveStackIndex)
                            ?: continue
                    val upper = WallSlotLayout.deal13thTileUpperPose(seat, m) ?: continue
                    val (side, alongI) = sideAlong
                    val slot = WallSlotLayout.slotId(side, alongI, floor)
                    // Lone 13th sits on the table (bottom floor + peek), not the upper
                    // stack pose — floor 1 would float above the adjacent hand bases.
                    assignWallTile(tiles[tileInSlot(lesson, slot)], upper, floor = 0, m)
                }
            }
            if (overlays.showDeal14thEast && breakStackCount > 0) {
                val src = WallSlotLayout.DEAL_14TH_EAST_SOURCE
                val sideAlong =
                    WallSlotLayout.liveWallStack(breakStackCount, src.liveStackIndex)
                val upper = WallSlotLayout.deal14thEastUpperPose(m)
                if (sideAlong != null && upper != null) {
                    val (side, alongI) = sideAlong
                    val slot = WallSlotLayout.slotId(side, alongI, src.floor)
                    assignWallTile(tiles[tileInSlot(lesson, slot)], upper, floor = 0, m)
                }
            }
        }
    }

    /**
     * Deal hands: each 2-stack square flies as one unit in deal order. Take *i*
     * is delayed by *i ×* [DEAL_BLOCK_DURATION]. [only13thTiles] / [only14thEast]
     * limit the stagger to leftover tiles; other tiles snap.
     */
    fun startDealHandsStaggeredMotion(
        tiles: Array<WallBuildAnimTile>,
        lesson: WallBuildLessonState,
        breakStackCount: Int,
        overlays: PushOverlays,
        only13thTiles: Boolean = false,
        only14thEast: Boolean = false,
    ) {
        val takeIndexOfTile = IntArray(tiles.size) { -1 }
        if (breakStackCount > 0) {
            if (!only13thTiles && !only14thEast) {
                for (dealI in 0 until WallSlotLayout.DEAL_STACKS_TOTAL) {
                    val sideAlong = WallSlotLayout.liveWallStack(breakStackCount, dealI) ?: continue
                    val takeI = dealI / WallSlotLayout.DEAL_STACKS_PER_TAKE
                    val (side, alongI) = sideAlong
                    for (floor in 0 until WallSlotLayout.FLOORS_PER_STACK) {
                        val slot = WallSlotLayout.slotId(side, alongI, floor)
                        val tileIndex = tileInSlot(lesson, slot)
                        if (tileIndex in tiles.indices) {
                            takeIndexOfTile[tileIndex] = takeI
                        }
                    }
                }
            }
            if (overlays.showDeal13thTile && !only14thEast) {
                val baseTake = if (only13thTiles) 0 else WallSlotLayout.DEAL_STACKS_TOTAL /
                    WallSlotLayout.DEAL_STACKS_PER_TAKE
                for ((seat, liveStackIndex, floor) in WallSlotLayout.DEAL_13TH_SOURCES) {
                    val sideAlong =
                        WallSlotLayout.liveWallStack(breakStackCount, liveStackIndex) ?: continue
                    val (side, alongI) = sideAlong
                    val slot = WallSlotLayout.slotId(side, alongI, floor)
                    val tileIndex = tileInSlot(lesson, slot)
                    if (tileIndex in tiles.indices) {
                        takeIndexOfTile[tileIndex] = baseTake + seat
                    }
                }
            }
            if (overlays.showDeal14thEast) {
                val takeI = when {
                    only14thEast -> 0
                    only13thTiles -> WallSlotLayout.SIDE_COUNT
                    else ->
                        WallSlotLayout.DEAL_STACKS_TOTAL / WallSlotLayout.DEAL_STACKS_PER_TAKE +
                            WallSlotLayout.SIDE_COUNT
                }
                val src = WallSlotLayout.DEAL_14TH_EAST_SOURCE
                val sideAlong =
                    WallSlotLayout.liveWallStack(breakStackCount, src.liveStackIndex)
                if (sideAlong != null) {
                    val (side, alongI) = sideAlong
                    val slot = WallSlotLayout.slotId(side, alongI, src.floor)
                    val tileIndex = tileInSlot(lesson, slot)
                    if (tileIndex in tiles.indices) {
                        takeIndexOfTile[tileIndex] = takeI
                    }
                }
            }
        }
        for (i in tiles.indices) {
            val t = tiles[i]
            val alreadySettled = t.atTarget
            t.fromCx = t.cx
            t.fromCy = t.cy
            t.fromW = t.w
            t.fromH = t.h
            t.fromRot = t.rot
            t.fromOpen = t.open
            t.duration = DEAL_BLOCK_DURATION
            val takeI = takeIndexOfTile[i]
            when {
                takeI >= 0 && alreadySettled -> t.elapsed = t.duration
                takeI >= 0 -> t.elapsed = -takeI * DEAL_BLOCK_DURATION
                alreadySettled -> t.elapsed = t.duration
                else -> t.snapToTarget()
            }
        }
    }

    /**
     * Tile indices for [seat]’s opening hand in deal order (2×2 groups, then
     * 13th, then East 14th). Caps at [tileCount].
     */
    fun collectDealHandTileIndices(
        lesson: WallBuildLessonState,
        seat: Int,
        breakStackCount: Int,
        tileCount: Int,
        include13th: Boolean,
        include14thEast: Boolean,
    ): MutableList<Int> {
        val slots = WallSlotLayout.dealHandSlotIds(
            breakStackCount = breakStackCount,
            seat = seat,
            tileCount = tileCount,
            include13th = include13th,
            include14thEast = include14thEast,
        )
        return slots.mapTo(ArrayList(slots.size)) { tileInSlot(lesson, it) }
    }

    fun sortHandTilesByType(tiles: Array<WallBuildAnimTile>, handTiles: MutableList<Int>) {
        handTiles.sortWith(
            compareBy(
                { tiles[it].type },
                { tiles[it].copy },
            ),
        )
    }

    /**
     * Lined-hand tiles currently on screen for table handoff (content coords).
     * Empty when hands are not in look/sort layout yet.
     */
    fun exportLinedHandHandoff(
        tiles: Array<WallBuildAnimTile>,
        lesson: WallBuildLessonState,
        hasArea: Boolean,
        showLookHands: Boolean,
        overlays: PushOverlays,
    ): List<WallBuildHandoffTile> {
        if (!showLookHands || !hasArea) return emptyList()
        val breakCount = if (lesson.hasRoll) lesson.stackCount.coerceIn(2, 12) else return emptyList()
        val out = ArrayList<WallBuildHandoffTile>(53)
        for (seat in 0 until WallSlotLayout.SIDE_COUNT) {
            val tileCount = WallSlotLayout.linedHandTileCount(seat)
            val handTiles = collectDealHandTileIndices(
                lesson = lesson,
                seat = seat,
                breakStackCount = breakCount,
                tileCount = tileCount,
                include13th = overlays.showDeal13thTile,
                include14thEast = overlays.showDeal14thEast,
            )
            if (overlays.showSortHands) {
                sortHandTilesByType(tiles, handTiles)
            }
            for ((handIndex, tileIndex) in handTiles.withIndex()) {
                val t = tiles[tileIndex]
                out += WallBuildHandoffTile(
                    seat = seat,
                    handIndex = handIndex,
                    tileType = t.type,
                    open = t.open,
                    cx = t.cx,
                    cy = t.cy,
                    w = t.w,
                    h = t.h,
                    rot = t.rot,
                )
            }
        }
        return out
    }

    /** Hands plus residual live/dead wall poses for the fluid table handoff. */
    fun exportHandoffSnapshot(
        tiles: Array<WallBuildAnimTile>,
        lesson: WallBuildLessonState,
        hasArea: Boolean,
        showLookHands: Boolean,
        overlays: PushOverlays,
    ): WallBuildHandoffSnapshot {
        val hands = exportLinedHandHandoff(tiles, lesson, hasArea, showLookHands, overlays)
        if (hands.isEmpty()) return WallBuildHandoffSnapshot.EMPTY
        val breakCount = lesson.stackCount.coerceIn(2, 12)
        val residual = WallSlotLayout.residualWallSlots(breakCount)
        fun poseAt(slot: Int): WallBuildHandoffPose {
            val t = tiles[tileInSlot(lesson, slot)]
            return WallBuildHandoffPose(
                tileType = t.type,
                cx = t.cx,
                cy = t.cy,
                w = t.w,
                h = t.h,
                rot = t.rot,
            )
        }
        return WallBuildHandoffSnapshot(
            hands = hands,
            residualLive = residual.live.map { poseAt(it) },
            residualDead = residual.dead.map { poseAt(it) },
        )
    }

    private fun assignWallTile(
        tile: WallBuildAnimTile,
        upper: WallSlotLayout.UpperPose,
        floor: Int,
        m: WallSlotLayout.Metrics,
        open: Boolean = false,
    ) {
        val (cx, cy) = WallSlotLayout.floorTarget(upper, floor, m)
        tile.setTarget(
            cx = cx,
            cy = cy,
            w = m.tileW,
            h = m.tileH,
            rot = upper.rot,
            open = open,
            stackFloor = floor,
        )
    }

    private fun layoutDealHandStacks(
        tiles: Array<WallBuildAnimTile>,
        lesson: WallBuildLessonState,
        m: WallSlotLayout.Metrics,
        breakStackCount: Int,
    ) {
        for (dealI in 0 until WallSlotLayout.DEAL_STACKS_TOTAL) {
            val sideAlong = WallSlotLayout.liveWallStack(breakStackCount, dealI) ?: continue
            val target = WallSlotLayout.dealTarget(dealI) ?: continue
            val upper = WallSlotLayout.dealHandUpperPose(
                seat = target.seat,
                groupIndex = target.groupIndex,
                stackInGroup = target.stackInGroup,
                m = m,
            ) ?: continue
            val (side, alongI) = sideAlong
            for (floor in 0 until WallSlotLayout.FLOORS_PER_STACK) {
                val slot = WallSlotLayout.slotId(side, alongI, floor)
                assignWallTile(tiles[tileInSlot(lesson, slot)], upper, floor, m)
            }
        }
    }

    private fun layoutLinedHands(
        tiles: Array<WallBuildAnimTile>,
        lesson: WallBuildLessonState,
        m: WallSlotLayout.Metrics,
        breakStackCount: Int,
        overlays: PushOverlays,
    ) {
        for (seat in 0 until WallSlotLayout.SIDE_COUNT) {
            val tileCount = WallSlotLayout.linedHandTileCount(seat)
            val open = seat == WallSlotLayout.EAST_SIDE
            val handTiles = collectDealHandTileIndices(
                lesson = lesson,
                seat = seat,
                breakStackCount = breakStackCount,
                tileCount = tileCount,
                include13th = overlays.showDeal13thTile,
                include14thEast = overlays.showDeal14thEast,
            )
            if (overlays.showSortHands) {
                sortHandTilesByType(tiles, handTiles)
            }
            for (handIndex in handTiles.indices) {
                val upper = WallSlotLayout.linedHandUpperPose(
                    seat = seat,
                    index = handIndex,
                    tileCount = tileCount,
                    m = m,
                ) ?: continue
                assignWallTile(
                    tile = tiles[handTiles[handIndex]],
                    upper = upper,
                    floor = 0,
                    m = m,
                    open = open,
                )
            }
        }
    }

    private fun layoutDeadWallStacks(
        tiles: Array<WallBuildAnimTile>,
        lesson: WallBuildLessonState,
        m: WallSlotLayout.Metrics,
        breakStackCount: Int,
        originX: Float,
        originY: Float,
        ux: Float,
        uy: Float,
        vx: Float,
        vy: Float,
    ) {
        for (k in 0 until WallSlotLayout.TAKE7_STACKS) {
            val sideAlong = WallSlotLayout.take7Stack(breakStackCount, k) ?: continue
            val upper = WallSlotLayout.deadWallUpperPose(
                breakStackCount = breakStackCount,
                stackIndex = k,
                m = m,
                originX = originX,
                originY = originY,
                ux = ux,
                uy = uy,
                vx = vx,
                vy = vy,
            ) ?: continue
            val (side, alongI) = sideAlong
            for (floor in 0 until WallSlotLayout.FLOORS_PER_STACK) {
                val slot = WallSlotLayout.slotId(side, alongI, floor)
                assignWallTile(tiles[tileInSlot(lesson, slot)], upper, floor, m)
            }
        }
    }
}
