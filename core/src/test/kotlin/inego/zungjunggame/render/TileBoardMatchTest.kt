package inego.zungjunggame.render

import inego.zungjunggame.render.tiles.DesiredSlot
import inego.zungjunggame.render.tiles.SlotKey
import inego.zungjunggame.render.tiles.SlotKind
import inego.zungjunggame.render.tiles.TileBoard
import inego.zungjunggame.render.tiles.TileFocusGlow
import inego.zungjunggame.render.tiles.TilePose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileBoardMatchTest {

    private fun pose(x: Float, y: Float = 0f, open: Boolean = true) =
        TilePose(x, y, 0f, 30f, 40f, open)

    private fun handSlot(seat: Int, index: Int, type: Int, x: Float, open: Boolean = true) =
        DesiredSlot(
            pose = pose(x, open = open),
            tileType = type,
            seatIndex = seat,
            slotKey = SlotKey(SlotKind.Hand, seat, index),
        )

    private fun riverSlot(seat: Int, index: Int, type: Int, x: Float, y: Float = 100f) =
        DesiredSlot(
            pose = pose(x, y, open = true),
            tileType = type,
            seatIndex = seat,
            slotKey = SlotKey(SlotKind.River, seat, index),
        )

    @Test
    fun sortReordersSameMultisetWithStableIds() {
        val board = TileBoard()
        board.wallSpawnPose = pose(0f, 0f, open = false)
        board.moveDurationSec = 0.2f

        val initial = listOf(
            handSlot(0, 0, type = 1, x = 0f),
            handSlot(0, 1, type = 5, x = 40f),
            handSlot(0, 2, type = 9, x = 80f),
        )
        board.reconcile(initial, dealStagger = false)
        // Snap to targets.
        board.advance(1f)
        val idsBefore = board.tilesSnapshot().associate { it.tileType to it.id }

        // Same multiset, swapped positions (sorted order change).
        val sorted = listOf(
            handSlot(0, 0, type = 1, x = 0f),
            handSlot(0, 1, type = 9, x = 40f),
            handSlot(0, 2, type = 5, x = 80f),
        )
        board.reconcile(sorted, dealStagger = false)
        val after = board.tilesSnapshot()
        assertEquals(3, after.size)
        // Types keep the same visual ids (no respawn).
        for (v in after) {
            assertEquals(idsBefore[v.tileType], v.id, "type ${v.tileType} should keep id")
        }
        val type5 = after.first { it.tileType == 5 }
        assertEquals(80f, type5.target.x, 0.01f)
    }

    @Test
    fun discardMovesMatchingTypeToRiver() {
        val board = TileBoard()
        board.wallSpawnPose = pose(0f, 0f, open = false)
        board.moveDurationSec = 0.2f

        board.reconcile(
            listOf(
                handSlot(0, 0, type = 3, x = 0f),
                handSlot(0, 1, type = 7, x = 40f),
            ),
            dealStagger = false,
        )
        board.advance(1f)
        val discardedId = board.tilesSnapshot().first { it.tileType == 7 }.id

        board.preferredVisualId = discardedId
        board.reconcile(
            listOf(
                handSlot(0, 0, type = 3, x = 0f),
                riverSlot(0, 0, type = 7, x = 10f, y = 120f),
            ),
            dealStagger = false,
        )
        val river = board.tilesSnapshot().first { it.slotKey.kind == SlotKind.River }
        assertEquals(discardedId, river.id)
        assertEquals(120f, river.target.y, 0.01f)
    }

    @Test
    fun discardDoesNotStealSameTypeFromAnotherPlayersRiver() {
        val board = TileBoard()
        board.wallSpawnPose = pose(0f, 0f, open = false)
        board.moveDurationSec = 0.2f

        val opponentRiver = listOf(
            riverSlot(1, 0, type = 1, x = 160f, y = 300f),
            riverSlot(1, 1, type = 2, x = 200f, y = 300f),
            riverSlot(1, 2, type = 7, x = 240f, y = 300f),
        )
        board.reconcile(
            listOf(
                handSlot(0, 0, type = 3, x = 0f),
                handSlot(0, 1, type = 7, x = 40f),
            ) + opponentRiver,
            dealStagger = false,
        )
        board.advance(1f)
        val discardedId = board.tilesSnapshot().first {
            it.tileType == 7 && it.slotKey.kind == SlotKind.Hand
        }.id
        val opponentCopy = board.tilesSnapshot().first {
            it.tileType == 7 && it.slotKey.kind == SlotKind.River
        }
        val opponentId = opponentCopy.id
        val opponentTarget = opponentCopy.target

        board.preferredVisualId = discardedId
        board.reconcile(
            listOf(
                handSlot(0, 0, type = 3, x = 0f),
                riverSlot(0, 0, type = 7, x = 10f, y = 120f),
            ) + opponentRiver,
            dealStagger = false,
        )

        val humanRiver = board.tilesSnapshot().first {
            it.slotKey == SlotKey(SlotKind.River, 0, 0)
        }
        assertEquals(discardedId, humanRiver.id)
        assertEquals(120f, humanRiver.target.y, 0.01f)

        val stillOpponent = board.tilesSnapshot().first {
            it.slotKey == SlotKey(SlotKind.River, 1, 2)
        }
        assertEquals(opponentId, stillOpponent.id)
        assertEquals(opponentTarget.x, stillOpponent.target.x, 0.01f)
        assertEquals(opponentTarget.y, stillOpponent.target.y, 0.01f)
        assertEquals(opponentTarget.x, stillOpponent.current.x, 0.01f)
        assertEquals(opponentTarget.y, stillOpponent.current.y, 0.01f)
    }

    @Test
    fun discardDoesNotStealSameTypeFromOwnRiverBeforeNewSlotExists() {
        val board = TileBoard()
        board.wallSpawnPose = pose(0f, 0f, open = false)
        board.moveDurationSec = 0.2f

        val before = listOf(
            handSlot(0, 0, type = 3, x = 0f),
            handSlot(0, 1, type = 7, x = 40f),
            riverSlot(0, 0, type = 7, x = 10f, y = 120f),
        )
        board.reconcile(before, dealStagger = false)
        board.advance(1f)
        val discardedId = board.tilesSnapshot().first {
            it.tileType == 7 && it.slotKey.kind == SlotKind.Hand
        }.id
        val existingRiver = board.tilesSnapshot().first {
            it.tileType == 7 && it.slotKey.kind == SlotKind.River
        }
        val existingRiverId = existingRiver.id

        // Online click: hint is set, but the snapshot has not grown the river yet.
        board.preferredVisualId = discardedId
        board.reconcile(before, dealStagger = false)

        val stillHand = board.tilesSnapshot().first { it.id == discardedId }
        assertEquals(SlotKind.Hand, stillHand.slotKey.kind)
        assertEquals(40f, stillHand.target.x, 0.01f)
        assertEquals(0f, stillHand.target.y, 0.01f)
        assertEquals(discardedId, board.preferredVisualId)

        val stillRiver = board.tilesSnapshot().first { it.id == existingRiverId }
        assertEquals(SlotKey(SlotKind.River, 0, 0), stillRiver.slotKey)
        assertEquals(10f, stillRiver.current.x, 0.01f)
        assertEquals(120f, stillRiver.current.y, 0.01f)
        assertEquals(10f, stillRiver.target.x, 0.01f)
        assertEquals(120f, stillRiver.target.y, 0.01f)

        board.reconcile(
            listOf(
                handSlot(0, 0, type = 3, x = 0f),
                riverSlot(0, 0, type = 7, x = 10f, y = 120f),
                riverSlot(0, 1, type = 7, x = 50f, y = 120f),
            ),
            dealStagger = false,
        )
        val newRiver = board.tilesSnapshot().first {
            it.slotKey == SlotKey(SlotKind.River, 0, 1)
        }
        assertEquals(discardedId, newRiver.id)
        assertEquals(50f, newRiver.target.x, 0.01f)
        assertEquals(-1, board.preferredVisualId)

        val keptRiver = board.tilesSnapshot().first {
            it.slotKey == SlotKey(SlotKind.River, 0, 0)
        }
        assertEquals(existingRiverId, keptRiver.id)
        assertEquals(10f, keptRiver.current.x, 0.01f)
        assertEquals(120f, keptRiver.current.y, 0.01f)
    }

    @Test
    fun discardOfTypeAlreadyInOwnRiverKeepsExistingRiverTile() {
        val board = TileBoard()
        board.wallSpawnPose = pose(0f, 0f, open = false)
        board.moveDurationSec = 0.2f

        board.reconcile(
            listOf(
                handSlot(0, 0, type = 3, x = 0f),
                handSlot(0, 1, type = 7, x = 40f),
                riverSlot(0, 0, type = 7, x = 10f, y = 120f),
            ),
            dealStagger = false,
        )
        board.advance(1f)
        val discardedId = board.tilesSnapshot().first {
            it.tileType == 7 && it.slotKey.kind == SlotKind.Hand
        }.id
        val existingRiverId = board.tilesSnapshot().first {
            it.tileType == 7 && it.slotKey.kind == SlotKind.River
        }.id

        board.preferredVisualId = discardedId
        board.reconcile(
            listOf(
                handSlot(0, 0, type = 3, x = 0f),
                riverSlot(0, 0, type = 7, x = 10f, y = 120f),
                riverSlot(0, 1, type = 7, x = 50f, y = 120f),
            ),
            dealStagger = false,
        )

        val newRiver = board.tilesSnapshot().first {
            it.slotKey == SlotKey(SlotKind.River, 0, 1)
        }
        assertEquals(discardedId, newRiver.id)
        assertEquals(50f, newRiver.target.x, 0.01f)
        assertEquals(120f, newRiver.target.y, 0.01f)

        val keptRiver = board.tilesSnapshot().first {
            it.slotKey == SlotKey(SlotKind.River, 0, 0)
        }
        assertEquals(existingRiverId, keptRiver.id)
        assertEquals(10f, keptRiver.current.x, 0.01f)
        assertEquals(120f, keptRiver.current.y, 0.01f)
    }

    @Test
    fun preferredOriginDoesNotStealOwnRiverBeforeNewSlotExists() {
        val board = TileBoard()
        board.wallSpawnPose = pose(200f, 200f, open = false)
        board.moveDurationSec = 0.2f

        val hand = (0..1).map { i ->
            DesiredSlot(
                pose = pose(10f * i, 300f, open = false),
                tileType = -1,
                seatIndex = 1,
                slotKey = SlotKey(SlotKind.Hand, 1, i),
            )
        }
        val existingRiver = riverSlot(1, 0, type = 5, x = 50f, y = 180f)
        board.reconcile(hand + existingRiver, dealStagger = false)
        board.advance(1f)
        val originId = board.tilesSnapshot().first {
            it.slotKey == SlotKey(SlotKind.Hand, 1, 1)
        }.id
        val riverId = board.tilesSnapshot().first {
            it.slotKey.kind == SlotKind.River
        }.id

        board.preferredDiscardOrigin = SlotKey(SlotKind.Hand, 1, 1)
        board.reconcile(hand + existingRiver, dealStagger = false)

        assertEquals(originId, board.tilesSnapshot().first {
            it.slotKey == SlotKey(SlotKind.Hand, 1, 1)
        }.id)
        assertEquals(riverId, board.tilesSnapshot().first {
            it.slotKey == SlotKey(SlotKind.River, 1, 0)
        }.id)
        assertEquals(SlotKey(SlotKind.Hand, 1, 1), board.preferredDiscardOrigin)

        val remainingHand = listOf(hand[0])
        val grownRiver = listOf(
            existingRiver,
            riverSlot(1, 1, type = 9, x = 90f, y = 180f),
        )
        board.reconcile(remainingHand + grownRiver, dealStagger = false)

        val newRiver = board.tilesSnapshot().first {
            it.slotKey == SlotKey(SlotKind.River, 1, 1)
        }
        assertEquals(originId, newRiver.id)
        assertEquals(90f, newRiver.target.x, 0.01f)
        assertNull(board.preferredDiscardOrigin)
        assertEquals(riverId, board.tilesSnapshot().first {
            it.slotKey == SlotKey(SlotKind.River, 1, 0)
        }.id)
    }

    @Test
    fun highlightMatchesMarksEveryVisibleCopyAcrossRegionsAndSeats() {
        val board = TileBoard()
        board.wallSpawnPose = pose(0f, 0f, open = false)
        // No tile here is selectable, which is the point: no turn gate.
        board.reconcile(
            listOf(
                handSlot(0, 0, type = 7, x = 0f),
                DesiredSlot(
                    pose = pose(40f, 0f),
                    tileType = 7,
                    seatIndex = 0,
                    slotKey = SlotKey(SlotKind.Meld, 0, 0),
                ),
                riverSlot(0, 0, type = 7, x = 80f),
                DesiredSlot(
                    pose = pose(120f, 0f),
                    tileType = 7,
                    seatIndex = 1,
                    slotKey = SlotKey(SlotKind.Meld, 1, 0),
                ),
                riverSlot(1, 0, type = 7, x = 160f),
                riverSlot(0, 1, type = 3, x = 200f),
                // Hidden same-type face: marking it would leak its identity.
                handSlot(1, 0, type = 7, x = 240f, open = false),
                DesiredSlot(
                    pose = pose(280f, 100f),
                    tileType = 7,
                    seatIndex = 2,
                    slotKey = SlotKey(SlotKind.River, 2, 0),
                    focusGlow = TileFocusGlow.Soft,
                ),
            ),
            dealStagger = false,
        )

        board.highlightMatches(7)

        val byKey = board.tilesSnapshot().associateBy { it.slotKey }
        assertEquals(TileFocusGlow.Match, byKey[SlotKey(SlotKind.Hand, 0, 0)]!!.focusGlow)
        assertEquals(TileFocusGlow.Match, byKey[SlotKey(SlotKind.Meld, 0, 0)]!!.focusGlow)
        assertEquals(TileFocusGlow.Match, byKey[SlotKey(SlotKind.River, 0, 0)]!!.focusGlow)
        assertEquals(TileFocusGlow.Match, byKey[SlotKey(SlotKind.Meld, 1, 0)]!!.focusGlow)
        assertEquals(TileFocusGlow.Match, byKey[SlotKey(SlotKind.River, 1, 0)]!!.focusGlow)
        assertEquals(TileFocusGlow.None, byKey[SlotKey(SlotKind.River, 0, 1)]!!.focusGlow)
        assertEquals(TileFocusGlow.None, byKey[SlotKey(SlotKind.Hand, 1, 0)]!!.focusGlow)
        assertEquals(TileFocusGlow.Soft, byKey[SlotKey(SlotKind.River, 2, 0)]!!.focusGlow)
    }

    @Test
    fun hitAtHitsTheVisibleTileNotTheHiddenOneOnTop() {
        val board = TileBoard()
        board.wallSpawnPose = pose(0f, 0f, open = false)
        // Hidden opponent hand spawns last, so it is the topmost tile geometrically.
        board.reconcile(
            listOf(
                riverSlot(seat = 0, index = 0, type = 6, x = 50f, y = 0f),
                handSlot(seat = 3, index = 0, type = 9, x = 50f, open = false),
            ),
            dealStagger = false,
        )

        assertEquals(6, board.hitAt(50f, 0f)?.tileType)
        assertNull(board.hitAt(500f, 500f))
    }

    @Test
    fun hitAtReportsSelectableHand() {
        val board = TileBoard()
        board.wallSpawnPose = pose(0f, 0f, open = false)
        board.reconcile(
            listOf(
                DesiredSlot(
                    pose = pose(10f, 0f),
                    tileType = 2,
                    seatIndex = 0,
                    slotKey = SlotKey(SlotKind.Hand, 0, 0),
                    selectableHand = true,
                ),
                riverSlot(seat = 0, index = 0, type = 2, x = 90f, y = 0f),
            ),
            dealStagger = false,
        )

        assertTrue(board.hitAt(10f, 0f)!!.selectableHand)
        assertFalse(board.hitAt(90f, 0f)!!.selectableHand)
    }

    @Test
    fun pendingDiscardHitIsTheSoftRiverTile() {
        val board = TileBoard()
        board.wallSpawnPose = pose(0f, 0f, open = false)
        board.reconcile(
            listOf(
                riverSlot(seat = 0, index = 0, type = 1, x = 0f),
                DesiredSlot(
                    pose = pose(80f, 120f),
                    tileType = 9,
                    seatIndex = 2,
                    slotKey = SlotKey(SlotKind.River, 2, 0),
                    focusGlow = TileFocusGlow.Soft,
                ),
                handSlot(seat = 0, index = 0, type = 3, x = 0f),
            ),
            dealStagger = false,
        )
        board.advance(1f)
        val hit = board.pendingDiscardHit()
        assertEquals(9, hit?.tileType)
        assertEquals(80f, hit?.worldX ?: 0f, 0.01f)
        assertEquals(120f, hit?.worldY ?: 0f, 0.01f)
    }

    @Test
    fun youCopyHitPrefersHandOverMeldAndSkipsRiver() {
        val board = TileBoard()
        board.wallSpawnPose = pose(0f, 0f, open = false)
        board.reconcile(
            listOf(
                riverSlot(seat = 0, index = 0, type = 4, x = 200f),
                DesiredSlot(
                    pose = pose(10f, 0f),
                    tileType = 4,
                    seatIndex = 0,
                    slotKey = SlotKey(SlotKind.Meld, 0, 0),
                ),
                handSlot(seat = 0, index = 0, type = 4, x = 40f),
                handSlot(seat = 1, index = 0, type = 4, x = 80f, open = false),
            ),
            dealStagger = false,
        )
        board.advance(1f)
        val hit = board.youCopyHit(humanSeat = 0, type = 4)
        assertEquals(4, hit?.tileType)
        assertEquals(40f, hit?.worldX ?: 0f, 0.01f)
    }

    @Test
    fun tutorialOutlineStacksOnPlaySoft() {
        val board = TileBoard()
        board.wallSpawnPose = pose(0f, 0f, open = false)
        board.reconcile(
            listOf(
                DesiredSlot(
                    pose = pose(10f, 100f),
                    tileType = 7,
                    seatIndex = 2,
                    slotKey = SlotKey(SlotKind.River, 2, 0),
                    focusGlow = TileFocusGlow.Soft,
                    tutorialOutline = TileFocusGlow.Deny,
                ),
            ),
            dealStagger = false,
        )
        val tile = board.tilesSnapshot().single()
        assertEquals(TileFocusGlow.Soft, tile.focusGlow)
        assertEquals(TileFocusGlow.Deny, tile.tutorialOutline)
        assertTrue(tile.focusGlow.isPlayHalo)
        assertTrue(tile.tutorialOutline.isTutorialOutline)
    }

    @Test
    fun dealStaggerAssignsIncreasingDelays() {
        val board = TileBoard()
        board.wallSpawnPose = pose(200f, 200f, open = false)
        board.dealStaggerSec = 0.005f

        val desired = listOf(
            handSlot(0, 0, type = 1, x = 10f, open = true),
            handSlot(0, 1, type = 2, x = 50f, open = true),
            handSlot(1, 0, type = 3, x = 300f, open = false),
        )
        board.reconcile(desired, dealStagger = true)
        val delays = board.tilesSnapshot().map { it.startDelay }.sorted()
        assertEquals(3, delays.size)
        assertEquals(0f, delays[0], 1e-6f)
        assertEquals(0.005f, delays[1], 1e-6f)
        assertEquals(0.010f, delays[2], 1e-6f)
        // All start at wall marker.
        for (v in board.tilesSnapshot()) {
            assertEquals(200f, v.current.x, 0.01f)
            assertEquals(200f, v.current.y, 0.01f)
            assertTrue(!v.current.open)
        }
    }

    @Test
    fun singleWallDepartureStartsOnTheFrontCard() {
        val board = TileBoard()
        board.wallSpawnPose = pose(100f, 100f, open = false)
        board.wallDeparturePose = TilePose(100f, 120f, 0f, 45f, 60f, open = true)
        board.reconcile(listOf(handSlot(0, 0, type = 4, x = 50f, open = true)), dealStagger = false)
        val v = board.tilesSnapshot().single()
        assertEquals(1, board.wallSpawnCount)
        assertEquals(100f, v.from.x, 0.01f)
        assertEquals(120f, v.from.y, 0.01f)
        assertEquals(45f, v.from.w, 0.01f)
        assertEquals(60f, v.from.h, 0.01f)
        assertFalse(v.from.open)
        assertEquals(50f, v.target.x, 0.01f)
        assertTrue(v.target.open)
    }

    @Test
    fun dealAndMultiSpawnStayOnTheHandSizedMarker() {
        val board = TileBoard()
        board.wallSpawnPose = pose(200f, 200f, open = false)
        board.wallDeparturePose = TilePose(200f, 200f, 0f, 90f, 120f, open = false)
        board.dealStaggerSec = 0.005f
        val desired = listOf(
            handSlot(0, 0, type = 1, x = 10f),
            handSlot(0, 1, type = 2, x = 50f),
        )
        board.reconcile(desired, dealStagger = true)
        assertEquals(2, board.wallSpawnCount)
        for (v in board.tilesSnapshot()) {
            assertEquals(30f, v.from.w, 0.01f)
            assertEquals(40f, v.from.h, 0.01f)
        }
    }

    @Test
    fun spawnFromWallWithoutStagger() {
        val board = TileBoard()
        board.wallSpawnPose = pose(100f, 100f, open = false)
        board.reconcile(listOf(handSlot(0, 0, type = 4, x = 50f)), dealStagger = false)
        val v = board.tilesSnapshot().single()
        assertEquals(0f, v.startDelay, 1e-6f)
        assertEquals(100f, v.current.x, 0.01f)
        assertEquals(50f, v.target.x, 0.01f)
    }

    @Test
    fun advanceReachesTarget() {
        val board = TileBoard()
        board.wallSpawnPose = pose(0f, 0f, open = false)
        board.moveDurationSec = 0.2f
        board.reconcile(listOf(handSlot(0, 0, type = 1, x = 100f)), dealStagger = false)
        board.advance(0.1f)
        val mid = board.tilesSnapshot().single()
        assertTrue(mid.current.x > 0f && mid.current.x < 100f)
        board.advance(1f)
        val done = board.tilesSnapshot().single()
        assertEquals(100f, done.current.x, 0.5f)
        assertTrue(done.isSettled(0.2f))
    }

    @Test
    fun snapAllToTargetsJumpsWithoutFlight() {
        val board = TileBoard()
        board.wallSpawnPose = pose(0f, 0f, open = false)
        board.moveDurationSec = 0.3f
        board.reconcile(listOf(handSlot(0, 0, type = 1, x = 100f)), dealStagger = false)
        board.advance(0.05f)
        // Retarget as if the viewport resized.
        board.reconcile(listOf(handSlot(0, 0, type = 1, x = 400f)), dealStagger = false)
        board.snapAllToTargets()
        val v = board.tilesSnapshot().single()
        assertEquals(400f, v.current.x, 0.01f)
        assertEquals(400f, v.target.x, 0.01f)
        assertEquals(0f, v.startDelay, 1e-6f)
        assertTrue(v.isSettled(0.3f))
    }

    @Test
    fun opponentDiscardMovesTileFromHandToRiverInsteadOfWall() {
        val board = TileBoard()
        board.wallSpawnPose = pose(200f, 200f, open = false)
        board.moveDurationSec = 0.2f

        // Opponent (seat 1) has 13 face-down hand tiles + 1 face-down drawn tile at y = 300
        val hand = (0..12).map { i ->
            DesiredSlot(
                pose = pose(10f * i, 300f, open = false),
                tileType = -1,
                seatIndex = 1,
                slotKey = SlotKey(SlotKind.Hand, 1, i),
            )
        }
        val drawn = DesiredSlot(
            pose = pose(150f, 300f, open = false),
            tileType = -1,
            seatIndex = 1,
            slotKey = SlotKey(SlotKind.Drawn, 1, 0),
        )
        board.reconcile(hand + drawn, dealStagger = false)
        board.advance(1f)
        val handVisualIds = board.tilesSnapshot().map { it.id }.toSet()

        // Opponent discards tile 7 into their river at y = 180
        val river = DesiredSlot(
            pose = pose(50f, 180f, open = true),
            tileType = 7,
            seatIndex = 1,
            slotKey = SlotKey(SlotKind.River, 1, 0),
        )
        board.reconcile(hand + river, dealStagger = false)

        val riverTile = board.tilesSnapshot().first { it.slotKey.kind == SlotKind.River }
        // Must be one of the visual tiles from the opponent's hand/drawn slot
        assertTrue(riverTile.id in handVisualIds, "River tile must originate from opponent hand")
        // Start position of the animation must be from opponent hand (y = 300), NOT wall marker (y = 200)
        assertEquals(300f, riverTile.from.y, 0.01f)
        assertEquals(150f, riverTile.from.x, 0.01f)
        assertEquals(180f, riverTile.target.y, 0.01f)
    }

    @Test
    fun opponentDrawMovesTileFromWallToHand() {
        val board = TileBoard()
        board.wallSpawnPose = pose(200f, 200f, open = false)
        board.moveDurationSec = 0.2f

        // Opponent has 13 hand tiles
        val hand = (0..12).map { i ->
            DesiredSlot(
                pose = pose(10f * i, 300f, open = false),
                tileType = -1,
                seatIndex = 1,
                slotKey = SlotKey(SlotKind.Hand, 1, i),
            )
        }
        board.reconcile(hand, dealStagger = false)
        board.advance(1f)

        // Opponent draws a tile
        val drawn = DesiredSlot(
            pose = pose(150f, 300f, open = false),
            tileType = -1,
            seatIndex = 1,
            slotKey = SlotKey(SlotKind.Drawn, 1, 0),
        )
        board.reconcile(hand + drawn, dealStagger = false)

        val drawnTile = board.tilesSnapshot().first { it.slotKey.kind == SlotKind.Drawn }
        // Draw must start from wall marker
        assertEquals(200f, drawnTile.from.x, 0.01f)
        assertEquals(200f, drawnTile.from.y, 0.01f)
        assertEquals(150f, drawnTile.target.x, 0.01f)
        assertEquals(300f, drawnTile.target.y, 0.01f)
    }

    @Test
    fun opponentHandRevealFlipsInPlaceWithoutFlyingFromWall() {
        val board = TileBoard()
        board.wallSpawnPose = pose(200f, 200f, open = false)
        board.moveDurationSec = 0.2f

        // Opponent has 13 face-down hand tiles
        val handBefore = (0..12).map { i ->
            DesiredSlot(
                pose = pose(10f * i, 300f, open = false),
                tileType = -1,
                seatIndex = 1,
                slotKey = SlotKey(SlotKind.Hand, 1, i),
            )
        }
        board.reconcile(handBefore, dealStagger = false)
        board.advance(1f)
        val initialIds = board.tilesSnapshot().map { it.id }

        // Hand reveal: same slots now open with real tile types
        val handAfter = (0..12).map { i ->
            DesiredSlot(
                pose = pose(10f * i, 300f, open = true),
                tileType = i % 10,
                seatIndex = 1,
                slotKey = SlotKey(SlotKind.Hand, 1, i),
            )
        }
        board.reconcile(handAfter, dealStagger = false)

        val afterIds = board.tilesSnapshot().map { it.id }
        assertEquals(initialIds, afterIds, "Visual tile IDs must remain identical (in-place reveal)")
        for (v in board.tilesSnapshot()) {
            assertEquals(300f, v.from.y, 0.01f)
            assertEquals(300f, v.target.y, 0.01f)
        }
    }

    @Test
    fun opponentDiscardWithPreferredSlotPlucksExactHandTile() {
        val board = TileBoard()
        board.wallSpawnPose = pose(200f, 200f, open = false)
        board.moveDurationSec = 0.2f

        // Opponent (seat 1) has 13 face-down hand tiles (indices 0..12 at x = 10 * i) + drawn tile at x = 150
        val hand = (0..12).map { i ->
            DesiredSlot(
                pose = pose(10f * i, 300f, open = false),
                tileType = -1,
                seatIndex = 1,
                slotKey = SlotKey(SlotKind.Hand, 1, i),
            )
        }
        val drawn = DesiredSlot(
            pose = pose(150f, 300f, open = false),
            tileType = -1,
            seatIndex = 1,
            slotKey = SlotKey(SlotKind.Drawn, 1, 0),
        )
        board.reconcile(hand + drawn, dealStagger = false)
        board.advance(1f)

        // Opponent discards 4th tile from left (index 3, at x = 30) into river at y = 180
        val remainingHand = List((0..12).filter { it != 3 }.size) { newIdx ->
            DesiredSlot(
                pose = pose(10f * newIdx, 300f, open = false),
                tileType = -1,
                seatIndex = 1,
                slotKey = SlotKey(SlotKind.Hand, 1, newIdx),
            )
        }
        val river = DesiredSlot(
            pose = pose(50f, 180f, open = true),
            tileType = 5,
            seatIndex = 1,
            slotKey = SlotKey(SlotKind.River, 1, 0),
        )

        // Set preferred origin to the 4th tile from left (index 3)
        board.preferredDiscardOrigin = SlotKey(SlotKind.Hand, 1, 3)
        board.reconcile(remainingHand + drawn + river, dealStagger = false)

        val riverTile = board.tilesSnapshot().first { it.slotKey.kind == SlotKind.River }
        // River tile MUST originate from index 3 (x = 30), NOT the drawn slot (x = 150) or rightmost (x = 120)
        assertEquals(30f, riverTile.from.x, 0.01f)
        assertEquals(300f, riverTile.from.y, 0.01f)
        assertEquals(180f, riverTile.target.y, 0.01f)

        // The drawn tile at x = 150 must still exist and stay in place
        val drawnTile = board.tilesSnapshot().first { it.slotKey.kind == SlotKind.Drawn }
        assertEquals(150f, drawnTile.current.x, 0.01f)
    }

    @Test
    fun opponentDiscardWithPreferredDrawnSlotPlucksDrawnTile() {
        val board = TileBoard()
        board.wallSpawnPose = pose(200f, 200f, open = false)
        board.moveDurationSec = 0.2f

        val hand = (0..12).map { i ->
            DesiredSlot(
                pose = pose(10f * i, 300f, open = false),
                tileType = -1,
                seatIndex = 1,
                slotKey = SlotKey(SlotKind.Hand, 1, i),
            )
        }
        val drawn = DesiredSlot(
            pose = pose(150f, 300f, open = false),
            tileType = -1,
            seatIndex = 1,
            slotKey = SlotKey(SlotKind.Drawn, 1, 0),
        )
        board.reconcile(hand + drawn, dealStagger = false)
        board.advance(1f)

        val river = DesiredSlot(
            pose = pose(50f, 180f, open = true),
            tileType = 9,
            seatIndex = 1,
            slotKey = SlotKey(SlotKind.River, 1, 0),
        )

        // Set preferred origin to the drawn slot
        board.preferredDiscardOrigin = SlotKey(SlotKind.Drawn, 1, 0)
        board.reconcile(hand + river, dealStagger = false)

        val riverTile = board.tilesSnapshot().first { it.slotKey.kind == SlotKind.River }
        // River tile MUST originate from drawn slot (x = 150)
        assertEquals(150f, riverTile.from.x, 0.01f)
        assertEquals(300f, riverTile.from.y, 0.01f)
    }

    @Test
    fun leftoverOpenHandIsDroppedInsteadOfRetargetedOntoConcealedSlots() {
        val board = TileBoard()
        board.wallSpawnPose = pose(200f, 200f, open = false)
        board.moveDurationSec = 0.2f

        // Leftover local game: seat 0 was the human, faces up.
        val leftover = (0..12).map { i ->
            handSlot(seat = 0, index = i, type = i % 10, x = 10f * i, open = true)
        }
        board.reconcile(leftover, dealStagger = false)
        board.advance(1f)
        val leftoverIds = board.tilesSnapshot().map { it.id }.toSet()

        // Ticket remapped us to another seat: seat 0 is now a concealed opponent.
        val concealed = (0..12).map { i ->
            handSlot(seat = 0, index = i, type = -1, x = 10f * i, open = false)
        }
        board.reconcile(concealed, dealStagger = false)

        for (v in board.tilesSnapshot()) {
            if (v.current.open) {
                assertTrue(v.tileType >= 0, "open pose must not carry tileType=${v.tileType}")
            }
        }
        val spawned = board.tilesSnapshot()
        assertEquals(13, spawned.size)
        for (v in spawned) {
            assertTrue(v.id !in leftoverIds, "concealed slot must spawn, not reuse an open leftover")
            assertEquals(-1, v.tileType)
            assertTrue(!v.current.open)
            assertTrue(!v.target.open)
            assertEquals(200f, v.from.x, 0.01f)
            assertEquals(200f, v.from.y, 0.01f)
        }
    }
}
