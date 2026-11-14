package inego.zungjunggame.render.seats

import com.badlogic.gdx.graphics.Color
import inego.zungjunggame.engine.NUM_PLAYERS
import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.i18n.L10n

/**
 * Localized display names for seats and winds relative to the human viewer,
 * or custom player names for online games.
 * Order is You → Right → Opposite → Left (relative seats 0..3).
 * Engine state stays seat/tile ints; UI resolves strings via [L10n] each draw.
 */
object PlayerLabels {
    private val SEAT_KEYS = arrayOf("seat.you", "seat.right", "seat.opposite", "seat.left")

    /**
     * Seat-wind characters 東南西北. These are **glyphs, not translated text**:
     * the mahjong canon is the Traditional form, as written on the wall labels,
     * the tile faces and in the bundled reference docs. It is drawn in every
     * locale, `zh-Hans` included — Simplified Chinese keeps 東 for mahjong winds
     * exactly like Japanese does.
     */
    private val HAN_WINDS = arrayOf("東", "南", "西", "北")

    /** Order of relative seats in the columns of the scores table: Left, You, Opposite, Right. */
    val COLUMN_ORDER_TO_RELATIVE_SEATS: IntArray = intArrayOf(3, 0, 2, 1)

    /**
     * Alpha of a bot's name.
     *
     * Bots are fillers, so their label is *less prominent* rather than
     * differently styled: the seat's normal colour at 60% opacity. Dimming
     * instead of recolouring keeps the difference one of weight, not of kind,
     * and it stays correct wherever the base colour changes (active-seat tint,
     * panel palettes).
     */
    const val BOT_NAME_ALPHA: Float = 0.6f

    var customNames: Array<String>? = null
        private set
    var customIsBot: BooleanArray? = null
        private set

    fun setPlayerNames(names: Array<String>?, isBot: BooleanArray? = null) {
        customNames = names?.copyOf()
        customIsBot = isBot?.copyOf()
    }

    fun clearCustomNames() {
        customNames = null
        customIsBot = null
    }

    fun isBot(absoluteSeat: Int): Boolean =
        customIsBot?.getOrNull(absoluteSeat) ?: false

    fun isBotForColumn(columnIndex: Int, humanSeat: Int): Boolean {
        val absSeats = absoluteSeatsInColumnOrder(humanSeat)
        val absSeat = absSeats.getOrElse(columnIndex and 3) { 0 }
        return isBot(absSeat)
    }

    /**
     * Colour for a bot's name: [base] at [BOT_NAME_ALPHA].
     *
     * Same hue as every human seat — only the prominence differs. Written into
     * the caller's [out] because the draw that consumes it can outlive the call
     * (a font tint, a layout), and a shared instance would alias the previous
     * seat's label. [base] is never mutated, so passing a live `font.color` is safe.
     */
    fun botNameTint(base: Color, out: Color): Color =
        out.set(base.r, base.g, base.b, base.a * BOT_NAME_ALPHA)

    fun relativeName(relativeSeat: Int): String =
        L10n.get(SEAT_KEYS[relativeSeat and 3])

    /** Name of the seat shown in display column [columnIndex] (Left, You, Opposite, Right). */
    fun columnName(columnIndex: Int, humanSeat: Int = 0): String =
        nameForAbsoluteSeat(
            absoluteSeatsInColumnOrder(humanSeat).getOrElse(columnIndex and 3) { 0 },
            humanSeat,
        )

    /**
     * Name drawn for an absolute seat.
     *
     * Bot seats are **synthetic**: their label is composed here, not on the wire.
     * The server has no locale of its own, so it ships a plain English placeholder
     * (`"Bot 3"`) as if it were a display name; the client therefore formats the
     * label from the `is_bot` flag ([botLabel]) and never echoes the placeholder.
     *
     * Human names pass through verbatim. With no custom names at all (local
     * free-play, tutorials) seats fall back to the viewer-relative labels.
     */
    fun nameForAbsoluteSeat(seat: Int, humanSeat: Int): String {
        if (isBot(seat)) return botLabel(seat)
        val names = customNames
        if (names != null && seat in names.indices && names[seat].isNotBlank()) {
            return names[seat]
        }
        val relative = (seat - humanSeat + NUM_PLAYERS) % NUM_PLAYERS
        return relativeName(relative)
    }

    /**
     * Localized label for the bot at [absoluteSeat].
     *
     * Mirrors the server's numbering exactly — bots are counted in seat-index
     * order and the ordinal is only printed when the table holds more than one —
     * so the wire's `Bot 3` and the rendered `Бот 3` denote the same seat.
     */
    private fun botLabel(absoluteSeat: Int): String {
        val flags = customIsBot ?: return L10n.get("seat.bot")
        if (flags.count { it } <= 1) return L10n.get("seat.bot")
        return L10n.format("seat.bot_numbered", flags.take(absoluteSeat + 1).count { it })
    }

    /** Localized seat-wind name for UI (East / South / West / North). */
    fun windName(windTile: Int): String = when (windTile) {
        Tiles.EAST -> L10n.get("wind.east")
        Tiles.SOUTH -> L10n.get("wind.south")
        Tiles.WEST -> L10n.get("wind.west")
        Tiles.NORTH -> L10n.get("wind.north")
        else -> "?"
    }

