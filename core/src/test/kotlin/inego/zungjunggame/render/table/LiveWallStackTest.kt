package inego.zungjunggame.render.table

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveWallStackTest {

    @Test
    fun fullStackStepsForwardAndFadesANewBackAtTheThirdSlot() {
        val atStart = LiveWallStack.shiftCards(before = 3, after = 3, u = 0f)
        assertEquals(atStart, listOf(card(1f), card(2f)))

        val mid = LiveWallStack.shiftCards(before = 3, after = 3, u = 0.5f)
        val promoted = mid.minBy { it.depth }
        assertTrue(promoted.depth > 0f && promoted.depth < 1f)
        val born = mid.maxBy { it.depth }
        assertEquals(2f, born.depth, 1e-4f)
        assertTrue(born.alpha > 0f && born.alpha < 1f)

        assertEquals(LiveWallStack.restCards(3), LiveWallStack.shiftCards(3, 3, 1f))
    }

    @Test
    fun stackShrinksWhenFewerThanThreeRemain() {
        assertEquals(LiveWallStack.shiftCards(3, 2, 0f), listOf(card(1f), card(2f)))
        assertEquals(LiveWallStack.restCards(2), LiveWallStack.shiftCards(3, 2, 1f))

        assertEquals(LiveWallStack.shiftCards(2, 1, 0f), listOf(card(1f)))
        assertEquals(LiveWallStack.restCards(1), LiveWallStack.shiftCards(2, 1, 1f))

        assertEquals(LiveWallStack.shiftCards(1, 0, 0f), emptyList())
        assertEquals(LiveWallStack.shiftCards(1, 0, 1f), emptyList())
    }

    @Test
    fun oneWallTakeRefillsThenALaterTakeShrinks() {
        val stack = LiveWallStack()
        stack.sync(liveWallSize = 70, spawnedFromWall = 0)
        assertEquals(LiveWallStack.restCards(3), stack.cards())
        assertTrue(stack.willDepartOne(69))
        assertFalse(stack.willDepartOne(68))

        stack.sync(liveWallSize = 69, spawnedFromWall = 1)
        assertFalse(stack.cards().any { it.depth == 0f && it.alpha == 1f })

        // A recount with no new spawn must not restart the step.
        val midBefore = stack.cards().minOf { it.depth }
        stack.advance(0.05f, 0.3f)
        stack.sync(liveWallSize = 69, spawnedFromWall = 0)
        val midAfter = stack.cards().minOf { it.depth }
        assertTrue(midAfter < midBefore)
        assertTrue(midAfter > 0f)

        stack.advance(1f, 0.3f)
        assertEquals(LiveWallStack.restCards(3), stack.cards())

        // The wall is down to three, then each take removes one card.
        stack.sync(liveWallSize = 3, spawnedFromWall = 0)
        stack.sync(liveWallSize = 2, spawnedFromWall = 1)
        stack.advance(1f, 0.3f)
        assertEquals(LiveWallStack.restCards(2), stack.cards())

        stack.sync(liveWallSize = 1, spawnedFromWall = 1)
        stack.advance(1f, 0.3f)
        assertEquals(LiveWallStack.restCards(1), stack.cards())

        stack.sync(liveWallSize = 0, spawnedFromWall = 1)
        stack.advance(1f, 0.3f)
        assertEquals(emptyList(), stack.cards())
    }

    @Test
    fun burstAndACountChangeWithoutASpawnSnap() {
        val stack = LiveWallStack()
        stack.sync(liveWallSize = 70, spawnedFromWall = 53)
        assertEquals(LiveWallStack.restCards(3), stack.cards())

        stack.sync(liveWallSize = 17, spawnedFromWall = 53)
        assertEquals(LiveWallStack.restCards(3), stack.cards())

        stack.sync(liveWallSize = 16, spawnedFromWall = 0)
        assertEquals(LiveWallStack.restCards(3), stack.cards())

        stack.sync(liveWallSize = 2, spawnedFromWall = 0)
        assertEquals(LiveWallStack.restCards(2), stack.cards())
    }

    private fun card(depth: Float, alpha: Float = 1f) = WallStackCard(depth, alpha)
}
