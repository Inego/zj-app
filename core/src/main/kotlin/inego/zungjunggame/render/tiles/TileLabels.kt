package inego.zungjunggame.render.tiles

import inego.zungjunggame.engine.Tiles
import inego.zungjunggame.i18n.L10n

/**
 * Short text tag for a face-up tile — the optional "tile labels" learner aid.
 *
 * The mapping is deliberately pure and lives outside the drawer: the app mirrors
 * the persisted *Show tile labels* preference into [enabled], renderers ask
 * [tagOrNull] for the text and pass it alongside the tile they are about to draw,
 * and the drawer paints it. [tagOrNull] is the **only** place a renderer's
 * preference gate is written, so the default (off) path resolves no text and
 * issues exactly the draw calls it did before this feature existed.
 *
 * Suit tags are **not** localized: a number tile reads as its numeral in every
 * language, so [lookup] is never consulted for a suit. Honor tags do come from
 * the bundle (`tilelabel.*`) because honor tiles carry no digit of their own.
 */
object TileLabels {
    /** Runtime display flag; mirrored from the persisted preference by the app. */
    var enabled: Boolean = false

    /** The seven honor i18n keys, in wind-then-dragon order (for tests/locale coverage). */
    val HONOR_KEYS: List<String> = listOf(
        "tilelabel.east",
        "tilelabel.south",
        "tilelabel.west",
        "tilelabel.north",
        "tilelabel.red",
        "tilelabel.green",
        "tilelabel.white",
    )

    /**
     * Default honor lookup.
     *
     * Held as a value rather than written inline as `L10n::get` in the parameter
     * list: Kotlin materializes a new function reference on every call that takes
     * a default argument, which for the play surface is once per labeled tile per
     * frame. The reference is bound to the [L10n] singleton, so it is locale-agnostic
     * and never needs rebuilding when the bundle hot-swaps.
     */
    private val DEFAULT_LOOKUP: (String) -> String = L10n::get

    /** `"1".."9"`, shared literals so a suit tag allocates nothing per draw. */
    private val DIGITS: Array<String> = Array(9) { (it + 1).toString() }

    /**
     * Tag for [type]: the digit for suit tiles, a localized tag for honors, "" if unknown.
     *
     * Honors resolve through [lookup] on **every** call — no cache. [L10n] replaces its
     * bundle when the locale changes at runtime ([L10n.setLocale]), so a cached tag
     * could outlive the locale it was read in; the lookup itself is a plain map read
     * with no allocation, so paying it per draw is cheaper than invalidating a cache.
     */
    fun label(type: Int, lookup: (String) -> String = DEFAULT_LOOKUP): String =
        when (type) {
            in Tiles.CHAR_1..Tiles.CHAR_9 -> DIGITS[type - Tiles.CHAR_1]
            in Tiles.DOT_1..Tiles.DOT_9 -> DIGITS[type - Tiles.DOT_1]
            in Tiles.BAMBOO_1..Tiles.BAMBOO_9 -> DIGITS[type - Tiles.BAMBOO_1]
            // Honors are a contiguous 27..33 wind-then-dragon block, so the
            // HONOR_KEYS index is simply the offset from EAST.
            in Tiles.EAST..Tiles.WHITE -> lookup(HONOR_KEYS[type - Tiles.EAST])
            else -> ""
        }

    /**
     * Tag to draw for [type], or null when there is nothing to draw — what a
     * renderer hands to `TileDrawer.drawOpen`.
     *
     * The one place the [enabled] gate is written. Renderers used to test the flag
     * themselves and hand the drawer `label(...)` or null, which spelled the same
     * condition out per call site; nothing kept a third call site from forgetting
     * it. Null also absorbs [label]'s `""` (a type outside the tile range), so a
     * caller never has to test the empty string too.
     *
     * `TileDrawer.drawOpen` re-reads [enabled] as a defence — passing a tag is
     * still not enough to get one painted while the preference is off, unless
     * the caller also passes `forceLabel` (tutorial comparison faces).
     */
    fun tagOrNull(type: Int, lookup: (String) -> String = DEFAULT_LOOKUP): String? =
        if (enabled) label(type, lookup).ifEmpty { null } else null
}
