package inego.zungjunggame.render.richtext

import inego.zungjunggame.engine.Tiles
import kotlin.test.*

class TileNotationTest {

    @Test
    fun singleSuitTiles() {
        assertEquals(Tiles.CHAR_1, TileNotation.parseToken("1m").single())
        assertEquals(Tiles.DOT_5, TileNotation.parseToken("5p").single())
        assertEquals(Tiles.BAMBOO_9, TileNotation.parseToken("9s").single())
        assertEquals(Tiles.CHAR_3, TileNotation.parseToken("3c").single())
        assertEquals(Tiles.DOT_2, TileNotation.parseToken("2d").single())
        assertEquals(Tiles.BAMBOO_7, TileNotation.parseToken("7b").single())
    }

    @Test
    fun suitRunsExpandDigits() {
        assertContentEquals(
            intArrayOf(Tiles.CHAR_1, Tiles.CHAR_2, Tiles.CHAR_3),
            TileNotation.parseToken("123m"),
        )
        assertContentEquals(
            intArrayOf(Tiles.DOT_5, Tiles.DOT_5),
            TileNotation.parseToken("55p"),
        )
    }

    @Test
    fun honorLettersAndNames() {
        assertEquals(Tiles.EAST, TileNotation.parseToken("E").single())
        assertEquals(Tiles.SOUTH, TileNotation.parseToken("S").single())
        assertEquals(Tiles.WEST, TileNotation.parseToken("W").single())
        assertEquals(Tiles.NORTH, TileNotation.parseToken("N").single())
        assertEquals(Tiles.RED, TileNotation.parseToken("C").single())
        assertEquals(Tiles.GREEN, TileNotation.parseToken("F").single())
        assertEquals(Tiles.WHITE, TileNotation.parseToken("P").single())
        assertEquals(Tiles.EAST, TileNotation.parseToken("east").single())
        assertEquals(Tiles.WHITE, TileNotation.parseToken("haku").single())
        assertEquals(Tiles.RED, TileNotation.parseToken("chun").single())
    }

    @Test
    fun honorRuns() {
        assertContentEquals(
            intArrayOf(Tiles.EAST, Tiles.EAST),
            TileNotation.parseToken("EE"),
        )
        assertContentEquals(
            intArrayOf(Tiles.WHITE, Tiles.WHITE, Tiles.WHITE),
            TileNotation.parseToken("PPP"),
        )
    }

    @Test
    fun zNotation() {
        assertEquals(Tiles.EAST, TileNotation.parseToken("1z").single())
        assertEquals(Tiles.RED, TileNotation.parseToken("7z").single())
        assertContentEquals(
            intArrayOf(Tiles.EAST, Tiles.SOUTH, Tiles.WEST),
            TileNotation.parseToken("123z"),
        )
    }

    @Test
    fun groupWithSpaces() {
        assertContentEquals(
            intArrayOf(
                Tiles.CHAR_1, Tiles.CHAR_2, Tiles.CHAR_3,
                Tiles.DOT_4, Tiles.DOT_5, Tiles.DOT_6,
                Tiles.EAST, Tiles.EAST,
            ),
            TileNotation.parseGroup("123m 456p EE"),
        )
    }

    @Test
    fun tryParseReturnsNullOnGarbage() {
        assertNull(TileNotation.tryParseGroup("not-a-tile"))
        assertNull(TileNotation.tryParseGroup("0m"))
        assertFailsWith<IllegalArgumentException> { TileNotation.parseToken("xyz") }
    }
}
