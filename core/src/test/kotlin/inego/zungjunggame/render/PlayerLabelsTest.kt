package inego.zungjunggame.render

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.i18n.L10n
import inego.zungjunggame.i18n.LocaleController
import inego.zungjunggame.render.reference.ReferenceAssetSources
import inego.zungjunggame.render.seats.PlayerLabels
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Seat labels carry the locale-invariant mahjong canon 東南西北. The table
 * draws that character alone; a tile-tag symbol is a paint decision in
 * [inego.zungjunggame.render.seats.SeatLabelDrawer], not a second name here.
 *
 * Bot seats are synthetic opponents, so their label is composed on the client
 * from the `is_bot` flag rather than echoed from the server's English wire
 * placeholder — see [inego.zungjunggame.render.seats.PlayerLabels.nameForAbsoluteSeat].
 */
class PlayerLabelsTest {

    @BeforeTest
    fun setUp() {
        val root = requireNotNull(ReferenceAssetSources.findAssetsRoot()) {
            "assets/ not found from ${File(".").absolutePath}"
        }
        L10n.resetForTests()
        L10n.bootstrap(Locale.US, FileHandle(File(root, "i18n/ui")))
    }

    @AfterTest
    fun tearDown() {
        L10n.resetForTests()
        LocaleController.resetForTests()
        PlayerLabels.clearCustomNames()
    }

    @Test
    fun windGlyphsAreTheTraditionalCanon() {
        // 東南西北 are glyphs, not translated text: the mahjong canon is the
        // Traditional form in every locale (zh-Hans included). windHan takes no
        // locale or profile input, so there is no per-locale loop to run — this
        // table is the spec for the four wind indices.
        val canon = listOf(
            Tiles.EAST to "東",
            Tiles.SOUTH to "南",
            Tiles.WEST to "西",
            Tiles.NORTH to "北",
        )
        for ((tile, han) in canon) {
            assertEquals(han, PlayerLabels.windHan(tile), "tile $tile should draw the canon")
        }
    }

    @Test
    fun simplifiedChineseStillDrawsTheCanonEast() {
        // 东 would be the Simplified form for a compass point, but mahjong canon
        // keeps 東; see PlayerLabels.HAN_WINDS.
        assertEquals("東", PlayerLabels.windHan(Tiles.EAST))
    }

    @Test
    fun nonWindTilesHaveNoGlyph() {
        assertEquals("", PlayerLabels.windHan(Tiles.RED))
        assertEquals("", PlayerLabels.windHan(-1))
        assertEquals("", PlayerLabels.windHan(Tiles.NORTH + 1))
    }

    // --- bot seats are synthetic names ------------------------------------

    @Test
    fun botOrdinalComesFromTheBotFlagNotTheWireName() {
        // The server's placeholder is deliberately wrong for every seat: the
        // label must be derived from is_bot, numbered in seat-index order.
        PlayerLabels.setPlayerNames(
            arrayOf("Alice", "Bot 9", "Bot 9", "Bot 9"),
            booleanArrayOf(false, true, true, true),
        )
        assertEquals("Alice", PlayerLabels.nameForAbsoluteSeat(0, humanSeat = 0))
        assertEquals(L10n.format("seat.bot_numbered", 1), PlayerLabels.nameForAbsoluteSeat(1, humanSeat = 0))
        assertEquals(L10n.format("seat.bot_numbered", 2), PlayerLabels.nameForAbsoluteSeat(2, humanSeat = 0))
        assertEquals(L10n.format("seat.bot_numbered", 3), PlayerLabels.nameForAbsoluteSeat(3, humanSeat = 0))
    }

    @Test
    fun aLoneBotIsNotNumbered() {
        PlayerLabels.setPlayerNames(
            arrayOf("Alice", "Bob", "Carol", "Bot 1"),
            booleanArrayOf(false, false, false, true),
        )
        assertEquals(L10n.get("seat.bot"), PlayerLabels.nameForAbsoluteSeat(3, humanSeat = 0))

        L10n.setLocale(Locale.forLanguageTag("ru"))
        assertEquals(L10n.get("seat.bot"), PlayerLabels.nameForAbsoluteSeat(3, humanSeat = 0))
    }

    @Test
    fun humanNamesAndRelativeFallbackAreUnchanged() {
        PlayerLabels.setPlayerNames(
            arrayOf("Alice", "Bob", "Carol", "Dave"),
            booleanArrayOf(false, false, false, false),
        )
        assertEquals("Bob", PlayerLabels.nameForAbsoluteSeat(1, humanSeat = 0))

        // No custom names at all (local free-play, tutorials): relative labels.
        PlayerLabels.setPlayerNames(null, null)
        assertEquals(L10n.get("seat.right"), PlayerLabels.nameForAbsoluteSeat(1, humanSeat = 0))
        assertEquals(L10n.get("seat.opposite"), PlayerLabels.columnName(2, humanSeat = 0))
    }

    // --- bot names are dimmed, not recoloured -----------------------------

    @Test
    fun botNamesAreTheSeatColourAtSixtyPercentOpacity() {
        // Bots are fillers, so the label keeps the seat's own hue and only loses
        // prominence. A hardcoded tint would read as a different class of text.
        val base = Color(0.96f, 0.94f, 0.88f, 1f)
        val out = Color()
        PlayerLabels.botNameTint(base, out)
        assertEquals(base.r, out.r)
        assertEquals(base.g, out.g)
        assertEquals(base.b, out.b)
        assertEquals(0.6f, out.a, 1e-6f)
    }

    @Test
    fun botTintNeverMutatesTheBaseColour() {
        // Callers pass a live `font.color`, so an in-place set would leak the
        // dimmed alpha onto every later name and score drawn with that face.
        val base = Color(0.96f, 0.94f, 0.88f, 1f)
        PlayerLabels.botNameTint(base, Color())
        assertEquals(Color(0.96f, 0.94f, 0.88f, 1f), base)
    }

    @Test
    fun botTintScalesAnAlreadyTranslucentBase() {
        val out = Color()
        PlayerLabels.botNameTint(Color(1f, 1f, 1f, 0.5f), out)
        assertEquals(0.3f, out.a, 1e-6f)
    }
}
