package inego.zungjunggame.render.assets

import inego.zungjunggame.engine.NUM_TILE_TYPES
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Build-time tile atlas generator invoked by `:core:packTileAtlases`.
 *
 * Lives in the host-only `tilePacker` source set (never shipped in `core.jar`,
 * Android DEX, or TeaVM Wasm). Reads the 600×800 FluffyStuff master PNGs from
 * `assets/tiles/`, composites each symbol onto `Front.png` in premultiplied
 * alpha, downscales cleanly with separable Lanczos-2 to every tier in
 * [TileAtlasLayout.TIERS], extrudes a 1 px gutter border around every cell, and
 * writes `<outRoot>/tiles/atlas_<height>.png`.
 */
object TileAtlasPacker {
    private const val TILES_DIR = "tiles"
    private const val FRONT_FILE = "Front.png"
    private const val LANCZOS_RADIUS = 2.0

    /** Transparent ivory (`245, 240, 235`, alpha 0) matching `Front.png`. */
    private const val TRANSPARENT_IVORY_ARGB: Int = (245 shl 16) or (240 shl 8) or 235

    private class ImageBuf(val w: Int, val h: Int, val argb: IntArray)

    private class AxisWeights(
        val dstLen: Int,
        val start: IntArray,
        val count: IntArray,
        val weights: DoubleArray,
        val offset: IntArray,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2) { "usage: TileAtlasPacker <assetsRoot> <outRoot>" }
        pack(File(args[0]), File(args[1]))
    }

    fun pack(assetsRoot: File, outRoot: File) {
        val tilesIn = File(assetsRoot, TILES_DIR)
        val tilesOut = File(outRoot, TILES_DIR)
        tilesOut.mkdirs()
        tilesOut.listFiles()?.forEach { existing ->
            if (existing.isFile && existing.name.startsWith("atlas_") && existing.name.endsWith(".png")) {
                existing.delete()
            }
        }

        val front = readImage(File(tilesIn, FRONT_FILE))
        replaceZeroAlphaRgb(front, TRANSPARENT_IVORY_ARGB)

        val insetX = (front.w * TileAtlasLayout.SYMBOL_INSET).roundToInt()
        val insetY = (front.h * TileAtlasLayout.SYMBOL_INSET).roundToInt()
        val symDstW = front.w - insetX * 2
        val symDstH = front.h - insetY * 2

        val tiers = TileAtlasLayout.TIERS
        val atlases = tiers.map { tier ->
            BufferedImage(
                TileAtlasLayout.atlasWidth(tier),
                TileAtlasLayout.atlasHeight(tier),
                BufferedImage.TYPE_INT_ARGB,
            )
        }
        val tierWeightsX = tiers.map { tier -> precomputeWeights(front.w, tier.tileW) }
        val tierWeightsY = tiers.map { tier -> precomputeWeights(front.h, tier.tileH) }

        var symWeightsX: AxisWeights? = null
        var symWeightsY: AxisWeights? = null
        var symSrcW = -1
        var symSrcH = -1

        for (tileType in 0 until NUM_TILE_TYPES) {
            val symFile = File(tilesIn, "${TileAtlasLayout.assetNameForTile(tileType)}.png")
            val sym = readImage(symFile)
            if (sym.w != symSrcW || sym.h != symSrcH) {
                symSrcW = sym.w
                symSrcH = sym.h
                symWeightsX = precomputeWeights(sym.w, symDstW)
                symWeightsY = precomputeWeights(sym.h, symDstH)
            }
            val resizedSym = resamplePremul(sym, symWeightsX!!, symWeightsY!!, transparentFallback = 0)
            val composited = compositeOver(front, resizedSym, insetX, insetY)

            for (t in tiers.indices) {
                val tier = tiers[t]
                val scaled = resamplePremul(
                    composited,
                    tierWeightsX[t],
                    tierWeightsY[t],
                    transparentFallback = TRANSPARENT_IVORY_ARGB,
                )
                blitWithExtrudedBorder(
                    atlas = atlases[t],
                    tile = scaled,
                    dstX = TileAtlasLayout.cellX(tier, tileType),
                    dstY = TileAtlasLayout.cellY(tier, tileType),
                )
            }
        }

        for (t in tiers.indices) {
            val outFile = File(outRoot, TileAtlasLayout.atlasPath(tiers[t]))
            outFile.parentFile.mkdirs()
            check(ImageIO.write(atlases[t], "png", outFile)) {
                "ImageIO failed to write PNG: ${outFile.absolutePath}"
            }
        }
    }

    private fun readImage(file: File): ImageBuf {
        require(file.isFile) { "Missing tile source PNG: ${file.absolutePath}" }
        val img = ImageIO.read(file) ?: error("Unreadable PNG: ${file.absolutePath}")
        val w = img.width
        val h = img.height
        val argb = IntArray(w * h)
        img.getRGB(0, 0, w, h, argb, 0, w)
        return ImageBuf(w, h, argb)
    }

    private fun replaceZeroAlphaRgb(buf: ImageBuf, transparentArgb: Int) {
        val pixels = buf.argb
        for (i in pixels.indices) {
            if ((pixels[i] ushr 24) == 0) {
                pixels[i] = transparentArgb
            }
        }
    }

    /** Straight-alpha source-over composite of [src] onto a copy of [dst] at ([offX], [offY]). */
    private fun compositeOver(dst: ImageBuf, src: ImageBuf, offX: Int, offY: Int): ImageBuf {
        val out = dst.argb.copyOf()
        val dw = dst.w
        val sw = src.w
        val sh = src.h
        val sp = src.argb
        for (sy in 0 until sh) {
            val dy = sy + offY
            if (dy !in 0 until dst.h) continue
            val dRow = dy * dw + offX
            val sRow = sy * sw
            for (sx in 0 until sw) {
                val dx = sx + offX
                if (dx !in 0 until dw) continue
                val s = sp[sRow + sx]
                val sa = (s ushr 24) and 0xFF
                if (sa == 0) continue
                if (sa == 255) {
                    out[dRow + sx] = s
                    continue
                }
                val d = out[dRow + sx]
                val da = (d ushr 24) and 0xFF
                val aS = sa / 255.0
                val aD = da / 255.0
                val outA = aS + aD * (1.0 - aS)
                if (outA <= 1e-6) continue
                val sr = ((s ushr 16) and 0xFF) / 255.0
                val sg = ((s ushr 8) and 0xFF) / 255.0
                val sb = (s and 0xFF) / 255.0
                val dr = ((d ushr 16) and 0xFF) / 255.0
                val dg = ((d ushr 8) and 0xFF) / 255.0
                val db = (d and 0xFF) / 255.0
                val inv = 1.0 - aS
                val r = ((sr * aS + dr * aD * inv) / outA).coerceIn(0.0, 1.0)
                val g = ((sg * aS + dg * aD * inv) / outA).coerceIn(0.0, 1.0)
                val b = ((sb * aS + db * aD * inv) / outA).coerceIn(0.0, 1.0)
                out[dRow + sx] = packArgb(outA, r, g, b)
            }
        }
        return ImageBuf(dst.w, dst.h, out)
    }

    /**
     * Separable 2-pass Lanczos-2 resampling in premultiplied alpha.
     *
     * Premultiplying RGB by alpha prevents transparent pixels from bleeding
     * dark/arbitrary RGB into semi-transparent stroke or corner edges, while
     * Lanczos-2 preserves crisp ink contrast without visible ringing.
     */
    private fun resamplePremul(
        src: ImageBuf,
        wx: AxisWeights,
        wy: AxisWeights,
        transparentFallback: Int,
    ): ImageBuf {
        val dstW = wx.dstLen
        val dstH = wy.dstLen
        val srcW = src.w
        val srcH = src.h
        val sp = src.argb

        // Horizontal pass into premultiplied float channels (dstW × srcH).
        val midN = dstW * srcH
        val midA = DoubleArray(midN)
        val midR = DoubleArray(midN)
        val midG = DoubleArray(midN)
        val midB = DoubleArray(midN)

        for (y in 0 until srcH) {
            val sRow = y * srcW
            val mRow = y * dstW
            for (x in 0 until dstW) {
                val s0 = wx.start[x]
                val cnt = wx.count[x]
                val wOff = wx.offset[x]
                var aSum = 0.0
                var rSum = 0.0
                var gSum = 0.0
                var bSum = 0.0
                for (k in 0 until cnt) {
                    val w = wx.weights[wOff + k]
                    val p = sp[sRow + s0 + k]
                    val a = ((p ushr 24) and 0xFF) / 255.0
                    aSum += w * a
                    if (a > 0.0) {
                        val wa = w * a
                        rSum += wa * (((p ushr 16) and 0xFF) / 255.0)
                        gSum += wa * (((p ushr 8) and 0xFF) / 255.0)
                        bSum += wa * ((p and 0xFF) / 255.0)
                    }
                }
                val idx = mRow + x
                midA[idx] = aSum
                midR[idx] = rSum
                midG[idx] = gSum
                midB[idx] = bSum
            }
        }

        // Vertical pass into final straight-alpha ARGB (dstW × dstH).
        val out = IntArray(dstW * dstH)
        for (y in 0 until dstH) {
            val s0 = wy.start[y]
            val cnt = wy.count[y]
            val wOff = wy.offset[y]
            val dRow = y * dstW
            for (x in 0 until dstW) {
                var aSum = 0.0
                var rSum = 0.0
                var gSum = 0.0
                var bSum = 0.0
                for (k in 0 until cnt) {
                    val w = wy.weights[wOff + k]
                    val mIdx = (s0 + k) * dstW + x
                    aSum += w * midA[mIdx]
                    rSum += w * midR[mIdx]
                    gSum += w * midG[mIdx]
                    bSum += w * midB[mIdx]
                }
                val outA = aSum.coerceIn(0.0, 1.0)
                out[dRow + x] = if (outA <= 0.5 / 255.0) {
                    transparentFallback
                } else {
                    val invA = 1.0 / outA
                    packArgb(
                        outA,
                        (rSum * invA).coerceIn(0.0, 1.0),
                        (gSum * invA).coerceIn(0.0, 1.0),
                        (bSum * invA).coerceIn(0.0, 1.0),
                    )
                }
            }
        }
        return ImageBuf(dstW, dstH, out)
    }

    private fun precomputeWeights(srcLen: Int, dstLen: Int): AxisWeights {
        val scale = srcLen.toDouble() / dstLen.toDouble()
        val filterScale = if (scale > 1.0) scale else 1.0
        val support = LANCZOS_RADIUS * filterScale

        val start = IntArray(dstLen)
        val count = IntArray(dstLen)
        val offset = IntArray(dstLen)
        val weightsList = ArrayList<Double>(dstLen * (ceil(support * 2.0).toInt() + 2))

        for (d in 0 until dstLen) {
            val center = (d + 0.5) * scale - 0.5
            val left = floor(center - support).toInt().coerceAtLeast(0)
            val right = ceil(center + support).toInt().coerceAtMost(srcLen - 1)
            start[d] = left
            count[d] = right - left + 1
            offset[d] = weightsList.size

            var sum = 0.0
            for (s in left..right) {
                val w = lanczos2((s - center) / filterScale)
                weightsList.add(w)
                sum += w
            }
            if (sum != 0.0) {
                val inv = 1.0 / sum
                val base = offset[d]
                for (k in 0 until count[d]) {
                    weightsList[base + k] = weightsList[base + k] * inv
                }
            }
        }
        return AxisWeights(dstLen, start, count, weightsList.toDoubleArray(), offset)
    }

    private fun lanczos2(x: Double): Double {
        val ax = abs(x)
        if (ax < 1e-9) return 1.0
        if (ax >= LANCZOS_RADIUS) return 0.0
        val px = PI * ax
        val pxs = px / LANCZOS_RADIUS
        return (sin(px) / px) * (sin(pxs) / pxs)
    }

    private fun packArgb(a: Double, r: Double, g: Double, b: Double): Int {
        val ia = (a * 255.0).roundToInt().coerceIn(0, 255)
        val ir = (r * 255.0).roundToInt().coerceIn(0, 255)
        val ig = (g * 255.0).roundToInt().coerceIn(0, 255)
        val ib = (b * 255.0).roundToInt().coerceIn(0, 255)
        return (ia shl 24) or (ir shl 16) or (ig shl 8) or ib
    }

    /**
     * Copy [tile] into [atlas] at ([dstX], [dstY]) and replicate its 1 px outer
     * rim into the surrounding [TileAtlasLayout.BORDER_PAD] gutter on all sides
     * and corners.
     */
    private fun blitWithExtrudedBorder(atlas: BufferedImage, tile: ImageBuf, dstX: Int, dstY: Int) {
        val tw = tile.w
        val th = tile.h
        val p = tile.argb
        atlas.setRGB(dstX, dstY, tw, th, p, 0, tw)

        val pad = TileAtlasLayout.BORDER_PAD
        for (b in 1..pad) {
            // Top and bottom extruded rows.
            for (x in 0 until tw) {
                atlas.setRGB(dstX + x, dstY - b, p[x])
                atlas.setRGB(dstX + x, dstY + th - 1 + b, p[(th - 1) * tw + x])
            }
            // Left and right extruded columns.
            for (y in 0 until th) {
                atlas.setRGB(dstX - b, dstY + y, p[y * tw])
                atlas.setRGB(dstX + tw - 1 + b, dstY + y, p[y * tw + tw - 1])
            }
            // Four corner pixels.
            val tl = p[0]
            val tr = p[tw - 1]
            val bl = p[(th - 1) * tw]
            val br = p[(th - 1) * tw + tw - 1]
            for (by in 1..pad) {
                atlas.setRGB(dstX - b, dstY - by, tl)
                atlas.setRGB(dstX + tw - 1 + b, dstY - by, tr)
                atlas.setRGB(dstX - b, dstY + th - 1 + by, bl)
                atlas.setRGB(dstX + tw - 1 + b, dstY + th - 1 + by, br)
            }
        }
    }
}
