package inego.zungjunggame.render

import inego.zungjunggame.render.assets.buildThenSwap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ordering invariants of [inego.zungjunggame.render.assets.buildThenSwap]: the replacement is built before the
 * predecessor is touched, and a failed build rolls the partial set back.
 */
class ResourceSwapTest {

    private class Res(val id: Int)

    @Test
    fun successPublishesReplacementAndDisposesPreviousExactlyOnce() {
        val previous = listOf(Res(0), Res(1))
        val disposed = mutableListOf<Res>()
        var published: List<Res>? = null

        buildThenSwap(
            previous = previous,
            dispose = { disposed += it },
            build = { track -> listOf(track(Res(2)), track(Res(3))) },
            publish = { published = it },
        )

        val result = assertNotNull(published)
        assertEquals(listOf(2, 3), result.map { it.id })
        assertEquals(2, disposed.size)
        assertEquals(previous.toSet(), disposed.toSet())
        // The published replacement must not be disposed by its own swap.
        assertTrue(result.none { it in disposed })
    }

    @Test
    fun failureDisposesPartialsAndLeavesPreviousPublished() {
        val previous = listOf(Res(0), Res(1))
        val disposed = mutableListOf<Res>()
        var publishCalled = false
        var built = 0

        assertFailsWith<IllegalStateException> {
            buildThenSwap(
                previous = previous,
                dispose = { disposed += it },
                build = { track ->
                    track(Res(2).also { built++ })
                    track(Res(3).also { built++ })
                    error("upload failed")
                },
                publish = { publishCalled = true },
            )
        }

        assertFalse(publishCalled)
        assertEquals(2, built)
        assertEquals(listOf(2, 3), disposed.map { it.id })
        // The predecessor was never published away and must not be disposed.
        assertTrue(previous.none { it in disposed })
    }

    @Test
    fun emptyBuildStillDisposesPrevious() {
        val previous = listOf(Res(0))
        val disposed = mutableListOf<Res>()
        var published: List<Res>? = null

        buildThenSwap(
            previous = previous,
            dispose = { disposed += it },
            build = { emptyList() },
            publish = { published = it },
        )

        assertTrue(assertNotNull(published).isEmpty())
        assertEquals(previous, disposed)
    }
}
