package inego.zungjunggame.render.assets

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.utils.Disposable
import inego.zungjunggame.engine.NUM_TILE_TYPES
import inego.zungjunggame.engine.Tiles
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Loads face textures (FluffyStuff Regular) and builds procedural closed-tile backs.
 * Maps engine tile type indices (0..33) to drawable assets.
 *
 * Every texture here is **replaced**, never re-uploaded in place ([reload]): an
 * unmanaged texture whose context is gone is only reachable again through a fresh
 * GL name. Consumers therefore read them through the getters at draw time, and
 * anything they build *from* one — a nine-patch, a region, a cached handle — has
 * to be re-derived when the instance changes.
 */
class TileAssets : Disposable {
    /**
     * All of these are **unmanaged** textures: when the driver discards the EGL
     * context their GL objects vanish and nothing restores them. Hence [reload],
     * driven by [TextureRecovery]. The procedural faces keep their source pixmaps
     * so they can be rebuilt rather than merely re-uploaded.
     */
    private var frontTex: Texture = loadTexture("tiles/Front.png")
    private var symbolTex: Array<Texture> = Array(NUM_TILE_TYPES) { i ->
        loadTexture("tiles/${assetNameForTile(i)}.png")
    }
    private var closedBlueTex: Texture = createClosedTileTexture(TileBackColor.Blue)
    private var glowTex: Texture = createGlowTexture()
    private var outlineGlowTex: Texture = createOutlineGlowTexture()

    val front: Texture get() = frontTex
    val closedBlue: Texture get() = closedBlueTex

    /** Soft white radial ring used as a play-mode focus halo behind a tile. */
    val glow: Texture get() = glowTex

    /**
     * 9-slice white rounded-rect ring (transparent center) for highlight outlines.
     * Stretch with [com.badlogic.gdx.graphics.g2d.NinePatch] split at
     * [OUTLINE_PATCH_PAD]. The corner cell is one quarter of the ring and its
     * inner corner is the arc center. [inego.zungjunggame.render.tiles.TileDrawer]
     * sizes that cell to the outer radius and grows the dest only by the stroke,
     * so the center sits inside the tile rather than on the vertex.
     */
    val outlineGlow: Texture get() = outlineGlowTex

    private val recoveryKey = "TileAssets@${System.identityHashCode(this)}"

    init {
        // The fields above already hold freshly uploaded textures, so register for
        // future recoveries only — running it now would dispose and rebuild them.
        TextureRecovery.registerOnly(recoveryKey, ::reload)
    }

    fun symbol(tileType: Int): Texture {
        require(tileType in 0 until NUM_TILE_TYPES) { "tileType out of range: $tileType" }
        return symbolTex[tileType]
    }

    /**
     * Rebuild every texture from its original source. File-backed faces are
     * re-read; the procedural ones are regenerated from their pixmaps.
     *
     * Build-then-publish: the complete replacement set is created first and only
     * then published, so a build failure part-way leaves the previous fields
     * published rather than a mix of new and old handles. An unmanaged texture
     * cannot be re-uploaded in place — its GL name died with the context — so a
     * successful reload **replaces** every instance. A held instance is therefore
     * **stale after this runs**: rebuild what was derived from it.
     *
     * The predecessors are **abandoned, not disposed**. Their names belong to the
     * old context, and the new context may have reissued one of them to a live
     * texture; deleting it would make a tile or icon draw that texture. See
     * [buildThenSwap].
     */
    private fun reload() {
        val previous = buildList {
            add(frontTex)
            addAll(symbolTex)
            add(closedBlueTex)
            add(glowTex)
            add(outlineGlowTex)
        }
        buildThenSwap(
            previous = previous,
            dispose = { it.disposeNow() },
            disposePrevious = false,
            build = { track ->
                val front = track(loadTexture("tiles/Front.png"))
                val symbols = Array(NUM_TILE_TYPES) { i ->
                    track(loadTexture("tiles/${assetNameForTile(i)}.png"))
                }
                val closedBlue = track(createClosedTileTexture(TileBackColor.Blue))
                val glow = track(createGlowTexture())
                val outlineGlow = track(createOutlineGlowTexture())
                listOf(front) + symbols.toList() + listOf(closedBlue, glow, outlineGlow)
            },
            publish = { set ->
                frontTex = set[0]
                symbolTex = set.subList(1, 1 + NUM_TILE_TYPES).toTypedArray()
                closedBlueTex = set[1 + NUM_TILE_TYPES]
                glowTex = set[2 + NUM_TILE_TYPES]
                outlineGlowTex = set[3 + NUM_TILE_TYPES]
            },
        )
    }

    private fun Texture.disposeNow() {
        try {
            dispose()
        } catch (_: Throwable) {
            // A texture whose context is already gone cannot be cleaned up.
        }
    }

