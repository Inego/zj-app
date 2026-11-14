package inego.zungjunggame.render.wallbuild

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WallSlotLayoutTest {

    @Test
    fun take7StacksStayOnEastWhenDiceCountIsAtLeast7() {
        // Dice sum 10 → 10 East stacks right of gap; take first 7 only.
        val n = 10
        val first = WallSlotLayout.firstHighlightedAlong(n)
        assertEquals(7, first)
        val stacks = (0 until 7).map { WallSlotLayout.take7Stack(n, it) }
        assertEquals(
            listOf(
                WallSlotLayout.EAST_SIDE to 7,
                WallSlotLayout.EAST_SIDE to 8,
                WallSlotLayout.EAST_SIDE to 9,
                WallSlotLayout.EAST_SIDE to 10,
                WallSlotLayout.EAST_SIDE to 11,
                WallSlotLayout.EAST_SIDE to 12,
                WallSlotLayout.EAST_SIDE to 13,
            ),
            stacks,
        )
    }

    @Test
    fun take7StacksSpillOntoSouthWhenDiceCountIsBelow7() {
        // Dice sum 5 → 5 on East, 2 more on South from alongI 0.
        val n = 5
        val stacks = (0 until 7).map { WallSlotLayout.take7Stack(n, it) }
        assertEquals(
            listOf(
                WallSlotLayout.EAST_SIDE to 12,
                WallSlotLayout.EAST_SIDE to 13,
                WallSlotLayout.EAST_SIDE to 14,
                WallSlotLayout.EAST_SIDE to 15,
                WallSlotLayout.EAST_SIDE to 16,
                WallSlotLayout.SOUTH_SIDE to 0,
                WallSlotLayout.SOUTH_SIDE to 1,
            ),
            stacks,
        )
    }

    @Test
    fun take7StackRejectsOutOfRange() {
        assertNull(WallSlotLayout.take7Stack(0, 0))
        assertNull(WallSlotLayout.take7Stack(5, -1))
        assertNull(WallSlotLayout.take7Stack(5, 7))
    }

    @Test
    fun liveWallStartsLeftOfGapAndSkipsDeadStacks() {
        val n = 5
        // First live = East alongI just left of gap (firstHighlighted - 1).
        val firstLive = WallSlotLayout.liveWallStack(n, 0)
        assertEquals(firstLive, WallSlotLayout.EAST_SIDE to 11)
        // First 24 deal stacks never land on a take-7 slot.
        for (i in 0 until WallSlotLayout.DEAL_STACKS_TOTAL) {
            val live = WallSlotLayout.liveWallStack(n, i)!!
            assertTrue(
                !WallSlotLayout.isTake7Stack(n, live.first, live.second),
                "deal stack $i hit dead wall ${live.first}/${live.second}",
            )
        }
    }

    @Test
    fun liveWallWithDice8StartsLeftOfGapNotRightOfDeadWall() {
        // Dice 8: gap before East alongI 9; dead = 9..15; leftover right = 16.
        val n = 8
        assertEquals(9, WallSlotLayout.firstHighlightedAlong(n))
        // Must start left of the split, not the rightmost leftover stack.
        assertEquals(WallSlotLayout.liveWallStack(n, 0), WallSlotLayout.EAST_SIDE to 8)
        assertEquals(WallSlotLayout.liveWallStack(n, 1), WallSlotLayout.EAST_SIDE to 7)
        // Continuing left: alongI 8..0 → 9 stacks, then North…
        assertEquals(WallSlotLayout.liveWallStack(n, 8), WallSlotLayout.EAST_SIDE to 0)
        assertEquals(WallSlotLayout.liveWallStack(n, 9), 3 to 16)
        // Leftover East 16 is live but only after the full circuit (not first).
        val live = (0 until WallSlotLayout.DEAL_STACKS_TOTAL).map {
            WallSlotLayout.liveWallStack(n, it)!!
        }
        assertTrue(
            live.none { it == WallSlotLayout.EAST_SIDE to 16 },
            "first 24 deal stacks should not take the right-of-dead leftover",
        )
        for ((first, second) in live) {
            assertTrue(
                !WallSlotLayout.isTake7Stack(n, first, second),
                "deal hit dead wall $first/$second",
            )
        }
        // Leftover is still on the live wall, just after North/West/South.
        // East left 9 + North 17 + West 17 + South 17 = 60; leftover is index 60.
        assertEquals(WallSlotLayout.liveWallStack(n, 60), WallSlotLayout.EAST_SIDE to 16)
        assertNull(WallSlotLayout.liveWallStack(n, 61))
    }

    @Test
    fun liveWallWithDice10PutsThreeEastLeftoversLast() {
        // Dice 10: first=7, dead=7..13, leftovers 16,15,14 (right end → dead).
        val n = 10
        assertEquals(WallSlotLayout.liveWallStack(n, 0), WallSlotLayout.EAST_SIDE to 6)
        // East left of gap: 6..0 = 7 stacks → index 7 is North.
        assertEquals(WallSlotLayout.liveWallStack(n, 7), 3 to 16)
        // After East-left 7 + N 17 + W 17 + S 17 = 58: leftovers 16,15,14.
        assertEquals(WallSlotLayout.liveWallStack(n, 58), WallSlotLayout.EAST_SIDE to 16)
        assertEquals(WallSlotLayout.liveWallStack(n, 59), WallSlotLayout.EAST_SIDE to 15)
        assertEquals(WallSlotLayout.liveWallStack(n, 60), WallSlotLayout.EAST_SIDE to 14)
        assertNull(WallSlotLayout.liveWallStack(n, 61))
    }

    @Test
    fun dealTargetsGiveThreeSquaresPerSeat() {
        assertEquals(24, WallSlotLayout.DEAL_STACKS_TOTAL)
        val bySeat = Array(4) { mutableListOf<WallSlotLayout.DealTarget>() }
        for (i in 0 until WallSlotLayout.DEAL_STACKS_TOTAL) {
            val t = WallSlotLayout.dealTarget(i)!!
            bySeat[t.seat] += t
        }
        for (seat in 0 until 4) {
            assertEquals(6, bySeat[seat].size, "seat $seat stack count")
            assertEquals(
                listOf(0, 0, 1, 1, 2, 2),
                bySeat[seat].map { it.groupIndex },
            )
            assertEquals(
                listOf(0, 1, 0, 1, 0, 1),
                bySeat[seat].map { it.stackInGroup },
            )
        }
        // Round order: E,E,S,S,W,W,N,N then next square…
        assertEquals(0, WallSlotLayout.dealTarget(0)!!.seat)
        assertEquals(1, WallSlotLayout.dealTarget(2)!!.seat)
        assertEquals(2, WallSlotLayout.dealTarget(4)!!.seat)
        assertEquals(3, WallSlotLayout.dealTarget(6)!!.seat)
        assertEquals(0, WallSlotLayout.dealTarget(8)!!.seat)
    }

    @Test
    fun residualWallSlotsPartitionAfterFullDeal() {
        // 136 wall tiles − 53 dealt − 14 dead = 69 residual live.
        for (n in 2..12) {
            val residual = WallSlotLayout.residualWallSlots(n)
            val hands = (0 until WallSlotLayout.SIDE_COUNT)
                .flatMap { WallSlotLayout.dealHandSlotIds(n, it) }
                .toSet()
            assertEquals(53, hands.size, "break=$n dealt hands")
            assertEquals(14, residual.dead.size, "break=$n dead")
            assertEquals(69, residual.live.size, "break=$n live residual")
            assertEquals(
                WallSlotLayout.SLOT_COUNT,
                hands.size + residual.live.size + residual.dead.size,
                "break=$n partition",
            )
            assertTrue(residual.live.none { it in hands })
            assertTrue(residual.dead.none { it in hands })
            assertTrue(residual.live.toSet().intersect(residual.dead.toSet()).isEmpty())
            for (slot in residual.dead) {
                assertTrue(
                    WallSlotLayout.isTake7Stack(
                        n,
                        WallSlotLayout.slotSide(slot),
                        WallSlotLayout.slotAlong(slot),
                    ),
                    "slot $slot should be take-7",
                )
            }
        }
    }

    @Test
    fun deadWallUpperPoseIsAbsoluteHorizontalAndFurtherDown() {
        val m = WallSlotLayout.metrics(
            sq = WallSlotLayout.TableSquare(0f, 0f, 800f),
            tableMm = 800f,
            tileMm = 40f,
            tileAspect = 0.75f,
        )
        var originX = 0f
        var originY = 0f
        var ux = 0f
        var uy = 0f
        var vx = 0f
        var vy = 0f
        WallSlotLayout.forEachPushedSide(m) { side, ox, oy, uxx, uyy, vxx, vyy ->
            if (side == WallSlotLayout.EAST_SIDE) {
                originX = ox
                originY = oy
                ux = uxx
                uy = uyy
                vx = vxx
                vy = vyy
            }
        }
        val n = 5
        val poses = (0 until 7).map { k ->
            WallSlotLayout.deadWallUpperPose(n, k, m, originX, originY, ux, uy, vx, vy)!!
        }
        for ((_, _, rot) in poses) {
            assertEquals(0f, rot, 0.01f)
        }
        // True horizontal: all same cy, step pure +X.
        for (i in 1 until poses.size) {
            assertEquals(poses[0].cy, poses[i].cy, 0.01f)
            assertEquals(m.step, poses[i].cx - poses[i - 1].cx, 0.01f)
        }
        val wall0 = WallSlotLayout.pushedUpperPose(
            side = WallSlotLayout.EAST_SIDE,
            alongI = WallSlotLayout.firstHighlightedAlong(n),
            m = m,
            breakStackCount = n,
            originX = originX,
            originY = originY,
            ux = ux,
            uy = uy,
            vx = vx,
            vy = vy,
        )
        assertTrue(
            poses[0].cy < wall0.cy - m.step,
            "dead wall should sit clearly below the East wall line",
        )
    }
}
