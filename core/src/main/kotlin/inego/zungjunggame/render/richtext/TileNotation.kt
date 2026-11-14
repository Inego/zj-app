package inego.zungjunggame.render.richtext

import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.Tiles

/**
 * Parses compact mahjong tile notation used inside `{…}` rich-text tokens.
 *
 * Supported forms (whitespace-separated groups inside one brace):
 * - Suit runs: `1m`, `123p`, `99s` (digits then suit). Suit letters:
 *   `m`/`c` characters, `p`/`d` dots, `s`/`b` bamboo.
 * - Honors by letter: `E` `S` `W` `N` (winds), `C` red, `F` green, `P` white;
 *   repeats like `EE` / `WWW` allowed.
 * - Honors by name: `east`, `south`, `west`, `north`, `red`, `green`, `white`
 *   (and `haku` / `hatsu` / `chun`).
 * - Tenhou z-suit: `1z`–`7z` (E S W N white green red), e.g. `123z`.
 *
 * Invalid tokens throw [IllegalArgumentException] from [parseGroup] / [parseToken];
 * [tryParseGroup] returns null instead.
 */
object TileNotation {

    fun tryParseGroup(inner: String): IntArray? =
        try {
            parseGroup(inner)
        } catch (_: IllegalArgumentException) {
            null
        }

    fun parseGroup(inner: String): IntArray {
        val trimmed = inner.trim()
        if (trimmed.isEmpty()) return IntArray(0)
        val out = ArrayList<Int>(trimmed.length)
        for (raw in trimmed.split(WHITESPACE)) {
            if (raw.isEmpty()) continue
            parseToken(raw).forEach { out += it }
        }
        return out.toIntArray()
    }

    /** Compact token for [type] (`5s`, `E`, `C`, …) that [parseToken] accepts. */
    fun token(type: Int): String {
        require(type in 0 until NUM_TILE_TYPES) { "tile type $type out of 0..33" }
        return when (type) {
            in Tiles.CHAR_1..Tiles.CHAR_9 -> "${type - Tiles.CHAR_1 + 1}m"
            in Tiles.DOT_1..Tiles.DOT_9 -> "${type - Tiles.DOT_1 + 1}p"
            in Tiles.BAMBOO_1..Tiles.BAMBOO_9 -> "${type - Tiles.BAMBOO_1 + 1}s"
            Tiles.EAST -> "E"
            Tiles.SOUTH -> "S"
            Tiles.WEST -> "W"
            Tiles.NORTH -> "N"
            Tiles.RED -> "C"
            Tiles.GREEN -> "F"
            Tiles.WHITE -> "P"
            else -> error("unhandled tile type $type")
        }
    }

    /** `{5s}` / `{E}` markup for rich-text coach copy. */
    fun markup(type: Int): String = "{${token(type)}}"

    fun parseToken(token: String): IntArray {
        val t = token.trim()
        if (t.isEmpty()) throw IllegalArgumentException("empty tile token")

        honorName(t)?.let { type -> return intArrayOf(type) }

        // Suit or z run: one or more digits + suit letter.
        val suitRun = SUIT_RUN.matchEntire(t)
        if (suitRun != null) {
            val digits = suitRun.groupValues[1]
            val suit = suitRun.groupValues[2].lowercase()
            return IntArray(digits.length) { i ->
                val d = digits[i] - '0'
                digitToTile(d, suit)
            }
        }

        // Repeated honor letters: EE, WWW, NN, CC, FF, PP.
        val honorRun = HONOR_RUN.matchEntire(t)
        if (honorRun != null) {
            val letter = honorRun.groupValues[1][0]
            val type = honorLetter(letter)
                ?: throw IllegalArgumentException("unknown honor letter in '$t'")
            val n = honorRun.groupValues[1].length
            return IntArray(n) { type }
        }

        throw IllegalArgumentException("unrecognized tile token '$t'")
    }

    private fun digitToTile(digit: Int, suit: String): Int {
        if (digit !in 1..9 && suit != "z") {
            throw IllegalArgumentException("tile digit out of range: $digit$suit")
        }
        return when (suit) {
            "m", "c" -> Tiles.CHAR_1 + (digit - 1)
            "p", "d" -> Tiles.DOT_1 + (digit - 1)
            "s", "b" -> Tiles.BAMBOO_1 + (digit - 1)
            "z" -> when (digit) {
                1 -> Tiles.EAST
                2 -> Tiles.SOUTH
                3 -> Tiles.WEST
                4 -> Tiles.NORTH
                5 -> Tiles.WHITE
                6 -> Tiles.GREEN
                7 -> Tiles.RED
                else -> throw IllegalArgumentException("z-tile digit out of range: ${digit}z")
            }
            else -> throw IllegalArgumentException("unknown suit '$suit'")
        }
    }

    private fun honorLetter(c: Char): Int? = when (c.uppercaseChar()) {
        'E' -> Tiles.EAST
        'S' -> Tiles.SOUTH
        'W' -> Tiles.WEST
        'N' -> Tiles.NORTH
        'C' -> Tiles.RED
        'F' -> Tiles.GREEN
        'P' -> Tiles.WHITE
        else -> null
    }

    private fun honorName(raw: String): Int? = when (raw.lowercase()) {
        "east", "ton" -> Tiles.EAST
        "south", "nan" -> Tiles.SOUTH
        "west", "shaa", "sha" -> Tiles.WEST
        "north", "pei", "pe" -> Tiles.NORTH
        "red", "chun" -> Tiles.RED
        "green", "hatsu", "hatu" -> Tiles.GREEN
        "white", "haku" -> Tiles.WHITE
        else -> null
    }

    private val WHITESPACE = Regex("\\s+")
    private val SUIT_RUN = Regex("^([1-9]+)([mpscdbz])$", RegexOption.IGNORE_CASE)
    private val HONOR_RUN = Regex("^([ESWNCFP]+)$", RegexOption.IGNORE_CASE)
}