    override fun dispose() {
        TextureRecovery.unregister(recoveryKey)
        frontTex.disposeNow()
        symbolTex.forEach { it.disposeNow() }
        closedBlueTex.disposeNow()
        glowTex.disposeNow()
        outlineGlowTex.disposeNow()
    }

    companion object {
        /** Engine tile type → FluffyStuff asset basename (no extension). */
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

        private fun loadTexture(path: String): Texture =
            Texture(path).also {
                it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            }

        private enum class TileBackColor(
            val face: Color,
            val faceDark: Color,
            val faceLight: Color,
        ) {
            Blue(
                face = Color(0.18f, 0.38f, 0.78f, 1f),
                faceDark = Color(0.12f, 0.28f, 0.62f, 1f),
                faceLight = Color(0.32f, 0.52f, 0.88f, 1f),
            ),
        }

        /**
         * Soft rounded-rect *ring* (not a filled blob). Brightest near the tile
         * silhouette so a larger draw still shows a halo outside the opaque face.
         * Designed for [inego.zungjunggame.render.tiles.TileDrawer] expand ≈ 2 (tile edge near half the glow extent).
         */
        private fun createGlowTexture(): Texture {
            val size = 160
            val pixmap = Pixmap(size, size, Pixmap.Format.RGBA8888)
            pixmap.setBlending(Pixmap.Blending.None)
            pixmap.setColor(Color.CLEAR)
            pixmap.fill()
            val cx = (size - 1) * 0.5f
            val cy = (size - 1) * 0.5f
            // Tile edge when drawn at ~2× tile size (half of glow half-extent).
            val tileEdge = 0.50f
            val corner = 0.18f
            for (y in 0 until size) {
                for (x in 0 until size) {
                    // Normalize so ±1 is the outer rim of the texture.
                    val nx = abs(x - cx) / cx
                    val ny = abs(y - cy) / cy
                    // Rounded-box distance from center (0 inside, grows outward).
                    val kx = (nx - (tileEdge - corner)).coerceAtLeast(0f)
                    val ky = (ny - (tileEdge - corner)).coerceAtLeast(0f)
                    // 0 at the rounded tile edge, positive outside, negative inside.
                    val outside = sqrt(kx * kx + ky * ky) - corner
                    val a = when {
                        // Under the tile: nearly empty (face covers it).
                        outside < -0.02f -> {
                            val depth = ((-outside) / tileEdge).coerceIn(0f, 1f)
                            0.04f * (1f - depth)
                        }
                        // Soft band just outside the silhouette.
                        outside < 0.28f -> {
                            val t = outside / 0.28f
                            (1f - t * t) * 0.55f
                        }
                        // Gentle outer falloff.
                        outside < 0.50f -> {
                            val t = (outside - 0.28f) / (0.50f - 0.28f)
                            0.18f * (1f - t) * (1f - t)
                        }
                        else -> 0f
                    }.coerceIn(0f, 1f)
                    if (a <= 0.001f) continue
                    pixmap.drawPixel(x, y, Color.rgba8888(1f, 1f, 1f, a))
                }
            }
            val texture = Texture(pixmap)
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            pixmap.dispose()
            return texture
        }

        /**
         * Nine-patch split (texture px) for [outlineGlow]: the outer corner radius
         * of the ring. Keep in sync with [createOutlineGlowTexture].
         */
        const val OUTLINE_PATCH_PAD = 24

        /**
         * Solid stroke width, in the same texture pixels as [OUTLINE_PATCH_PAD].
         * The rest of the corner cell is the transparent hole, so the arc center
         * (the patch split) sits this far inside the outer edge.
         */
        const val OUTLINE_STROKE = 8f

        /**
         * How far inside the tile edge the arc center sits, as a fraction of the
         * outlined rect's shorter side. The face art rounds at about 0.12 of its
         * width; this is a little larger so the orange border reads as a rounded
         * rectangle. Cosmetic — packs vary.
         */
        const val OUTLINE_INNER_RADIUS_FRACTION = 0.16f

        /**
         * Solid rounded-rect ring (no halo). The inner (9-patch center) is empty.
         * Each corner is a quarter annulus whose center is the inner corner of
         * the corner cell — the same point the nine-patch split maps into the
         * tile. Straight runs keep a uniform stroke so a long group stays a
         * rounded bar.
         */
        private fun createOutlineGlowTexture(): Texture {
            val corner = OUTLINE_PATCH_PAD
            val size = corner * 2 + 16
            val pixmap = Pixmap(size, size, Pixmap.Format.RGBA8888)
            pixmap.setBlending(Pixmap.Blending.None)
            pixmap.setColor(Color.CLEAR)
            pixmap.fill()
            val outer = corner.toFloat()
            val inner = outer - OUTLINE_STROKE
            val maxX = (size - 1).toFloat()
            val maxY = (size - 1).toFloat()
            val aa = 1f
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val fx = x.toFloat()
                    val fy = y.toFloat()
                    // Arc center: inset by [outer] from the texture's outer corner,
                    // which is the nine-patch split. Along a straight side this
                    // collapses to the point on the split nearest the pixel.
                    val cx = when {
                        fx < outer -> outer
                        fx > maxX - outer -> maxX - outer
                        else -> fx
                    }
                    val cy = when {
                        fy < outer -> outer
                        fy > maxY - outer -> maxY - outer
                        else -> fy
                    }
                    val dist = hypot(fx - cx, fy - cy)
                    val outerFade = ((outer - dist) / aa).coerceIn(0f, 1f)
                    val innerFade = ((dist - inner) / aa).coerceIn(0f, 1f)
                    val a = min(outerFade, innerFade)
                    if (a <= 0.001f) continue
                    pixmap.drawPixel(x, y, Color.rgba8888(1f, 1f, 1f, a))
                }
            }
            val texture = Texture(pixmap)
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            pixmap.dispose()
            return texture
        }

        private fun createClosedTileTexture(back: TileBackColor): Texture {
            val w = 150
            val h = 200
            val pixmap = Pixmap(w, h, Pixmap.Format.RGBA8888)
            pixmap.setBlending(Pixmap.Blending.SourceOver)
            pixmap.setColor(Color.CLEAR)
            pixmap.fill()

            val outerR = 20
            fillRoundedRect(pixmap, 0, 0, w, h, outerR, back.face)
            fillRoundedRect(
                pixmap,
                8,
                8,
                w / 2,
                h / 5,
                10,
                Color(back.faceLight.r, back.faceLight.g, back.faceLight.b, 0.35f),
            )

            val frame1 = 14
            strokeRoundedRect(
                pixmap,
                frame1,
                frame1,
                w - frame1 * 2,
                h - frame1 * 2,
                12,
                back.faceLight,
                thickness = 3,
                fillColor = back.face,
            )
            val frame2 = 26
            strokeRoundedRect(
                pixmap,
                frame2,
                frame2,
                w - frame2 * 2,
                h - frame2 * 2,
                10,
                back.faceDark,
                thickness = 2,
                fillColor = back.face,
            )

            val cx = w / 2
            val cy = h / 2
            fillDiamond(pixmap, cx, cy, 28, 36, back.faceLight)
            fillDiamond(pixmap, cx, cy, 20, 26, back.faceDark)
            fillDiamond(pixmap, cx, cy, 12, 16, back.faceLight)

            val texture = Texture(pixmap)
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            pixmap.dispose()
            return texture
        }

        private fun fillRoundedRect(
            pixmap: Pixmap,
            x: Int,
            y: Int,
            w: Int,
            h: Int,
            radius: Int,
            color: Color,
        ) {
            if (w <= 0 || h <= 0) return
            val r = min(radius, min(w, h) / 2)
            pixmap.setColor(color)
            pixmap.fillRectangle(x + r, y, w - 2 * r, h)
            pixmap.fillRectangle(x, y + r, w, h - 2 * r)
            pixmap.fillCircle(x + r, y + r, r)
            pixmap.fillCircle(x + w - r - 1, y + r, r)
            pixmap.fillCircle(x + r, y + h - r - 1, r)
            pixmap.fillCircle(x + w - r - 1, y + h - r - 1, r)
        }

        private fun strokeRoundedRect(
            pixmap: Pixmap,
            x: Int,
            y: Int,
            w: Int,
            h: Int,
            radius: Int,
            strokeColor: Color,
            thickness: Int,
            fillColor: Color,
        ) {
            if (w <= thickness * 2 || h <= thickness * 2) return
            fillRoundedRect(pixmap, x, y, w, h, radius, strokeColor)
            fillRoundedRect(
                pixmap,
                x + thickness,
                y + thickness,
                w - thickness * 2,
                h - thickness * 2,
                maxOf(0, radius - thickness),
                fillColor,
            )
        }

        private fun fillDiamond(
            pixmap: Pixmap,
            cx: Int,
            cy: Int,
            halfW: Int,
            halfH: Int,
            color: Color,
        ) {
            if (halfW <= 0 || halfH <= 0) return
            pixmap.setColor(color)
            for (y in -halfH..halfH) {
                val t = 1f - (abs(y).toFloat() / halfH)
                val halfSpan = (halfW * t).toInt().coerceAtLeast(0)
                if (halfSpan > 0) {
                    pixmap.drawLine(cx - halfSpan, cy + y, cx + halfSpan, cy + y)
                }
            }
        }
    }
}
