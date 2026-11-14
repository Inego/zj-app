package inego.zungjunggame.render

import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.render.tiles.TileLabels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pure label mapping — no GL, no [com.badlogic.gdx.Gdx] app. Honors are exercised
 * through a stub lookup so the test pins both the key consulted and the value drawn.
 */
class TileLabelsTest {

    /** Fails the test if a suit tile ever asks the bundle for a tag. */
    private val throwingLookup: (String) -> String = { key ->
        fail("suit tiles must not consult the i18n bundle, asked for '$key'")
    }

    private val honors: List<Int> = listOf(
        Tiles.EAST, Tiles.SOUTH, Tiles.WEST, Tiles.NORTH,
        Tiles.RED, Tiles.GREEN, Tiles.WHITE,
    )

    @Test
    fun suitTilesMapToTheirDigit() {
        // 0..26 is characters, then dots, then bamboo; each suit counts 1..9.
        for (type in 0 until Tiles.EAST) {
            val digit = (type % 9 + 1).toString()
            assertEquals(digit, TileLabels.label(type, throwingLookup), "tile type $type")
        }
    }

    @Test
    fun honorsConsultExactlyTheirKey() {
        for ((index, type) in honors.withIndex()) {
            val key = TileLabels.HONOR_KEYS[index]
            val asked = ArrayList<String>(1)
            val tag = TileLabels.label(type) { k ->
                asked += k
                "tag:$k"
            }
            assertEquals(listOf(key), asked, "keys consulted for tile type $type")
            assertEquals("tag:$key", tag)
        }
    }

    @Test
    fun suitsNeverConsultLookup() {
        val asked = ArrayList<String>()
        val tag: (String) -> String = { key ->
            asked += key
            key
        }
        for (type in 0 until Tiles.EAST) {
            TileLabels.label(type, tag)
        }
        assertTrue(asked.isEmpty(), "suit labels must not resolve i18n keys, saw $asked")
    }

    @Test
    fun outOfRangeTypesAreEmpty() {
        val outOfRange = listOf(-1, NUM_TILE_TYPES, NUM_TILE_TYPES + 1, 999, Int.MIN_VALUE)
        for (type in outOfRange) {
            assertEquals("", TileLabels.label(type, throwingLookup), "tile type $type")
        }
    }

    @Test
    fun tagOrNullIsTheOnlyPreferenceGate() {
        val prev = TileLabels.enabled
        try {
            TileLabels.enabled = false
            assertNull(TileLabels.tagOrNull(Tiles.CHAR_5), "off: suit")
            assertNull(TileLabels.tagOrNull(Tiles.EAST), "off: honor")

            TileLabels.enabled = true
            assertEquals("5", TileLabels.tagOrNull(Tiles.CHAR_5), "on: suit")
            assertEquals("tag:tilelabel.west", TileLabels.tagOrNull(Tiles.WEST) { "tag:$it" })
            // Nothing to draw is null too, so a caller never tests the empty string.
            assertNull(TileLabels.tagOrNull(NUM_TILE_TYPES), "on: out of range")
            assertNull(TileLabels.tagOrNull(999), "on: nonsense")
        } finally {
            TileLabels.enabled = prev
        }
    }

}
