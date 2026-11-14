package inego.zungjunggame.render.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Routing of one press between several movable blocks: the first block in tap
 * order that is under the pointer owns press → drag → release.
 */
class ParkChromeGroupTest {

    private class FakeBlock(
        val name: String,
        var area: (Float, Float) -> Boolean = { _, _ -> false },
        /** Simulates a block whose release turns out to be a park drag. */
        var consumesRelease: Boolean = false,
        /** Simulates a block that refuses the press (click-only control inside). */
        var acceptsPress: Boolean = true,
    ) : ParkableChrome {
        override val parkChromeLabel: String get() = name
        val downs: MutableList<Pair<Float, Float>> = mutableListOf()
        val drags: MutableList<Pair<Float, Float>> = mutableListOf()
        var ups: Int = 0
            private set
        var cancels: Int = 0
            private set

        override fun hitsParkChrome(worldX: Float, worldY: Float): Boolean = area(worldX, worldY)

        override fun onParkPointerDown(worldX: Float, worldY: Float): Boolean {
            if (!acceptsPress || !area(worldX, worldY)) return false
            downs += worldX to worldY
            return true
        }

        override fun onParkPointerDrag(worldX: Float, worldY: Float) {
            drags += worldX to worldY
        }

        override fun onParkPointerUp(worldX: Float, worldY: Float): ParkRelease {
            ups++
            return if (consumesRelease) ParkRelease.Consumed else ParkRelease.Tap
        }

        override fun cancelPark() {
            cancels++
        }
    }

    private fun block(
        name: String,
        x0: Float,
        x1: Float,
        y0: Float,
        y1: Float,
    ): FakeBlock = FakeBlock(name, area = { x, y -> x in x0..x1 && y in y0..y1 })

    @Test
    fun hitsReportsAnyBlockUnderThePoint() {
        val first = block("hint", 0f, 100f, 0f, 100f)
        val group = ParkChromeGroup(listOf(first, block("claim", 200f, 400f, 0f, 100f)))
        assertTrue(group.hitsParkChrome(50f, 50f))
        assertTrue(group.hitsParkChrome(300f, 50f))
        assertFalse(group.hitsParkChrome(150f, 50f))
    }

    @Test
    fun theFirstBlockInTapOrderOwnsAnOverlappingPress() {
        val hint = block("hint", 0f, 200f, 0f, 200f)
        val claim = block("claim", 0f, 200f, 0f, 200f)
        val group = ParkChromeGroup(listOf(hint, claim))

        assertTrue(group.onParkPointerDown(100f, 100f))
        assertEquals(listOf(100f to 100f), hint.downs)
        assertTrue(claim.downs.isEmpty(), "the block a tap would hit first owns the press")
    }

    @Test
    fun pressOutsideEveryBlockIsNotClaimed() {
        val hint = block("hint", 0f, 100f, 0f, 100f)
        val group = ParkChromeGroup(listOf(hint))
        assertFalse(group.onParkPointerDown(500f, 500f))
        assertTrue(hint.downs.isEmpty())
        assertFalse(group.onParkPointerUp(500f, 500f).consumed)
    }

    @Test
    fun aBlockThatDeclinesThePressLeavesNoOwner() {
        val declining = FakeBlock("declining", area = { _, _ -> true }, acceptsPress = false)
        val claim = block("claim", 0f, 500f, 0f, 500f)
        val group = ParkChromeGroup(listOf(declining, claim))

        // First in order declines (its press belongs to a click-only control), and
        // the group does not silently keep it as the owner.
        assertFalse(group.onParkPointerDown(100f, 100f))
        group.onParkPointerDrag(200f, 200f)
        assertTrue(declining.drags.isEmpty())
        assertEquals(0, declining.ups)
    }

    @Test
    fun dragAndReleaseFollowTheOwnerAfterThePointerLeavesTheBlock() {
        val hint = block("hint", 0f, 100f, 0f, 100f)
        val claim = block("claim", 0f, 100f, 0f, 100f)
        val group = ParkChromeGroup(listOf(hint, claim))
        hint.consumesRelease = true

        assertTrue(group.onParkPointerDown(50f, 50f))
        group.onParkPointerDrag(600f, 400f)
        group.onParkPointerDrag(700f, 400f)
        assertTrue(group.onParkPointerUp(700f, 400f).consumed)

        assertEquals(listOf(600f to 400f, 700f to 400f), hint.drags)
        assertEquals(1, hint.ups)
        assertTrue(claim.drags.isEmpty(), "the other block must not move with it")
    }

    @Test
    fun aTapReleaseIsLeftToTheHost() {
        val hint = block("hint", 0f, 100f, 0f, 100f)
        val group = ParkChromeGroup(listOf(hint))
        assertTrue(group.onParkPointerDown(50f, 50f))
        assertFalse(
            group.onParkPointerUp(51f, 50f).consumed,
            "a press that never became a drag is an ordinary tap",
        )
    }

    @Test
    fun theOwnersNameIsReportedForTheDiagnosticsLog() {
        val hint = block("hint-button", 0f, 100f, 0f, 100f)
        val claim = block("claim-row", 0f, 100f, 0f, 100f)
        val group = ParkChromeGroup(listOf(hint, claim))
        assertEquals("board-chrome", group.parkChromeLabel, "nothing in hand yet")
        assertTrue(group.onParkPointerDown(50f, 50f))
        assertEquals("hint-button", group.parkChromeLabel)
    }

    @Test
    fun cancelForwardsToTheOwnerAndForgetsIt() {
        val hint = block("hint", 0f, 100f, 0f, 100f)
        val group = ParkChromeGroup(listOf(hint))
        assertTrue(group.onParkPointerDown(50f, 50f))

        group.cancelPark()
        assertEquals(1, hint.cancels)

        // The press is gone: a later drag or release must not reach the block.
        group.onParkPointerDrag(80f, 80f)
        assertTrue(hint.drags.isEmpty())
        assertEquals(0, hint.ups)
    }
}
