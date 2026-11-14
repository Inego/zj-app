package inego.zungjunggame.render.wallbuild

import kotlin.test.*

/**
 * Tool tests for wall-build helpers. Does **not** assert lesson body keys or
 * curriculum beats — see docs/tutorial-authoring-guide.md.
 */
class WallBuildBeatTest {

    @Test
    fun inventIsDeterministicAndValid() {
        val a = WallBuildDeal.invent(kotlin.random.Random(11))
        val b = WallBuildDeal.invent(kotlin.random.Random(11))
        val c = WallBuildDeal.invent(kotlin.random.Random(12))
        assertEquals(a, b)
        assertTrue(a != c)
        assertTrue(a.faceA in 1..6 && a.faceB in 1..6)
        assertEquals(a.slotToTile.distinct().size, a.slotToTile.size)
    }

    @Test
    fun unknownBodyKeyIsNull() {
        assertNull(WallBuildBeats.of("not.a.registered.beat"))
    }
}

class WallBuildLessonStateTest {

    @Test
    fun commitAndClear() {
        val s = WallBuildLessonState()
        assertFalse(s.hasRoll)
        assertEquals(0, s.stackCount)
        s.commit(3, 4)
        assertTrue(s.hasRoll)
        assertEquals(7, s.stackCount)
        assertEquals(Triple(3, 4, 7), s.facesOrNull())
        s.clear()
        assertFalse(s.hasRoll)
        assertNull(s.facesOrNull())
    }

    @Test
    fun clearRollKeepsWallMapping() {
        val s = WallBuildLessonState()
        s.ensureShuffledWallMapping(kotlin.random.Random(1))
        assertTrue(s.hasWallMapping)
        val t0 = s.tileAtSlot(0)
        s.commit(2, 3)
        s.clearRoll()
        assertFalse(s.hasRoll)
        assertTrue(s.hasWallMapping)
        assertEquals(t0, s.tileAtSlot(0))
    }

    @Test
    fun newRollAfterClearKeepsMappingAndChangesOpeningHands() {
        val s = WallBuildLessonState()
        s.ensureShuffledWallMapping(kotlin.random.Random(5))
        s.commit(1, 1)
        val before = s.exportDeal()!!
        val handsBefore = WallBuildToGameState.openingState(before).players[0].concealed.copyOf()
        s.clearRoll()
        s.commit(6, 6)
        val after = s.exportDeal()!!
        assertContentEquals(before.slotToTile, after.slotToTile)
        assertEquals(1, before.faceA)
        assertEquals(6, after.faceA)
        val handsAfter = WallBuildToGameState.openingState(after).players[0].concealed
        assertFalse(
            handsBefore.contentEquals(handsAfter),
            "new dice must rebuild opening hands from the same wall map",
        )
    }

    @Test
    fun ensureShuffledWallMappingIsPermutationAndStable() {
        val s = WallBuildLessonState()
        assertFalse(s.hasWallMapping)
        s.ensureShuffledWallMapping(kotlin.random.Random(42))
        assertTrue(s.hasWallMapping)
        val n = inego.zungjunggame.engine.WALL_SIZE
        val seen = BooleanArray(n)
        for (slot in 0 until n) {
            val t = s.tileAtSlot(slot)
            assertTrue(t in 0 until n)
            assertFalse(seen[t], "duplicate tile $t")
            seen[t] = true
        }
        // Not the identity map (would produce sorted 3-kong cold hands).
        var identity = true
        for (slot in 0 until n) {
            if (s.tileAtSlot(slot) != slot) {
                identity = false
                break
            }
        }
        assertFalse(identity)
        val again = s.tileAtSlot(7)
        s.ensureShuffledWallMapping(kotlin.random.Random(99))
        assertEquals(again, s.tileAtSlot(7), "ensure must not re-shuffle")
    }

    @Test
    fun commitWallMappingOverwritesEnsure() {
        val s = WallBuildLessonState()
        s.ensureShuffledWallMapping(kotlin.random.Random(1))
        val n = inego.zungjunggame.engine.WALL_SIZE
        // Reverse permutation — valid and distinct from a typical shuffle.
        val rev = IntArray(n) { n - 1 - it }
        s.commitWallMapping(rev)
        assertEquals(n - 1, s.tileAtSlot(0))
        assertEquals(0, s.tileAtSlot(n - 1))
    }

    @Test
    fun clearDropsWallMapping() {
        val s = WallBuildLessonState()
        s.ensureShuffledWallMapping(kotlin.random.Random(2))
        s.commit(1, 1)
        s.clear()
        assertFalse(s.hasWallMapping)
        assertFalse(s.hasRoll)
    }

    @Test
    fun committedDealNeedsBothRollAndMapping() {
        val s = WallBuildLessonState()
        assertFalse(s.hasRoll && s.hasWallMapping)
        s.commit(4, 5)
        assertTrue(s.hasRoll)
        assertFalse(s.hasRoll && s.hasWallMapping)
        s.ensureShuffledWallMapping(kotlin.random.Random(3))
        assertTrue(s.hasRoll && s.hasWallMapping)
        s.clear()
        assertFalse(s.hasRoll && s.hasWallMapping)
    }

    @Test
    fun installDealMakesOpeningStateStable() {
        val s = WallBuildLessonState()
        s.commit(3, 3)
        s.ensureShuffledWallMapping(kotlin.random.Random(17))
        val deal = s.exportDeal()!!
        val a = WallBuildToGameState.openingState(
            breakStackCount = deal.stackCount,
            tileTypeAtSlot = { slot -> deal.slotToTile[slot] / 4 },
        )
        val b = WallBuildToGameState.openingState(
            breakStackCount = deal.stackCount,
            tileTypeAtSlot = { slot -> deal.slotToTile[slot] / 4 },
        )
        assertContentEquals(a.players[0].concealed, b.players[0].concealed)
        // Different deal ⇒ different East multiset (with high probability / seed).
        val s2 = WallBuildLessonState()
        s2.commit(5, 5)
        s2.ensureShuffledWallMapping(kotlin.random.Random(99))
        val deal2 = s2.exportDeal()!!
        val c = WallBuildToGameState.openingState(
            breakStackCount = deal2.stackCount,
            tileTypeAtSlot = { slot -> deal2.slotToTile[slot] / 4 },
        )
        assertFalse(a.players[0].concealed.contentEquals(c.players[0].concealed))
    }
}