    /**
     * Han character for [windTile] (東南西北), or `""` when it is not a wind.
     *
     * The glyph is locale-invariant: the mahjong canon is Traditional, so
     * `zh-Hans` shows 東 too rather than the Simplified 东. See [HAN_WINDS].
     */
    fun windHan(windTile: Int): String {
        val index = windTile - Tiles.EAST
        if (index !in 0 until NUM_PLAYERS) return ""
        return HAN_WINDS[index]
    }

    /** Absolute seats in display column order (Left, You, Opposite, Right). */
    fun absoluteSeatsInColumnOrder(humanSeat: Int): IntArray =
        IntArray(NUM_PLAYERS) { col ->
            val rel = COLUMN_ORDER_TO_RELATIVE_SEATS[col]
            (humanSeat + rel) % NUM_PLAYERS
        }

    /**
     * Localized name for the discarder seat inside the collapsed bar's "from {0}"
     * phrase: the human's pronoun in the case the preposition needs
     * ([seat.from_you]), kept in its mid-sentence casing.
     */
    fun fromSeatName(seat: Int, humanSeat: Int): String =
        if (customNames == null && seat == humanSeat) {
            L10n.get("seat.from_you")
        } else {
            nameForAbsoluteSeat(seat, humanSeat)
        }

    /**
     * The discarder seat as the expanded header's standalone "From:" value
     * ("From: You").
     *
     * The header's label already carries the preposition, so the pronoun starts
     * the value and takes a capital. Only the localized pronoun is re-cased — a
     * custom player name keeps the spelling its owner chose.
     */
    private fun fromSeatFieldName(seat: Int, humanSeat: Int): String =
        if (customNames == null && seat == humanSeat) {
            L10n.get("seat.from_you").replaceFirstChar { it.titlecase() }
        } else {
            nameForAbsoluteSeat(seat, humanSeat)
        }

    /**
     * The hand-end card header's first line: the muted label ("Winner:") and the
     * winner's name, kept apart so the card paints the name bright — green when
     * the winner is the human player.
     *
     * @param winner absolute seat index of the winner
     * @param humanSeat absolute seat index of the human player
     */
    fun winTitle(winner: Int, humanSeat: Int): LabeledLine =
        labeledLine(
            key = "win.headline.winner",
            name = nameForAbsoluteSeat(winner, humanSeat),
            tone = if (winner == humanSeat) NameTone.SelfWon else NameTone.Neutral,
        )

    /**
     * The header's second line: how the winning tile arrived.
     *
     * A discard win splits into its label ("From:") and the discarder's name, red
     * when that discarder is the human player. A self-draw has no name, so its
     * whole phrase is a label: the card right-aligns it with the "Winner:" /
     * "From:" labels and paints it muted.
     *
     * @param discarder absolute seat index of the discarder, or null for self-draw
     * @param humanSeat absolute seat index of the human player
     */
    fun winSourceTitle(discarder: Int?, humanSeat: Int): LabeledLine =
        if (discarder != null) {
            labeledLine(
                key = "win.headline.from",
                name = fromSeatFieldName(discarder, humanSeat),
                tone = if (discarder == humanSeat) NameTone.SelfDiscarded else NameTone.Neutral,
            )
        } else {
            LabeledLine(label = L10n.get("win.headline.self_draw"))
        }

    /**
     * Splits a headline template at its `{0}` seat-name slot: the run before the
     * slot is the muted label, the run after it belongs to the bright name.
     *
     * The label is trimmed at the slot. Every bundled template ends its label with
     * the translation's own separator — "Winner: ", "Gagnant : ", "勝者：" — but
     * the card sets the gutter between its label and name columns itself, so the
     * separator's whitespace must not ride along and double it.
     *
     * A template with no slot (a broken translation) still shows the name; the
     * card can lose the label, never the winner.
     */
    private fun labeledLine(key: String, name: String, tone: NameTone): LabeledLine {
        val template = L10n.get(key)
        val slot = template.indexOf("{0}")
        if (slot < 0) return LabeledLine(label = "", name = name, tone = tone)
        return LabeledLine(
            label = template.substring(0, slot).trimEnd(),
            name = name + template.substring(slot + 3),
            tone = tone,
        )
    }

    /**
     * Short second line for the collapsed hand-end card: how the winning tile
     * arrived — "from <seat>" on a discard win, "self-draw" otherwise.
     *
     * @param discarder absolute seat index of the discarder, or null for self-draw
     * @param humanSeat absolute seat index of the human player
     */
    fun winSourceLabel(discarder: Int?, humanSeat: Int): String =
        if (discarder != null) {
            L10n.format("win.source.discard", fromSeatName(discarder, humanSeat))
        } else {
            L10n.get("win.source.self_draw")
        }
}

/**
 * A hand-end header line split into the muted [label] that introduces it and the
 * bright [name] it names.
 *
 * A line that names nobody keeps its whole phrase in [label] and leaves [name]
 * empty — "Self-draw" is such a phrase. It is still a label, so the card keeps it
 * in the label column, right-aligned with the rest and muted.
 */
data class LabeledLine(
    val label: String,
    val name: String = "",
    /** How the card paints [name]; meaningless when there is no name. */
    val tone: NameTone = NameTone.Neutral,
)

/**
 * How the hand-end card paints a name. Only the human player's own part in the
 * hand changes it: their win reads green, their discard to the winner red.
 */
enum class NameTone {
    /** Any other seat — plain white. */
    Neutral,

    /** The human player, as the hand's winner — green. */
    SelfWon,

    /** The human player, as the discarder to the winner — red. */
    SelfDiscarded,
}
