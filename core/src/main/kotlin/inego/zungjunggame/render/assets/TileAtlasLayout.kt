package inego.zungjunggame.render.assets

import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.Tiles

/**
 * Single source of truth for the pre-baked tile face tier atlases shared by the
 * build-time packer (`TileAtlasPacker`) and the runtime loader ([TileAssets]).
 *
 * Pure Kotlin (no libGDX or AWT imports) so both the runtime core and the
 * host-only JVM packer source set share the exact same tier ladder, cell order,
 * gutter geometry, and filenames.
 */
object TileAtlasLayout {
    /** One pre-baked resolution tier, in exact 3:4 tile aspect ratio. */
    data class Tier(val tileW: Int, val tileH: Int)

    /**
     * Exact-3:4 tiers in ascending physical height order.
     *
     * Selecting the smallest tier with `tileH >= physicalHeightPx * MIN_OVERSAMPLE`
     * keeps every open tile in a ~1.25×–2.5× bilinear downscale: never near 1.0×
     * (where sub-pixel `Linear` coordinates double-blur tiny bitmaps like 32 px
     * popup tiles) and never >2.5× downscaled (which aliases into grain).
     */
    val TIERS: List<Tier> = listOf(
        Tier(tileW = 48, tileH = 64),
        Tier(tileW = 96, tileH = 128),
        Tier(tileW = 192, tileH = 256),
        Tier(tileW = 384, tileH = 512),
    )

    /**
     * Minimum source-to-screen oversample ratio required before stepping up to
     * the next tier. Prevents a tile drawn right at a tier's exact height (e.g.
     * 32 px, 64 px, 128 px) from landing at 1.0× where sub-pixel `Linear`
     * positioning blurs every pixel 50/50 with its neighbor.
     */
    const val MIN_OVERSAMPLE: Float = 1.25f

    /** Grid dimensions: 7×5 = 35 slots for [NUM_TILE_TYPES] (34) open faces. */
    const val COLUMNS: Int = 7
    const val ROWS: Int = 5

    /**
     * One extruded border pixel on every side of each tile (2 px total between
     * neighbors) so `TextureFilter.Linear` sampling at a region's edge behaves
     * like `ClampToEdge` with zero cross-tile bleed.
     */
    const val BORDER_PAD: Int = 1

    /** Fraction of tile width/height inset on each side around the symbol art. */
    const val SYMBOL_INSET: Float = 0.12f

    /** Relative asset path under the staged runtime tree for [tier]. */
    fun atlasPath(tier: Tier): String = "tiles/atlas_${tier.tileH}.png"

    fun cellStrideX(tier: Tier): Int = tier.tileW + BORDER_PAD * 2
    fun cellStrideY(tier: Tier): Int = tier.tileH + BORDER_PAD * 2

    fun atlasWidth(tier: Tier): Int = COLUMNS * cellStrideX(tier)
    fun atlasHeight(tier: Tier): Int = ROWS * cellStrideY(tier)

    fun cellX(tier: Tier, tileType: Int): Int {
        require(tileType in 0 until NUM_TILE_TYPES) { "tileType out of range: $tileType" }
        return (tileType % COLUMNS) * cellStrideX(tier) + BORDER_PAD
    }

    fun cellY(tier: Tier, tileType: Int): Int {
        require(tileType in 0 until NUM_TILE_TYPES) { "tileType out of range: $tileType" }
        return (tileType / COLUMNS) * cellStrideY(tier) + BORDER_PAD
    }

    /**
     * Index into [TIERS] for a tile drawn at [physicalHeightPx] framebuffer
     * pixels: the smallest tier whose height is at least
     * `physicalHeightPx * MIN_OVERSAMPLE`, clamped to the highest tier.
     */
    fun tierIndexForHeight(physicalHeightPx: Float): Int {
        val required = physicalHeightPx * MIN_OVERSAMPLE
        for (i in TIERS.indices) {
            if (TIERS[i].tileH >= required) return i
        }
        return TIERS.lastIndex
    }

    /** Engine tile type → FluffyStuff source PNG basename (no extension). */
    fun assetNameForTile(tileType: Int): String = when (tileType) {
        Tiles.CHAR_1 -> "Man1"
        Tiles.CHAR_2 -> "Man2"
        Tiles.CHAR_3 -> "Man3"
        Tiles.CHAR_4 -> "Man4"
        Tiles.CHAR_5 -> "Man5"
        Tiles.CHAR_6 -> "Man6"
        Tiles.CHAR_7 -> "Man7"
        Tiles.CHAR_8 -> "Man8"
        Tiles.CHAR_9 -> "Man9"
        Tiles.DOT_1 -> "Pin1"
        Tiles.DOT_2 -> "Pin2"
        Tiles.DOT_3 -> "Pin3"
        Tiles.DOT_4 -> "Pin4"
        Tiles.DOT_5 -> "Pin5"
        Tiles.DOT_6 -> "Pin6"
        Tiles.DOT_7 -> "Pin7"
        Tiles.DOT_8 -> "Pin8"
        Tiles.DOT_9 -> "Pin9"
        Tiles.BAMBOO_1 -> "Sou1"
        Tiles.BAMBOO_2 -> "Sou2"
        Tiles.BAMBOO_3 -> "Sou3"
        Tiles.BAMBOO_4 -> "Sou4"
        Tiles.BAMBOO_5 -> "Sou5"
        Tiles.BAMBOO_6 -> "Sou6"
        Tiles.BAMBOO_7 -> "Sou7"
        Tiles.BAMBOO_8 -> "Sou8"
        Tiles.BAMBOO_9 -> "Sou9"
        Tiles.EAST -> "Ton"
        Tiles.SOUTH -> "Nan"
        Tiles.WEST -> "Shaa"
        Tiles.NORTH -> "Pei"
        Tiles.RED -> "Chun"
        Tiles.GREEN -> "Hatsu"
        Tiles.WHITE -> "Haku"
        else -> error("Unknown tile type $tileType")
    }
}
